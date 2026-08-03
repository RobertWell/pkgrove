package com.pkgrove.pkgrovekit.coordination

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class PlansTest {

    private fun xa(id: String) = Participant(ParticipantId(id), ParticipantCapability.XaCapable)
    private fun local(id: String) = Participant(ParticipantId(id), ParticipantCapability.LocalJdbc)
    private fun comp(id: String) = Participant(ParticipantId(id), ParticipantCapability.Compensatable)

    @Test
    fun `an all-XA plan under Xa2Pc is valid`() {
        val plan = CoordinationPlan(CoordinationPolicy.Xa2Pc(Duration.ofSeconds(30)), listOf(xa("pg-a"), xa("pg-b")))
        assertInstanceOf(PlanValidation.Valid::class.java, Plans.validate(plan))
    }

    @Test
    fun `a non-XA participant is rejected from an Xa2Pc plan`() {
        val duck = local("duckdb")
        val plan = CoordinationPlan(CoordinationPolicy.Xa2Pc(Duration.ofSeconds(30)), listOf(xa("pg-a"), duck))
        val invalid = Plans.validate(plan) as PlanValidation.Invalid
        val violation = invalid.violations.filterIsInstance<PlanViolation.NonXaParticipant>().single()
        assertEquals(duck, violation.participant)
        assertTrue(violation.message.contains("saga"), "message should point at the saga alternative")
    }

    @Test
    fun `empty plans, duplicate ids and non-positive timeouts are all reported together`() {
        val invalid = Plans.validate(
            CoordinationPlan(CoordinationPolicy.Xa2Pc(Duration.ZERO), emptyList()),
        ) as PlanValidation.Invalid
        assertTrue(invalid.violations.any { it is PlanViolation.NoParticipants })
        assertTrue(invalid.violations.any { it is PlanViolation.NonPositiveTimeout })

        val dup = Plans.validate(
            CoordinationPlan(CoordinationPolicy.Xa2Pc(Duration.ofSeconds(1)), listOf(xa("a"), xa("a"))),
        ) as PlanValidation.Invalid
        assertEquals(ParticipantId("a"), dup.violations.filterIsInstance<PlanViolation.DuplicateParticipantId>().single().id)
    }

    @Test
    fun `saga plans require compensatable participants`() {
        val invalid = Plans.validate(
            CoordinationPlan(CoordinationPolicy.Saga(), listOf(comp("files"), xa("pg-a"))),
        ) as PlanValidation.Invalid
        assertInstanceOf(
            PlanViolation.NonCompensatableParticipant::class.java,
            invalid.violations.single(),
        )
    }

    @Test
    fun `Local policy accepts any capability mix`() {
        val plan = CoordinationPlan(CoordinationPolicy.Local, listOf(local("a"), xa("b"), comp("c")))
        assertInstanceOf(PlanValidation.Valid::class.java, Plans.validate(plan))
    }

    @Test
    fun `PlanRejectedException carries every violation message`() {
        val violations = listOf(
            PlanViolation.NoParticipants(),
            PlanViolation.NonPositiveTimeout(Duration.ZERO),
        )
        val ex = assertThrows(PlanRejectedException::class.java) { throw PlanRejectedException(violations) }
        assertEquals(violations, ex.violations)
        assertTrue(ex.message!!.contains("at least one participant"))
        assertTrue(ex.message!!.contains("timeout"))
    }

    @Test
    fun `blank participant ids are impossible`() {
        assertThrows(IllegalArgumentException::class.java) { ParticipantId("  ") }
    }
}
