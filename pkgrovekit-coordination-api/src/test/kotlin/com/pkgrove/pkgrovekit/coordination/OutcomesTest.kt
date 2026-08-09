package com.pkgrove.pkgrovekit.coordination

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * HEL-234: direct coverage of the typed outcome surface — every [GlobalOutcome]
 * variant, [CoordinationFailure] messages, [CoordinatedResult] commit
 * semantics, identity types, and the rejected-plan exception. These values are
 * the operator-facing contract (in-doubt/heuristic states are first-class), so
 * their construction and messages are pinned here, not left to integration
 * suites.
 */
class OutcomesTest {

    private val tx = TransactionId("tx-42")
    private val p1 = ParticipantStatus(ParticipantId("orders"), BranchState.COMMITTED)
    private val p2 = ParticipantStatus(ParticipantId("ledger"), BranchState.ROLLED_BACK, detail = "heuristic")

    @Test
    fun `transaction id is a transparent value`() {
        assertEquals("tx-42", tx.value)
        assertEquals("tx-42", tx.toString())
        assertEquals(TransactionId("tx-42"), tx)
    }

    @Test
    fun `participant status carries branch state and optional detail`() {
        assertNull(p1.detail)
        assertEquals(BranchState.COMMITTED, p1.state)
        assertEquals("heuristic", p2.detail)
        // all branch states are addressable (recovery tooling iterates them)
        assertEquals(
            listOf("ENLISTED", "PREPARED", "COMMITTED", "ROLLED_BACK", "HEURISTIC", "UNKNOWN"),
            BranchState.entries.map { it.name },
        )
    }

    @Test
    fun `work failure message carries the cause`() {
        val f = CoordinationFailure.WorkFailed(IllegalStateException("constraint violated"))
        assertEquals("work failed: constraint violated", f.message)
    }

    @Test
    fun `coordinator rollback message handles both a cause and no cause`() {
        val with = CoordinationFailure.CoordinatorRolledBack(RuntimeException("timeout"))
        assertEquals("coordinator rolled back: timeout", with.message)
        val without = CoordinationFailure.CoordinatorRolledBack(null)
        assertEquals("coordinator rolled back: (no cause)", without.message)
    }

    @Test
    fun `system failure message carries the cause`() {
        val f = CoordinationFailure.SystemFailure(RuntimeException("object store unreachable"))
        assertEquals("coordinator system failure: object store unreachable", f.message)
    }

    @Test
    fun `committed outcome exposes tx identity and branches`() {
        val o: GlobalOutcome = GlobalOutcome.Committed(tx, listOf(p1))
        assertEquals(tx, o.txId)
        assertEquals(listOf(p1), o.participants)
    }

    @Test
    fun `rolled back outcome carries its failure cause`() {
        val cause = CoordinationFailure.WorkFailed(RuntimeException("boom"))
        val o = GlobalOutcome.RolledBack(tx, listOf(p1, p2), cause)
        assertSame(cause, o.cause)
        assertEquals(2, o.participants.size)
    }

    @Test
    fun `in doubt outcome is a first-class value not an exception`() {
        val cause = CoordinationFailure.SystemFailure(RuntimeException("crash between prepare and commit"))
        val o: GlobalOutcome = GlobalOutcome.InDoubt(tx, listOf(p1), cause)
        assertTrue(o is GlobalOutcome.InDoubt)
        assertSame(cause, (o as GlobalOutcome.InDoubt).cause)
    }

    @Test
    fun `heuristic mixed and recovery pending carry optional operator detail`() {
        val mixed = GlobalOutcome.HeuristicMixed(tx, listOf(p1, p2), detail = "branch ledger rolled back heuristically")
        assertEquals("branch ledger rolled back heuristically", mixed.detail)
        assertNull(GlobalOutcome.HeuristicMixed(tx, emptyList()).detail)

        val pending = GlobalOutcome.RecoveryPending(tx, listOf(p1), detail = "resource unreachable")
        assertEquals("resource unreachable", pending.detail)
        assertNull(GlobalOutcome.RecoveryPending(tx, emptyList()).detail)
    }

    @Test
    fun `coordinated result is committed only for the committed outcome`() {
        val committed = CoordinatedResult(GlobalOutcome.Committed(tx, listOf(p1)), "value")
        assertTrue(committed.committed)
        assertEquals("value", committed.value)

        val rolledBack = CoordinatedResult<String>(
            GlobalOutcome.RolledBack(tx, listOf(p1), CoordinationFailure.CoordinatorRolledBack(null)),
            null,
        )
        assertFalse(rolledBack.committed)
        assertNull(rolledBack.value)

        val inDoubt = CoordinatedResult<String>(
            GlobalOutcome.InDoubt(tx, listOf(p1), CoordinationFailure.SystemFailure(RuntimeException("x"))),
            null,
        )
        assertFalse(inDoubt.committed)
    }

    @Test
    fun `plan rejected exception lists every violation`() {
        val nonXa = Participant(ParticipantId("files"), ParticipantCapability.Compensatable)
        val e = PlanRejectedException(listOf(
            PlanViolation.NonXaParticipant(nonXa),
            PlanViolation.UnregisteredParticipant(ParticipantId("ghost")),
        ))
        val msg = e.message ?: ""
        assertTrue("cannot join an XA" in msg, msg)
        assertTrue("'ghost' is not registered" in msg, msg)
        assertEquals(2, e.violations.size)
    }

    @Test
    fun `unregistered participant violation names the missing registration`() {
        val v = PlanViolation.UnregisteredParticipant(ParticipantId("ghost"))
        assertTrue("no XADataSource known" in v.message, v.message)
    }

    @Test
    fun `non compensatable violation names the capability`() {
        val v = PlanViolation.NonCompensatableParticipant(
            Participant(ParticipantId("db"), ParticipantCapability.LocalJdbc),
        )
        assertTrue("cannot join a saga plan" in v.message, v.message)
    }

    @Test
    fun `invalid validation requires at least one violation`() {
        val plan = CoordinationPlan(CoordinationPolicy.Local, emptyList())
        assertThrows<IllegalArgumentException> { PlanValidation.Invalid(plan, emptyList()) }
    }

    @Test
    fun `blank participant id is rejected at construction`() {
        assertThrows<IllegalArgumentException> { ParticipantId(" ") }
        assertThrows<IllegalArgumentException> { ParticipantId("") }
    }

    @Test
    fun `concurrency and enlistment guard exceptions are constructible with messages`() {
        assertTrue(ConcurrentScopeAccessException("scope crossed threads").message!!.contains("scope"))
        assertTrue(EnlistedConnectionViolationException("closed enlisted connection").message!!.contains("enlisted"))
    }

    @Test
    fun `xa plan with zero timeout and duplicate ids reports each violation`() {
        val dup = Participant(ParticipantId("a"), ParticipantCapability.XaCapable)
        val plan = CoordinationPlan(
            CoordinationPolicy.Xa2Pc(Duration.ZERO),
            listOf(dup, dup.copy(description = "again")),
        )
        val result = Plans.validate(plan)
        assertTrue(result is PlanValidation.Invalid)
        val kinds = (result as PlanValidation.Invalid).violations.map { it::class.simpleName }
        assertTrue("NonPositiveTimeout" in kinds, kinds.toString())
        assertTrue("DuplicateParticipantId" in kinds, kinds.toString())
    }
}
