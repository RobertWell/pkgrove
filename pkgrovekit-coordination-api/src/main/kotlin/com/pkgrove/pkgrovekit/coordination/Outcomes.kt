package com.pkgrove.pkgrovekit.coordination

/**
 * Typed global outcomes (HEL-170). In-doubt and heuristic states are first-class
 * values — never collapsed into a generic exception — because they are exactly
 * the states an operator must act on (recovery scan, manual resolution).
 */

/** The coordinator-assigned global transaction identity, surfaced for recovery. */
@JvmInline
value class TransactionId(val value: String) {
    override fun toString(): String = value
}

/** Last known state of one participant's branch. */
enum class BranchState { ENLISTED, PREPARED, COMMITTED, ROLLED_BACK, HEURISTIC, UNKNOWN }

/** Per-participant status carried on every global outcome. */
data class ParticipantStatus(
    val id: ParticipantId,
    val state: BranchState,
    val detail: String? = null,
)

/** Why a global transaction did not commit. */
sealed interface CoordinationFailure {
    val message: String

    /** The caller's work function (or one branch's SQL) failed before commit. */
    data class WorkFailed(val cause: Throwable) : CoordinationFailure {
        override val message: String = "work failed: ${cause.message}"
    }

    /** The transaction manager rolled the transaction back (e.g. timeout, enlist failure). */
    data class CoordinatorRolledBack(val cause: Throwable?) : CoordinationFailure {
        override val message: String = "coordinator rolled back: ${cause?.message ?: "(no cause)"}"
    }

    /** The coordinator itself failed in a way that leaves no certain outcome. */
    data class SystemFailure(val cause: Throwable) : CoordinationFailure {
        override val message: String = "coordinator system failure: ${cause.message}"
    }
}

/**
 * The result of interpreting an XA plan. Exactly one of these is returned per
 * execution; [txId] and [participants] make every outcome operationally
 * addressable (which transaction, which branches, what state).
 */
sealed interface GlobalOutcome {
    val txId: TransactionId
    val participants: List<ParticipantStatus>

    /** Every branch prepared and committed. The work's value is available. */
    data class Committed(
        override val txId: TransactionId,
        override val participants: List<ParticipantStatus>,
    ) : GlobalOutcome

    /** All branches rolled back — no partial effects remain. */
    data class RolledBack(
        override val txId: TransactionId,
        override val participants: List<ParticipantStatus>,
        val cause: CoordinationFailure,
    ) : GlobalOutcome

    /**
     * The commit decision's fate is unknown (e.g. coordinator/system failure
     * between prepare and commit acknowledgements). Branches may hold locks
     * until recovery resolves them. OPERATOR ACTION: run/await the recovery
     * manager against the coordinator's object store.
     */
    data class InDoubt(
        override val txId: TransactionId,
        override val participants: List<ParticipantStatus>,
        val cause: CoordinationFailure,
    ) : GlobalOutcome

    /**
     * Some branches committed while others rolled back (heuristic decision by a
     * resource manager). Data is INCONSISTENT until manually reconciled.
     */
    data class HeuristicMixed(
        override val txId: TransactionId,
        override val participants: List<ParticipantStatus>,
        val detail: String? = null,
    ) : GlobalOutcome

    /**
     * The coordinator recorded state that requires a recovery pass (e.g. a
     * prepared branch whose resource became unreachable). Not resolved yet;
     * neither committed nor rolled back from the caller's perspective.
     */
    data class RecoveryPending(
        override val txId: TransactionId,
        override val participants: List<ParticipantStatus>,
        val detail: String? = null,
    ) : GlobalOutcome
}

/**
 * Value + outcome of one coordinated execution. [value] is non-null only when
 * [outcome] is [GlobalOutcome.Committed] (and the work returned non-null).
 */
data class CoordinatedResult<T>(
    val outcome: GlobalOutcome,
    val value: T?,
) {
    val committed: Boolean get() = outcome is GlobalOutcome.Committed
}
