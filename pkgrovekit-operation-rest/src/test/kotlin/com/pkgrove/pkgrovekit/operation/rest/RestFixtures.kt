package com.pkgrove.pkgrovekit.operation.rest

import com.pkgrove.pkgrovekit.operation.AuthProfiles
import com.pkgrove.pkgrovekit.operation.CallerContext
import com.pkgrove.pkgrovekit.operation.CategorySurfacePolicy
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationCategory
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.authProfile
import com.pkgrove.pkgrovekit.operation.conflict
import com.pkgrove.pkgrovekit.operation.decimalStringOrNull
import com.pkgrove.pkgrovekit.operation.notFound
import com.pkgrove.pkgrovekit.operation.operations
import com.pkgrove.pkgrovekit.operation.string
import com.pkgrove.pkgrovekit.operation.stringOrNull

internal data class GetPlan(val planId: String, val expand: Boolean)

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

internal const val REST_TOKEN: String = "rest-token-alice"

/** Records what the handlers were actually asked to do. */
internal class RestDomain {
    val calls = mutableListOf<String>()
    var revision = 0
}

internal fun restRegistry(domain: RestDomain): OperationRegistry = operations(CategorySurfacePolicy.default) {
    decoder<GetPlan> { GetPlan(it.string("planId"), it.stringOrNull("expand") == "true") }
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
        both { rest GET "/v1/plans/{planId}"; mcp("get_plan") }
        requires("plans:read")
        input { field("planId", "string", required = true); field("expand", "string") }
        handle { caller, input ->
            domain.calls += "get:${input.planId}:expand=${input.expand}:by=${caller.subjectId}"
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
            domain.calls += "set:${input.planId}/${input.itemId}=${input.state}"
            if (input.state == "DONE" && input.planId == "locked") conflict("plan is locked")
            domain.revision++
            MutationResponse(input.planId, domain.revision)
        }
    }

    write<SetActual, MutationResponse>("actual.replace") {
        restOnly(reason = "bulk replacement is not an agent-safe operation") {
            rest PUT "/v1/plans/{planId}/items/{itemId}/actual"
        }
        requires("plans:write")
        handle { _, input ->
            domain.revision++
            MutationResponse(input.planId, domain.revision)
        }
    }

    write<RotateCreds, Unit>("admin.credentials.rotate") {
        restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
            rest POST "/v1/admin/credentials/rotate"
        }
        requires("admin")
        categories(OperationCategory.CREDENTIAL_ROTATION)
        handle { _, input -> domain.calls += "rotate:${input.service}" }
    }

    read<GetPlan, PlanDetail>("plan.summary") {
        mcpOnly(reason = "agent-only narrative summary") { mcp("summarise_plan") }
        requires("plans:read")
        handle { _, input -> PlanDetail(input.planId, "summary") }
    }
}

internal fun restProfiles(scopes: Set<String> = setOf("plans:read", "plans:write", "admin")): AuthProfiles =
    AuthProfiles.of(
        Surface.REST to authProfile<String>("rest-bearer", "Authorization: Bearer <token>") { token, _ ->
            if (token != REST_TOKEN) {
                null
            } else {
                CallerContext("alice", Surface.REST, scopes = scopes, clientId = "ios-app")
            }
        },
    )
