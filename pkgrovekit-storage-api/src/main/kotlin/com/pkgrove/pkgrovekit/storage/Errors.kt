package com.pkgrove.pkgrovekit.storage

/**
 * Typed storage outcomes (HEL-236). Every failure an implementation surfaces
 * is one of these — callers classify by TYPE, never by parsing provider
 * message strings.
 *
 * [retrySafe] is the retry contract: `true` means re-running the SAME
 * operation cannot corrupt state (idempotent read/delete, or a write whose
 * source is repeatable and whose condition still guards it); `false` means a
 * blind retry is UNSAFE (one-shot source already partially consumed, or a
 * non-idempotent sequence) and the caller must re-plan, not re-fire.
 *
 * Security rule: messages may contain object KEYS (validated to carry no
 * secrets — see [ObjectKey]) but never credentials, presigned query strings,
 * or row values.
 */
sealed class StorageException(
    message: String,
    cause: Throwable? = null,
    val retrySafe: Boolean,
) : RuntimeException(message, cause)

/** The object (or bucket) addressed by a read/copy does not exist. */
class ObjectNotFoundException(val key: ObjectKey, cause: Throwable? = null) :
    StorageException("object not found: '$key'", cause, retrySafe = true)

/**
 * A [WriteCondition] was not met — for [WriteCondition.IfAbsent] someone else
 * already created the key (lost the commit race); for [WriteCondition.IfMatch]
 * the object changed underneath (stale etag). This is the CONFLICT signal for
 * checkpoints/manifests: the caller must reload and decide, never overwrite.
 */
class PreconditionFailedException(
    val key: ObjectKey,
    val condition: WriteCondition,
    cause: Throwable? = null,
) : StorageException(
    "precondition failed for '$key' ($condition) — concurrent writer or stale version",
    cause,
    retrySafe = false,
)

/** Content did not match its declared/stored checksum. Fails VISIBLY, always. */
class ChecksumMismatchException(
    val key: ObjectKey,
    val expectedBase64: String,
    val actualBase64: String?,
    cause: Throwable? = null,
) : StorageException(
    "checksum mismatch for '$key': expected $expectedBase64, got ${actualBase64 ?: "<rejected by provider>"}",
    cause,
    retrySafe = false,
)

/**
 * A workflow needs capabilities this provider does not support. Thrown BEFORE
 * data movement (HEL-236 capability model) so the failure is cheap and typed.
 */
class CapabilityRejectedException(
    val operation: String,
    val provider: String,
    val missing: Set<StorageCapability>,
) : StorageException(
    "operation '$operation' requires ${missing.joinToString(", ")} which provider '$provider' does not support",
    cause = null,
    retrySafe = false,
)

/** Transport/provider failure with an explicit retry-safety verdict. */
class StorageIoException(
    message: String,
    cause: Throwable? = null,
    retrySafe: Boolean,
) : StorageException(message, cause, retrySafe)
