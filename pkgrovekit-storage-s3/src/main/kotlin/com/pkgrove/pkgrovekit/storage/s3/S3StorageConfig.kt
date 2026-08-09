package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.storage.StorageCapability
import com.pkgrove.pkgrovekit.storage.StorageLimits
import java.net.URI
import java.time.Duration

/**
 * Configuration for [S3ObjectStore] (HEL-236). Vendor-neutral surface: nothing
 * here is an AWS SDK type, so ordinary consumers configure MinIO or Amazon S3
 * without importing the SDK. TLS is governed by the [endpoint] scheme
 * (`https://` verified by default); [trustAllTls] exists for throwaway dev
 * endpoints with self-signed certificates and must never reach production.
 */
data class S3StorageConfig(
    /** AWS region, or the region string a compatible provider expects (MinIO accepts any). */
    val region: String,
    /** Custom endpoint (MinIO, R2, …); null = Amazon S3's own endpoints. */
    val endpoint: URI? = null,
    val credentials: S3Credentials = S3Credentials.DefaultChain,
    /**
     * Path-style addressing (`endpoint/bucket/key`). Default follows the
     * endpoint: custom endpoints (MinIO et al.) usually need path-style;
     * Amazon S3 prefers virtual-hosted.
     */
    val pathStyleAccess: Boolean = endpoint != null,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val socketTimeout: Duration = Duration.ofSeconds(60),
    /** Whole-call ceiling including SDK retries; null = no ceiling. */
    val apiCallTimeout: Duration? = null,
    /** Max attempts for the SDK's retryable (idempotent) operations. */
    val maxRetries: Int = 3,
    /** DEV ONLY: accept any TLS certificate (self-signed local MinIO). */
    val trustAllTls: Boolean = false,
    /** What this provider supports; defaults by endpoint (AWS vs MinIO-class). */
    val profile: S3CompatibilityProfile =
        if (endpoint == null) S3CompatibilityProfile.amazonS3() else S3CompatibilityProfile.minio(),
) {
    init {
        require(region.isNotBlank()) { "region must not be blank" }
        require(maxRetries >= 1) { "maxRetries must be >= 1 (1 = no retry)" }
    }
}

/**
 * Credential selection without exposing AWS SDK types. [Static] never prints
 * its secret; the advanced escape hatch (a caller-built `S3Client`) is
 * [S3ObjectStore.wrap].
 */
sealed class S3Credentials {
    /** The SDK default chain: env, system props, profile files, IMDS, IRSA … */
    data object DefaultChain : S3Credentials()

    /** Explicit keys (MinIO root/service accounts, provider tokens). */
    class Static(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String? = null,
    ) : S3Credentials() {
        init {
            require(accessKeyId.isNotBlank()) { "accessKeyId must not be blank" }
            require(secretAccessKey.isNotBlank()) { "secretAccessKey must not be blank" }
        }

        /** Secrets never leak through logs/toString (HEL-236 security rule). */
        override fun toString(): String = "S3Credentials.Static(accessKeyId=$accessKeyId, secret=***)"
    }
}

/**
 * Provider capability/limits profile (HEL-236 capability model). "S3
 * compatible" varies by provider — this is where the variance is DECLARED so
 * workflows can fail before data movement. The shipped profiles cover the two
 * officially tested targets; for other providers start from [generic] and
 * declare only what you have verified (see docs/storage.md § provider
 * compatibility).
 */
data class S3CompatibilityProfile(
    val name: String,
    val capabilities: Set<StorageCapability>,
    val limits: StorageLimits = StorageLimits.s3Baseline(),
) {
    companion object {
        /** Amazon S3 (verified via the opt-in cloud smoke test). */
        @JvmStatic
        fun amazonS3(): S3CompatibilityProfile = S3CompatibilityProfile(
            name = "amazon-s3",
            capabilities = setOf(
                StorageCapability.MULTIPART_UPLOAD,
                StorageCapability.CONDITIONAL_CREATE,
                StorageCapability.CONDITIONAL_UPDATE,
                StorageCapability.CHECKSUM_SHA256,
                StorageCapability.CHECKSUM_CRC32C,
                StorageCapability.VERSIONING,
                StorageCapability.SERVER_SIDE_ENCRYPTION,
                StorageCapability.PRESIGNED_URLS,
                StorageCapability.OBJECT_LOCK,
                StorageCapability.CONSISTENT_LISTING,
            ),
        )

        /** MinIO (verified in CI by the Testcontainers suite in this module). */
        @JvmStatic
        fun minio(): S3CompatibilityProfile = S3CompatibilityProfile(
            name = "minio",
            capabilities = setOf(
                StorageCapability.MULTIPART_UPLOAD,
                StorageCapability.CONDITIONAL_CREATE,
                StorageCapability.CONDITIONAL_UPDATE,
                StorageCapability.CHECKSUM_SHA256,
                StorageCapability.CHECKSUM_CRC32C,
                StorageCapability.VERSIONING,
                StorageCapability.SERVER_SIDE_ENCRYPTION,
                StorageCapability.PRESIGNED_URLS,
                StorageCapability.OBJECT_LOCK,
                StorageCapability.CONSISTENT_LISTING,
            ),
        )

        /**
         * Start-from-nothing profile for other S3-compatible services: declare
         * ONLY verified capabilities — an over-claimed capability turns the
         * fail-fast gate into a mid-transfer surprise, an under-claimed one is
         * merely conservative.
         */
        @JvmStatic
        fun generic(
            name: String,
            capabilities: Set<StorageCapability>,
            limits: StorageLimits = StorageLimits.s3Baseline(),
        ): S3CompatibilityProfile = S3CompatibilityProfile(name, capabilities, limits)
    }
}
