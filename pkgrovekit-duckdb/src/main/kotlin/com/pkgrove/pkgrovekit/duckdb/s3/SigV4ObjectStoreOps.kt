package com.pkgrove.pkgrovekit.duckdb.s3

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HEL-236: the built-in [S3Publisher.ObjectStoreOps] — CopyObject / Delete /
 * (for tests and first-run consumers) CreateBucket against any S3-compatible
 * endpoint, signed with AWS Signature V4 over `java.net.http`. Zero new
 * dependencies, matching this module's consumer-controlled-runtime stance.
 *
 * Only the two operations DuckDB's httpfs cannot perform live here; reads and
 * writes of object BYTES stay on the proven DuckDB path.
 */
class SigV4ObjectStoreOps @JvmOverloads constructor(
    private val session: S3Session,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build(),
    private val clock: Clock = Clock.systemUTC(),
) : S3Publisher.ObjectStoreOps {

    /** An object-store request that did not succeed. Carries status + body
     *  head (S3 error documents are small XML); never credentials. */
    class ObjectStoreException(message: String) : RuntimeException(message)

    override fun copyObject(bucket: String, sourceKey: String, destinationKey: String) {
        val response = send(
            "PUT", bucket, destinationKey,
            extraHeaders = mapOf(
                "x-amz-copy-source" to "/$bucket/${AwsSigV4.encodePath(sourceKey)}"
            ),
        )
        // S3 quirk: CopyObject may return 200 OK with an <Error> document in
        // the body (the copy failed midway server-side). Success is 200 AND a
        // CopyObjectResult body — anything else is a loud failure.
        if (response.statusCode() != 200 || "<CopyObjectResult" !in response.body()) {
            throw failure("CopyObject $bucket/$sourceKey -> $destinationKey", response)
        }
    }

    override fun deleteObject(bucket: String, key: String) {
        val response = send("DELETE", bucket, key)
        if (response.statusCode() !in setOf(200, 204)) {
            throw failure("DeleteObject $bucket/$key", response)
        }
    }

    /** Create [bucket] if it does not exist (409/"already owned" is success).
     *  Convenience for first-run consumers and the integration tests. */
    fun createBucket(bucket: String) {
        val response = send("PUT", bucket, key = null)
        if (response.statusCode() !in setOf(200, 409)) {
            throw failure("CreateBucket $bucket", response)
        }
    }

    private fun send(
        method: String,
        bucket: String,
        key: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val encodedKey = key?.let { AwsSigV4.encodePath(it) }
        val (host, canonicalUri) = when (session.urlStyle) {
            S3Session.UrlStyle.PATH ->
                session.endpoint to "/$bucket" + (encodedKey?.let { "/$it" } ?: "")
            S3Session.UrlStyle.VHOST ->
                "$bucket.${session.endpoint}" to "/" + (encodedKey ?: "")
        }
        val amzDate = AMZ_DATE.format(clock.instant().atOffset(ZoneOffset.UTC))
        val headers = TreeMap<String, String>()
        headers["host"] = host
        headers["x-amz-date"] = amzDate
        headers["x-amz-content-sha256"] = AwsSigV4.EMPTY_PAYLOAD_SHA256
        for ((n, v) in extraHeaders) headers[n.lowercase()] = v

        val authorization = AwsSigV4.authorizationHeader(
            method = method,
            canonicalUri = canonicalUri,
            canonicalQuery = "",
            headers = headers,
            payloadSha256 = AwsSigV4.EMPTY_PAYLOAD_SHA256,
            region = session.region,
            accessKeyId = session.accessKeyId,
            secretAccessKey = session.secretAccessKey,
            amzDate = amzDate,
        )

        val scheme = if (session.useSsl) "https" else "http"
        val builder = HttpRequest.newBuilder(URI("$scheme://$host$canonicalUri"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .timeout(java.time.Duration.ofSeconds(60))
            .header("Authorization", authorization)
        // `host` is set by the client itself; everything else is ours to add.
        for ((n, v) in headers) if (n != "host") builder.header(n, v)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun failure(operation: String, response: HttpResponse<String>) =
        ObjectStoreException(
            "$operation failed: HTTP ${response.statusCode()} ${response.body().take(500)}"
        )

    private companion object {
        val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}

/**
 * AWS Signature Version 4 for the S3 service — pure functions, pinned by unit
 * test against AWS's published signing example.
 */
internal object AwsSigV4 {

    /** SHA-256 of the empty string — the payload hash for bodiless requests. */
    const val EMPTY_PAYLOAD_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * Build the `Authorization` header. [headers] must contain every header to
     * be signed (at minimum host, x-amz-date, x-amz-content-sha256), keyed by
     * LOWERCASE name; [amzDate] is `yyyyMMdd'T'HHmmss'Z'`.
     */
    fun authorizationHeader(
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadSha256: String,
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        amzDate: String,
        service: String = "s3",
    ): String {
        val sorted = TreeMap<String, String>()
        for ((n, v) in headers) sorted[n.lowercase()] = v.trim()
        val signedHeaders = sorted.keys.joinToString(";")
        val canonicalRequest = buildString {
            append(method).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            for ((n, v) in sorted) append(n).append(':').append(v).append('\n')
            append('\n')
            append(signedHeaders).append('\n')
            append(payloadSha256)
        }
        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n" +
            hex(sha256(canonicalRequest.toByteArray(UTF_8)))
        val signingKey = hmac(
            hmac(
                hmac(
                    hmac("AWS4$secretAccessKey".toByteArray(UTF_8), dateStamp),
                    region,
                ),
                service,
            ),
            "aws4_request",
        )
        val signature = hex(hmac(signingKey, stringToSign))
        return "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
    }

    /**
     * URI-encode an object key for the canonical URI / copy-source header:
     * every byte percent-encoded (uppercase hex, UTF-8) except unreserved
     * characters and `/`. S3 canonicalizes the path encoded ONCE.
     */
    fun encodePath(key: String): String = buildString {
        for (b in key.toByteArray(UTF_8)) {
            val c = b.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '.' || c == '_' || c == '~' || c == '/'
            ) {
                append(c)
            } else {
                append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray(UTF_8))
        }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
