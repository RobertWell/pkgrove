package com.pkgrove.pkgrovekit.storage

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded streaming upload over the [MultipartUpload] lifecycle (HEL-236
 * guarantees 1+3). The input stream is read ONCE, sequentially, into part
 * buffers of [Options.partSizeBytes]; at most [Options.concurrency] parts are
 * in flight, so peak memory is `concurrency * partSizeBytes` — stated, bounded,
 * and asserted by test, never "whatever the SDK buffers".
 *
 * Failure/cancellation ABORTS the multipart upload (guarantee 3): no invisible
 * billed part garbage survives an exception. Content that fits in a single
 * part never starts a multipart upload at all — it degrades to a plain [ObjectStore.put]
 * (each buffered part is repeatable, so both paths are retry-safe).
 */
object MultipartTransfer {

    data class Options(
        val partSizeBytes: Int = 8 * 1024 * 1024,
        val concurrency: Int = 4,
        val cancelToken: CancelToken = CancelToken.none(),
        /** Per-part SHA-256, provider-verified where supported. */
        val checksumParts: Boolean = true,
        val put: PutOptions = PutOptions(),
        /** (partsUploaded, bytesUploaded) — log-safe progress, no key/data. */
        val onProgress: ((Int, Long) -> Unit)? = null,
    ) {
        init {
            require(partSizeBytes > 0) { "partSizeBytes must be positive" }
            require(concurrency >= 1) { "concurrency must be >= 1" }
        }
    }

    /** Peak concurrently-buffered part count of the LAST upload — test hook. */
    internal val lastPeakBufferedParts = AtomicInteger(0)

    /**
     * Stream [input] (length may be unknown) into [key]. Requires
     * [StorageCapability.MULTIPART_UPLOAD] only when the content actually
     * exceeds one part. The caller owns closing [input].
     */
    @JvmStatic
    fun upload(
        store: ObjectStore,
        key: ObjectKey,
        input: InputStream,
        options: Options = Options(),
    ): PutResult {
        val limits = store.capabilities.limits
        require(options.partSizeBytes <= limits.maxPartSizeBytes) {
            "partSizeBytes ${options.partSizeBytes} exceeds provider maxPartSizeBytes ${limits.maxPartSizeBytes}"
        }
        lastPeakBufferedParts.set(0)
        val first = readPart(input, options.partSizeBytes) ?: ByteArray(0)
        if (first.size < options.partSizeBytes) {
            // fits in one part — no multipart lifecycle, no extra capability
            val put = options.put.let {
                if (options.checksumParts && it.checksum == null) it.copy(checksum = Checksum.sha256(first)) else it
            }
            lastPeakBufferedParts.set(1)
            return store.put(key, ContentSource.of(first), put)
        }

        // fail BEFORE moving data when the provider cannot do multipart at all
        store.capabilities.require("multipart upload of '$key'", StorageCapability.MULTIPART_UPLOAD)
        options.cancelToken.throwIfCancelled()

        // min-part-size guard: every part except the last must satisfy the provider
        require(options.partSizeBytes >= limits.minPartSizeBytes) {
            "partSizeBytes ${options.partSizeBytes} below provider minPartSizeBytes ${limits.minPartSizeBytes}"
        }

        val executor: ExecutorService = Executors.newFixedThreadPool(options.concurrency)
        val inFlight = Semaphore(options.concurrency)
        val buffered = AtomicInteger(1) // `first` is already held
        val completed = ConcurrentLinkedQueue<CompletedUploadPart>()
        val futures = mutableListOf<Future<*>>()
        val upload = store.startMultipart(key, options.put)
        var bytesUploaded = 0L
        try {
            upload.use { mpu ->
                var partNumber = 0
                var next: ByteArray? = first
                while (next != null) {
                    options.cancelToken.throwIfCancelled()
                    partNumber += 1
                    require(partNumber <= limits.maxPartsPerUpload) {
                        "content needs more than ${limits.maxPartsPerUpload} parts of " +
                            "${options.partSizeBytes} bytes — raise partSizeBytes"
                    }
                    val part = next
                    val thisNumber = partNumber
                    inFlight.acquire() // BOUND: blocks the reader until a slot frees
                    lastPeakBufferedParts.accumulateAndGet(buffered.get()) { a, b -> maxOf(a, b) }
                    futures += executor.submit {
                        try {
                            val checksum = if (options.checksumParts) Checksum.sha256(part) else null
                            completed += mpu.uploadPart(thisNumber, ContentSource.of(part), checksum)
                        } finally {
                            buffered.decrementAndGet()
                            inFlight.release()
                        }
                    }
                    bytesUploaded += part.size
                    next = if (part.size < options.partSizeBytes) {
                        null // short part == EOF
                    } else {
                        readPart(input, options.partSizeBytes)?.also { buffered.incrementAndGet() }
                    }
                    options.onProgress?.invoke(thisNumber, bytesUploaded)
                }
                // surface the FIRST worker failure (cancellation stays typed)
                futures.forEach { f ->
                    try {
                        f.get()
                    } catch (e: java.util.concurrent.ExecutionException) {
                        when (val cause = e.cause) {
                            is OperationCancelledException, is StorageException -> throw cause
                            else -> throw StorageIoException(
                                "part upload for '$key' failed", cause, retrySafe = false,
                            )
                        }
                    }
                }
                options.cancelToken.throwIfCancelled()
                return mpu.complete(completed.sortedBy { it.partNumber })
            } // close() aborts when complete() was not reached — guarantee 3
        } finally {
            executor.shutdownNow()
        }
    }

    /** Read up to [size] bytes; null at immediate EOF. Never over-allocates. */
    private fun readPart(input: InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val n = input.read(buf, filled, size - filled)
            if (n < 0) break
            filled += n
        }
        if (filled == 0) return null
        return if (filled == size) buf else buf.copyOf(filled)
    }
}
