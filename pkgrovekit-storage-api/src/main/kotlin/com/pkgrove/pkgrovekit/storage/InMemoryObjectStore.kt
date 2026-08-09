package com.pkgrove.pkgrovekit.storage

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reference/testing implementation of [ObjectStore] — plain JDK, thread-safe,
 * strongly consistent, full capability set. This is what consumers unit-test
 * storage workflows against without a container, and what defines expected
 * semantics for provider adapters (the MinIO suite in `pkgrovekit-storage-s3`
 * runs the same assertions against a real provider).
 */
class InMemoryObjectStore(
    private val clock: Clock = Clock.systemUTC(),
) : ObjectStore {

    private class StoredObject(
        val bytes: ByteArray,
        val etag: String,
        val lastModified: Instant,
        val contentType: String?,
        val checksum: Checksum?,
        val userMetadata: Map<String, String>,
    )

    private class PendingUpload(val key: ObjectKey, val options: PutOptions) {
        val parts = ConcurrentHashMap<Int, ByteArray>()
        val terminated = AtomicBoolean(false)
    }

    private val objects = ConcurrentHashMap<String, StoredObject>()
    private val uploads = ConcurrentHashMap<String, PendingUpload>()

    override val capabilities: StorageCapabilities = StorageCapabilities(
        provider = "in-memory",
        supported = setOf(
            StorageCapability.MULTIPART_UPLOAD,
            StorageCapability.CONDITIONAL_CREATE,
            StorageCapability.CONDITIONAL_UPDATE,
            StorageCapability.CHECKSUM_SHA256,
            StorageCapability.CONSISTENT_LISTING,
        ),
        // a testing store imposes no real part-size floor — workflows exercise
        // small parts cheaply; provider adapters advertise their REAL limits
        limits = StorageLimits(
            maxObjectSizeBytes = Long.MAX_VALUE,
            maxPartSizeBytes = Long.MAX_VALUE,
            minPartSizeBytes = 1,
            maxPartsPerUpload = 10_000,
        ),
    )

    override fun head(key: ObjectKey): ObjectMetadata? = objects[key.value]?.toMetadata(key)

    private fun StoredObject.toMetadata(key: ObjectKey) = ObjectMetadata(
        key = key, sizeBytes = bytes.size.toLong(), etag = etag, lastModified = lastModified,
        contentType = contentType, checksum = checksum, userMetadata = userMetadata,
    )

    override fun list(prefix: String, pageSize: Int): Sequence<ObjectSummary> =
        objects.entries
            .filter { it.key.startsWith(prefix) }
            .sortedBy { it.key }
            .map { (k, o) ->
                ObjectSummary(ObjectKey(k), o.bytes.size.toLong(), o.etag, o.lastModified)
            }
            .asSequence()

    override fun put(key: ObjectKey, source: ContentSource, options: PutOptions): PutResult {
        val bytes = source.open().use { it.readBytes() }
        return store(key, bytes, options)
    }

    /** Single synchronization point: conditional create/update is atomic here. */
    private fun store(key: ObjectKey, bytes: ByteArray, options: PutOptions): PutResult {
        options.checksum?.let { declared ->
            require(declared.algorithm == ChecksumAlgorithm.SHA256) {
                "in-memory store verifies SHA256 checksums (got ${declared.algorithm})"
            }
            val actual = Checksum.sha256(bytes)
            if (actual.valueBase64 != declared.valueBase64) {
                // reject BEFORE storing — a corrupt object is never published
                throw ChecksumMismatchException(key, declared.valueBase64, actual.valueBase64)
            }
        }
        val stored = StoredObject(
            bytes = bytes,
            etag = "\"${sha256Hex(bytes).take(32)}\"",
            lastModified = clock.instant(),
            contentType = options.contentType,
            checksum = options.checksum ?: Checksum.sha256(bytes),
            userMetadata = options.userMetadata,
        )
        synchronized(objects) {
            val existing = objects[key.value]
            when (val c = options.condition) {
                is WriteCondition.None -> {}
                is WriteCondition.IfAbsent ->
                    if (existing != null) throw PreconditionFailedException(key, c)
                is WriteCondition.IfMatch ->
                    if (existing == null || existing.etag != c.etag) {
                        throw PreconditionFailedException(key, c)
                    }
            }
            objects[key.value] = stored
        }
        return PutResult(key, stored.etag, bytes.size.toLong(), stored.checksum)
    }

    override fun get(key: ObjectKey, options: GetOptions): ObjectContent {
        val stored = objects[key.value] ?: throw ObjectNotFoundException(key)
        val slice = options.range?.let { r ->
            val from = r.first.toInt().coerceAtLeast(0)
            val to = (r.last + 1).toInt().coerceAtMost(stored.bytes.size)
            stored.bytes.copyOfRange(from, to)
        } ?: stored.bytes
        val expected = options.expectedChecksum
            ?: stored.checksum.takeIf { options.verifyChecksum && options.range == null }
        val raw = ByteArrayInputStream(slice)
        val body = expected?.let { ChecksumVerifyingInputStream(raw, it, key) } ?: raw
        return ObjectContent(stored.toMetadata(key), body)
    }

    override fun delete(key: ObjectKey) {
        objects.remove(key.value)
    }

    override fun deletePrefix(prefix: String): Long {
        val doomed = objects.keys.filter { it.startsWith(prefix) }
        doomed.forEach { objects.remove(it) }
        return doomed.size.toLong()
    }

    override fun copy(from: ObjectKey, to: ObjectKey, options: CopyOptions): PutResult {
        val src = objects[from.value] ?: throw ObjectNotFoundException(from)
        return store(
            to, src.bytes,
            PutOptions(
                contentType = src.contentType, checksum = src.checksum,
                condition = options.condition, userMetadata = src.userMetadata,
            ),
        )
    }

    override fun startMultipart(key: ObjectKey, options: PutOptions): MultipartUpload {
        capabilities.require("multipart upload", StorageCapability.MULTIPART_UPLOAD)
        val id = UUID.randomUUID().toString()
        val pending = PendingUpload(key, options)
        uploads[id] = pending
        return object : MultipartUpload {
            override val key: ObjectKey = pending.key
            override val uploadId: String = id

            override fun uploadPart(partNumber: Int, source: ContentSource, checksum: Checksum?): CompletedUploadPart {
                require(partNumber >= 1) { "part numbers are 1-based" }
                check(!pending.terminated.get()) { "upload $id already completed/aborted" }
                val bytes = source.open().use { it.readBytes() }
                checksum?.let { declared ->
                    val actual = Checksum.sha256(bytes)
                    if (actual.valueBase64 != declared.valueBase64) {
                        throw ChecksumMismatchException(pending.key, declared.valueBase64, actual.valueBase64)
                    }
                }
                pending.parts[partNumber] = bytes
                return CompletedUploadPart(
                    partNumber, "\"${sha256Hex(bytes).take(32)}\"",
                    bytes.size.toLong(), Checksum.sha256(bytes),
                )
            }

            override fun complete(parts: List<CompletedUploadPart>): PutResult {
                check(pending.terminated.compareAndSet(false, true)) {
                    "upload $id already completed/aborted"
                }
                try {
                    val assembled = parts.sortedBy { it.partNumber }
                        .map {
                            pending.parts[it.partNumber]
                                ?: throw StorageIoException(
                                    "upload $id has no part ${it.partNumber}", retrySafe = false,
                                )
                        }
                        .fold(ByteArray(0)) { acc, b -> acc + b }
                    // the assembled object appears ATOMICALLY (complete-time), like S3
                    val result = store(pending.key, assembled, pending.options)
                    uploads.remove(id)
                    return result
                } catch (e: Exception) {
                    // failed completion leaves the upload OPEN (same as S3) so
                    // close()/abort() still reaps it — never a silent leak
                    pending.terminated.set(false)
                    throw e
                }
            }

            override fun abort() {
                pending.terminated.set(true)
                uploads.remove(id)
            }

            override fun close() {
                if (!pending.terminated.get()) abort()
            }
        }
    }

    /** Testing hook: ids of multipart uploads started but not completed/aborted. */
    fun incompleteUploadIds(): Set<String> = uploads.keys.toSet()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
