package com.pkgrove.pkgrovekit.core

/**
 * HEL-167: the typed **execution outcome** of a workflow (or one branch of it).
 * Deliberately distinct from [Choice] (business routing) and from a nullable
 * report/error pair: partial completion is impossible to mistake for success,
 * and cancellation is a first-class state, never a swallowed exception.
 *
 * A workflow that routed a value to its [Choice.Left] sink and finished cleanly
 * is [Completed] — `Left` is a path, not a failure.
 */
sealed interface WorkflowOutcome<out A> {
    /** Everything the plan promised ran; [value] is the result. */
    data class Completed<out A>(val value: A) : WorkflowOutcome<A>

    /** Some branches/effects succeeded and at least one failed. [value] is
     *  whatever completed (may be null); [failures] names what did not, in the
     *  order encountered. Never presented as success. */
    data class Partial<out A>(val value: A?, val failures: List<BranchFailure>) :
        WorkflowOutcome<A>

    /** Nothing usable completed; [cause] is the primary failure (cleanup
     *  failures, if any, are attached as suppressed on it). */
    data class Failed(val cause: Throwable) : WorkflowOutcome<Nothing>

    /** The workflow was cancelled cooperatively; partial effects are described
     *  by [committed] where the executor can account for them. */
    data class Cancelled(val committed: List<BranchFailure> = emptyList()) :
        WorkflowOutcome<Nothing>

    val isCompleted: Boolean get() = this is Completed
}

/** One branch's failure within a [WorkflowOutcome.Partial]/[Cancelled]. */
data class BranchFailure(val branch: String, val cause: Throwable)

/** Map a Completed value; every other outcome passes through unchanged. */
inline fun <A, B> WorkflowOutcome<A>.mapCompleted(f: (A) -> B): WorkflowOutcome<B> =
    when (this) {
        is WorkflowOutcome.Completed -> WorkflowOutcome.Completed(f(value))
        is WorkflowOutcome.Partial -> WorkflowOutcome.Partial(value?.let(f), failures)
        is WorkflowOutcome.Failed -> this
        is WorkflowOutcome.Cancelled -> this
    }

/** The completed value, or null for any non-Completed outcome. */
fun <A> WorkflowOutcome<A>.getOrNull(): A? =
    (this as? WorkflowOutcome.Completed)?.value
