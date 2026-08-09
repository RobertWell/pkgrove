package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.storage.Checksum
import com.pkgrove.pkgrovekit.storage.ChecksumAlgorithm
import com.pkgrove.pkgrovekit.storage.ChecksumMismatchException
import com.pkgrove.pkgrovekit.storage.ChecksumVerifyingInputStream
import com.pkgrove.pkgrovekit.storage.CompletedUploadPart
import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.CopyOptions
import com.pkgrove.pkgrovekit.storage.GetOptions
import com.pkgrove.pkgrovekit.storage.MultipartUpload
import com.pkgrove.pkgrovekit.storage.ObjectContent
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.ObjectMetadata
import com.pkgrove.pkgrovekit.storage.ObjectNotFoundException
import com.pkgrove.pkgrovekit.storage.ObjectStore
import com.pkgrove.pkgrovekit.storage.ObjectSummary
import com.pkgrove.pkgrovekit.storage.PreconditionFailedException
import com.pkgrove.pkgrovekit.storage.PutOptions
import com.pkgrove.pkgrovekit.storage.PutResult
import com.pkgrove.pkgrovekit.storage.StorageCapabilities
import com.pkgrove.pkgrovekit.storage.StorageCapability
import com.pkgrove.pkgrovekit.storage.StorageIoException
import com.pkgrove.pkgrovekit.storage.WriteCondition
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.SdkHttpConfigurationOption
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.utils.AttributeMap
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [ObjectStore] over any S3-compatible endpoint via AWS SDK for Java 2.x
 * (HEL-236). MinIO is the CI-tested target, Amazon S3 the reference cloud
 * target — same client, same code path; that identity is the compatibility
 * contract. AWS SDK types do not appear on this API except the documented
 * escape hatch ([wrap]).
 *
 * Retry semantics (HEL-236 guarantee 4): the SDK retries only what is safe —
 * reads/deletes are idempotent; a PUT is re-sent only because every
 * [ContentSource] is re-opened per attempt, so a one-shot source's second
 * open FAILS (typed, `retrySafe = false`) instead of corrupting the object
 * with a half-consumed stream.
 *
 * Security (HEL-236): error messages carry operation + key only — never
 * credentials, presigned query strings, or data. Presigned URLs are returned
 * as [PresignedUrl] whose `toString` REDACTS the query.
 */
class S3ObjectStore private constructor(
    private val client: S3Client,
    private val presignerFactory: (() -> S3Presigner)?,
    val bucket: String,
    val prefix: String,
    override val capabilities: StorageCapabilities,
    private val ownsClient: Boolean,
) : ObjectStore {

    private var presigner: S3Presigner? = null

    companion object {
        /** Build a store from vendor-neutral [config]. The store owns the client. */
        @JvmStatic
        @JvmOverloads
        fun open(config: S3StorageConfig, bucket: String, prefix: String = ""): S3ObjectStore {
            require(bucket.isNotBlank()) { "bucket must not be blank" }
            val httpBuilder = ApacheHttpClient.builder()
                .connectionTimeout(config.connectTimeout)
                .socketTimeout(config.socketTimeout)
            val httpClient = if (config.trustAllTls) {
                httpBuilder.buildWithDefaults(
                    AttributeMap.builder()
                        .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, java.lang.Boolean.TRUE)
                        .build(),
                )
            } else {
                httpBuilder.build()
            }
            val credentialsProvider = when (val c = config.credentials) {
                is S3Credentials.DefaultChain -> DefaultCredentialsProvider.create()
                is S3Credentials.Static ->
                    StaticCredentialsProvider.create(
                        if (c.sessionToken != null) {
                            AwsSessionCredentials.create(c.accessKeyId, c.secretAccessKey, c.sessionToken)
                        } else {
                            AwsBasicCredentials.create(c.accessKeyId, c.secretAccessKey)
                        },
                    )
            }
            val client = S3Client.builder()
                .region(Region.of(config.region))
                .credentialsProvider(credentialsProvider)
                .apply { config.endpoint?.let { endpointOverride(it) } }
                .forcePathStyle(config.pathStyleAccess)
                .httpClient(httpClient)
                .overrideConfiguration { o ->
                    o.retryPolicy(RetryPolicy.builder().numRetries(config.maxRetries - 1).build())
                    config.apiCallTimeout?.let { o.apiCallTimeout(it) }
                }
                .build()
            val presignerFactory = {
                S3Presigner.builder()
                    .region(Region.of(config.region))
                    .credentialsProvider(credentialsProvider)
                    .apply { config.endpoint?.let { endpointOverride(it) } }
                    .serviceConfiguration(
                        software.amazon.awssdk.services.s3.S3Configuration.builder()
                            .pathStyleAccessEnabled(config.pathStyleAccess)
                            .build(),
                    )
                    .build()
            }
            return S3ObjectStore(
                client, presignerFactory, bucket, prefix.trim('/'),
                StorageCapabilities(config.profile.name, config.profile.capabilities, config.profile.limits),
                ownsClient = true,
            )
        }

        /**
         * ESCAPE HATCH: adopt a caller-built [S3Client] (advanced SDK options).
         * The caller keeps ownership (this store never closes it) and must
         * declare an honest [profile] for the endpoint the client targets.
         */
        @JvmStatic
        @JvmOverloads
        fun wrap(
            client: S3Client,
            bucket: String,
            prefix: String = "",
            profile: S3CompatibilityProfile = S3CompatibilityProfile.amazonS3(),
        ): S3ObjectStore = S3ObjectStore(
            client, null, bucket, prefix.trim('/'),
            StorageCapabilities(profile.name, profile.capabilities, profile.limits),
            ownsClient = false,
        )

        private const val METADATA_CONTENT_SHA256 = "pkgrovekit-content-sha256"
    }

    private fun physical(key: ObjectKey): String =
        if (prefix.isEmpty()) key.value else "$prefix/${key.value}"

    private fun physicalPrefix(p: String): String =
        if (prefix.isEmpty()) p else if (p.isEmpty()) "$prefix/" else "$prefix/$p"

    private fun logical(physicalKey: String): ObjectKey =
        ObjectKey(if (prefix.isEmpty()) physicalKey else physicalKey.removePrefix("$prefix/"))

    // ── reads ────────────────────────────────────────────────────────────────

    override fun head(key: ObjectKey): ObjectMetadata? = try {
        val r = client.headObject {
            it.bucket(bucket).key(physical(key)).checksumMode(ChecksumMode.ENABLED)
        }
        ObjectMetadata(
            key = key,
            sizeBytes = r.contentLength() ?: -1,
            etag = r.eTag(),
            lastModified = r.lastModified(),
            contentType = r.contentType(),
            checksum = wholeObjectSha256(r.checksumSHA256(), r.metadata()),
            userMetadata = r.metadata() ?: emptyMap(),
        )
    } catch (e: S3Exception) {
        if (e.statusCode() == 404) null else throw translate("head", key, e)
    } catch (e: SdkClientException) {
        throw StorageIoException("s3 head failed for '$key'", e, retrySafe = true)
    }

    override fun list(prefix: String, pageSize: Int): Sequence<ObjectSummary> {
        require(pageSize in 1..1000) { "pageSize must be 1..1000 (S3 page limit)" }
        val physPrefix = physicalPrefix(prefix)
        return sequence {
            var token: String? = null
            do {
                val resp = guard("list", null, retrySafe = true) {
                    client.listObjectsV2 {
                        it.bucket(bucket).prefix(physPrefix).maxKeys(pageSize)
                        token?.let { t -> it.continuationToken(t) }
                    }
                }
                for (o in resp.contents()) {
                    yield(ObjectSummary(logical(o.key()), o.size(), o.eTag(), o.lastModified()))
                }
                token = resp.nextContinuationToken()
            } while (resp.isTruncated == true)
        }
    }

    override fun get(key: ObjectKey, options: GetOptions): ObjectContent {
        val stream = guard("get", key, retrySafe = true) {
            client.getObject { b ->
                b.bucket(bucket).key(physical(key)).checksumMode(ChecksumMode.ENABLED)
                options.range?.let { r -> b.range("bytes=${r.first}-${r.last}") }
            }
        }
        val r = stream.response()
        val stored = wholeObjectSha256(r.checksumSHA256(), r.metadata())
        val metadata = ObjectMetadata(
            key = key, sizeBytes = r.contentLength() ?: -1, etag = r.eTag(),
            lastModified = r.lastModified(), contentType = r.contentType(),
            checksum = stored, userMetadata = r.metadata() ?: emptyMap(),
        )
        // caller-supplied expectation always wins; the stored checksum is used
        // only for whole-object reads (verifying a range against it would be wrong)
        val expected = options.expectedChecksum
            ?: stored.takeIf { options.verifyChecksum && options.range == null }
        val body = expected?.let { ChecksumVerifyingInputStream(stream, it, key) } ?: stream
        return ObjectContent(metadata, body)
    }

    // ── writes ───────────────────────────────────────────────────────────────

    override fun put(key: ObjectKey, source: ContentSource, options: PutOptions): PutResult {
        val length = source.lengthBytes
            ?: throw IllegalArgumentException(
                "put('$key') needs a known content length — stream unknown-length " +
                    "content with MultipartTransfer.upload(...)",
            )
        val serverChecksum = resolveServerChecksum(key, source, options.checksum)
        requireCondition("put", options.condition)
        val response = guard("put", key, retrySafe = source.repeatable, condition = options.condition) {
            client.putObject(
                { b ->
                    b.bucket(bucket).key(physical(key))
                    options.contentType?.let { b.contentType(it) }
                    val meta = userMetadataWithChecksum(options)
                    if (meta.isNotEmpty()) b.metadata(meta)
                    serverChecksum?.let { applyChecksum(b, it) }
                    when (val c = options.condition) {
                        is WriteCondition.None -> {}
                        is WriteCondition.IfAbsent -> b.ifNoneMatch("*")
                        is WriteCondition.IfMatch -> b.ifMatch(c.etag)
                    }
                },
                RequestBody.fromContentProvider(
                    { source.open() }, length,
                    options.contentType ?: "application/octet-stream",
                ),
            )
        }
        return PutResult(key, response.eTag(), length, options.checksum)
    }

    override fun delete(key: ObjectKey) {
        guard("delete", key, retrySafe = true) {
            client.deleteObject { it.bucket(bucket).key(physical(key)) }
        }
    }

    override fun deletePrefix(prefix: String): Long {
        var deleted = 0L
        list(prefix).map { it.key }.chunked(1000).forEach { chunk ->
            guard("deletePrefix", null, retrySafe = true) {
                client.deleteObjects { b ->
                    b.bucket(bucket).delete(
                        Delete.builder()
                            .objects(chunk.map { ObjectIdentifier.builder().key(physical(it)).build() })
                            .quiet(true)
                            .build(),
                    )
                }
            }
            deleted += chunk.size
        }
        return deleted
    }

    override fun copy(from: ObjectKey, to: ObjectKey, options: CopyOptions): PutResult {
        require(options.condition is WriteCondition.None) {
            "S3 CopyObject cannot be made conditional on the DESTINATION — copy to a " +
                "run-unique key and commit through a conditional manifest put (StagingArea)"
        }
        val response = guard("copy", to, retrySafe = true) {
            client.copyObject {
                it.sourceBucket(bucket).sourceKey(physical(from))
                    .destinationBucket(bucket).destinationKey(physical(to))
            }
        }
        val size = head(to)?.sizeBytes ?: -1
        return PutResult(to, response.copyObjectResult()?.eTag(), size)
    }

    // ── multipart ────────────────────────────────────────────────────────────

    override fun startMultipart(key: ObjectKey, options: PutOptions): MultipartUpload {
        capabilities.require("multipart upload of '$key'", StorageCapability.MULTIPART_UPLOAD)
        requireCondition("multipart complete", options.condition)
        val checksummed = StorageCapability.CHECKSUM_SHA256 in capabilities
        val created = guard("createMultipartUpload", key, retrySafe = true) {
            client.createMultipartUpload { b ->
                b.bucket(bucket).key(physical(key))
                options.contentType?.let { b.contentType(it) }
                val meta = userMetadataWithChecksum(options)
                if (meta.isNotEmpty()) b.metadata(meta)
                if (checksummed) b.checksumAlgorithm(software.amazon.awssdk.services.s3.model.ChecksumAlgorithm.SHA256)
            }
        }
        return S3MultipartUploadImpl(key, created.uploadId(), options, checksummed)
    }

    private inner class S3MultipartUploadImpl(
        override val key: ObjectKey,
        override val uploadId: String,
        private val options: PutOptions,
        private val checksummed: Boolean,
    ) : MultipartUpload {
        private val terminated = AtomicBoolean(false)

        override fun uploadPart(partNumber: Int, source: ContentSource, checksum: Checksum?): CompletedUploadPart {
            require(partNumber >= 1) { "part numbers are 1-based" }
            check(!terminated.get()) { "multipart upload $uploadId already completed/aborted" }
            val length = source.lengthBytes
                ?: throw IllegalArgumentException("uploadPart needs a known part length")
            // the declared algorithm demands a checksum per part: compute one
            // when the caller did not supply it (repeatable buffers make this cheap)
            val effective = when {
                checksum != null -> checksum.also {
                    require(it.algorithm == ChecksumAlgorithm.SHA256) {
                        "s3 part checksums use SHA256 (got ${it.algorithm})"
                    }
                }
                checksummed && source.repeatable -> Checksum.sha256Of(source)
                else -> null
            }
            if (checksum != null && !checksummed) {
                // provider cannot verify — verify locally against the source
                val actual = Checksum.sha256Of(source)
                if (actual.valueBase64 != checksum.valueBase64) {
                    throw ChecksumMismatchException(key, checksum.valueBase64, actual.valueBase64)
                }
            }
            val response = guard("uploadPart", key, retrySafe = source.repeatable) {
                client.uploadPart(
                    { b ->
                        b.bucket(bucket).key(physical(key)).uploadId(uploadId).partNumber(partNumber)
                        if (checksummed) effective?.let { b.checksumSHA256(it.valueBase64) }
                    },
                    RequestBody.fromContentProvider(
                        { source.open() }, length, "application/octet-stream",
                    ),
                )
            }
            return CompletedUploadPart(partNumber, response.eTag(), length, effective)
        }

        override fun complete(parts: List<CompletedUploadPart>): PutResult {
            check(terminated.compareAndSet(false, true)) {
                "multipart upload $uploadId already completed/aborted"
            }
            val response = try {
                completeRequest(parts)
            } catch (e: Exception) {
                // completion FAILED (e.g. unmet condition) — the upload still
                // exists provider-side, so close()/abort() must still reap it
                terminated.set(false)
                throw e
            }
            return PutResult(key, response.eTag(), parts.sumOf { it.sizeBytes })
        }

        private fun completeRequest(parts: List<CompletedUploadPart>) =
            guard("completeMultipartUpload", key, retrySafe = true,
                  condition = options.condition) {
                client.completeMultipartUpload { b ->
                    b.bucket(bucket).key(physical(key)).uploadId(uploadId)
                        .multipartUpload { mu ->
                            mu.parts(
                                parts.sortedBy { it.partNumber }.map { p ->
                                    CompletedPart.builder()
                                        .partNumber(p.partNumber)
                                        .eTag(p.etag)
                                        .apply {
                                            if (checksummed) p.checksum?.let { checksumSHA256(it.valueBase64) }
                                        }
                                        .build()
                                },
                            )
                        }
                    when (val c = options.condition) {
                        is WriteCondition.None -> {}
                        is WriteCondition.IfAbsent -> b.ifNoneMatch("*")
                        is WriteCondition.IfMatch -> b.ifMatch(c.etag)
                    }
                }
            }

        override fun abort() {
            terminated.set(true)
            // abort is idempotent server-side; guard only translates
            guard("abortMultipartUpload", key, retrySafe = true) {
                client.abortMultipartUpload {
                    it.bucket(bucket).key(physical(key)).uploadId(uploadId)
                }
            }
        }

        override fun close() {
            if (!terminated.get()) abort()
        }
    }

    /**
     * Adapter-level convenience for local development/tests: create [bucket]
     * when absent (idempotent). Production buckets should be provisioned by
     * infrastructure, not by the library — this never alters policies of an
     * existing bucket (HEL-236: the library must not silently broaden
     * bucket/prefix policies).
     */
    fun createBucketIfMissing() {
        val exists = try {
            client.headBucket { it.bucket(bucket) }
            true
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) false else throw translate("headBucket", null, e)
        } catch (e: SdkClientException) {
            throw StorageIoException("s3 headBucket failed for bucket '$bucket'", e, retrySafe = true)
        }
        if (!exists) {
            guard("createBucket", null, retrySafe = true) {
                client.createBucket { it.bucket(bucket) }
            }
        }
    }

    /**
     * Adapter-level: incomplete multipart uploads under [keyPrefix]. The
     * prefix filter is applied CLIENT-side — providers disagree on
     * ListMultipartUploads prefix semantics (MinIO's differs from AWS), and an
     * upload invisible to cleanup is a silent cost leak.
     */
    fun incompleteUploads(keyPrefix: String = ""): List<IncompleteUpload> {
        val physPrefix = physicalPrefix(keyPrefix)
        return guard("listMultipartUploads", null, retrySafe = true) {
            client.listMultipartUploads { it.bucket(bucket) }
        }.uploads()
            .filter { it.key().startsWith(physPrefix) }
            .map { IncompleteUpload(logical(it.key()), it.uploadId(), it.initiated()) }
    }

    /**
     * Adapter-level cleanup rule for incomplete multipart uploads (HEL-236
     * guarantee 3's backstop — e.g. after a process was SIGKILLed between
     * start and abort). Deterministic: aborts uploads initiated before
     * `now - olderThan`. Amazon S3 users should ALSO set a lifecycle rule
     * (`AbortIncompleteMultipartUpload`) as documented in docs/storage.md.
     */
    fun abortIncompleteUploads(olderThan: Duration, keyPrefix: String = ""): Int {
        val cutoff = Instant.now().minus(olderThan)
        val doomed = incompleteUploads(keyPrefix).filter { it.initiated?.isBefore(cutoff) == true }
        doomed.forEach { u ->
            guard("abortMultipartUpload", u.key, retrySafe = true) {
                client.abortMultipartUpload {
                    it.bucket(bucket).key(physical(u.key)).uploadId(u.uploadId)
                }
            }
        }
        return doomed.size
    }

    data class IncompleteUpload(val key: ObjectKey, val uploadId: String, val initiated: Instant?)

    // ── presigned access (adapter-level API, HEL-236 scenario 7) ─────────────

    /**
     * Presigned GET for [key], valid [validFor]. Capability-gated. The URL
     * QUERY carries the signature: treat the whole value as a secret — the
     * returned [PresignedUrl] redacts it from `toString`/logs by design.
     */
    fun presignGet(key: ObjectKey, validFor: Duration): PresignedUrl {
        capabilities.require("presigned GET of '$key'", StorageCapability.PRESIGNED_URLS)
        val factory = presignerFactory
            ?: throw IllegalStateException(
                "presigning needs store-owned credentials — S3ObjectStore.open(...), not wrap(...)",
            )
        val p = synchronized(this) { presigner ?: factory().also { presigner = it } }
        val presigned = p.presignGetObject { b ->
            b.signatureDuration(validFor)
                .getObjectRequest { it.bucket(bucket).key(physical(key)) }
        }
        return PresignedUrl(presigned.url().toURI(), presigned.expiration())
    }

    override fun close() {
        synchronized(this) { presigner?.close() }
        if (ownsClient) client.close()
    }

    // ── translation helpers ──────────────────────────────────────────────────

    /** Checksum to hand the PROVIDER for verification (capability-gated). */
    private fun resolveServerChecksum(key: ObjectKey, source: ContentSource, declared: Checksum?): Checksum? {
        if (declared == null) return null
        return when {
            declared.algorithm == ChecksumAlgorithm.SHA256 &&
                StorageCapability.CHECKSUM_SHA256 in capabilities -> declared
            declared.algorithm == ChecksumAlgorithm.CRC32C &&
                StorageCapability.CHECKSUM_CRC32C in capabilities -> declared
            declared.algorithm == ChecksumAlgorithm.SHA256 && source.repeatable -> {
                // provider cannot verify — verify LOCALLY so the guarantee holds
                val actual = Checksum.sha256Of(source)
                if (actual.valueBase64 != declared.valueBase64) {
                    throw ChecksumMismatchException(key, declared.valueBase64, actual.valueBase64)
                }
                null
            }
            else -> throw IllegalArgumentException(
                "cannot verify a ${declared.algorithm} checksum on provider " +
                    "'${capabilities.provider}'" +
                    if (source.repeatable) "" else " with a non-repeatable source",
            )
        }
    }

    private fun applyChecksum(
        b: software.amazon.awssdk.services.s3.model.PutObjectRequest.Builder,
        checksum: Checksum,
    ) {
        when (checksum.algorithm) {
            ChecksumAlgorithm.SHA256 -> b.checksumSHA256(checksum.valueBase64)
            ChecksumAlgorithm.CRC32C -> b.checksumCRC32C(checksum.valueBase64)
            // resolveServerChecksum never yields MD5 (no capability models it)
            ChecksumAlgorithm.MD5 -> throw IllegalArgumentException("MD5 is not a server-verified checksum here")
        }
    }

    /**
     * Record a SHA-256 also as user metadata: multipart-composited AND
     * plain-put objects then expose ONE stable whole-object checksum that
     * [get]/[head] can verify against regardless of provider checksum quirks.
     */
    private fun userMetadataWithChecksum(options: PutOptions): Map<String, String> {
        val checksum = options.checksum
        return if (checksum?.algorithm == ChecksumAlgorithm.SHA256) {
            options.userMetadata + (METADATA_CONTENT_SHA256 to checksum.valueBase64)
        } else {
            options.userMetadata
        }
    }

    /** A whole-object SHA-256 from the provider header (single put) or our metadata echo. */
    private fun wholeObjectSha256(headerValue: String?, metadata: Map<String, String>?): Checksum? {
        val meta = metadata?.get(METADATA_CONTENT_SHA256)
        val value = when {
            meta != null -> meta
            // "-N" suffix = composite (multipart) checksum — NOT a content digest
            headerValue != null && '-' !in headerValue -> headerValue
            else -> null
        }
        return value?.let { Checksum(ChecksumAlgorithm.SHA256, it) }
    }

    private fun requireCondition(operation: String, condition: WriteCondition) {
        when (condition) {
            is WriteCondition.None -> {}
            is WriteCondition.IfAbsent ->
                capabilities.require("conditional $operation", StorageCapability.CONDITIONAL_CREATE)
            is WriteCondition.IfMatch ->
                capabilities.require("conditional $operation", StorageCapability.CONDITIONAL_UPDATE)
        }
    }

    private fun <T> guard(
        op: String,
        key: ObjectKey?,
        retrySafe: Boolean,
        condition: WriteCondition = WriteCondition.None,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: S3Exception) {
        throw translate(op, key, e, condition, retrySafe)
    } catch (e: SdkClientException) {
        // transport-level: safe to retry exactly when the source was re-openable
        throw StorageIoException("s3 $op failed for '${key ?: "<multiple>"}'", e, retrySafe)
    }

    private fun translate(
        op: String,
        key: ObjectKey?,
        e: S3Exception,
        condition: WriteCondition = WriteCondition.None,
        retrySafe: Boolean = false,
    ): RuntimeException {
        val code = e.awsErrorDetails()?.errorCode() ?: ""
        return when {
            e.statusCode() == 404 && key != null -> ObjectNotFoundException(key, e)
            e.statusCode() == 412 && key != null -> PreconditionFailedException(key, condition, e)
            key != null && (
                code == "BadDigest" || code == "InvalidDigest" ||
                    code == "XAmzContentChecksumMismatch" || code == "XAmzContentSHA256Mismatch" ||
                    e.message?.contains("checksum", ignoreCase = true) == true
                ) ->
                ChecksumMismatchException(key, "<declared>", null, e)
            else -> StorageIoException(
                "s3 $op failed for '${key ?: "<multiple>"}' " +
                    "(status ${e.statusCode()}${if (code.isEmpty()) "" else ", $code"})",
                e,
                retrySafe = retrySafe && e.statusCode() >= 500,
            )
        }
    }
}

/** A presigned URL whose textual form never exposes the signed query string. */
class PresignedUrl(val url: URI, val expiresAt: Instant) {
    override fun toString(): String =
        "${url.scheme}://${url.authority}${url.path}?<presigned-query-redacted> (expires $expiresAt)"
}
