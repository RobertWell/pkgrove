package io.maxxga.rowrelay.core

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
class CancelToken(private val deadlineNanos: Long? = null) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() { cancelled.set(true) }

    val isCancelled: Boolean
        get() = cancelled.get() ||
            (deadlineNanos != null && System.nanoTime() >= deadlineNanos)

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
    }
}

class OperationCancelledException : RuntimeException("operation cancelled")

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
