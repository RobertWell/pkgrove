package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.storage.Checksum
import com.pkgrove.pkgrovekit.storage.ChecksumAlgorithm
import com.pkgrove.pkgrovekit.storage.ChecksumMismatchException
import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.MultipartTransfer
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.PutOptions
import com.pkgrove.pkgrovekit.storage.StorageCapability
import com.pkgrove.pkgrovekit.storage.StorageIoException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.util.Base64
import java.util.zip.CRC32C

/**
 * Adapter edges against real MinIO: CRC32C checksums, capability-degraded
 * profiles (local verification), the `wrap` escape hatch, transport-failure
 * translation with the retry-safety verdict, and bucket bootstrap.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioAdapterEdgeIT {

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

    private fun crc32c(bytes: ByteArray): Checksum {
        val crc = CRC32C()
        crc.update(bytes)
        val v = crc.value
        val raw = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
        )
        return Checksum(ChecksumAlgorithm.CRC32C, Base64.getEncoder().encodeToString(raw))
    }

    @Test
    fun `crc32c checksums are provider-verified`() {
        val bytes = "crc-checked".toByteArray()
        store.put(ObjectKey("crc/ok"), ContentSource.of(bytes), PutOptions(checksum = crc32c(bytes)))
        assertThrows(ChecksumMismatchException::class.java) {
            store.put(
                ObjectKey("crc/bad"), ContentSource.of(bytes),
                PutOptions(checksum = crc32c("different".toByteArray())),
            )
        }
        assertFalse(store.exists(ObjectKey("crc/bad")))
    }

    @Test
    fun `capability-degraded profile still verifies sha256 locally`() {
        val degraded = S3StorageConfig(
            region = "us-east-1",
            endpoint = URI.create(MinioSupport.container.s3URL),
            credentials = S3Credentials.Static(MinioSupport.container.userName, MinioSupport.container.password),
            profile = S3CompatibilityProfile.generic(
                "no-checksums",
                setOf(StorageCapability.MULTIPART_UPLOAD, StorageCapability.CONDITIONAL_CREATE),
            ),
        )
        S3ObjectStore.open(degraded, store.bucket, "degraded").use { d ->
            val bytes = "locally-verified".toByteArray()
            // correct declaration passes (verified client-side, sent without header)
            d.put(ObjectKey("chk/ok"), ContentSource.of(bytes), PutOptions(checksum = Checksum.sha256(bytes)))
            // wrong declaration is caught BEFORE upload
            assertThrows(ChecksumMismatchException::class.java) {
                d.put(
                    ObjectKey("chk/bad"), ContentSource.of(bytes),
                    PutOptions(checksum = Checksum.sha256("other".toByteArray())),
                )
            }
            assertFalse(d.exists(ObjectKey("chk/bad")))
            // a checksum that CANNOT be verified anywhere is refused, typed
            assertThrows(IllegalArgumentException::class.java) {
                d.put(
                    ObjectKey("chk/md5"), ContentSource.of(bytes),
                    PutOptions(checksum = Checksum(ChecksumAlgorithm.MD5, "AAAA")),
                )
            }
            // multipart on the degraded profile: parts verified locally
            val big = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
            val result = MultipartTransfer.upload(
                d, ObjectKey("chk/mpu.bin"), big.inputStream(),
                MultipartTransfer.Options(partSizeBytes = 5 * 1024 * 1024, concurrency = 2),
            )
            assertEquals(big.size.toLong(), result.sizeBytes)
        }
    }

    @Test
    fun `wrap adopts a caller-built client and refuses to presign`() {
        val client = S3Client.builder()
            .region(Region.of("us-east-1"))
            .endpointOverride(URI.create(MinioSupport.container.s3URL))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                        MinioSupport.container.userName, MinioSupport.container.password,
                    ),
                ),
            )
            .forcePathStyle(true)
            .build()
        val wrapped = S3ObjectStore.wrap(client, store.bucket, "/wrapped/", S3CompatibilityProfile.minio())
        wrapped.put(ObjectKey("via-escape-hatch"), ContentSource.of("advanced"))
        assertTrue(store.exists(ObjectKey("wrapped/via-escape-hatch")))
        // presigning needs store-owned credentials — wrap() must refuse, typed
        assertThrows(IllegalStateException::class.java) {
            wrapped.presignGet(ObjectKey("via-escape-hatch"), java.time.Duration.ofMinutes(1))
        }
        wrapped.close() // must NOT close the caller's client…
        client.listBuckets() // …which therefore still works
        client.close()
    }

    @Test
    fun `transport failure surfaces as StorageIoException with a retry verdict`() {
        val dead = S3StorageConfig(
            region = "us-east-1",
            endpoint = URI.create("http://127.0.0.1:1"),
            credentials = S3Credentials.Static("k", "s"),
            connectTimeout = java.time.Duration.ofMillis(200),
            apiCallTimeout = java.time.Duration.ofSeconds(2),
            maxRetries = 1,
        )
        S3ObjectStore.open(dead, "nowhere").use { d ->
            val repeatable = assertThrows(StorageIoException::class.java) {
                d.put(ObjectKey("x/y"), ContentSource.of("data"))
            }
            assertTrue(repeatable.retrySafe, "a repeatable source is safe to retry after transport failure")

            val oneShot = assertThrows(StorageIoException::class.java) {
                d.put(ObjectKey("x/z"), ContentSource.oneShot("data".byteInputStream(), 4))
            }
            assertFalse(oneShot.retrySafe, "a one-shot source must NOT be marked retry-safe")
        }
    }

    @Test
    fun `bucket bootstrap is idempotent and list rejects bad page sizes`() {
        store.createBucketIfMissing() // exists — the head-bucket branch
        assertThrows(IllegalArgumentException::class.java) { store.list("", pageSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { store.list("", pageSize = 1001) }
    }

    @Test
    fun `session-token credentials construct without network`() {
        // MinIO won't accept an arbitrary STS token, so only the CONSTRUCTION
        // path is exercised: the client builds, no request is made.
        S3ObjectStore.open(
            S3StorageConfig(
                region = "us-east-1",
                endpoint = URI.create(MinioSupport.container.s3URL),
                credentials = S3Credentials.Static("k", "s", sessionToken = "t"),
                trustAllTls = true,
            ),
            "unused-bucket",
        ).close()
    }
}
