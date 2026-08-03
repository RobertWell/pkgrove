package com.pkgrove.pkgrovekit.jta

import com.pkgrove.pkgrovekit.coordination.ConcurrentScopeAccessException
import com.pkgrove.pkgrovekit.coordination.CoordinationFailure
import com.pkgrove.pkgrovekit.coordination.CoordinationPlan
import com.pkgrove.pkgrovekit.coordination.CoordinationPolicy
import com.pkgrove.pkgrovekit.coordination.EnlistedConnectionViolationException
import com.pkgrove.pkgrovekit.coordination.GlobalOutcome
import com.pkgrove.pkgrovekit.coordination.Participant
import com.pkgrove.pkgrovekit.coordination.ParticipantCapability
import com.pkgrove.pkgrovekit.coordination.ParticipantId
import com.pkgrove.pkgrovekit.coordination.PlanRejectedException
import com.pkgrove.pkgrovekit.coordination.PlanViolation
import jakarta.transaction.HeuristicMixedException
import jakarta.transaction.HeuristicRollbackException
import jakarta.transaction.RollbackException
import jakarta.transaction.Status
import jakarta.transaction.SystemException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class JtaCoordinatorTest {

    private val a = ParticipantId("pg-a")
    private val b = ParticipantId("pg-b")
    private lateinit var tm: FakeTransactionManager
    private lateinit var dsA: FakeXADataSource
    private lateinit var dsB: FakeXADataSource
    private lateinit var coordinator: JtaCoordinator

    private fun plan(vararg ids: String, timeout: Duration = Duration.ofSeconds(30)) =
        CoordinationPlan(
            CoordinationPolicy.Xa2Pc(timeout),
            ids.map { Participant(ParticipantId(it), ParticipantCapability.XaCapable) },
        )

    @BeforeEach
    fun setup() {
        tm = FakeTransactionManager()
        dsA = FakeXADataSource()
        dsB = FakeXADataSource()
        coordinator = JtaCoordinator(
            tm,
            XaParticipants.build {
                register(a, dsA)
                register(b, dsB)
            },
        )
    }

    @Test
    fun `commit path - begins, enlists once per branch, delists, commits, returns the value`() {
        val result = coordinator.inGlobalTransaction(plan("pg-a", "pg-b")) { scope ->
            scope.connection(a).createStatement()
            scope.connection(b).createStatement()
            // second request for the same participant returns the SAME branch connection
            assertSame(scope.connection(a), scope.connection(a))
            "done"
        }

        assertInstanceOf(GlobalOutcome.Committed::class.java, result.outcome)
        assertEquals("done", result.value)
        assertTrue(result.committed)
        assertEquals(1, dsA.handedOut.size, "one XAConnection per branch")
        assertEquals(1, dsB.handedOut.size)
        assertEquals(listOf("timeout:30", "begin", "enlist", "enlist", "delist:67108864", "delist:67108864", "commit", "timeout:0"), tm.calls)
        // coordinator-owned close of the physical connections after completion
        assertTrue(dsA.handedOut.single().closed)
        assertTrue(dsB.handedOut.single().closed)
    }

    @Test
    fun `work failure - rolls back and surfaces the cause as typed data`() {
        val boom = IllegalStateException("insert failed")
        val result = coordinator.inGlobalTransaction(plan("pg-a", "pg-b")) { scope ->
            scope.connection(a)
            throw boom
        }

        val rolledBack = assertInstanceOf(GlobalOutcome.RolledBack::class.java, result.outcome)
        assertSame(boom, (rolledBack.cause as CoordinationFailure.WorkFailed).cause)
        assertNull(result.value)
        assertTrue(tm.calls.contains("rollback"))
        assertFalse(tm.calls.contains("commit"))
        assertTrue(dsA.handedOut.single().closed)
    }

    @Test
    fun `commit-time RollbackException maps to RolledBack`() {
        tm.commitError = RollbackException("prepare vote said no")
        val result = coordinator.inGlobalTransaction(plan("pg-a")) { it.connection(a); 1 }
        val out = assertInstanceOf(GlobalOutcome.RolledBack::class.java, result.outcome)
        assertInstanceOf(CoordinationFailure.CoordinatorRolledBack::class.java, out.cause)
        assertNull(result.value)
    }

    @Test
    fun `HeuristicMixedException keeps its heuristic state`() {
        tm.commitError = HeuristicMixedException("branch 2 committed, branch 1 rolled back")
        val result = coordinator.inGlobalTransaction(plan("pg-a")) { it.connection(a); 1 }
        val out = assertInstanceOf(GlobalOutcome.HeuristicMixed::class.java, result.outcome)
        assertTrue(out.detail!!.contains("branch 2"))
    }

    @Test
    fun `HeuristicRollbackException maps to RolledBack`() {
        tm.commitError = HeuristicRollbackException("all branches heuristically rolled back")
        val result = coordinator.inGlobalTransaction(plan("pg-a")) { it.connection(a); 1 }
        assertInstanceOf(GlobalOutcome.RolledBack::class.java, result.outcome)
    }

    @Test
    fun `SystemException with a prepared TM status maps to RecoveryPending`() {
        tm.commitError = SystemException("coordinator lost contact after prepare")
        tm.statusAfterCommitFailure = Status.STATUS_PREPARED
        val result = coordinator.inGlobalTransaction(plan("pg-a")) { it.connection(a); 1 }
        assertInstanceOf(GlobalOutcome.RecoveryPending::class.java, result.outcome)
    }

    @Test
    fun `SystemException without a recoverable status maps to InDoubt`() {
        tm.commitError = SystemException("who knows")
        tm.statusAfterCommitFailure = Status.STATUS_NO_TRANSACTION
        val result = coordinator.inGlobalTransaction(plan("pg-a")) { it.connection(a); 1 }
        val out = assertInstanceOf(GlobalOutcome.InDoubt::class.java, result.outcome)
        assertInstanceOf(CoordinationFailure.SystemFailure::class.java, out.cause)
    }

    @Test
    fun `invalid plans are rejected BEFORE any transaction or connection exists`() {
        val duck = Participant(ParticipantId("duckdb"), ParticipantCapability.LocalJdbc)
        val bad = CoordinationPlan(
            CoordinationPolicy.Xa2Pc(Duration.ofSeconds(5)),
            listOf(Participant(a, ParticipantCapability.XaCapable), duck),
        )

        val ex = assertThrows(PlanRejectedException::class.java) {
            coordinator.inGlobalTransaction(bad) { 1 }
        }
        assertTrue(ex.violations.any { it is PlanViolation.NonXaParticipant })
        // also unregistered here (duckdb has no XADataSource)
        assertTrue(ex.violations.any { it is PlanViolation.UnregisteredParticipant })
        assertTrue(tm.calls.isEmpty(), "no begin/enlist may happen for a rejected plan")
        assertTrue(dsA.handedOut.isEmpty(), "no connection may be opened for a rejected plan")
    }

    @Test
    fun `enlisted connections refuse local commit rollback close and auto-commit`() {
        coordinator.inGlobalTransaction(plan("pg-a")) { scope ->
            val c = scope.connection(a)
            assertThrows(EnlistedConnectionViolationException::class.java) { c.commit() }
            assertThrows(EnlistedConnectionViolationException::class.java) { c.rollback() }
            assertThrows(EnlistedConnectionViolationException::class.java) { c.close() }
            assertThrows(EnlistedConnectionViolationException::class.java) { c.autoCommit = true }
            c.autoCommit = false // disabling is fine
            c.createStatement()  // ordinary work is fine
            1
        }
        val physical = dsA.handedOut.single().physical
        assertFalse(physical.invocations.any { it.startsWith("commit") || it.startsWith("rollback") || it.startsWith("close") },
            "forbidden verbs must never reach the physical connection: ${physical.invocations}")
        assertTrue(physical.invocations.any { it.startsWith("createStatement") })
    }

    @Test
    fun `a scope connection cannot be used from another thread`() {
        coordinator.inGlobalTransaction(plan("pg-a")) { scope ->
            val c = scope.connection(a)
            val fromScope = AtomicReference<Throwable>()
            val fromConn = AtomicReference<Throwable>()
            thread {
                runCatching { scope.connection(a) }.exceptionOrNull()?.let(fromScope::set)
                runCatching { c.createStatement() }.exceptionOrNull()?.let(fromConn::set)
            }.join()
            assertInstanceOf(ConcurrentScopeAccessException::class.java, fromScope.get())
            assertInstanceOf(ConcurrentScopeAccessException::class.java, fromConn.get())
            1
        }
    }

    @Test
    fun `an escaped connection dies with the scope`() {
        var escaped: java.sql.Connection? = null
        coordinator.inGlobalTransaction(plan("pg-a")) { scope ->
            escaped = scope.connection(a)
            1
        }
        assertThrows(EnlistedConnectionViolationException::class.java) { escaped!!.createStatement() }
    }

    @Test
    fun `a participant outside the plan is refused even when registered`() {
        coordinator.inGlobalTransaction(plan("pg-a")) { scope ->
            assertThrows(IllegalArgumentException::class.java) { scope.connection(b) }
            1
        }
        assertTrue(dsB.handedOut.isEmpty())
    }

    @Test
    fun `never-enlisted participants are reported honestly in the outcome`() {
        val result = coordinator.inGlobalTransaction(plan("pg-a", "pg-b")) { scope ->
            scope.connection(a) // b never used
            1
        }
        val statuses = result.outcome.participants.associateBy({ it.id }, { it })
        assertEquals(com.pkgrove.pkgrovekit.coordination.BranchState.COMMITTED, statuses[a]!!.state)
        assertEquals(com.pkgrove.pkgrovekit.coordination.BranchState.UNKNOWN, statuses[b]!!.state)
        assertTrue(statuses[b]!!.detail!!.contains("never enlisted"))
    }
}
