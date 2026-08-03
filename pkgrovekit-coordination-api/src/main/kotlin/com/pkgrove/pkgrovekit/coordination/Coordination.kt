package com.pkgrove.pkgrovekit.coordination

import java.time.Duration

/**
 * HEL-170 — coordination as inert data.
 *
 * A [CoordinationPlan] is a value: it can be built, validated, inspected and
 * tested without opening a single connection. Effectful interpretation lives in
 * the optional runtime modules (`pkgrovekit-jta` for XA/JTA, `pkgrovekit-saga`
 * for compensation). Ordinary PkgroveKit transfers never see these types —
 * there is deliberately no `xa = true` option on the standard transfer path,
 * because a flag cannot carry the operational requirements (recovery log,
 * coordinator identity, crash recovery) that a global transaction implies.
 */

/** Stable identity of a coordination participant (a database or other resource). */
@JvmInline
value class ParticipantId(val value: String) {
    init { require(value.isNotBlank()) { "participant id must not be blank" } }
    override fun toString(): String = value
}

/**
 * What a participant can actually guarantee. Capabilities are DECLARED at
 * registration and validated against the selected policy — a plan never
 * discovers an unsupported combination after writes begin.
 */
sealed interface ParticipantCapability {
    /** Ordinary pooled JDBC. Local transactions only — can NEVER join XA. */
    data object LocalJdbc : ParticipantCapability

    /** Backed by a real `javax.sql.XADataSource`; may join a 2PC global transaction. */
    data object XaCapable : ParticipantCapability

    /**
     * Has an explicit execute + compensate pair (saga step). No isolation and
     * no atomic visibility — see `pkgrovekit-saga` for what this does NOT give.
     */
    data object Compensatable : ParticipantCapability
}

/** A participant declaration: pure data, no connection, no driver. */
data class Participant(
    val id: ParticipantId,
    val capability: ParticipantCapability,
    val description: String = "",
)

/**
 * The coordination strategy. XA/2PC and saga compensation are DIFFERENT
 * strategies with DIFFERENT guarantees — the type system keeps them apart so
 * they cannot be mixed accidentally.
 */
sealed interface CoordinationPolicy {
    /** No cross-resource coordination — each resource uses its own local policy. */
    data object Local : CoordinationPolicy

    /**
     * Strict XA two-phase commit through an EXTERNAL transaction manager.
     * Every participant must be [ParticipantCapability.XaCapable].
     */
    data class Xa2Pc(val timeout: Duration) : CoordinationPolicy

    /**
     * Compensation-based coordination for resources that cannot join XA.
     * Provides completion-or-compensation, NOT atomic visibility.
     */
    data class Saga(val compensationOrder: CompensationOrder = CompensationOrder.REVERSE) : CoordinationPolicy
}

/** Order in which completed saga steps are compensated after a failure. */
enum class CompensationOrder { REVERSE, FORWARD }

/** An inert coordination plan: policy + declared participants. */
data class CoordinationPlan(
    val policy: CoordinationPolicy,
    val participants: List<Participant>,
)

/** A specific reason a plan cannot be interpreted under its policy. */
sealed interface PlanViolation {
    val message: String

    data class NoParticipants(
        override val message: String = "a coordination plan needs at least one participant",
    ) : PlanViolation

    data class DuplicateParticipantId(val id: ParticipantId) : PlanViolation {
        override val message: String = "participant id '$id' is declared more than once"
    }

    /** The core preflight rule: a non-XA resource can never enter a 2PC plan. */
    data class NonXaParticipant(val participant: Participant) : PlanViolation {
        override val message: String =
            "participant '${participant.id}' (${participant.capability}) cannot join an XA " +
                "global transaction — only XaCapable participants may; use a saga or " +
                "staging-and-publish for this resource"
    }

    data class NonCompensatableParticipant(val participant: Participant) : PlanViolation {
        override val message: String =
            "participant '${participant.id}' (${participant.capability}) has no " +
                "execute/compensate pair and cannot join a saga plan"
    }

    data class NonPositiveTimeout(val timeout: Duration) : PlanViolation {
        override val message: String = "XA transaction timeout must be positive, was $timeout"
    }

    /** Interpreter-side check: the plan names a participant the runtime has no registration for. */
    data class UnregisteredParticipant(val id: ParticipantId) : PlanViolation {
        override val message: String =
            "participant '$id' is not registered with this coordinator (no XADataSource known for it)"
    }
}

/** Typed validation result — the planning-time answer, never a runtime surprise. */
sealed interface PlanValidation {
    data class Valid(val plan: CoordinationPlan) : PlanValidation
    data class Invalid(val plan: CoordinationPlan, val violations: List<PlanViolation>) : PlanValidation {
        init { require(violations.isNotEmpty()) { "Invalid requires at least one violation" } }
    }
}

/** Pure plan validation — no I/O, usable from tests and tooling. */
object Plans {

    fun validate(plan: CoordinationPlan): PlanValidation {
        val violations = buildList {
            if (plan.participants.isEmpty()) add(PlanViolation.NoParticipants())
            plan.participants.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
                add(PlanViolation.DuplicateParticipantId(it))
            }
            when (val p = plan.policy) {
                is CoordinationPolicy.Xa2Pc -> {
                    if (p.timeout.isZero || p.timeout.isNegative) add(PlanViolation.NonPositiveTimeout(p.timeout))
                    plan.participants
                        .filter { it.capability != ParticipantCapability.XaCapable }
                        .forEach { add(PlanViolation.NonXaParticipant(it)) }
                }
                is CoordinationPolicy.Saga ->
                    plan.participants
                        .filter { it.capability != ParticipantCapability.Compensatable }
                        .forEach { add(PlanViolation.NonCompensatableParticipant(it)) }
                CoordinationPolicy.Local -> Unit
            }
        }
        return if (violations.isEmpty()) PlanValidation.Valid(plan)
        else PlanValidation.Invalid(plan, violations)
    }
}

/** Thrown when an interpreter is asked to execute a plan that failed validation. */
class PlanRejectedException(val violations: List<PlanViolation>) :
    IllegalArgumentException(
        "coordination plan rejected: " + violations.joinToString("; ") { it.message },
    )
