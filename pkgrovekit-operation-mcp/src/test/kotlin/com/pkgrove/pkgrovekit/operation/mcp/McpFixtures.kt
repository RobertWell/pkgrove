package com.pkgrove.pkgrovekit.operation.mcp

import com.pkgrove.pkgrovekit.operation.AuthProfiles
import com.pkgrove.pkgrovekit.operation.CallerContext
import com.pkgrove.pkgrovekit.operation.CategorySurfacePolicy
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationCategory
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.OperationStatus
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.authProfile
import com.pkgrove.pkgrovekit.operation.decimalStringOrNull
import com.pkgrove.pkgrovekit.operation.notFound
import com.pkgrove.pkgrovekit.operation.operations
import com.pkgrove.pkgrovekit.operation.string
import com.pkgrove.pkgrovekit.operation.stringOrNull

internal data class GetPlan(val planId: String)

internal data class PlanDetail(val planId: String, val title: String)

internal data class SetActual(
    val planId: String,
    val itemId: String,
    val state: String,
    val amount: String?,
    val opId: String?,
)

internal data class MutationResponse(val planId: String, val revision: Int)

internal data class RotateCreds(val service: String)

/** An MCP credential: an agent session, NOT a bearer string. */
internal data class AgentSession(val agent: String, val subject: String, val scopes: Set<String>)

/** Records whether a handler was reached — the security proof needs "never". */
internal class McpDomain {
    val handlerCalls = mutableListOf<String>()
    var revision = 0
}

internal fun mcpRegistry(domain: McpDomain): OperationRegistry = operations(CategorySurfacePolicy.default) {
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
    encoder<PlanDetail> { Json.obj("planId" to Json.of(it.planId), "title" to Json.of(it.title)) }
    encoder<MutationResponse> {
        Json.obj("planId" to Json.of(it.planId), "revision" to Json.of(it.revision))
    }

    read<GetPlan, PlanDetail>("plan.get") {
        both { rest GET "/v1/plans/{planId}"; mcp("get_plan", "Reads one travel plan") }
        requires("plans:read")
        input { field("planId", "string", required = true, description = "the plan id") }
        handle { _, input ->
            domain.handlerCalls += "get:${input.planId}"
            if (input.planId == "absent") notFound("no plan '${input.planId}'")
            PlanDetail(input.planId, "Kyoto trip")
        }
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
            field("state", "string", required = true)
            field("amount", "number")
            field("opId", "string")
        }
        validate {
            field(SetActual::state) {
                required()
                oneOf("PLANNED", "DONE")
            }
            field(SetActual::amount) { scale(2) }
        }
        idempotent(SetActual::opId)
        handle { _, input ->
            domain.handlerCalls += "set:${input.planId}/${input.itemId}=${input.state}"
            domain.revision++
            MutationResponse(input.planId, domain.revision)
        }
    }

    // The security-proof subject: sensitive, REST only, never an agent tool.
    write<RotateCreds, Unit>("admin.credentials.rotate") {
        restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
            rest POST "/v1/admin/credentials/rotate"
        }
        requires("admin")
        categories(OperationCategory.CREDENTIAL_ROTATION)
        handle { _, input ->
            domain.handlerCalls += "ROTATE:${input.service}"
        }
    }

    read<GetPlan, PlanDetail>("plan.summary") {
        mcpOnly(reason = "agent-only narrative summary") { mcp("summarise_plan", "Summarises a plan in prose") }
        requires("plans:read")
        status(OperationStatus.EXPERIMENTAL)
        handle { _, input -> PlanDetail(input.planId, "summary") }
    }

    write<GetPlan, Unit>("plan.reindex") {
        internalOnly(reason = "batch job")
        handle { _, input -> domain.handlerCalls += "REINDEX:${input.planId}" }
    }
}

internal fun mcpProfiles(
    scopes: Set<String> = setOf("plans:read", "plans:write", "admin"),
): AuthProfiles = AuthProfiles.of(
    Surface.MCP to authProfile<AgentSession>("mcp-session", "MCP session token") { session, _ ->
        CallerContext(
            subjectId = session.subject,
            surface = Surface.MCP,
            scopes = if (session.scopes.isEmpty()) scopes else session.scopes,
            clientId = session.agent,
        )
    },
)

internal val agent: AgentSession = AgentSession("planner-agent", "alice", setOf("plans:read", "plans:write", "admin"))
