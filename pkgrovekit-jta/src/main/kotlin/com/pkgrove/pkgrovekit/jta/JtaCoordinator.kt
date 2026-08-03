package com.pkgrove.pkgrovekit.jta

import com.pkgrove.pkgrovekit.coordination.BranchState
import com.pkgrove.pkgrovekit.coordination.ConcurrentScopeAccessException
import com.pkgrove.pkgrovekit.coordination.CoordinatedResult
import com.pkgrove.pkgrovekit.coordination.CoordinationFailure
import com.pkgrove.pkgrovekit.coordination.CoordinationPlan
import com.pkgrove.pkgrovekit.coordination.CoordinationPolicy
import com.pkgrove.pkgrovekit.coordination.GlobalOutcome
import com.pkgrove.pkgrovekit.coordination.ParticipantId
import com.pkgrove.pkgrovekit.coordination.ParticipantStatus
import com.pkgrove.pkgrovekit.coordination.PlanRejectedException
import com.pkgrove.pkgrovekit.coordination.PlanValidation
import com.pkgrove.pkgrovekit.coordination.PlanViolation
import com.pkgrove.pkgrovekit.coordination.Plans
import com.pkgrove.pkgrovekit.coordination.TransactionId
import com.pkgrove.pkgrovekit.coordination.XaCoordinator
import com.pkgrove.pkgrovekit.coordination.XaScope
import jakarta.transaction.HeuristicMixedException
import jakarta.transaction.HeuristicRollbackException
import jakarta.transaction.RollbackException
import jakarta.transaction.Status
import jakarta.transaction.SystemException
import jakarta.transaction.Transaction
import jakarta.transaction.TransactionManager
import java.sql.Connection
import javax.sql.XAConnection
import javax.transaction.xa.XAResource

/**
 * Interprets [CoordinationPolicy.Xa2Pc] plans through an EXTERNAL Jakarta
 * [TransactionManager] (HEL-170). This class never prepares, commits or rolls
 * back a branch itself — it begins/completes the global transaction via the TM
 * and enlists each participant's [XAResource]; the TM runs the 2PC protocol.
 *
 * Outcome mapping (commit path):
 *  - normal return                  → [GlobalOutcome.Committed]
 *  - [RollbackException]            → [GlobalOutcome.RolledBack] (coordinator decided)
 *  - [HeuristicMixedException]      → [GlobalOutcome.HeuristicMixed]
 *  - [HeuristicRollbackException]   → [GlobalOutcome.RolledBack] (heuristic detail kept)
 *  - [SystemException] while the TM still reports a prepared/committing status
 *                                   → [GlobalOutcome.RecoveryPending]
 *  - other [SystemException]        → [GlobalOutcome.InDoubt]
 *
 * Work failures roll the transaction back and surface as
 * [GlobalOutcome.RolledBack] with the cause attached — never a bare rethrow.
 */
class JtaCoordinator(
    private val tm: TransactionManager,
    private val participants: XaParticipants,
) : XaCoordinator {

    override fun validate(plan: CoordinationPlan): PlanValidation {
        val base = Plans.validate(plan)
        val unregistered = plan.participants
            .filter { it.id !in participants }
            .map { PlanViolation.UnregisteredParticipant(it.id) }
        if (unregistered.isEmpty()) return base
        return when (base) {
            is PlanValidation.Valid -> PlanValidation.Invalid(plan, unregistered)
            is PlanValidation.Invalid -> PlanValidation.Invalid(plan, base.violations + unregistered)
        }
    }

    override fun <T> inGlobalTransaction(plan: CoordinationPlan, work: (XaScope) -> T): CoordinatedResult<T> {
        val validation = validate(plan)
        if (validation is PlanValidation.Invalid) throw PlanRejectedException(validation.violations)
        val policy = plan.policy as CoordinationPolicy.Xa2Pc

        tm.setTransactionTimeout(policy.timeout.seconds.toInt().coerceAtLeast(1))
        tm.begin()
        val tx: Transaction = tm.transaction
        val scope = Scope(tx, plan)
        val txId = TransactionId(tx.toString())

        var value: T? = null
        var workFailure: Throwable? = null
        try {
            try {
                value = work(scope)
            } catch (t: Throwable) {
                workFailure = t
            }

            val outcome: GlobalOutcome = if (workFailure != null) {
                runCatching { tm.rollback() }
                GlobalOutcome.RolledBack(
                    txId, scope.statuses(BranchState.ROLLED_BACK),
                    CoordinationFailure.WorkFailed(workFailure),
                )
            } else {
                scope.delistAll(tx)
                try {
                    tm.commit()
                    GlobalOutcome.Committed(txId, scope.statuses(BranchState.COMMITTED))
                } catch (e: RollbackException) {
                    GlobalOutcome.RolledBack(
                        txId, scope.statuses(BranchState.ROLLED_BACK),
                        CoordinationFailure.CoordinatorRolledBack(e),
                    )
                } catch (e: HeuristicMixedException) {
                    GlobalOutcome.HeuristicMixed(
                        txId, scope.statuses(BranchState.HEURISTIC),
                        detail = e.message ?: "heuristic mixed completion",
                    )
                } catch (e: HeuristicRollbackException) {
                    GlobalOutcome.RolledBack(
                        txId, scope.statuses(BranchState.ROLLED_BACK),
                        CoordinationFailure.CoordinatorRolledBack(e),
                    )
                } catch (e: SystemException) {
                    val status = runCatching { tm.status }.getOrDefault(Status.STATUS_UNKNOWN)
                    if (status in RECOVERY_STATUSES) {
                        GlobalOutcome.RecoveryPending(
                            txId, scope.statuses(BranchState.PREPARED),
                            detail = "TM status $status after SystemException: ${e.message}",
                        )
                    } else {
                        GlobalOutcome.InDoubt(
                            txId, scope.statuses(BranchState.UNKNOWN),
                            CoordinationFailure.SystemFailure(e),
                        )
                    }
                }
            }
            return CoordinatedResult(outcome, if (outcome is GlobalOutcome.Committed) value else null)
        } finally {
            scope.closeScope()
            // restore the TM's default timeout for subsequent transactions
            runCatching { tm.setTransactionTimeout(0) }
        }
    }

    private companion object {
        val RECOVERY_STATUSES = setOf(
            Status.STATUS_PREPARED, Status.STATUS_PREPARING, Status.STATUS_COMMITTING,
        )
    }

    /** One branch: the physical XA connection + the guarded handle handed to work. */
    private class Branch(
        val xaConnection: XAConnection,
        val xaResource: XAResource,
        val guarded: Connection,
    )

    private inner class Scope(
        private val tx: Transaction,
        private val plan: CoordinationPlan,
    ) : XaScope {
        private val owner: Thread = Thread.currentThread()
        private val branches = LinkedHashMap<ParticipantId, Branch>()

        @Volatile
        private var closed = false

        override val txId: TransactionId get() = TransactionId(tx.toString())

        override fun connection(id: ParticipantId): Connection {
            if (Thread.currentThread() !== owner) {
                throw ConcurrentScopeAccessException(
                    "XaScope for $txId belongs to thread '${owner.name}' — " +
                        "'${Thread.currentThread().name}' may not use it",
                )
            }
            check(!closed) { "XaScope for $txId is closed" }
            require(plan.participants.any { it.id == id }) {
                "participant '$id' is not part of this plan"
            }
            return branches.getOrPut(id) {
                val xaConn = participants.dataSource(id).getXAConnection()
                val xaRes = xaConn.xaResource
                try {
                    val physical = xaConn.connection
                    // Some drivers (pgjdbc) keep the logical handle reporting
                    // auto-commit=true even once enlisted; JoinExisting rightly
                    // refuses auto-commit connections, so disable it up front.
                    // Drivers that manage this themselves may refuse — tolerated.
                    runCatching { physical.autoCommit = false }
                    check(tx.enlistResource(xaRes)) { "TM refused to enlist resource for '$id'" }
                    // One enlisted connection per branch; local tx verbs forbidden.
                    Branch(xaConn, xaRes, EnlistedConnections.guard(physical, owner) { closed })
                } catch (t: Throwable) {
                    runCatching { xaConn.close() }
                    throw t
                }
            }.guarded
        }

        fun statuses(state: BranchState): List<ParticipantStatus> =
            plan.participants.map { p ->
                if (p.id in branches) ParticipantStatus(p.id, state)
                else ParticipantStatus(p.id, BranchState.UNKNOWN, detail = "branch never enlisted (work did not request this connection)")
            }

        fun delistAll(tx: Transaction) {
            branches.values.forEach { b ->
                runCatching { tx.delistResource(b.xaResource, XAResource.TMSUCCESS) }
            }
        }

        /** Coordinator-owned close of the PHYSICAL connections — after completion only. */
        fun closeScope() {
            closed = true
            branches.values.forEach { runCatching { it.xaConnection.close() } }
        }
    }
}
