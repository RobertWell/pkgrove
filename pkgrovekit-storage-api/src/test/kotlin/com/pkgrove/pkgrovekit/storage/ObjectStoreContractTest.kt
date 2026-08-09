package com.pkgrove.pkgrovekit.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HEL-236: the vendor-neutral store contract, proven on the reference
 * [InMemoryObjectStore]. The MinIO suite in pkgrovekit-storage-s3 asserts the
 * same semantics against a real S3-compatible provider.
 */
class ObjectStoreContractTest {

    private val store = InMemoryObjectStore()

    @Test
    fun `put head get round trip with metadata`() {
        val key = ObjectKey("data/hello.txt")
        val put = store.put(
            key, ContentSource.of("hello world"),
            PutOptions(contentType = "text/plain", userMetadata = mapOf("purpose" to "test")),
        )
        assertEquals(11L, put.sizeBytes)
        assertNotNull(put.etag)

        val head = store.head(key)!!
        assertEquals("text/plain", head.contentType)
        assertEquals(mapOf("purpose" to "test"), head.userMetadata)
        assertEquals(11L, head.sizeBytes)

        store.get(key).use { content ->
            assertEquals("hello world", content.stream().readBytes().toString(Charsets.UTF_8))
        }
        assertTrue(store.exists(key))
        assertNull(store.head(ObjectKey("data/absent.txt")))
        assertFalse(store.exists(ObjectKey("data/absent.txt")))
    }

    @Test
    fun `get of absent key is a typed not-found`() {
        val e = assertThrows(ObjectNotFoundException::class.java) {
            store.get(ObjectKey("nope/missing"))
        }
        assertEquals(ObjectKey("nope/missing"), e.key)
        assertTrue(e.retrySafe)
    }

    @Test
    fun `list is prefix-scoped and key-ordered`() {
        listOf("a/1", "a/2", "b/1", "a/10").forEach {
            store.put(ObjectKey(it), ContentSource.of(it))
        }
        assertEquals(listOf("a/1", "a/10", "a/2"), store.list("a/").map { it.key.value }.toList())
        assertEquals(4, store.list("").count())
        assertEquals(0, store.list("zzz/").count())
    }

    @Test
    fun `delete is idempotent and deletePrefix counts`() {
        listOf("p/x", "p/y", "q/z").forEach { store.put(ObjectKey(it), ContentSource.of(it)) }
        store.delete(ObjectKey("p/x"))
        store.delete(ObjectKey("p/x")) // absent — not an error
        assertEquals(1L, store.deletePrefix("p/"))
        assertEquals(0L, store.deletePrefix("p/"))
        assertTrue(store.exists(ObjectKey("q/z")))
    }

    @Test
    fun `copy is server-side and preserves content and metadata`() {
        val from = ObjectKey("src/a")
        store.put(from, ContentSource.of("payload"), PutOptions(contentType = "text/plain"))
        val result = store.copy(from, ObjectKey("dst/a"))
        assertEquals(7L, result.sizeBytes)
        assertEquals("text/plain", store.head(ObjectKey("dst/a"))!!.contentType)
        assertThrows(ObjectNotFoundException::class.java) {
            store.copy(ObjectKey("src/absent"), ObjectKey("dst/b"))
        }
    }

    @Test
    fun `conditional create refuses when the key exists`() {
        val key = ObjectKey("cond/create")
        store.put(key, ContentSource.of("first"), PutOptions(condition = WriteCondition.IfAbsent))
        val e = assertThrows(PreconditionFailedException::class.java) {
            store.put(key, ContentSource.of("second"), PutOptions(condition = WriteCondition.IfAbsent))
        }
        assertEquals(key, e.key)
        assertFalse(e.retrySafe)
        // loser did not overwrite
        store.get(key).use { assertEquals("first", it.stream().readBytes().toString(Charsets.UTF_8)) }
    }

    @Test
    fun `conditional update requires the current etag`() {
        val key = ObjectKey("cond/update")
        val v1 = store.put(key, ContentSource.of("v1"))
        store.put(key, ContentSource.of("v2"), PutOptions(condition = WriteCondition.IfMatch(v1.etag!!)))
        // etag changed — the stale writer must fail
        assertThrows(PreconditionFailedException::class.java) {
            store.put(key, ContentSource.of("v3"), PutOptions(condition = WriteCondition.IfMatch(v1.etag!!)))
        }
        // if-match against an absent key also fails
        assertThrows(PreconditionFailedException::class.java) {
            store.put(ObjectKey("cond/absent"), ContentSource.of("x"),
                      PutOptions(condition = WriteCondition.IfMatch("\"nope\"")))
        }
    }

    @Test
    fun `declared checksum is verified on put and mismatch stores nothing`() {
        val key = ObjectKey("check/ok")
        val bytes = "checksummed".toByteArray()
        val ok = store.put(key, ContentSource.of(bytes), PutOptions(checksum = Checksum.sha256(bytes)))
        assertEquals(Checksum.sha256(bytes), ok.checksum)

        val bad = ObjectKey("check/bad")
        val e = assertThrows(ChecksumMismatchException::class.java) {
            store.put(bad, ContentSource.of("actual"), PutOptions(checksum = Checksum.sha256("declared".toByteArray())))
        }
        assertFalse(e.retrySafe)
        assertFalse(store.exists(bad)) // never a corrupt published object
    }

    @Test
    fun `get verifies stored checksum at EOF and expectedChecksum overrides`() {
        val key = ObjectKey("check/get")
        store.put(key, ContentSource.of("content"))
        // stored checksum verifies fine on full read
        store.get(key).use { it.stream().readBytes() }
        // caller-supplied wrong expectation fails AT EOF, visibly
        val wrong = Checksum.sha256("other".toByteArray())
        store.get(key, GetOptions(expectedChecksum = wrong)).use { content ->
            assertThrows(ChecksumMismatchException::class.java) { content.stream().readBytes() }
        }
    }

    @Test
    fun `range get returns the slice`() {
        val key = ObjectKey("range/a")
        store.put(key, ContentSource.of("0123456789"))
        store.get(key, GetOptions(range = 2L..5L)).use {
            assertEquals("2345", it.stream().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `multipart lifecycle completes atomically and close aborts`() {
        val key = ObjectKey("mpu/asm")
        val upload = store.startMultipart(key, PutOptions(contentType = "application/octet-stream"))
        val p1 = upload.uploadPart(1, ContentSource.of("aaaa"))
        val p2 = upload.uploadPart(2, ContentSource.of("bbbb"), Checksum.sha256("bbbb".toByteArray()))
        assertFalse(store.exists(key)) // invisible until complete
        val result = upload.complete(listOf(p2, p1)) // any order
        assertEquals(8L, result.sizeBytes)
        store.get(key).use { assertEquals("aaaabbbb", it.stream().readBytes().toString(Charsets.UTF_8)) }
        assertTrue(store.incompleteUploadIds().isEmpty())

        // close-without-complete aborts (guarantee 3: nothing leaks)
        val abandoned = store.startMultipart(ObjectKey("mpu/abandoned"))
        abandoned.uploadPart(1, ContentSource.of("zz"))
        abandoned.close()
        assertTrue(store.incompleteUploadIds().isEmpty())
        assertFalse(store.exists(ObjectKey("mpu/abandoned")))
    }

    @Test
    fun `multipart part checksum mismatch is typed`() {
        val upload = store.startMultipart(ObjectKey("mpu/badpart"))
        assertThrows(ChecksumMismatchException::class.java) {
            upload.uploadPart(1, ContentSource.of("actual"), Checksum.sha256("declared".toByteArray()))
        }
        upload.abort()
        upload.abort() // idempotent
    }

    @Test
    fun `one-shot content source refuses a second open`() {
        val src = ContentSource.oneShot("abc".byteInputStream(), 3)
        assertFalse(src.repeatable)
        src.open().readBytes()
        assertThrows(IllegalStateException::class.java) { src.open() }
    }

    @Test
    fun `file content source streams and reports length`() {
        val f = kotlin.io.path.createTempFile(suffix = ".bin")
        try {
            f.toFile().writeBytes("file-bytes".toByteArray())
            val src = ContentSource.of(f)
            assertTrue(src.repeatable)
            assertEquals(10L, src.lengthBytes)
            assertEquals("file-bytes", src.open().use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            f.toFile().delete()
        }
    }
}
