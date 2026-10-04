package com.pkgrove.pkgrovekit.quarkus.it.operations

import com.pkgrove.pkgrovekit.operation.AuthProfiles
import com.pkgrove.pkgrovekit.operation.CallerContext
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationCategory
import com.pkgrove.pkgrovekit.operation.OperationModule
import com.pkgrove.pkgrovekit.operation.OperationPipeline
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.decimalStringOrNull
import com.pkgrove.pkgrovekit.operation.defaultPipeline
import com.pkgrove.pkgrovekit.operation.notFound
import com.pkgrove.pkgrovekit.operation.quarkus.IdentityClaims
import com.pkgrove.pkgrovekit.operation.quarkus.QuarkusAuthProfiles
import com.pkgrove.pkgrovekit.operation.rest.RestDispatcher
import com.pkgrove.pkgrovekit.operation.string
import com.pkgrove.pkgrovekit.operation.stringOrNull
import com.pkgrove.pkgrovekit.operation.mcp.McpDispatcher
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * The sample application for the HEL-602 real-framework proof.
 *
 * It declares one catalogue — a read, an idempotent write, a sensitive
 * `restOnly` and an agent-only `mcpOnly` — and wires REST and MCP to it through
 * DIFFERENT credentials that resolve to the same [CallerContext]. Everything
 * here runs inside a live Arc container in `integration-tests-quarkus`, which is
 * the point: the plain-JUnit module tests prove the logic, this proves the CDI
 * wiring and the startup gate.
 */

/** The write payload: a client-supplied `opId` makes a retry safe. */
public data class SetActual(
    public val planId: String,
    public val itemId: String,
    public val state: String,
    public val amount: String?,
    public val opId: String?,
)

public data class GetPlan(public val planId: String)

public data class PlanDetail(public val planId: String, public val title: String, public val viewer: String)

public data class MutationResponse(public val planId: String, public val revision: Int)

public data class RotateCreds(public val service: String)

/** In-memory domain, so the proof is about wiring and not about a database. */
@ApplicationScoped
public open class TravelDomain {
    private val titles = mutableMapOf("p-1" to "Kyoto trip")

    /** Every handler invocation, in order — the test's evidence. */
    public val invocations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())

    public var revision: Int = 0
        private set

    public open fun get(caller: CallerContext, request: GetPlan): PlanDetail {
        invocations += "get:${request.planId}:surface=${caller.surface.token}:subject=${caller.subjectId}"
        val title = titles[request.planId] ?: notFound("no plan '${request.planId}'")
        return PlanDetail(request.planId, title, caller.subjectId)
    }

    public open fun setActual(caller: CallerContext, request: SetActual): MutationResponse {
        invocations += "set:${request.planId}/${request.itemId}=${request.state}:surface=${caller.surface.token}"
        if (request.planId !in titles) notFound("no plan '${request.planId}'")
        revision++
        return MutationResponse(request.planId, revision)
    }

    public open fun rotate(caller: CallerContext, request: RotateCreds) {
        invocations += "ROTATE:${request.service}:surface=${caller.surface.token}"
    }

    public open fun explain(caller: CallerContext, request: GetPlan): PlanDetail =
        PlanDetail(request.planId, "an agent-only narrative", caller.subjectId).also {
            invocations += "explain:${request.planId}"
        }
}

/**
 * The application's operation catalogue, discovered as a CDI bean.
 *
 * One declaration per operation; the surfaces are declared, never implied.
 */
@ApplicationScoped
public open class TravelOperationModule(private val domain: TravelDomain) : OperationModule {

    override fun contribute(builder: com.pkgrove.pkgrovekit.operation.OperationsBuilder) {
        with(builder) {
            decoder<GetPlan> { GetPlan(it.string("planId")) }
            decoder<SetActual> {
                SetActual(
                    planId = it.string("planId"),
                    itemId = it.string("itemId"),
                    state = it.string("state"),
                    amount = it.decimalStringOrNull("amount"),
                    opId = it.stringOrNull("opId"),
                )
            }
            decoder<RotateCreds> { RotateCreds(it.string("service")) }
            encoder<PlanDetail> {
                Json.obj(
                    "planId" to Json.of(it.planId),
                    "title" to Json.of(it.title),
                    "viewer" to Json.of(it.viewer),
                )
            }
            encoder<MutationResponse> {
                Json.obj("planId" to Json.of(it.planId), "revision" to Json.of(it.revision))
            }

            read<GetPlan, PlanDetail>("plan.get") {
                both { rest GET "/v1/plans/{planId}"; mcp("get_plan", "Reads one travel plan") }
                requires("plans:read")
                input { field("planId", "string", required = true) }
                handle(domain::get)
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
                    field("state", "string", required = true, allowed = listOf("PLANNED", "DONE"))
                    field("amount", "number")
                    field("opId", "string")
                }
                validate {
                    field(SetActual::state) {
                        required()
                        oneOf("PLANNED", "DONE")
                    }
                    field(SetActual::amount) {
                        positive()
                        scale(2)
                    }
                }
                idempotent(SetActual::opId)
                handle(domain::setActual)
            }

            write<RotateCreds, Unit>("admin.credentials.rotate") {
                restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
                    rest POST "/v1/admin/credentials/rotate"
                }
                requires("admin")
                categories(OperationCategory.CREDENTIAL_ROTATION)
                handle(domain::rotate)
            }

            read<GetPlan, PlanDetail>("plan.explain") {
                mcpOnly(reason = "Agent-specific reasoning helper") { mcp("explain_plan") }
                requires("plans:read")
                handle(domain::explain)
            }
        }
    }
}

/** A REST credential: an opaque bearer token the host verified. */
public const val REST_BEARER: String = "Bearer rest-token-alice"

/** An MCP credential: a session id from the JSON-RPC transport — a DIFFERENT shape. */
public const val MCP_SESSION: String = "mcp-session-planner-agent"

/**
 * The deployment's per-surface auth profiles.
 *
 * REST reads an ambient framework identity; MCP reads a credential the host
 * extracted from its own transport. Both produce one [CallerContext], which is
 * why one handler can serve both without knowing which arrived.
 */
@ApplicationScoped
public open class TravelAuth {

    /** The ambient "framework-verified" identity, request-scoped in a real app. */
    public val restIdentity: ThreadLocal<String?> = ThreadLocal()

    @Produces
    @Singleton
    public open fun profiles(): AuthProfiles = AuthProfiles.of(
        Surface.REST to QuarkusAuthProfiles.ambient(
            name = "rest-bearer",
            credentialDescription = "Authorization: Bearer <token>",
        ) {
            if (restIdentity.get() != REST_BEARER) {
                null
            } else {
                object : IdentityClaims {
                    override val subject: String get() = "alice"
                    override val scopes: Set<String> get() = setOf("plans:read", "plans:write", "admin")
                    override val clientId: String get() = "ios-app"
                }
            }
        },
        Surface.MCP to QuarkusAuthProfiles.credential(
            name = "mcp-session",
            credentialDescription = "MCP session id",
        ) { session ->
            if (session != MCP_SESSION) {
                null
            } else {
                object : IdentityClaims {
                    // The SAME subject as the REST credential: the agent acts
                    // for alice, so the handler must see alice either way.
                    override val subject: String get() = "alice"
                    override val scopes: Set<String> get() = setOf("plans:read", "plans:write")
                    override val clientId: String get() = "planner-agent"
                }
            }
        },
        Surface.INTERNAL to QuarkusAuthProfiles.internalJob("reindexer", setOf("plans:write")),
    )
}

/** The single pipeline both adapters run. */
@ApplicationScoped
public open class TravelPipeline {

    @Produces
    @Singleton
    public open fun pipeline(profiles: AuthProfiles): OperationPipeline = defaultPipeline(
        profiles = profiles,
        idempotencyStore = com.pkgrove.pkgrovekit.operation.IdempotencyStore.inMemory(),
    )

    @Produces
    @Singleton
    public open fun rest(registry: OperationRegistry, pipeline: OperationPipeline): RestDispatcher =
        RestDispatcher(registry, pipeline)

    @Produces
    @Singleton
    public open fun mcp(registry: OperationRegistry, pipeline: OperationPipeline): McpDispatcher =
        McpDispatcher(registry, pipeline)
}
