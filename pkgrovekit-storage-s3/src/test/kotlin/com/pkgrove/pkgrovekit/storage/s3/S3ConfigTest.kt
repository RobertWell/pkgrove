package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.storage.CapabilityRejectedException
import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.PutOptions
import com.pkgrove.pkgrovekit.storage.StorageCapability
import com.pkgrove.pkgrovekit.storage.WriteCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant

/** Docker-free adapter checks: config validation, secret hygiene, fail-fast gates. */
class S3ConfigTest {

    @Test
    fun `static credentials never print their secret`() {
        val creds = S3Credentials.Static("AKIAEXAMPLE", "super-secret-value", "session-token")
        val text = creds.toString()
        assertFalse(text.contains("super-secret-value"))
        assertFalse(text.contains("session-token"))
        assertTrue(text.contains("AKIAEXAMPLE")) // the key id is an identifier, not a secret
        assertThrows(IllegalArgumentException::class.java) { S3Credentials.Static("", "x") }
        assertThrows(IllegalArgumentException::class.java) { S3Credentials.Static("x", " ".trim()) }
    }

    @Test
    fun `config defaults follow the endpoint`() {
        val aws = S3StorageConfig(region = "eu-west-1")
        assertFalse(aws.pathStyleAccess)
        assertEquals("amazon-s3", aws.profile.name)

        val minio = S3StorageConfig(region = "us-east-1", endpoint = URI.create("http://localhost:9000"))
        assertTrue(minio.pathStyleAccess)
        assertEquals("minio", minio.profile.name)

        assertThrows(IllegalArgumentException::class.java) { S3StorageConfig(region = " ") }
        assertThrows(IllegalArgumentException::class.java) { S3StorageConfig(region = "r", maxRetries = 0) }
    }

    @Test
    fun `generic profiles claim only what the caller verified`() {
        val profile = S3CompatibilityProfile.generic(
            "wasabi-unverified",
            setOf(StorageCapability.MULTIPART_UPLOAD),
        )
        assertEquals(setOf(StorageCapability.MULTIPART_UPLOAD), profile.capabilities)
        // the two tested targets both claim the full working set
        assertTrue(StorageCapability.CONDITIONAL_CREATE in S3CompatibilityProfile.amazonS3().capabilities)
        assertTrue(StorageCapability.CONDITIONAL_CREATE in S3CompatibilityProfile.minio().capabilities)
    }

    @Test
    fun `capability gates fire before any network traffic`() {
        // endpoint points nowhere routable — proving the rejection is pre-I/O
        val config = S3StorageConfig(
            region = "us-east-1",
            endpoint = URI.create("http://127.0.0.1:1"),
            credentials = S3Credentials.Static("k", "s"),
            profile = S3CompatibilityProfile.generic("crippled", emptySet()),
        )
        S3ObjectStore.open(config, "bucket").use { store ->
            val mpu = assertThrows(CapabilityRejectedException::class.java) {
                store.startMultipart(ObjectKey("a/b"))
            }
            assertEquals(setOf(StorageCapability.MULTIPART_UPLOAD), mpu.missing)

            val cond = assertThrows(CapabilityRejectedException::class.java) {
                store.put(
                    ObjectKey("a/b"), ContentSource.of("x"),
                    PutOptions(condition = WriteCondition.IfAbsent),
                )
            }
            assertEquals(setOf(StorageCapability.CONDITIONAL_CREATE), cond.missing)

            val presign = assertThrows(CapabilityRejectedException::class.java) {
                store.presignGet(ObjectKey("a/b"), java.time.Duration.ofMinutes(1))
            }
            assertEquals(setOf(StorageCapability.PRESIGNED_URLS), presign.missing)
        }
    }

    @Test
    fun `presigned url textual form is redacted by construction`() {
        val url = PresignedUrl(
            URI.create(
                "https://s3.example.com/bucket/key" +
                    "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=deadbeef&X-Amz-Credential=AKIA%2F123",
            ),
            Instant.parse("2026-08-09T00:00:00Z"),
        )
        val text = url.toString()
        assertFalse(text.contains("deadbeef"))
        assertFalse(text.contains("X-Amz-Signature"))
        assertTrue(text.contains("<presigned-query-redacted>"))
        assertTrue(text.contains("/bucket/key"))
    }
}
