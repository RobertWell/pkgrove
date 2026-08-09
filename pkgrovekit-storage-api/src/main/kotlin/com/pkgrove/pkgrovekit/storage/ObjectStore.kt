package com.pkgrove.pkgrovekit.storage

import java.io.InputStream
import java.time.Instant

/**
 * Vendor-neutral object storage (HEL-236). The contract is S3-COMPATIBLE
 * semantics — immutable whole objects under string keys, prefix listing,
 * copy-not-rename — expressed without any provider type. Implementations:
 * [InMemoryObjectStore] (reference/testing, this module) and `S3ObjectStore`
 * (`pkgrovekit-storage-s3`, AWS SDK v2 — MinIO and Amazon S3 tested).
 *
 * Provider-varying behavior is DISCOVERABLE via [capabilities]; workflows that
 * need a capability call [StorageCapabilities.require] BEFORE moving data and
 * fail with a typed [CapabilityRejectedException] (HEL-236 capability model).
 *
 * NOTHING here assumes atomic rename — object storage has none. Atomicity is
 * built from what S3 actually gives: unique staged keys, server-side copy, and
 * a conditional (if-absent) manifest PUT as the single commit point — see
 * [StagingArea].
 */
interface ObjectStore : AutoCloseable {

    /** What THIS store/provider supports; stable for the store's lifetime. */
    val capabilities: StorageCapabilities

    /** Metadata without content, or null when the object does not exist. */
    fun head(key: ObjectKey): ObjectMetadata?

    fun exists(key: ObjectKey): Boolean = head(key) != null

    /**
     * Lazily-paginated listing of every object whose key starts with [prefix],
     * in lexicographic key order. Consistency is whatever the provider gives —
     * see [StorageCapability.CONSISTENT_LISTING].
     */
    fun list(prefix: String = "", pageSize: Int = 1000): Sequence<ObjectSummary>

    /**
     * Streaming single-object write. The content is read ONCE from
     * [ContentSource.open] and never materialized whole; [ContentSource.lengthBytes]
     * must be known (unknown-length streams belong to [MultipartTransfer]).
     *
     * A declared [PutOptions.checksum] is VERIFIED (server-side where the
     * provider can, client-side otherwise) — a mismatch throws
     * [ChecksumMismatchException] and never leaves a corrupt published object.
     * [PutOptions.condition] gives conditional-create/update semantics; an
     * unmet condition throws [PreconditionFailedException].
     */
    fun put(key: ObjectKey, source: ContentSource, options: PutOptions = PutOptions()): PutResult

    /**
     * Streaming read. The caller OWNS the returned [ObjectContent] and must
     * close it. With [GetOptions.expectedChecksum] (or a stored checksum and
     * [GetOptions.verifyChecksum]) the stream verifies at EOF and throws
     * [ChecksumMismatchException] on corruption — a short read that never
     * reaches EOF is NOT a verification.
     */
    fun get(key: ObjectKey, options: GetOptions = GetOptions()): ObjectContent

    /** Idempotent delete: deleting an absent key is not an error. */
    fun delete(key: ObjectKey)

    /** Delete every object under [prefix]; returns how many keys were deleted. */
    fun deletePrefix(prefix: String): Long

    /**
     * Server-side copy (no content through the client). [CopyOptions.condition]
     * applies to the DESTINATION where the provider supports it; note Amazon S3
     * itself cannot make CopyObject conditional on the destination — which is
     * exactly why [StagingArea] commits via a conditional manifest PUT, never
     * via a conditional copy.
     */
    fun copy(from: ObjectKey, to: ObjectKey, options: CopyOptions = CopyOptions()): PutResult

    /**
     * Begin a multipart upload lifecycle (capability-gated:
     * [StorageCapability.MULTIPART_UPLOAD]). Callers should prefer
     * [MultipartTransfer.upload], which adds bounded buffering/concurrency and
     * guaranteed abort; using the raw lifecycle, the caller owns calling
     * [MultipartUpload.complete] or [MultipartUpload.abort] — [MultipartUpload.close]
     * aborts anything not completed so an incomplete upload is never leaked.
     */
    fun startMultipart(key: ObjectKey, options: PutOptions = PutOptions()): MultipartUpload

    override fun close() {}
}

/**
 * A validated object key. Rejects shapes that are unsafe or provider-ambiguous
 * and — HEL-236 security rule — anything that smells like an embedded query
 * string or signature (keys appear in logs/manifests; credentials and signed
 * parameters must never ride in them).
 */
@JvmInline
value class ObjectKey(val value: String) {
    init {
        require(value.isNotEmpty()) { "object key must not be empty" }
        require(value.length <= 1024) { "object key longer than 1024 bytes" }
        require(!value.startsWith("/")) { "object key must not start with '/'" }
        require(!value.endsWith("/")) { "object key must not end with '/'" }
        require("//" !in value) { "object key must not contain '//'" }
        val parts = value.split('/')
        require(parts.none { it == "." || it == ".." }) { "object key must not contain '.' or '..' segments" }
        require(value.none { it == '?' || it == '#' || it == ' ' || it.code < 0x20 || it.code == 0x7f }) {
            "object key must not contain '?', '#', spaces, or control characters"
        }
    }

    /** key under an additional parent prefix (`""` parent = unchanged). */
    fun under(prefix: String): ObjectKey =
        if (prefix.isEmpty()) this else ObjectKey("${prefix.trimEnd('/')}/$value")

    override fun toString(): String = value
}

/** One listing entry — deliberately small (listing pages can be huge). */
data class ObjectSummary(
    val key: ObjectKey,
    val sizeBytes: Long,
    val etag: String? = null,
    val lastModified: Instant? = null,
)

/** Full metadata of one object (a `head`, or what a write reported back). */
data class ObjectMetadata(
    val key: ObjectKey,
    val sizeBytes: Long,
    val etag: String? = null,
    val lastModified: Instant? = null,
    val contentType: String? = null,
    val checksum: Checksum? = null,
    /** User metadata: values must never contain secrets or row data. */
    val userMetadata: Map<String, String> = emptyMap(),
)

/**
 * Conditional-write semantics (HEL-236). Providers differ — [IfAbsent] maps to
 * `If-None-Match: *` ([StorageCapability.CONDITIONAL_CREATE]); [IfMatch] to
 * `If-Match: <etag>` ([StorageCapability.CONDITIONAL_UPDATE]). An implementation
 * MUST throw [CapabilityRejectedException] rather than silently ignore a
 * condition it cannot enforce — an unenforced condition is a lost-update bug.
 */
sealed class WriteCondition {
    /** Unconditional overwrite (the storage default). */
    data object None : WriteCondition()

    /** Succeed only when the key does not exist yet — the commit primitive. */
    data object IfAbsent : WriteCondition()

    /** Succeed only when the key currently has [etag] (optimistic update). */
    data class IfMatch(val etag: String) : WriteCondition()
}

data class PutOptions(
    val contentType: String? = null,
    /** Verified on write; see [ObjectStore.put]. */
    val checksum: Checksum? = null,
    val condition: WriteCondition = WriteCondition.None,
    val userMetadata: Map<String, String> = emptyMap(),
)

data class GetOptions(
    /** Inclusive byte range, e.g. `0..1023`; null = whole object. */
    val range: LongRange? = null,
    /** Verify against the object's STORED checksum when one exists. */
    val verifyChecksum: Boolean = true,
    /** Verify against THIS checksum (takes precedence over the stored one). */
    val expectedChecksum: Checksum? = null,
)

data class CopyOptions(
    val condition: WriteCondition = WriteCondition.None,
)

/** Typed outcome of a completed write/copy. */
data class PutResult(
    val key: ObjectKey,
    val etag: String?,
    val sizeBytes: Long,
    val checksum: Checksum? = null,
)

/**
 * A streaming object read. [stream] never holds the whole object; close()
 * releases the underlying connection/resources even when not fully consumed.
 */
class ObjectContent(
    val metadata: ObjectMetadata,
    private val body: InputStream,
) : AutoCloseable {
    fun stream(): InputStream = body
    override fun close() = body.close()
}

/**
 * Raw multipart lifecycle (HEL-236 guarantee 3). Thread-safe: parts may be
 * uploaded concurrently. Terminal states are exactly one of [complete] /
 * [abort]; [close] aborts when neither happened, so try-with-resources can
 * never leak an incomplete upload (leaked parts are invisible AND billed).
 */
interface MultipartUpload : AutoCloseable {
    val key: ObjectKey
    val uploadId: String

    /**
     * Upload one part ([partNumber] is 1-based, as in S3). A non-null
     * [checksum] is verified for that part (server-side where supported);
     * mismatch throws [ChecksumMismatchException].
     */
    fun uploadPart(partNumber: Int, source: ContentSource, checksum: Checksum? = null): CompletedUploadPart

    /** Commit the object from [parts] (any order; sorted by part number). */
    fun complete(parts: List<CompletedUploadPart>): PutResult

    /** Discard the upload and every part uploaded so far. Idempotent. */
    fun abort()

    override fun close()
}

data class CompletedUploadPart(
    val partNumber: Int,
    val etag: String?,
    val sizeBytes: Long,
    val checksum: Checksum? = null,
)
