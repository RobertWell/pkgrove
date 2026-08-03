package com.pkgrove.pkgrovekit.core

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cancellation + deadline + conversion policy + operation reporting
 * (HEL-120 capabilities 5–7). Framework-neutral: no metrics vendor, no
 * logging dependency — structured values the caller forwards wherever it
 * wants.
 */

/**
 * Cooperative cancellation shared between a caller and a running operation.
 * Operations poll [isCancelled] at batch boundaries and abort promptly;
 * adapters additionally propagate cancellation to the driver where supported
 * (Statement.cancel).
 */
class CancelToken(
    private val deadlineNanos: Long? = null,
    /** HEL-128: parents this token is linked to — cancelled when ANY parent is. */
    private val parents: List<CancelToken> = emptyList(),
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }

    val isCancelled: Boolean
        get() = cancelled.get() ||
            (deadlineNanos != null && System.nanoTime() >= deadlineNanos) ||
            parents.any { it.isCancelled }

    fun throwIfCancelled() {
        if (isCancelled) throw OperationCancelledException()
    }

    companion object {
        /** A token that auto-cancels after [millis] from now. */
        @JvmStatic
        fun withTimeout(millis: Long): CancelToken =
            CancelToken(System.nanoTime() + millis * 1_000_000)

        /** A token that never expires (explicit cancel only). */
        @JvmStatic
        fun none(): CancelToken = CancelToken(null)

        /**
         * A token cancelled when ANY of [tokens] is cancelled (or itself).
         * This is the coroutine-to-JDBC cancellation bridge (HEL-128): an
         * executor links each flow's own token to a scope token it cancels on
         * structured cancellation, so blocking JDBC work observes the cancel
         * at its next cooperative checkpoint and releases its resources.
         */
        @JvmStatic
        fun linked(vararg tokens: CancelToken): CancelToken =
            CancelToken(null, tokens.toList())
    }
}

/**
 * A cooperative cancellation was observed. First-class and distinguishable —
 * writers/executors propagate it UNWRAPPED so callers can classify it as
 * cancelled (never a business failure). When cancellation aborts a write, the
 * partial [report] carries what was durably committed (honest resumability for
 * chunked policies); it is null for read-side cancellation.
 */
class OperationCancelledException(val report: OperationReport? = null) :
    RuntimeException("operation cancelled")

/**
 * What to do when a value/type cannot be converted faithfully for a target.
 * The default everywhere is [REJECT]: correctness over convenience — the
 * caller opts into lossy behavior explicitly, and even then receives a
 * [DataWarning] per affected column (never silent).
 */
enum class ConversionPolicy {
    /** Fail the operation with a clear error naming the column. */
    REJECT,

    /** Convert the value to its string form, with a warning. */
    STRINGIFY,

    /** Pass raw bytes through unmodified where both sides accept binary. */
    BINARY_COPY,

    /** Drop the column from the operation entirely, with a warning. */
    SKIP,
}

class ConversionException(message: String, val column: String? = null) :
    RuntimeException(message)

/**
 * Structured outcome of a read/write/transfer operation. Partial completion
 * is always visible: [rowsAffected] is what actually happened, and
 * [completed] is false whenever the operation stopped early for any reason.
 */
data class OperationReport(
    val rowsAffected: Long,
    val batches: Int,
    val elapsedMillis: Long,
    val completed: Boolean,
    val warnings: List<DataWarning> = emptyList(),
    /** When a batch failed: which batch (0-based) and the affected row range. */
    val failedBatchIndex: Int? = null,
    val failedRowRange: LongRange? = null,
)
