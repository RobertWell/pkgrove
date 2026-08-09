package com.pkgrove.pkgrovekit.core;

import kotlin.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HEL-234: the Choice/WorkflowOutcome combinators are Kotlin {@code inline}
 * functions — every Kotlin call site splices the body into the CALLER, so the
 * public compiled copies in ChoiceKt/WorkflowOutcomeKt (the entry points Java
 * consumers use) are never measured by the Kotlin suites. Java cannot inline
 * them, so this test is both the Java-interop proof and the coverage of those
 * public copies.
 */
class InlineCopiesJavaTest {

    private final Choice<String, Integer> left = Choice.Companion.left("rejected");
    private final Choice<String, Integer> right = Choice.Companion.right(7);

    @Test
    void foldEliminatesBothPaths() {
        assertEquals("L:rejected", ChoiceKt.fold(left, l -> "L:" + l, r -> "R:" + r));
        assertEquals("R:7", ChoiceKt.fold(right, l -> "L:" + l, r -> "R:" + r));
    }

    @Test
    void mapRightTransformsRightAndPassesLeftThrough() {
        assertEquals(new Choice.Right<>(14), ChoiceKt.mapRight(right, r -> r * 2));
        assertEquals(left, ChoiceKt.mapRight(left, r -> r * 2));
    }

    @Test
    void mapLeftTransformsLeftAndPassesRightThrough() {
        assertEquals(new Choice.Left<>("REJECTED"), ChoiceKt.mapLeft(left, String::toUpperCase));
        assertEquals(right, ChoiceKt.mapLeft(right, String::toUpperCase));
    }

    @Test
    void bimapTransformsBothPaths() {
        assertEquals(new Choice.Left<>(8), ChoiceKt.bimap(left, String::length, r -> "n=" + r));
        assertEquals(new Choice.Right<>("n=7"), ChoiceKt.bimap(right, String::length, r -> "n=" + r));
    }

    @Test
    void partitionByChoiceRoutesIntoTwoOrderedBuckets() {
        Pair<List<String>, List<Integer>> out = ChoiceKt.partitionByChoice(
                List.of(1, 2, 3, 4),
                n -> (n % 2 == 0) ? Choice.Companion.right(n) : Choice.Companion.left("odd-" + n));
        assertEquals(List.of("odd-1", "odd-3"), out.getFirst());
        assertEquals(List.of(2, 4), out.getSecond());
    }

    @Test
    void rightOrNullAndLeftOrNullInterop() {
        assertEquals(7, ChoiceKt.rightOrNull(right));
        assertNull(ChoiceKt.leftOrNull(right));
        assertEquals("rejected", ChoiceKt.leftOrNull(left));
        assertNull(ChoiceKt.rightOrNull(left));
    }

    @Test
    void mapCompletedMapsEachOutcomeKindLawfully() {
        assertEquals(new WorkflowOutcome.Completed<>("v=1"),
                WorkflowOutcomeKt.mapCompleted(new WorkflowOutcome.Completed<>(1), v -> "v=" + v));

        List<BranchFailure> failures = List.of(new BranchFailure("sink", new RuntimeException("x")));
        assertEquals(new WorkflowOutcome.Partial<>("v=2", failures),
                WorkflowOutcomeKt.mapCompleted(new WorkflowOutcome.Partial<>(2, failures), v -> "v=" + v));
        assertEquals(new WorkflowOutcome.Partial<String>(null, failures),
                WorkflowOutcomeKt.mapCompleted(new WorkflowOutcome.Partial<Integer>(null, failures), v -> "v=" + v));

        RuntimeException boom = new RuntimeException("boom");
        assertEquals(new WorkflowOutcome.Failed(boom),
                WorkflowOutcomeKt.mapCompleted(new WorkflowOutcome.Failed(boom), v -> "v=" + v));
        assertEquals(new WorkflowOutcome.Cancelled(),
                WorkflowOutcomeKt.mapCompleted(new WorkflowOutcome.Cancelled(), v -> "v=" + v));
    }

    @Test
    void getOrNullReturnsValueOnlyForCompleted() {
        assertEquals(3, WorkflowOutcomeKt.getOrNull(new WorkflowOutcome.Completed<>(3)));
        assertNull(WorkflowOutcomeKt.getOrNull(new WorkflowOutcome.Partial<>(3, List.of())));
        assertNull(WorkflowOutcomeKt.getOrNull(new WorkflowOutcome.Failed(new RuntimeException())));
        assertNull(WorkflowOutcomeKt.getOrNull(new WorkflowOutcome.Cancelled()));
    }
}
