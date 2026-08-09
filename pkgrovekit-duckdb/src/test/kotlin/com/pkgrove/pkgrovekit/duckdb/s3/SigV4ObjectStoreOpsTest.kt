package com.pkgrove.pkgrovekit.duckdb.s3

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress

/**
 * HEL-234: [SigV4ObjectStoreOps] against a REAL http exchange (JDK HttpServer
 * as an in-process S3 endpoint) — the copy/delete/create success and failure
 * protocol including the S3 "200 OK with an error body" copy quirk, plus the
 * SigV4 request headers actually sent on the wire.
 */
class SigV4ObjectStoreOpsTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var body = ""
    private val requests = mutableListOf<Recorded>()

    private data class Recorded(val method: String, val path: String,
                                val headers: Map<String, String>)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex: HttpExchange ->
            requests += Recorded(
                ex.requestMethod, ex.requestURI.toString(),
                ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") },
            )
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        server.start()
    }

    @AfterEach
    fun tearDown() { server.stop(0) }

    private fun ops(): SigV4ObjectStoreOps = SigV4ObjectStoreOps(
        S3Session(
            endpoint = "127.0.0.1:${server.address.port}",
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "secret",
            useSsl = false,
            urlStyle = S3Session.UrlStyle.PATH,
        ),
    )

    // --- CopyObject ----------------------------------------------------------

    @Test
    fun `copyObject succeeds only on 200 WITH a CopyObjectResult body`() {
        status = 200
        body = "<CopyObjectResult><ETag>x</ETag></CopyObjectResult>"
        ops().copyObject("bkt", "src key.parquet", "dst.parquet")

        val r = requests.single()
        assertEquals("PUT", r.method)
        assertEquals("/bkt/dst.parquet", r.path)
        // copy-source carries the ENCODED source key
        assertEquals("/bkt/src%20key.parquet", r.headers["x-amz-copy-source"])
        val auth = r.headers["authorization"] ?: ""
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), auth)
        assertTrue("SignedHeaders=host;x-amz-content-sha256;x-amz-copy-source;x-amz-date" in auth, auth)
        assertTrue("Signature=" in auth, auth)
        assertEquals(AwsSigV4.EMPTY_PAYLOAD_SHA256, r.headers["x-amz-content-sha256"])
    }

    @Test
    fun `copyObject rejects the S3 quirk — 200 OK carrying an error document`() {
        status = 200
        body = "<Error><Code>InternalError</Code></Error>"
        val e = assertThrows<SigV4ObjectStoreOps.ObjectStoreException> {
            ops().copyObject("bkt", "a", "b")
        }
        assertTrue("CopyObject" in (e.message ?: ""), e.message ?: "")
        assertTrue("HTTP 200" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `copyObject fails loudly on a non-200 status`() {
        status = 404
        body = "<Error><Code>NoSuchKey</Code></Error>"
        val e = assertThrows<SigV4ObjectStoreOps.ObjectStoreException> {
            ops().copyObject("bkt", "missing", "b")
        }
        assertTrue("HTTP 404" in (e.message ?: ""), e.message ?: "")
        assertTrue("NoSuchKey" in (e.message ?: ""), e.message ?: "")
    }

    // --- DeleteObject --------------------------------------------------------

    @Test
    fun `deleteObject accepts 204 and 200`() {
        status = 204
        ops().deleteObject("bkt", "gone.parquet")
        status = 200
        ops().deleteObject("bkt", "gone.parquet")
        assertEquals(listOf("DELETE", "DELETE"), requests.map { it.method })
        assertEquals("/bkt/gone.parquet", requests[0].path)
    }

    @Test
    fun `deleteObject fails loudly on an error status`() {
        status = 403
        body = "<Error><Code>AccessDenied</Code></Error>"
        val e = assertThrows<SigV4ObjectStoreOps.ObjectStoreException> {
            ops().deleteObject("bkt", "k")
        }
        assertTrue("DeleteObject bkt/k" in (e.message ?: ""), e.message ?: "")
    }

    // --- CreateBucket --------------------------------------------------------

    @Test
    fun `createBucket treats 200 and already-exists 409 as success`() {
        status = 200
        ops().createBucket("bkt")
        status = 409
        body = "<Error><Code>BucketAlreadyOwnedByYou</Code></Error>"
        ops().createBucket("bkt")
        assertEquals(listOf("/bkt", "/bkt"), requests.map { it.path })
    }

    @Test
    fun `createBucket fails loudly on other statuses`() {
        status = 500
        body = "boom"
        assertThrows<SigV4ObjectStoreOps.ObjectStoreException> { ops().createBucket("bkt") }
    }

    // --- failure body truncation ---------------------------------------------

    @Test
    fun `failure messages truncate huge bodies and never carry credentials`() {
        status = 500
        body = "x".repeat(2000)
        val e = assertThrows<SigV4ObjectStoreOps.ObjectStoreException> {
            ops().deleteObject("bkt", "k")
        }
        val msg = e.message ?: ""
        assertTrue(msg.length < 700, "body must be truncated, was ${msg.length}")
        assertTrue("secret" !in msg, "credentials must never leak")
    }
}
