package com.pkgrove.pkgrovekit.coordination

import java.sql.Connection

/**
 * The interpreter seam for XA plans (HEL-170). Implementations (pkgrovekit-jta)
 * delegate begin/commit/rollback to an EXTERNAL transaction manager — this
 * library never implements its own two-phase commit.
 */
interface XaCoordinator {

    /** Pure preflight — same result as [Plans.validate] plus interpreter-specific checks. */
    fun validate(plan: CoordinationPlan): PlanValidation

    /**
     * Run [work] inside one coordinator-owned global transaction.
     *
     * Contract:
     *  - A plan that fails [validate] throws [PlanRejectedException] BEFORE any
     *    connection is opened or resource enlisted (no database effect).
     *  - [work] receives an [XaScope] bound to the calling thread; connections
     *    it hands out are ENLISTED and coordinator-owned — PkgroveKit-style
     *    operations must use JoinExisting semantics on them, and any attempt to
     *    locally `commit`, `rollback`, `close` or re-enable auto-commit throws.
     *  - The outcome is returned as typed data, never collapsed into a generic
     *    exception: work failures surface as [GlobalOutcome.RolledBack] with the
     *    cause attached; heuristic/in-doubt commit results keep their state.
     */
    fun <T> inGlobalTransaction(plan: CoordinationPlan, work: (XaScope) -> T): CoordinatedResult<T>
}

/**
 * Scope of one running global transaction. Thread-affine: only the thread that
 * entered [XaCoordinator.inGlobalTransaction] may use it — a second thread
 * calling [connection] gets [ConcurrentScopeAccessException] rather than the
 * chance to smuggle an enlisted connection across branches.
 */
interface XaScope {
    val txId: TransactionId

    /**
     * The single enlisted connection for [id]'s branch (created and enlisted on
     * first request, cached for the scope's lifetime — one connection per
     * branch, per the XA affinity rules). The returned connection refuses
     * commit/rollback/close/setAutoCommit(true) and dies with the scope.
     */
    fun connection(id: ParticipantId): Connection
}

/** A thread other than the scope owner tried to use the scope. */
class ConcurrentScopeAccessException(message: String) : IllegalStateException(message)

/** An operation forbidden on an enlisted, coordinator-owned connection. */
class EnlistedConnectionViolationException(message: String) : IllegalStateException(message)
