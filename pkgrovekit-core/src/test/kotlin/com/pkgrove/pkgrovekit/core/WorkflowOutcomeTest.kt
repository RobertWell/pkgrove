package com.pkgrove.pkgrovekit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HEL-167: execution outcomes are distinct from business routing and from a
 *  nullable report/error pair — partial ≠ success, cancellation is first-class. */
class WorkflowOutcomeTest {

    @Test
    fun `completed is the only success state`() {
        assertTrue(WorkflowOutcome.Completed(1).isCompleted)
        assertFalse(WorkflowOutcome.Partial(1, listOf(BranchFailure("b", RuntimeException()))).isCompleted)
        assertFalse(WorkflowOutcome.Failed(RuntimeException()).isCompleted)
        assertFalse(WorkflowOutcome.Cancelled().isCompleted)
    }

    @Test
    fun `partial carries what completed AND what failed - never hidden`() {
        val o = WorkflowOutcome.Partial("data", listOf(BranchFailure("audit", IllegalStateException("boom"))))
        assertEquals("data", o.value)
        assertEquals("audit", o.failures.single().branch)
        // a Left-routed business path is NOT a failure and would be Completed, not Partial.
    }

    @Test
    fun `mapCompleted only maps the completed value`() {
        assertEquals(WorkflowOutcome.Completed(4), WorkflowOutcome.Completed(2).mapCompleted { it * 2 })
        val f = WorkflowOutcome.Failed(RuntimeException("x"))
        assertEquals(f, f.mapCompleted { it })   // Failed passes through
        assertNull((WorkflowOutcome.Cancelled() as WorkflowOutcome<Int>).getOrNull())
    }

    @Test
    fun `getOrNull is null for every non-completed outcome`() {
        assertEquals(9, WorkflowOutcome.Completed(9).getOrNull())
        assertNull(WorkflowOutcome.Partial(null, emptyList<BranchFailure>() + BranchFailure("b", RuntimeException())).getOrNull())
        assertNull(WorkflowOutcome.Failed(RuntimeException()).getOrNull())
    }
}
