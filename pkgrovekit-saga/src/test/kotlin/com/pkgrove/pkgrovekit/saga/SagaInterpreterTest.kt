package com.pkgrove.pkgrovekit.saga

import com.pkgrove.pkgrovekit.coordination.CompensationOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SagaInterpreterTest {

    private class Ctx {
        val log = mutableListOf<String>()
    }

    private fun step(
        id: String,
        failExecute: Boolean = false,
        failCompensate: Boolean = false,
    ) = SagaStep<Ctx>(
        id = SagaStepId(id),
        idempotencyKey = "key-$id",
        execute = { c ->
            if (failExecute) error("execute $id blew up")
            c.log.add("exec:$id")
        },
        compensate = { c ->
            if (failCompensate) error("compensate $id blew up")
            c.log.add("comp:$id")
        },
    )

    @Test
    fun `all steps complete in declared order`() {
        val journal = InMemorySagaJournal()
        val ctx = Ctx()
        val out = SagaInterpreter(journal).run(
            SagaPlan(SagaId("s1"), listOf(step("a"), step("b"), step("c"))), ctx,
        )
        assertInstanceOf(SagaOutcome.Completed::class.java, out)
        assertEquals(listOf("exec:a", "exec:b", "exec:c"), ctx.log)
        assertEquals(SagaStepState.COMPLETED, journal.stateOf(SagaId("s1"), SagaStepId("b")))
    }

    @Test
    fun `a failing step triggers reverse-order compensation of completed steps only`() {
        val journal = InMemorySagaJournal()
        val ctx = Ctx()
        val out = SagaInterpreter(journal).run(
            SagaPlan(SagaId("s2"), listOf(step("a"), step("b"), step("c", failExecute = true), step("d"))), ctx,
        ) as SagaOutcome.Compensated

        assertEquals(SagaStepId("c"), out.failedStep)
        // a and b compensated in REVERSE completion order; c/d never compensated
        assertEquals(listOf("exec:a", "exec:b", "comp:b", "comp:a"), ctx.log)
        assertEquals(SagaStepState.COMPENSATED, journal.stateOf(SagaId("s2"), SagaStepId("a")))
        assertEquals(null, journal.stateOf(SagaId("s2"), SagaStepId("d")))
    }

    @Test
    fun `FORWARD compensation order is honored`() {
        val ctx = Ctx()
        SagaInterpreter(InMemorySagaJournal()).run(
            SagaPlan(
                SagaId("s3"),
                listOf(step("a"), step("b"), step("x", failExecute = true)),
                compensationOrder = CompensationOrder.FORWARD,
            ),
            ctx,
        )
        assertEquals(listOf("exec:a", "exec:b", "comp:a", "comp:b"), ctx.log)
    }

    @Test
    fun `a failed compensation is marked MANUAL_INTERVENTION_REQUIRED and the rest still compensate`() {
        val journal = InMemorySagaJournal()
        val ctx = Ctx()
        val out = SagaInterpreter(journal).run(
            SagaPlan(
                SagaId("s4"),
                listOf(step("a"), step("b", failCompensate = true), step("x", failExecute = true)),
            ),
            ctx,
        ) as SagaOutcome.CompensationFailed

        assertEquals(setOf(SagaStepId("b")), out.compensationFailures.keys)
        // b's compensation failed but a's still ran
        assertEquals(listOf("exec:a", "exec:b", "comp:a"), ctx.log)
        assertEquals(
            SagaStepState.MANUAL_INTERVENTION_REQUIRED,
            journal.stateOf(SagaId("s4"), SagaStepId("b")),
        )
        assertEquals(SagaStepState.COMPENSATED, journal.stateOf(SagaId("s4"), SagaStepId("a")))
    }

    @Test
    fun `journal resume skips already-completed steps but still compensates them on later failure`() {
        val journal = InMemorySagaJournal()
        val sagaId = SagaId("s5")
        // First run "crashed" after completing a (journal remembers it).
        journal.record(sagaId, SagaStepId("a"), SagaStepState.COMPLETED)

        val ctx = Ctx()
        val out = SagaInterpreter(journal).run(
            SagaPlan(sagaId, listOf(step("a"), step("b", failExecute = true))), ctx,
        ) as SagaOutcome.Compensated

        // a was NOT re-executed (idempotent resume) but WAS compensated.
        assertEquals(listOf("comp:a"), ctx.log)
        assertEquals(SagaStepId("b"), out.failedStep)
    }

    @Test
    fun `plans reject duplicates and emptiness`() {
        assertThrows(IllegalArgumentException::class.java) {
            SagaPlan(SagaId("bad"), listOf(step("a"), step("a")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SagaPlan<Ctx>(SagaId("bad"), emptyList())
        }
    }
}
