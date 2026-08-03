package com.pkgrove.pkgrovekit.saga

import com.pkgrove.pkgrovekit.coordination.CompensationOrder

/**
 * HEL-170 — the saga boundary.
 *
 * WHAT THIS GIVES: completion-or-compensation. Each step either runs to
 * completion, or — after a later step fails — its compensate action is invoked,
 * by default in reverse completion order.
 *
 * WHAT THIS DOES **NOT** GIVE (by design, stated loudly so nobody confuses the
 * two coordination strategies):
 *  - NO isolation: other readers observe every intermediate state.
 *  - NO atomic visibility: there is no instant where all steps become visible.
 *  - NO automatic durability: with the default [InMemorySagaJournal] a crash
 *    loses progress; supply a durable [SagaJournal] for restartable sagas.
 *
 * Steps carry a durable identity and an idempotency key: re-running a saga with
 * the same journal skips steps the journal already recorded as COMPLETED, so an
 * interrupted saga can be resumed without double-applying effects — provided
 * the step's execute action is idempotent per its key (that contract belongs to
 * the step author).
 */

@JvmInline
value class SagaId(val value: String) {
    init { require(value.isNotBlank()) { "saga id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class SagaStepId(val value: String) {
    init { require(value.isNotBlank()) { "step id must not be blank" } }
    override fun toString(): String = value
}

/** Persisted lifecycle of one step. */
enum class SagaStepState {
    PENDING, EXECUTING, COMPLETED, COMPENSATING, COMPENSATED,
    COMPENSATION_FAILED, MANUAL_INTERVENTION_REQUIRED,
}

/**
 * One saga step: durable identity, an idempotency key the execute/compensate
 * actions must honor, and the two effect functions. The step itself is data;
 * effects run only when the interpreter reaches it.
 */
data class SagaStep<C>(
    val id: SagaStepId,
    val idempotencyKey: String,
    val execute: (C) -> Unit,
    val compensate: (C) -> Unit,
)

/** An inert saga: interpret with [SagaInterpreter.run]. */
data class SagaPlan<C>(
    val id: SagaId,
    val steps: List<SagaStep<C>>,
    val compensationOrder: CompensationOrder = CompensationOrder.REVERSE,
) {
    init {
        require(steps.isNotEmpty()) { "a saga needs at least one step" }
        val dup = steps.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate step ids: $dup" }
    }
}

/** Where step states are recorded. Durability is the implementation's contract. */
interface SagaJournal {
    fun record(sagaId: SagaId, stepId: SagaStepId, state: SagaStepState, detail: String? = null)
    fun stateOf(sagaId: SagaId, stepId: SagaStepId): SagaStepState?
    fun states(sagaId: SagaId): Map<SagaStepId, SagaStepState>
}

/** Volatile journal — fine for tests and single-run sagas, useless after a crash. */
class InMemorySagaJournal : SagaJournal {
    private val states = LinkedHashMap<Pair<SagaId, SagaStepId>, SagaStepState>()
    private val details = LinkedHashMap<Pair<SagaId, SagaStepId>, String?>()

    @Synchronized
    override fun record(sagaId: SagaId, stepId: SagaStepId, state: SagaStepState, detail: String?) {
        states[sagaId to stepId] = state
        details[sagaId to stepId] = detail
    }

    @Synchronized
    override fun stateOf(sagaId: SagaId, stepId: SagaStepId): SagaStepState? = states[sagaId to stepId]

    @Synchronized
    override fun states(sagaId: SagaId): Map<SagaStepId, SagaStepState> =
        states.filterKeys { it.first == sagaId }.mapKeys { it.key.second }

    @Synchronized
    fun detailOf(sagaId: SagaId, stepId: SagaStepId): String? = details[sagaId to stepId]
}

/** Terminal result of one interpretation run. */
sealed interface SagaOutcome {
    val sagaId: SagaId
    val stepStates: Map<SagaStepId, SagaStepState>

    /** Every step completed. */
    data class Completed(
        override val sagaId: SagaId,
        override val stepStates: Map<SagaStepId, SagaStepState>,
    ) : SagaOutcome

    /** A step failed; every previously completed step was compensated. */
    data class Compensated(
        override val sagaId: SagaId,
        val failedStep: SagaStepId,
        val failure: Throwable,
        override val stepStates: Map<SagaStepId, SagaStepState>,
    ) : SagaOutcome

    /**
     * A step failed AND at least one compensation also failed — the saga is in
     * [SagaStepState.MANUAL_INTERVENTION_REQUIRED] for those steps. Effects of
     * the un-compensated steps REMAIN APPLIED.
     */
    data class CompensationFailed(
        override val sagaId: SagaId,
        val failedStep: SagaStepId,
        val failure: Throwable,
        val compensationFailures: Map<SagaStepId, Throwable>,
        override val stepStates: Map<SagaStepId, SagaStepState>,
    ) : SagaOutcome
}

/**
 * The effectful interpreter. Executes steps in declared order; on the first
 * execute failure, compensates every COMPLETED step (skipping journal-resumed
 * ones is deliberate — they completed in an earlier run and still need
 * compensation, so they are NOT skipped here; only execution is skipped).
 */
class SagaInterpreter(private val journal: SagaJournal) {

    fun <C> run(plan: SagaPlan<C>, context: C): SagaOutcome {
        val completed = ArrayList<SagaStep<C>>()

        for (step in plan.steps) {
            // Idempotent resume: a step the journal already saw complete is not re-executed.
            if (journal.stateOf(plan.id, step.id) == SagaStepState.COMPLETED) {
                completed.add(step)
                continue
            }
            journal.record(plan.id, step.id, SagaStepState.EXECUTING)
            try {
                step.execute(context)
                journal.record(plan.id, step.id, SagaStepState.COMPLETED)
                completed.add(step)
            } catch (t: Throwable) {
                journal.record(plan.id, step.id, SagaStepState.PENDING, "execute failed: ${t.message}")
                return compensate(plan, context, failedStep = step.id, failure = t, completed = completed)
            }
        }
        return SagaOutcome.Completed(plan.id, journal.states(plan.id))
    }

    private fun <C> compensate(
        plan: SagaPlan<C>,
        context: C,
        failedStep: SagaStepId,
        failure: Throwable,
        completed: List<SagaStep<C>>,
    ): SagaOutcome {
        val order = when (plan.compensationOrder) {
            CompensationOrder.REVERSE -> completed.asReversed()
            CompensationOrder.FORWARD -> completed
        }
        val compensationFailures = LinkedHashMap<SagaStepId, Throwable>()
        for (step in order) {
            journal.record(plan.id, step.id, SagaStepState.COMPENSATING)
            try {
                step.compensate(context)
                journal.record(plan.id, step.id, SagaStepState.COMPENSATED)
            } catch (t: Throwable) {
                compensationFailures[step.id] = t
                journal.record(plan.id, step.id, SagaStepState.COMPENSATION_FAILED, t.message)
                journal.record(plan.id, step.id, SagaStepState.MANUAL_INTERVENTION_REQUIRED, t.message)
            }
        }
        return if (compensationFailures.isEmpty()) {
            SagaOutcome.Compensated(plan.id, failedStep, failure, journal.states(plan.id))
        } else {
            SagaOutcome.CompensationFailed(
                plan.id, failedStep, failure, compensationFailures, journal.states(plan.id),
            )
        }
    }
}
