package com.pkgrove.pkgrovekit.operation

/**
 * The sample catalogue every operation-core test shares (HEL-602).
 *
 * Deliberately the EXACT shape from the issue — a read, an idempotent write, a
 * sensitive `restOnly`, an agent-only `mcpOnly` and an `internalOnly` job — so
 * the tests prove the declaration the documentation shows, not a simplified
 * stand-in.
 */

internal data class GetPlan(val planId: String)

internal data class PlanDetail(val planId: String, val title: String)

internal data class SetActual(
    val planId: String,
    val itemId: String,
    val state: String,
    val amount: String?,
    val currency: String?,
    val opId: String?,
)

internal data class MutationResponse(val planId: String, val revision: Int)

internal data class RotateCreds(val service: String)

internal data class ExplainReq(val recommendationId: String)

internal data class Explanation(val text: String)

internal data class ReindexRequest(val since: String)

/** An in-memory domain the sample operations call. */
internal class SamplePlans {
    val plans = mutableMapOf("p-1" to "Kyoto trip")
    var revision = 0
    val rotations = mutableListOf<String>()
    val reindexed = mutableListOf<String>()
    var explanations = 0

    fun get(caller: CallerContext, request: GetPlan): PlanDetail {
        val title = plans[request.planId] ?: notFound("no plan '${request.planId}'")
        return PlanDetail(request.planId, "$title (for ${caller.subjectId})")
    }

    fun setActual(caller: CallerContext, request: SetActual): MutationResponse {
        if (request.planId !in plans) notFound("no plan '${request.planId}'")
        revision++
        return MutationResponse(request.planId, revision)
    }

    fun rotate(caller: CallerContext, request: RotateCreds) {
        rotations += request.service
    }

    fun explain(caller: CallerContext, request: ExplainReq): Explanation {
        explanations++
        return Explanation("because ${request.recommendationId} scored highest")
    }

    fun reindex(caller: CallerContext, request: ReindexRequest) {
        reindexed += request.since
    }
}

/**
 * The one-screen declaration under test. This is the DSL exactly as implemented;
 * `docs/operations.md` shows the same text.
 */
internal fun sampleRegistry(
    plans: SamplePlans = SamplePlans(),
    policy: SurfacePolicy = CategorySurfacePolicy.default,
): OperationRegistry = operations(policy) {
    decoder<GetPlan> { GetPlan(it.string("planId")) }
    decoder<SetActual> {
        SetActual(
            planId = it.string("planId"),
            itemId = it.string("itemId"),
            state = it.string("state"),
            amount = it.decimalStringOrNull("amount"),
            currency = it.stringOrNull("currency"),
            opId = it.stringOrNull("opId"),
        )
    }
    decoder<RotateCreds> { RotateCreds(it.string("service")) }
    decoder<ExplainReq> { ExplainReq(it.string("recommendationId")) }
    encoder<PlanDetail> { Json.obj("planId" to Json.of(it.planId), "title" to Json.of(it.title)) }
    encoder<MutationResponse> {
        Json.obj("planId" to Json.of(it.planId), "revision" to Json.of(it.revision))
    }
    encoder<Explanation> { Json.obj("text" to Json.of(it.text)) }

    read<GetPlan, PlanDetail>("plan.get") {
        both { rest GET "/v1/plans/{planId}"; mcp("get_plan", "Reads one travel plan") }
        requires("plans:read")
        describe("Reads one travel plan")
        tags("plans")
        input { field("planId", "string", required = true) }
        handle(plans::get)
    }

    write<SetActual, MutationResponse>("actual.set") {
        both {
            rest POST "/v1/plans/{planId}/items/{itemId}/actual"
            mcp("set_item_actual")
        }
        requires("plans:write")
        describe("Records what an item actually cost")
        input {
            field("planId", "string", required = true)
            field("itemId", "string", required = true)
            field("state", "string", required = true, allowed = listOf("PLANNED", "BOOKED", "DONE"))
            field("amount", "number")
            field("currency", "string")
            field("opId", "string")
        }
        validate {
            field(SetActual::state) {
                required()
                oneOf("PLANNED", "BOOKED", "DONE")
            }
            field(SetActual::amount) {
                positive()
                scale(2)
            }
            field(SetActual::currency) { isoCurrency() }
        }
        idempotent(SetActual::opId)
        handle(plans::setActual)
    }

    write<RotateCreds, Unit>("admin.credentials.rotate") {
        restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
            rest POST "/v1/admin/credentials/rotate"
        }
        requires("admin")
        categories(OperationCategory.CREDENTIAL_ROTATION)
        describe("Rotates a service credential")
        handle(plans::rotate)
    }

    read<ExplainReq, Explanation>("recommendation.explain") {
        mcpOnly(reason = "Agent-specific reasoning helper") { mcp("explain_recommendation") }
        describe("Explains why a recommendation was made")
        handle(plans::explain)
    }

    write<ReindexRequest, Unit>("plan.reindex") {
        internalOnly(reason = "batch job")
        handle(plans::reindex)
    }
}

/** A REST credential: a bearer token string. */
internal const val REST_TOKEN: String = "rest-token-alice"

/** An MCP credential: a different SHAPE entirely. */
internal data class AgentSession(val agent: String, val subject: String, val scopes: Set<String>)

/**
 * REST and MCP use different credentials and different profiles, and both
 * produce the same [CallerContext] shape — the property the issue asks to be
 * proven.
 */
internal fun sampleProfiles(
    restScopes: Set<String> = setOf("plans:read", "plans:write", "admin"),
    agentScopes: Set<String> = setOf("plans:read", "plans:write"),
): AuthProfiles = AuthProfiles.of(
    Surface.REST to authProfile<String>("rest-bearer", "Authorization: Bearer <token>") { token, _ ->
        if (token != REST_TOKEN) {
            null
        } else {
            CallerContext(
                subjectId = "alice",
                surface = Surface.REST,
                scopes = restScopes,
                clientId = "ios-app",
            )
        }
    },
    Surface.MCP to authProfile<AgentSession>("mcp-session", "MCP session token") { session, _ ->
        CallerContext(
            subjectId = session.subject,
            surface = Surface.MCP,
            scopes = session.scopes.ifEmpty { agentScopes },
            clientId = session.agent,
        )
    },
    Surface.INTERNAL to authProfile<String>("internal-job", "none") { job, _ ->
        CallerContext(subjectId = job, surface = Surface.INTERNAL, scopes = setOf("plans:write"))
    },
)

/** Records what the pipeline told the audit sink. */
internal class RecordingAudit : AuditSink {
    val records = mutableListOf<AuditRecord>()

    override fun record(record: AuditRecord) {
        records += record
    }
}

/** Records what the pipeline told the metrics sink. */
internal class RecordingMetrics : MetricsSink {
    val observations = mutableListOf<String>()

    override fun observe(operationId: String, surface: Surface, outcome: String, durationMillis: Long) {
        observations += "$operationId|${surface.token}|$outcome"
    }
}
