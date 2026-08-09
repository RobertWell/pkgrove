package com.pkgrove.pkgrovekit.storage

/**
 * Provider-varying behavior, made DISCOVERABLE (HEL-236 capability model).
 * "S3-compatible" is a spectrum — MinIO, Amazon S3, R2, Ceph RGW, B2 etc.
 * differ on exactly these axes — so a workflow states what it needs via
 * [StorageCapabilities.require] and fails BEFORE any data moves with a typed
 * [CapabilityRejectedException] naming what is missing, instead of failing
 * halfway through a transfer with a provider-specific error.
 */
enum class StorageCapability {
    /** CreateMultipartUpload/UploadPart/Complete/Abort lifecycle. */
    MULTIPART_UPLOAD,

    /** Conditional CREATE — `If-None-Match: *` on PUT ([WriteCondition.IfAbsent]). */
    CONDITIONAL_CREATE,

    /** Conditional UPDATE — `If-Match: <etag>` on PUT ([WriteCondition.IfMatch]). */
    CONDITIONAL_UPDATE,

    /** Server-verified SHA-256 content checksums (`x-amz-checksum-sha256`). */
    CHECKSUM_SHA256,

    /** Server-verified CRC32C content checksums. */
    CHECKSUM_CRC32C,

    /** Object versioning (multiple versions retained per key). */
    VERSIONING,

    /** Server-side encryption at rest. */
    SERVER_SIDE_ENCRYPTION,

    /** Presigned URL generation (adapter-level API). */
    PRESIGNED_URLS,

    /** Object lock / retention (WORM). */
    OBJECT_LOCK,

    /** Strongly-consistent listing (a completed PUT is visible to `list`). */
    CONSISTENT_LISTING,
}

/** Provider size limits; workflows size parts/objects against THESE, never constants. */
data class StorageLimits(
    val maxObjectSizeBytes: Long,
    val maxPartSizeBytes: Long,
    val minPartSizeBytes: Long,
    val maxPartsPerUpload: Int,
) {
    companion object {
        /** Amazon S3's documented limits — the de-facto compatibility baseline. */
        @JvmStatic
        fun s3Baseline(): StorageLimits = StorageLimits(
            maxObjectSizeBytes = 5L * 1024 * 1024 * 1024 * 1024, // 5 TiB
            maxPartSizeBytes = 5L * 1024 * 1024 * 1024, // 5 GiB
            minPartSizeBytes = 5L * 1024 * 1024, // 5 MiB (except last part)
            maxPartsPerUpload = 10_000,
        )
    }
}

/**
 * What one concrete store supports. [provider] is a short diagnostic label
 * ("minio", "amazon-s3", "in-memory", ...) used in typed rejections — never
 * parsed, never a contract.
 */
data class StorageCapabilities(
    val provider: String,
    val supported: Set<StorageCapability>,
    val limits: StorageLimits = StorageLimits.s3Baseline(),
) {
    operator fun contains(capability: StorageCapability): Boolean = capability in supported

    /**
     * Fail-fast gate: throws [CapabilityRejectedException] listing EVERY
     * missing capability (not just the first) when [needed] is not fully
     * supported. Call this before data movement begins.
     */
    fun require(operation: String, vararg needed: StorageCapability) {
        val missing = needed.filter { it !in supported }
        if (missing.isNotEmpty()) {
            throw CapabilityRejectedException(operation, provider, missing.toSet())
        }
    }
}
