package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.storage.Checksum
import com.pkgrove.pkgrovekit.storage.ChecksumMismatchException
import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.CopyOptions
import com.pkgrove.pkgrovekit.storage.GetOptions
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.ObjectNotFoundException
import com.pkgrove.pkgrovekit.storage.PreconditionFailedException
import com.pkgrove.pkgrovekit.storage.PutOptions
import com.pkgrove.pkgrovekit.storage.StorageCapability
import com.pkgrove.pkgrovekit.storage.WriteCondition
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HEL-236: the vendor-neutral contract proven against REAL S3-compatible
 * storage (MinIO, path-style, via the AWS SDK v2 client) — the same
 * assertions ObjectStoreContractTest makes on the in-memory reference.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioObjectStoreIT {

    private val store = MinioSupport.newStore()

    @AfterAll
    fun tearDown() = store.close()

    @Test
    fun `put head get round trip with metadata and path-style access`() {
        val key = ObjectKey("data/hello.txt")
        val put = store.put(
            key, ContentSource.of("hello minio"),
            PutOptions(contentType = "text/plain", userMetadata = mapOf("purpose" to "it")),
        )
        assertEquals(11L, put.sizeBytes)
        assertNotNull(put.etag)

        val head = store.head(key)!!
        assertEquals(11L, head.sizeBytes)
        assertEquals("text/plain", head.contentType)
        assertEquals("it", head.userMetadata["purpose"])

        store.get(key).use {
            assertEquals("hello minio", it.stream().readBytes().toString(Charsets.UTF_8))
        }
        assertNull(store.head(ObjectKey("data/absent.txt")))
        val e = assertThrows(ObjectNotFoundException::class.java) {
            store.get(ObjectKey("data/absent.txt"))
        }
        assertTrue(e.retrySafe)
    }

    @Test
    fun `store-level prefix isolates keys and listing strips it`() {
        val prefixed = S3ObjectStore.open(MinioSupport.config(), store.bucket, "tenant-a")
        prefixed.use { p ->
            p.put(ObjectKey("inner/obj"), ContentSource.of("scoped"))
            assertEquals(listOf("inner/obj"), p.list("inner/").map { it.key.value }.toList())
            // physically the object lives under the prefix in the same bucket
            assertTrue(store.exists(ObjectKey("tenant-a/inner/obj")))
            assertEquals(1L, p.deletePrefix("inner/"))
        }
    }

    @Test
    fun `list paginates lazily in key order`() {
        (1..25).forEach { store.put(ObjectKey("paged/%03d".format(it)), ContentSource.of("v$it")) }
        val keys = store.list("paged/", pageSize = 10).map { it.key.value }.toList()
        assertEquals(25, keys.size)
        assertEquals(keys.sorted(), keys)
        assertEquals("paged/001", keys.first())
    }

    @Test
    fun `delete and deletePrefix`() {
        listOf("del/x", "del/y", "keep/z").forEach { store.put(ObjectKey(it), ContentSource.of(it)) }
        store.delete(ObjectKey("del/x"))
        store.delete(ObjectKey("del/x")) // idempotent
        assertEquals(1L, store.deletePrefix("del/"))
        assertTrue(store.exists(ObjectKey("keep/z")))
    }

    @Test
    fun `server-side copy preserves content and refuses destination conditions`() {
        store.put(ObjectKey("copy/src"), ContentSource.of("copy-me"), PutOptions(contentType = "text/plain"))
        val result = store.copy(ObjectKey("copy/src"), ObjectKey("copy/dst"))
        assertEquals(7L, result.sizeBytes)
        store.get(ObjectKey("copy/dst")).use {
            assertEquals("copy-me", it.stream().readBytes().toString(Charsets.UTF_8))
        }
        // S3 cannot do destination-conditional copy — must be refused loudly,
        // never silently unconditional (that would fake a commit primitive)
        assertThrows(IllegalArgumentException::class.java) {
            store.copy(ObjectKey("copy/src"), ObjectKey("copy/dst2"),
                       CopyOptions(condition = WriteCondition.IfAbsent))
        }
    }

    @Test
    fun `conditional create is enforced by the provider`() {
        assertTrue(StorageCapability.CONDITIONAL_CREATE in store.capabilities)
        val key = ObjectKey("cond/create")
        store.put(key, ContentSource.of("winner"), PutOptions(condition = WriteCondition.IfAbsent))
        val e = assertThrows(PreconditionFailedException::class.java) {
            store.put(key, ContentSource.of("loser"), PutOptions(condition = WriteCondition.IfAbsent))
        }
        assertFalse(e.retrySafe)
        store.get(key).use {
            assertEquals("winner", it.stream().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `conditional update requires the current etag`() {
        assertTrue(StorageCapability.CONDITIONAL_UPDATE in store.capabilities)
        val key = ObjectKey("cond/update")
        val v1 = store.put(key, ContentSource.of("v1"))
        store.put(key, ContentSource.of("v2"), PutOptions(condition = WriteCondition.IfMatch(v1.etag!!)))
        assertThrows(PreconditionFailedException::class.java) {
            store.put(key, ContentSource.of("v3"), PutOptions(condition = WriteCondition.IfMatch(v1.etag!!)))
        }
        store.get(key).use {
            assertEquals("v2", it.stream().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `provider verifies declared sha256 and rejects corruption at put`() {
        val key = ObjectKey("check/bad")
        val e = assertThrows(ChecksumMismatchException::class.java) {
            store.put(
                key, ContentSource.of("actual-content"),
                PutOptions(checksum = Checksum.sha256("declared-content".toByteArray())),
            )
        }
        assertFalse(e.retrySafe)
        assertFalse(store.exists(key), "a checksum-rejected object must not exist")

        // and a correct declaration round-trips with checksum surfaced on head
        val ok = ObjectKey("check/ok")
        val bytes = "verified-content".toByteArray()
        store.put(ok, ContentSource.of(bytes), PutOptions(checksum = Checksum.sha256(bytes)))
        assertEquals(Checksum.sha256(bytes).valueBase64, store.head(ok)!!.checksum!!.valueBase64)
    }

    @Test
    fun `download verifies against stored checksum and caller expectation`() {
        val key = ObjectKey("check/dl")
        val bytes = "download-me".toByteArray()
        store.put(key, ContentSource.of(bytes), PutOptions(checksum = Checksum.sha256(bytes)))
        // stored-checksum verification passes on a clean full read
        store.get(key).use { it.stream().readBytes() }
        // a wrong caller expectation fails AT EOF, typed
        store.get(key, GetOptions(expectedChecksum = Checksum.sha256("other".toByteArray()))).use { c ->
            assertThrows(ChecksumMismatchException::class.java) { c.stream().readBytes() }
        }
    }

    @Test
    fun `range get returns the slice without checksum interference`() {
        val key = ObjectKey("range/r")
        store.put(key, ContentSource.of("0123456789"), PutOptions(checksum = Checksum.sha256("0123456789".toByteArray())))
        store.get(key, GetOptions(range = 2L..5L)).use {
            assertEquals("2345", it.stream().readBytes().toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `presigned GET works over plain http and redacts its query in logs`() {
        val key = ObjectKey("presign/obj")
        store.put(key, ContentSource.of("presigned-content"))
        val presigned = store.presignGet(key, Duration.ofMinutes(5))

        // the textual form (what would reach logs) exposes NO signature material
        val text = presigned.toString()
        assertFalse(text.contains("X-Amz-Signature", ignoreCase = true), "presigned query leaked: $text")
        assertFalse(text.contains(MinioSupport.container.password), "credential leaked into toString")
        assertTrue(text.contains("<presigned-query-redacted>"))

        // but the URL itself grants access without credentials
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(presigned.url).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        assertEquals("presigned-content", response.body())
    }

    @Test
    fun `typed errors never carry credentials`() {
        val e = assertThrows(ObjectNotFoundException::class.java) {
            store.get(ObjectKey("errors/none"))
        }
        val text = "${e.message} ${e.cause?.message ?: ""}"
        assertFalse(text.contains(MinioSupport.container.password), "secret leaked into error text")
        assertFalse(text.contains("X-Amz-Signature"), "signed query leaked into error text")
    }

    @Test
    fun `unknown-length puts are refused toward MultipartTransfer`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put(
                ObjectKey("bad/unknown-length"),
                object : ContentSource {
                    override val lengthBytes: Long? = null
                    override val repeatable = false
                    override fun open() = "x".byteInputStream()
                },
            )
        }
    }
}
