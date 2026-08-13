package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.MultipartTransfer
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.PutOptions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.InputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * HEL-236 guarantees 1+3 against real MinIO: bounded streaming multipart
 * upload/download, abort of incomplete uploads on failure/cancellation, and
 * the deterministic incomplete-upload cleanup rule.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioMultipartIT {

    @BeforeAll
    fun dockerOrSkip() = Assumptions.assumeTrue(
        MinioSupport.dockerAvailable,
        "Docker unavailable — MinIO ITs skipped here; they run for real in the dind-backed CI jobs",
    )

    // LAZY on purpose: an eager field initialiser starts the container during
    // class CONSTRUCTION, i.e. before @BeforeAll can skip — which is why the
    // Docker assumption alone did not stop the no-daemon failure.
    private val store by lazy { MinioSupport.newStore() }

    @AfterAll
    fun tearDown() {
        // Only close what was actually opened — touching `store` here would
        // instantiate it and re-create the very failure this guards against.
        if (MinioSupport.dockerAvailable) store.close()
    }

    /** Deterministic stream, produced on demand — the test never holds it whole. */
    private class SyntheticStream(private val total: Long) : InputStream() {
        private var produced = 0L
        override fun read(): Int {
            if (produced >= total) return -1
            return (produced++ * 31 + 7).toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (produced >= total) return -1
            var n = 0
            while (n < len && produced < total) {
                b[off + n] = ((produced * 31 + 7).toInt() and 0xff).toByte()
                produced++
                n++
            }
            return n
        }
    }

    private fun digestOf(total: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        val s = SyntheticStream(total)
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return Base64.getEncoder().encodeToString(md.digest())
    }

    private fun usedHeap(): Long {
        repeat(3) { System.gc() }
        Thread.sleep(50)
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `128MiB stream uploads multipart with bounded memory and byte-exact content`() {
        val key = ObjectKey("big/128mib.bin")
        val total = 128L * 1024 * 1024
        val partSize = 8 * 1024 * 1024
        val before = usedHeap()
        val result = MultipartTransfer.upload(
            store, key, SyntheticStream(total),
            MultipartTransfer.Options(partSizeBytes = partSize, concurrency = 4),
        )
        val after = usedHeap()
        assertEquals(total, result.sizeBytes)

        // BOUNDED (guarantee 1): peak retention must reflect concurrency*partSize
        // (32 MiB) + JVM noise, never the 128 MiB object. The 512m test heap
        // (build.gradle.kts) would make whole-object materialization OOM anyway.
        val retained = after - before
        assertTrue(
            retained < 96L * 1024 * 1024,
            "retained ${retained / 1024 / 1024} MiB after upload — looks like whole-object buffering",
        )
        // (the exact concurrency*partSize part-buffer bound is asserted in
        // storage-api's MultipartTransferTest, where the counter is visible)

        // byte-exact round trip, streamed to a digest (never materialized)
        val md = MessageDigest.getInstance("SHA-256")
        val beforeDl = usedHeap()
        store.get(key).use { content ->
            val buf = ByteArray(1 shl 16)
            val s = content.stream()
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val dlRetained = usedHeap() - beforeDl
        assertEquals(digestOf(total), Base64.getEncoder().encodeToString(md.digest()))
        assertTrue(
            dlRetained < 64L * 1024 * 1024,
            "retained ${dlRetained / 1024 / 1024} MiB after download — looks like whole-object buffering",
        )
        assertTrue(store.incompleteUploads().isEmpty())
    }

    @Test
    fun `cancellation aborts the multipart upload on the provider`() {
        val token = CancelToken.none()
        var parts = 0
        assertThrows(OperationCancelledException::class.java) {
            MultipartTransfer.upload(
                store, ObjectKey("big/cancelled.bin"), SyntheticStream(64L * 1024 * 1024),
                MultipartTransfer.Options(
                    partSizeBytes = 5 * 1024 * 1024,
                    concurrency = 2,
                    cancelToken = token,
                    onProgress = { p, _ -> // cancel after the third part is queued
                        parts = p
                        if (p == 3) token.cancel()
                    },
                ),
            )
        }
        assertTrue(parts >= 3)
        // guarantee 3: no incomplete upload survives cancellation
        assertTrue(store.incompleteUploads().isEmpty(), "cancelled upload left provider-side parts")
        assertFalse(store.exists(ObjectKey("big/cancelled.bin")))
    }

    @Test
    fun `raw lifecycle close-without-complete aborts and cleanup rule reaps strays`() {
        // a stray upload someone forgot (simulating a SIGKILLed worker: started,
        // never completed, never aborted — close() skipped deliberately)
        val stray = store.startMultipart(ObjectKey("stray/upload.bin"), PutOptions())
        stray.uploadPart(1, ContentSource.of(ByteArray(5 * 1024 * 1024)))
        assertEquals(1, store.incompleteUploads("stray/").size)

        // deterministic cleanup rule: initiated before now-0s => reaped
        Thread.sleep(1100) // MinIO 'initiated' has second precision
        val reaped = store.abortIncompleteUploads(Duration.ZERO, "stray/")
        assertEquals(1, reaped)
        assertTrue(store.incompleteUploads("stray/").isEmpty())

        // try-with-resources path: close() aborts an unfinished upload
        store.startMultipart(ObjectKey("stray/closed.bin"), PutOptions()).use { mpu ->
            mpu.uploadPart(1, ContentSource.of(ByteArray(5 * 1024 * 1024)))
        }
        assertTrue(store.incompleteUploads("stray/").isEmpty())
        assertFalse(store.exists(ObjectKey("stray/closed.bin")))
    }

    @Test
    fun `multipart complete can be conditional on absence`() {
        val key = ObjectKey("mpu/conditional.bin")
        store.put(key, ContentSource.of("already-here"))
        val e = assertThrows(Exception::class.java) {
            val mpu = store.startMultipart(
                key,
                PutOptions(condition = com.pkgrove.pkgrovekit.storage.WriteCondition.IfAbsent),
            )
            mpu.use {
                val p = it.uploadPart(1, ContentSource.of(ByteArray(1024)))
                it.complete(listOf(p))
            }
        }
        assertTrue(
            e is com.pkgrove.pkgrovekit.storage.PreconditionFailedException ||
                e is com.pkgrove.pkgrovekit.storage.StorageIoException,
            "expected a typed conditional-complete failure, got ${e::class.simpleName}",
        )
        // the existing object was not clobbered
        store.get(key).use {
            assertEquals("already-here", it.stream().readBytes().toString(Charsets.UTF_8))
        }
    }
}
