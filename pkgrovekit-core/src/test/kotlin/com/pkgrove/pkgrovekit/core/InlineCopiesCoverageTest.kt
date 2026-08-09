package com.pkgrove.pkgrovekit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * HEL-234: the Choice/WorkflowOutcome combinators are `inline`, so ordinary
 * Kotlin call sites splice their bodies into the CALLER and the compiled
 * public copies in ChoiceKt/WorkflowOutcomeKt stay unmeasured (they are the
 * entry points Java consumers call). Invoking them through callable
 * references binds to those public copies, so this test both measures them
 * and proves the non-inlined path behaves identically.
 */
class InlineCopiesCoverageTest {

    private val left: Choice<String, Int> = Choice.left("rejected")
    private val right: Choice<String, Int> = Choice.right(7)

    @Test
    fun `fold public copy eliminates both paths`() {
        val fold: (Choice<String, Int>, (String) -> String, (Int) -> String) -> String =
            Choice<String, Int>::fold
        assertEquals("L:rejected", fold(left, { "L:$it" }, { "R:$it" }))
        assertEquals("R:7", fold(right, { "L:$it" }, { "R:$it" }))
    }

    @Test
    fun `mapRight public copy transforms right and passes left through`() {
        val mapRight: (Choice<String, Int>, (Int) -> Int) -> Choice<String, Int> =
            Choice<String, Int>::mapRight
        assertEquals(Choice.right(14), mapRight(right) { it * 2 })
        assertEquals(left, mapRight(left) { it * 2 })
    }

    @Test
    fun `mapLeft public copy transforms left and passes right through`() {
        val mapLeft: (Choice<String, Int>, (String) -> String) -> Choice<String, Int> =
            Choice<String, Int>::mapLeft
        assertEquals(Choice.left("REJECTED"), mapLeft(left) { it.uppercase() })
        assertEquals(right, mapLeft(right) { it.uppercase() })
    }

    @Test
    fun `bimap public copy transforms both paths`() {
        val bimap: (Choice<String, Int>, (String) -> Int, (Int) -> String) -> Choice<Int, String> =
            Choice<String, Int>::bimap
        assertEquals(Choice.left(8), bimap(left, { it.length }, { "n=$it" }))
        assertEquals(Choice.right("n=7"), bimap(right, { it.length }, { "n=$it" }))
    }

    @Test
    fun `partitionByChoice public copy routes into two ordered buckets`() {
        val partition: (Iterable<Int>, (Int) -> Choice<String, Int>) -> Pair<List<String>, List<Int>> =
            Iterable<Int>::partitionByChoice
        val (lefts, rights) = partition(listOf(1, 2, 3, 4)) {
            if (it % 2 == 0) Choice.right(it) else Choice.left("odd-$it")
        }
        assertEquals(listOf("odd-1", "odd-3"), lefts)
        assertEquals(listOf(2, 4), rights)
    }

    @Test
    fun `rightOrNull and leftOrNull interop accessors`() {
        assertEquals(7, right.rightOrNull())
        assertNull(right.leftOrNull())
        assertEquals("rejected", left.leftOrNull())
        assertNull(left.rightOrNull())
    }

    @Test
    fun `mapCompleted public copy maps each outcome kind lawfully`() {
        val mapCompleted: (WorkflowOutcome<Int>, (Int) -> String) -> WorkflowOutcome<String> =
            WorkflowOutcome<Int>::mapCompleted

        assertEquals(WorkflowOutcome.Completed("v=1"),
                     mapCompleted(WorkflowOutcome.Completed(1)) { "v=$it" })

        val failures = listOf(BranchFailure("sink", RuntimeException("x")))
        val partial = mapCompleted(WorkflowOutcome.Partial(2, failures)) { "v=$it" }
        assertEquals(WorkflowOutcome.Partial("v=2", failures), partial)
        val partialNull = mapCompleted(WorkflowOutcome.Partial(null, failures)) { "v=$it" }
        assertEquals(WorkflowOutcome.Partial(null, failures), partialNull)

        val boom = RuntimeException("boom")
        assertEquals(WorkflowOutcome.Failed(boom),
                     mapCompleted(WorkflowOutcome.Failed(boom)) { "v=$it" })
        assertEquals(WorkflowOutcome.Cancelled(),
                     mapCompleted(WorkflowOutcome.Cancelled()) { "v=$it" })
    }

    @Test
    fun `getOrNull returns the value only for completed`() {
        assertEquals(3, WorkflowOutcome.Completed(3).getOrNull())
        assertNull(WorkflowOutcome.Partial(3, emptyList()).getOrNull())
        assertNull(WorkflowOutcome.Failed(RuntimeException()).getOrNull())
        assertNull((WorkflowOutcome.Cancelled() as WorkflowOutcome<Int>).getOrNull())
    }
}
