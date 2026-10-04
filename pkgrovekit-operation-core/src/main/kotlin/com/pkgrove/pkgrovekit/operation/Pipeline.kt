package com.pkgrove.pkgrovekit.operation

/** The outcome of running an operation. Adapters map, they never interpret. */
public sealed class OperationOutcome {

    /** The handler ran (or its earlier result was replayed). */
    public data class Success(
        /** The typed output. */
        public val output: Any?,
        /** True when an idempotency key matched and the stored outcome was replayed. */
        public val replayed: Boolean = false,
    ) : OperationOutcome()

    /** The call failed, typed. */
    public data class Failure(public val error: OperationError) : OperationOutcome()

    /** The [OperationError] when this is a failure, else `null`. */
    public fun errorOrNull(): OperationError? = (this as? Failure)?.error
}

/**
 * What an OUTER stage can learn about what an INNER stage discovered.
 *
 * The [Exchange] is immutable, which is the right default — but it means the
 * outermost stage holds the PRE-authentication exchange and would therefore
 * audit every call as if nobody had been identified. Rather than move auditing
 * inside authentication (which would leave failed authentications unaudited —
 * the most interesting kind), one small by-reference box travels with the
 * exchange and records the resolved caller.
 *
 * Deliberately write-once-ish and tiny: it carries attribution, never decisions.
 */
public class CallAttribution internal constructor() {
    /** The caller the authenticate stage resolved, or `null` if none did. */
    @Volatile
    public var caller: CallerContext? = null
        internal set
}

/**
 * One call in flight, as it accumulates through the pipeline.
 *
 * Immutable: each stage produces a NEW exchange rather than mutating one, so a
 * stage cannot retroactively change what an earlier stage decided (an
 * authorization stage that could be un-decided is not an authorization stage).
 * The one exception is [attribution] — see [CallAttribution].
 */
public data class Exchange(
    public val operation: Operation<*, *>,
    public val surface: Surface,
    /** The raw credential the transport presented, if any. */
    public val credential: Any?,
    /** The transport payload. */
    public val payload: Json,
    public val correlationId: String,
    /** Request attributes carried into the audit record. */
    public val attributes: Map<String, String> = emptyMap(),
    /** Set by the authenticate stage. */
    public val caller: CallerContext? = null,
    /** Set by the decode stage. */
    public val input: Any? = null,
    /** Shared by every copy of this exchange; see [CallAttribution]. */
    public val attribution: CallAttribution = CallAttribution(),
)

/**
 * A composable pipeline stage: pre-work, then `next(exchange)`, then post-work.
 *
 * Stages are VALUES, so a deployment composes its own pipeline from the supplied
 * ones plus its own, and the composition is inspectable ([OperationPipeline.stageNames]).
 */
public class Stage(
    /** Stable stage name, used in ordering assertions and traces. */
    public val name: String,
    private val run: (Exchange, (Exchange) -> OperationOutcome) -> OperationOutcome,
) {
    internal fun invoke(exchange: Exchange, next: (Exchange) -> OperationOutcome): OperationOutcome =
        run(exchange, next)

    override fun toString(): String = "Stage($name)"
}

/** Reusable middleware: a named stage factory, independent of any one operation. */
public fun stage(
    name: String,
    run: (Exchange, (Exchange) -> OperationOutcome) -> OperationOutcome,
): Stage = Stage(name, run)

/**
 * An ordered composition of [Stage]s. The LAST stage is the innermost; it is
 * expected to be the handler stage and to ignore its `next`.
 */
public class OperationPipeline(stages: List<Stage>) {

    private val stages: List<Stage> = java.util.Collections.unmodifiableList(stages.toList())

    init {
        require(stages.isNotEmpty()) { "an OperationPipeline needs at least one stage" }
        val duplicates = stages.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate pipeline stage name(s): ${duplicates.sorted()}" }
    }

    /** Stage names, outermost first — the pipeline's shape, assertable in a test. */
    public fun stageNames(): List<String> = stages.map { it.name }

    /** Runs [exchange] through every stage. */
    public fun execute(exchange: Exchange): OperationOutcome = runFrom(0, exchange)

    private fun runFrom(index: Int, exchange: Exchange): OperationOutcome =
        if (index >= stages.size) {
            OperationOutcome.Failure(
                OperationError.Failed("pipeline for '${exchange.operation.id}' ended without a handler stage"),
            )
        } else {
            stages[index].invoke(exchange) { next -> runFrom(index + 1, next) }
        }
}

// ---------------------------------------------------------------------------
// Hooks. Interfaces with no-op defaults: a consumer opts into observability
// without the library choosing a logging or metrics stack for them.
// ---------------------------------------------------------------------------

/**
 * One audit record. [surface] + [clientId] are the SOURCE ATTRIBUTION the issue
 * asks for: after the fact, "an agent did this" is distinguishable from "a
 * person did this" without correlating logs by hand.
 */
public data class AuditRecord(
    public val operationId: String,
    public val kind: OperationKind,
    public val surface: Surface,
    public val subjectId: String?,
    public val clientId: String?,
    public val authProfileName: String?,
    public val correlationId: String,
    public val outcome: String,
    public val errorCode: String?,
    public val durationMillis: Long,
    public val replayed: Boolean,
    public val attributes: Map<String, String>,
)

/** Receives audit records. Default implementation does nothing. */
public interface AuditSink {
    public fun record(record: AuditRecord)

    public companion object {
        /** Discards records. */
        public val none: AuditSink = object : AuditSink {
            override fun record(record: AuditRecord) = Unit
        }
    }
}

/** Receives per-call metrics. Default implementation does nothing. */
public interface MetricsSink {
    /** One completed call. [outcome] is `success`, `replay` or an error code. */
    public fun observe(
        operationId: String,
        surface: Surface,
        outcome: String,
        durationMillis: Long,
    )

    public companion object {
        public val none: MetricsSink = object : MetricsSink {
            override fun observe(operationId: String, surface: Surface, outcome: String, durationMillis: Long) = Unit
        }
    }
}

/** Opens a span around a call. Default implementation does nothing. */
public interface Tracer {
    /** Runs [body] inside a span named after the operation. */
    public fun <T> span(operationId: String, surface: Surface, correlationId: String, body: () -> T): T

    public companion object {
        public val none: Tracer = object : Tracer {
            override fun <T> span(operationId: String, surface: Surface, correlationId: String, body: () -> T): T =
                body()
        }
    }
}

/**
 * Stores outcomes by idempotency key.
 *
 * Deliberately NOT a cache: the contract is "the same key returns the same
 * outcome", which is what makes a retrying agent safe. A real deployment backs
 * this with its database; [inMemory] exists for tests and single-node tools.
 */
public interface IdempotencyStore {
    /** The stored outcome for [key], or `null` when this key is new. */
    public fun lookup(key: String): Any?

    /** Stores [output] under [key]. */
    public fun store(key: String, output: Any?)

    public companion object {
        /** Disabled: nothing is ever replayed. */
        public val none: IdempotencyStore = object : IdempotencyStore {
            override fun lookup(key: String): Any? = null

            override fun store(key: String, output: Any?) = Unit
        }

        /** Unbounded in-memory store — tests and single-process tools only. */
        public fun inMemory(): IdempotencyStore = object : IdempotencyStore {
            private val entries = java.util.concurrent.ConcurrentHashMap<String, Any>()

            override fun lookup(key: String): Any? = entries[key]

            // A null output is simply not replayable here: storing a sentinel
            // would hand the adapter back something that is not the output type.
            // `Unit` is a real object, so Unit-returning writes do replay.
            override fun store(key: String, output: Any?) {
                if (output != null) entries[key] = output
            }
        }
    }
}

/**
 * The DOMAIN authorization hook.
 *
 * Operation-level scopes answer "may this caller call this operation at all".
 * Whether THIS caller may touch THAT plan is a domain question the library
 * cannot answer, so it is a declared seam rather than a guess (owner directive:
 * never infer client intent server-side).
 */
public fun interface DomainAuthorizer {
    /** Returns an error to reject the call, or `null` to allow it. */
    public fun authorize(caller: CallerContext, operation: Operation<*, *>, input: Any?): OperationError?

    public companion object {
        /** Allows everything — the handler owns its own domain checks. */
        public val allow: DomainAuthorizer = DomainAuthorizer { _, _, _ -> null }
    }
}

/** A clock seam, so duration assertions in tests are deterministic. */
public fun interface Clock {
    public fun millis(): Long

    public companion object {
        public val system: Clock = Clock { System.currentTimeMillis() }
    }
}

// ---------------------------------------------------------------------------
// The supplied stages.
// ---------------------------------------------------------------------------

/** The stages the default pipeline is built from. Each is reusable on its own. */
public object Stages {

    /** Stage names of [defaultPipeline], outermost first. */
    public val defaultOrder: List<String> = listOf(
        "audit", "authenticate", "decode", "validate", "authorizeOperation",
        "domainAuthorization", "idempotency", "handle",
    )

    /**
     * Resolves the credential to a [CallerContext] using the profile for the
     * exchange's surface. No profile, or no context, means
     * [OperationError.Unauthenticated] — never "anonymous with no scopes".
     */
    public fun authenticate(profiles: AuthProfiles): Stage = stage("authenticate") { exchange, next ->
        val profile = profiles.forSurface(exchange.surface)
        if (profile == null) {
            OperationOutcome.Failure(
                OperationError.Unauthenticated(
                    "no auth profile is configured for surface ${exchange.surface.token}",
                ),
            )
        } else {
            val caller = profile.authenticate(
                exchange.credential,
                AuthRequest(
                    surface = exchange.surface,
                    operationId = exchange.operation.id,
                    correlationId = exchange.correlationId,
                    attributes = exchange.attributes,
                ),
            )
            if (caller == null) {
                OperationOutcome.Failure(OperationError.Unauthenticated())
            } else {
                val resolved = caller.copy(attributes = caller.attributes + exchange.attributes)
                // Record attribution for the outer audit stage BEFORE running the
                // rest of the pipeline, so a failure downstream is still
                // attributable to whoever caused it.
                exchange.attribution.caller = resolved
                next(exchange.copy(caller = resolved))
            }
        }
    }

    /** Decodes the transport payload into the operation's input type. */
    public fun decode(): Stage = stage("decode") { exchange, next ->
        try {
            next(exchange.copy(input = exchange.operation.decodeErased(exchange.payload)))
        } catch (e: JsonDecodeException) {
            OperationOutcome.Failure(OperationError.Invalid(listOf(e.asValidationError())))
        } catch (e: IllegalStateException) {
            OperationOutcome.Failure(OperationError.Failed(e.message ?: "decoding failed", e))
        }
    }

    /** Runs the operation's validator. Identical on every surface, by construction. */
    public fun validate(): Stage = stage("validate") { exchange, next ->
        val errors = exchange.operation.validateErased(exchange.input)
        if (errors.isEmpty()) next(exchange) else OperationOutcome.Failure(OperationError.Invalid(errors))
    }

    /** Enforces the operation's declared scopes against the caller's. */
    public fun authorizeOperation(): Stage = stage("authorizeOperation") { exchange, next ->
        val caller = exchange.caller
            ?: return@stage OperationOutcome.Failure(
                OperationError.Failed("authorizeOperation ran before authenticate"),
            )
        val missing = caller.missing(exchange.operation.requiredScopes)
        if (missing.isEmpty()) {
            next(exchange)
        } else {
            OperationOutcome.Failure(
                OperationError.Forbidden(
                    "operation '${exchange.operation.id}' requires scope(s) ${missing.sorted()}",
                    missing,
                ),
            )
        }
    }

    /** Delegates the entity-level decision to the host's [DomainAuthorizer]. */
    public fun domainAuthorization(authorizer: DomainAuthorizer): Stage =
        stage("domainAuthorization") { exchange, next ->
            val caller = exchange.caller
                ?: return@stage OperationOutcome.Failure(
                    OperationError.Failed("domainAuthorization ran before authenticate"),
                )
            val rejection = authorizer.authorize(caller, exchange.operation, exchange.input)
            if (rejection == null) next(exchange) else OperationOutcome.Failure(rejection)
        }

    /**
     * Replays the stored outcome when the operation declares an idempotency key
     * and that key has been seen. Operations without a key extractor pass
     * straight through.
     */
    public fun idempotency(store: IdempotencyStore): Stage = stage("idempotency") { exchange, next ->
        val key = exchange.operation.idempotencyKeyErased(exchange.input)
        if (key.isNullOrBlank()) {
            next(exchange)
        } else {
            val scoped = "${exchange.operation.id}:$key"
            val replayed = store.lookup(scoped)
            if (replayed != null) {
                OperationOutcome.Success(replayed, replayed = true)
            } else {
                val outcome = next(exchange)
                if (outcome is OperationOutcome.Success) store.store(scoped, outcome.output)
                outcome
            }
        }
    }

    /**
     * Runs the handler. Terminal: it ignores `next`.
     *
     * [OperationException] maps to its declared error; anything else becomes
     * [OperationError.Failed] with the throwable kept for logs and OFF the wire.
     */
    public fun handle(): Stage = stage("handle") { exchange, _ ->
        val caller = exchange.caller
            ?: return@stage OperationOutcome.Failure(OperationError.Failed("handle ran before authenticate"))
        try {
            OperationOutcome.Success(exchange.operation.handleErased(caller, exchange.input))
        } catch (e: OperationException) {
            OperationOutcome.Failure(e.error)
        } catch (e: RuntimeException) {
            OperationOutcome.Failure(
                OperationError.Failed("operation '${exchange.operation.id}' failed", e),
            )
        }
    }

    /**
     * Outermost stage: times the call, opens a trace span, and emits the audit
     * and metric records for BOTH success and failure, attributed to the surface
     * and client. Outermost on purpose — an error raised by authentication is
     * exactly the kind that must not go unaudited.
     */
    public fun audit(
        audit: AuditSink = AuditSink.none,
        metrics: MetricsSink = MetricsSink.none,
        tracer: Tracer = Tracer.none,
        clock: Clock = Clock.system,
    ): Stage = stage("audit") { exchange, next ->
        val started = clock.millis()
        val outcome = tracer.span(exchange.operation.id, exchange.surface, exchange.correlationId) {
            next(exchange)
        }
        val elapsed = clock.millis() - started
        val replayed = (outcome as? OperationOutcome.Success)?.replayed ?: false
        val label = when {
            outcome is OperationOutcome.Failure -> outcome.error.code
            replayed -> "replay"
            else -> "success"
        }
        // The caller is unknown when authentication itself failed; the record is
        // still emitted — an unattributable attempt is the interesting one.
        val caller = exchange.caller ?: exchange.attribution.caller
        if (exchange.operation.audited) {
            audit.record(
                AuditRecord(
                    operationId = exchange.operation.id,
                    kind = exchange.operation.kind,
                    surface = exchange.surface,
                    subjectId = caller?.subjectId,
                    clientId = caller?.clientId,
                    authProfileName = caller?.authProfileName,
                    correlationId = exchange.correlationId,
                    outcome = if (outcome is OperationOutcome.Failure) "failure" else "success",
                    errorCode = outcome.errorOrNull()?.code,
                    durationMillis = elapsed,
                    replayed = replayed,
                    attributes = exchange.attributes,
                ),
            )
        }
        if (exchange.operation.metered) {
            metrics.observe(exchange.operation.id, exchange.surface, label, elapsed)
        }
        outcome
    }
}

/**
 * Builds the default pipeline in the documented order:
 * `audit -> authenticate -> decode -> validate -> authorizeOperation ->
 * domainAuthorization -> idempotency -> handle`.
 *
 * Encoding is NOT a stage: the typed output crosses the adapter boundary and
 * each adapter renders it into its own envelope (HTTP body vs MCP tool content)
 * using the operation's encoder.
 */
public fun defaultPipeline(
    profiles: AuthProfiles,
    domainAuthorizer: DomainAuthorizer = DomainAuthorizer.allow,
    idempotencyStore: IdempotencyStore = IdempotencyStore.none,
    audit: AuditSink = AuditSink.none,
    metrics: MetricsSink = MetricsSink.none,
    tracer: Tracer = Tracer.none,
    clock: Clock = Clock.system,
    /** Extra stages, appended just before `handle`. */
    extraStages: List<Stage> = emptyList(),
): OperationPipeline = OperationPipeline(
    buildList {
        add(Stages.audit(audit, metrics, tracer, clock))
        add(Stages.authenticate(profiles))
        add(Stages.decode())
        add(Stages.validate())
        add(Stages.authorizeOperation())
        add(Stages.domainAuthorization(domainAuthorizer))
        add(Stages.idempotency(idempotencyStore))
        addAll(extraStages)
        add(Stages.handle())
    },
)

/**
 * Runs one operation end to end. The single entry point every adapter — and an
 * INTERNAL caller — goes through, which is what keeps the surfaces honest.
 */
public fun OperationPipeline.call(
    operation: Operation<*, *>,
    surface: Surface,
    payload: Json,
    credential: Any? = null,
    correlationId: String = "",
    attributes: Map<String, String> = emptyMap(),
): OperationOutcome = execute(
    Exchange(
        operation = operation,
        surface = surface,
        credential = credential,
        payload = payload,
        correlationId = correlationId,
        attributes = attributes,
    ),
)
