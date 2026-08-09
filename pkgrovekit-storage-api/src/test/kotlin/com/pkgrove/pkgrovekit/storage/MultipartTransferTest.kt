package com.pkgrove.pkgrovekit.storage

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.security.MessageDigest

/**
 * HEL-236 guarantees 1+3: bounded streaming multipart upload — peak memory is
 * `concurrency * partSize` by construction — and guaranteed abort of
 * incomplete uploads on failure/cancellation.
 */
class MultipartTransferTest {

    private val store = InMemoryObjectStore()

    /** Deterministic pseudo-random stream of [total] bytes; never materialized. */
    private class SyntheticStream(private val total: Long) : InputStream() {
        var produced = 0L
            private set

        override fun read(): Int {
            if (produced >= total) return -1
            val b = (produced * 31 + 7).toInt() and 0xff
            produced++
            return b
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
        val buf = ByteArray(8192)
        val s = SyntheticStream(total)
        while (true) {
            val n = s.read(buf, 0, buf.size)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return java.util.Base64.getEncoder().encodeToString(md.digest())
    }

    @Test
    fun `content within one part degrades to a plain put`() {
        val key = ObjectKey("small/object")
        val result = MultipartTransfer.upload(
            store, key, SyntheticStream(1000),
            MultipartTransfer.Options(partSizeBytes = 4096),
        )
        assertEquals(1000L, result.sizeBytes)
        assertTrue(store.incompleteUploadIds().isEmpty())
        assertEquals(digestOf(1000), store.head(key)!!.checksum!!.valueBase64)
    }

    @Test
    fun `large stream uploads in bounded concurrent parts and reassembles exactly`() {
        // limits tuned small so the test exercises MANY parts cheaply; the
        // provider minimum is irrelevant to the in-memory reference
        val tiny = object : ObjectStore by store {
            override val capabilities = store.capabilities.copy(
                limits = StorageLimits(
                    maxObjectSizeBytes = Long.MAX_VALUE,
                    maxPartSizeBytes = 1 shl 20,
                    minPartSizeBytes = 1,
                    maxPartsPerUpload = 10_000,
                ),
            )
        }
        val key = ObjectKey("big/object")
        val total = 1_000_000L
        val result = MultipartTransfer.upload(
            tiny, key, SyntheticStream(total),
            MultipartTransfer.Options(partSizeBytes = 64 * 1024, concurrency = 3),
        )
        assertEquals(total, result.sizeBytes)
        assertTrue(store.incompleteUploadIds().isEmpty())

        // BOUND held: never more than concurrency+1 part buffers alive (the +1
        // is the reader's next part before it blocks on the semaphore)
        assertTrue(
            MultipartTransfer.lastPeakBufferedParts.get() <= 4,
            "peak buffered parts ${MultipartTransfer.lastPeakBufferedParts.get()} exceeded the declared bound",
        )

        // byte-exact reassembly
        val md = MessageDigest.getInstance("SHA-256")
        store.get(key).use { content ->
            val buf = ByteArray(8192)
            val s = content.stream()
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        assertEquals(digestOf(total), java.util.Base64.getEncoder().encodeToString(md.digest()))
    }

    @Test
    fun `part-upload failure aborts the multipart upload`() {
        val failing = object : ObjectStore by store {
            override fun startMultipart(key: ObjectKey, options: PutOptions): MultipartUpload {
                val real = store.startMultipart(key, options)
                return object : MultipartUpload by real {
                    override fun uploadPart(partNumber: Int, source: ContentSource, checksum: Checksum?): CompletedUploadPart {
                        if (partNumber == 3) throw StorageIoException("simulated transport failure", retrySafe = false)
                        return real.uploadPart(partNumber, source, checksum)
                    }
                }
            }
        }
        val e = assertThrows(StorageException::class.java) {
            MultipartTransfer.upload(
                failing, ObjectKey("fail/object"), SyntheticStream(1_000_000),
                MultipartTransfer.Options(partSizeBytes = 64 * 1024, concurrency = 2),
            )
        }
        assertFalse(e.retrySafe)
        assertTrue(store.incompleteUploadIds().isEmpty(), "incomplete upload must be aborted on failure")
        assertFalse(store.exists(ObjectKey("fail/object")))
    }

    @Test
    fun `cancellation aborts the upload and stays typed`() {
        val token = CancelToken.none()
        val cancellingStore = object : ObjectStore by store {
            override fun startMultipart(key: ObjectKey, options: PutOptions): MultipartUpload {
                val real = store.startMultipart(key, options)
                return object : MultipartUpload by real {
                    override fun uploadPart(partNumber: Int, source: ContentSource, checksum: Checksum?): CompletedUploadPart {
                        if (partNumber == 2) token.cancel() // cancel mid-flight
                        return real.uploadPart(partNumber, source, checksum)
                    }
                }
            }
        }
        assertThrows(OperationCancelledException::class.java) {
            MultipartTransfer.upload(
                cancellingStore, ObjectKey("cancel/object"), SyntheticStream(1_000_000),
                MultipartTransfer.Options(partSizeBytes = 64 * 1024, concurrency = 1, cancelToken = token),
            )
        }
        assertTrue(store.incompleteUploadIds().isEmpty(), "cancelled upload must be aborted")
        assertFalse(store.exists(ObjectKey("cancel/object")))
    }

    @Test
    fun `provider without multipart rejects a multi-part stream BEFORE uploading`() {
        val noMpu = object : ObjectStore by store {
            override val capabilities = StorageCapabilities(
                provider = "no-mpu",
                supported = setOf(StorageCapability.CHECKSUM_SHA256),
                limits = StorageLimits(Long.MAX_VALUE, 1 shl 20, 1, 10_000),
            )
        }
        val e = assertThrows(CapabilityRejectedException::class.java) {
            MultipartTransfer.upload(
                noMpu, ObjectKey("nompu/object"), SyntheticStream(1_000_000),
                MultipartTransfer.Options(partSizeBytes = 64 * 1024),
            )
        }
        assertEquals(setOf(StorageCapability.MULTIPART_UPLOAD), e.missing)
        assertEquals(0, store.list("nompu/").count(), "no data moved before the rejection")

        // …but content that FITS one part still works without the capability
        val ok = MultipartTransfer.upload(
            noMpu, ObjectKey("nompu/small"), SyntheticStream(100),
            MultipartTransfer.Options(partSizeBytes = 64 * 1024),
        )
        assertEquals(100L, ok.sizeBytes)
    }

    @Test
    fun `part size beyond provider limits is rejected up front`() {
        val strict = object : ObjectStore by store {
            override val capabilities = store.capabilities.copy(
                limits = StorageLimits(Long.MAX_VALUE, maxPartSizeBytes = 1 shl 20, minPartSizeBytes = 1, maxPartsPerUpload = 10),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultipartTransfer.upload(
                strict, ObjectKey("limit/part"), SyntheticStream(10),
                MultipartTransfer.Options(partSizeBytes = 2 shl 20),
            )
        }
        assertEquals(0, store.list("limit/").count())
    }
}
