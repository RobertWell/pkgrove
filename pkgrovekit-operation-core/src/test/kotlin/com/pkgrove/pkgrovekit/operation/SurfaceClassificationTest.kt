package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The FAIL-CLOSED half of HEL-602: every way of getting the surface declaration
 * wrong must stop the registry from existing, and must say which operation did
 * it.
 */
class SurfaceClassificationTest {

    @Test
    fun `an undeclared surface is refused and there is no default`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations {
                read<GetPlan, PlanDetail>("plan.get") {
                    requires("plans:read")
                    handle { _, input -> PlanDetail(input.planId, "t") }
                }
            }
        }
        assertTrue(failure.message!!.contains("plan.get"), failure.message)
        assertTrue(failure.message!!.contains("declares NO surface"), failure.message)
        assertTrue(failure.message!!.contains("there is no default"), failure.message)
    }

    @Test
    fun `declaring two surfaces is refused`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") {
                    restOnly(reason = "first") { rest GET "/v1/plans" }
                    internalOnly(reason = "second")
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("plan.get"), failure.message)
        assertTrue(failure.message!!.contains("declares its surface 2 times"), failure.message)
    }

    @Test
    fun `a blank restriction reason is refused for every restricted form`() {
        listOf<OperationBuilder<Unit, Unit>.() -> Unit>(
            { restOnly(reason = "   ") { rest GET "/v1/x" } },
            { mcpOnly(reason = "") { mcp("x_tool") } },
            { internalOnly(reason = " ") },
        ).forEach { declare ->
            val failure = assertThrows<IllegalArgumentException> {
                operations(PermissiveSurfacePolicy) {
                    read<Unit, Unit>("thing.x") {
                        declare()
                        handle { _, _ -> }
                    }
                }
            }
            assertTrue(
                failure.message!!.contains("NON-BLANK reason"),
                failure.message,
            )
        }
    }

    @Test
    fun `a duplicate operation id is refused, naming the id`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") {
                    internalOnly(reason = "a")
                    handle { _, _ -> }
                }
                read<Unit, Unit>("plan.get") {
                    internalOnly(reason = "b")
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("DUPLICATE OPERATION ID 'plan.get'"), failure.message)
    }

    @Test
    fun `a duplicate REST binding is refused, naming both operations`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") {
                    restOnly(reason = "a") { rest GET "/v1/plans/{planId}" }
                    handle { _, _ -> }
                }
                read<Unit, Unit>("plan.fetch") {
                    restOnly(reason = "b") { rest GET "/v1/plans/{planId}" }
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("DUPLICATE REST BINDING 'GET /v1/plans/{planId}'"), failure.message)
        assertTrue(failure.message!!.contains("plan.get"), failure.message)
        assertTrue(failure.message!!.contains("plan.fetch"), failure.message)
    }

    @Test
    fun `the same path with a different method is not a duplicate`() {
        val registry = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("plan.get") {
                restOnly(reason = "a") { rest GET "/v1/plans" }
                handle { _, _ -> }
            }
            write<Unit, Unit>("plan.create") {
                restOnly(reason = "b") { rest POST "/v1/plans" }
                handle { _, _ -> }
            }
        }
        assertEquals(2, registry.restBindings().size)
    }

    @Test
    fun `a duplicate MCP tool name is refused, naming both operations`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") {
                    mcpOnly(reason = "a") { mcp("get_plan") }
                    handle { _, _ -> }
                }
                read<Unit, Unit>("plan.fetch") {
                    mcpOnly(reason = "b") { mcp("get_plan") }
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("DUPLICATE MCP TOOL 'get_plan'"), failure.message)
        assertTrue(failure.message!!.contains("plan.fetch"), failure.message)
    }

    @Test
    fun `the policy refuses a sensitive category on MCP unless it is overridden`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations {
                write<RotateCreds, Unit>("admin.credentials.rotate") {
                    both { rest POST "/v1/admin/credentials/rotate"; mcp("rotate_credentials") }
                    categories(OperationCategory.CREDENTIAL_ROTATION)
                    decode { RotateCreds(it.string("service")) }
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("POLICY REFUSAL 'admin.credentials.rotate'"), failure.message)
        assertTrue(failure.message!!.contains("credential_rotation"), failure.message)
        assertTrue(failure.message!!.contains("mcpOverride"), failure.message)
    }

    @Test
    fun `an explicit reasoned override admits a refused category to MCP`() {
        val registry = operations {
            write<RotateCreds, Unit>("admin.flag.toggle") {
                both { rest POST "/v1/admin/flags"; mcp("toggle_flag") }
                categories(OperationCategory.PRIVILEGED_ADMIN)
                mcpOverride(reason = "feature flags are tenant-scoped and reversible; reviewed 2026-10")
                decode { RotateCreds(it.string("service")) }
                handle { _, _ -> }
            }
        }
        assertEquals(listOf("toggle_flag"), registry.mcpToolNames())
        assertEquals(
            "feature flags are tenant-scoped and reversible; reviewed 2026-10",
            registry.byId("admin.flag.toggle")!!.mcpPolicyOverrideReason,
        )
    }

    @Test
    fun `a blank override reason is refused`() {
        assertThrows<IllegalArgumentException> {
            operations {
                write<Unit, Unit>("admin.thing") {
                    mcpOnly(reason = "test") { mcp("thing") }
                    categories(OperationCategory.PRIVILEGED_ADMIN)
                    mcpOverride(reason = " ")
                    handle { _, _ -> }
                }
            }
        }
    }

    @Test
    fun `a refused category on a non-MCP surface is not a violation`() {
        val registry = sampleRegistry()
        assertEquals(
            setOf(OperationCategory.CREDENTIAL_ROTATION),
            registry.byId("admin.credentials.rotate")!!.categories,
        )
    }

    @Test
    fun `a transport-exposed operation without a codec is refused`() {
        val missingDecoder = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<GetPlan, PlanDetail>("plan.get") {
                    both { rest GET "/v1/plans/{planId}"; mcp("get_plan") }
                    encode { Json.obj("planId" to Json.of(it.planId)) }
                    handle { _, input -> PlanDetail(input.planId, "t") }
                }
            }
        }
        assertTrue(missingDecoder.message!!.contains("no decoder for GetPlan"), missingDecoder.message)

        val missingEncoder = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<GetPlan, PlanDetail>("plan.get") {
                    both { rest GET "/v1/plans/{planId}"; mcp("get_plan") }
                    decode { GetPlan(it.string("planId")) }
                    handle { _, input -> PlanDetail(input.planId, "t") }
                }
            }
        }
        assertTrue(missingEncoder.message!!.contains("no encoder for PlanDetail"), missingEncoder.message)
    }

    @Test
    fun `an internal-only operation needs no codec because no transport reaches it`() {
        val registry = operations(PermissiveSurfacePolicy) {
            write<ReindexRequest, Unit>("plan.reindex") {
                internalOnly(reason = "batch job")
                handle { _, _ -> }
            }
        }
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `a missing handler is refused`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") { internalOnly(reason = "test") }
            }
        }
        assertTrue(failure.message!!.contains("declares no handle"), failure.message)
    }

    @Test
    fun `an invalid operation id is refused`() {
        listOf("Plan.Get", "plan..get", "plan.get.", "1plan", "plan-get").forEach { bad ->
            assertThrows<OperationDeclarationException>(bad) {
                operations(PermissiveSurfacePolicy) {
                    read<Unit, Unit>(bad) {
                        internalOnly(reason = "test")
                        handle { _, _ -> }
                    }
                }
            }
        }
        assertThrows<IllegalArgumentException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>(" ") {
                    internalOnly(reason = "test")
                    handle { _, _ -> }
                }
            }
        }
    }

    @Test
    fun `an invalid MCP tool name is refused`() {
        listOf("GetPlan", "get-plan", "9tool", "get plan", "").forEach { bad ->
            assertThrows<IllegalArgumentException>(bad) {
                operations(PermissiveSurfacePolicy) {
                    read<Unit, Unit>("plan.get") {
                        mcpOnly(reason = "test") { mcp(bad) }
                        handle { _, _ -> }
                    }
                }
            }
        }
    }

    @Test
    fun `an invalid REST path is refused`() {
        listOf("v1/plans", "/v1/plans/").forEach { bad ->
            assertThrows<IllegalArgumentException>(bad) {
                operations(PermissiveSurfacePolicy) {
                    read<Unit, Unit>("plan.get") {
                        restOnly(reason = "test") { rest(HttpMethod.GET, bad) }
                        handle { _, _ -> }
                    }
                }
            }
        }
    }

    @Test
    fun `a surface block refuses two bindings of the same kind`() {
        assertThrows<IllegalStateException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") {
                    restOnly(reason = "test") {
                        rest GET "/v1/plans"
                        rest GET "/v1/plans/all"
                    }
                    handle { _, _ -> }
                }
            }
        }
        assertThrows<IllegalStateException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("plan.get") {
                    mcpOnly(reason = "test") {
                        mcp("a_tool")
                        mcp("b_tool")
                    }
                    handle { _, _ -> }
                }
            }
        }
    }

    @Test
    fun `a surface block missing its required binding is refused`() {
        val cases = listOf<OperationBuilder<Unit, Unit>.() -> Unit>(
            { both { rest GET "/v1/x" } },
            { both { mcp("x_tool") } },
            { restOnly(reason = "r") { mcp("x_tool") } },
            { mcpOnly(reason = "r") { rest GET "/v1/x" } },
        )
        cases.forEach { declare ->
            assertThrows<OperationDeclarationException> {
                operations(PermissiveSurfacePolicy) {
                    read<Unit, Unit>("thing.x") {
                        declare()
                        handle { _, _ -> }
                    }
                }
            }
        }
    }

    @Test
    fun `a REST path parameter absent from the declared input schema is refused`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<GetPlan, PlanDetail>("plan.get") {
                    restOnly(reason = "test") { rest GET "/v1/plans/{planId}/items/{itemId}" }
                    input { field("planId", "string", required = true) }
                    decode { GetPlan(it.string("planId")) }
                    encode { Json.obj("planId" to Json.of(it.planId)) }
                    handle { _, input -> PlanDetail(input.planId, "t") }
                }
            }
        }
        assertTrue(failure.message!!.contains("REST PATH PARAM 'plan.get'"), failure.message)
        assertTrue(failure.message!!.contains("itemId"), failure.message)
    }

    @Test
    fun `validation rules that name a field the schema lacks are refused as drift`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                write<SetActual, Unit>("actual.set") {
                    internalOnly(reason = "test")
                    input { field("planId", "string", required = true) }
                    validate { field(SetActual::state) { required() } }
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("have drifted"), failure.message)
        assertTrue(failure.message!!.contains("state"), failure.message)
    }

    @Test
    fun `the registry reports every violation at once, not just the first`() {
        val failure = assertThrows<OperationDeclarationException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("a.one") {
                    restOnly(reason = "r") { rest GET "/v1/x" }
                    handle { _, _ -> }
                }
                read<Unit, Unit>("a.one") {
                    restOnly(reason = "r") { rest GET "/v1/x" }
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("2 problem(s)"), failure.message)
    }

    @Test
    fun `restOnly exclusions are enumerable with their reasons`() {
        val exclusions = sampleRegistry().restOnlyExclusions()
        assertEquals(1, exclusions.size)
        assertEquals("admin.credentials.rotate", exclusions.single().operationId)
        assertEquals("rest_only", exclusions.single().classification)
        assertEquals(
            "Sensitive admin operation must not be callable by AI/MCP clients",
            exclusions.single().reason,
        )
    }

    @Test
    fun `all exclusions are enumerable for a security review`() {
        val exclusions = sampleRegistry().exclusions().associate { it.operationId to it.classification }
        assertEquals(
            mapOf(
                "admin.credentials.rotate" to "rest_only",
                "recommendation.explain" to "mcp_only",
                "plan.reindex" to "internal_only",
            ),
            exclusions,
        )
    }

    @Test
    fun `the registry is immutable after construction`() {
        val registry = sampleRegistry()
        val all = registry.all()
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (all as MutableList<Operation<*, *>>).removeAt(0)
        }
        assertEquals(5, registry.all().size)
        assertTrue(registry.toString().contains("5 operations"))
    }

    @Test
    fun `lookups resolve by id, REST binding and MCP tool, and miss cleanly`() {
        val registry = sampleRegistry()
        assertEquals("plan.get", registry.byRest(HttpMethod.GET, "/v1/plans/{planId}")!!.id)
        assertEquals("plan.get", registry.byMcpTool("get_plan")!!.id)
        assertEquals(null, registry.byId("nope"))
        assertEquals(null, registry.byRest(HttpMethod.DELETE, "/v1/plans/{planId}"))
        assertEquals(null, registry.byMcpTool("rotate_credentials"))
    }

    @Test
    fun `exposedOn lists only what each surface can reach`() {
        val registry = sampleRegistry()
        assertEquals(
            listOf("plan.get", "actual.set", "admin.credentials.rotate"),
            registry.exposedOn(Surface.REST).map { it.id },
        )
        assertEquals(
            listOf("plan.get", "actual.set", "recommendation.explain"),
            registry.exposedOn(Surface.MCP).map { it.id },
        )
        assertEquals(listOf("plan.reindex"), registry.exposedOn(Surface.INTERNAL).map { it.id })
    }

    @Test
    fun `a configured category policy refuses exactly what it was configured with`() {
        val policy = CategorySurfacePolicy(setOf(OperationCategory.BULK_EXPORT), "export-guard")
        val failure = assertThrows<OperationDeclarationException> {
            operations(policy) {
                read<Unit, Unit>("report.export") {
                    mcpOnly(reason = "agent helper") { mcp("export_report") }
                    categories(OperationCategory.BULK_EXPORT)
                    handle { _, _ -> }
                }
            }
        }
        assertTrue(failure.message!!.contains("export-guard"), failure.message)

        // CREDENTIAL_ROTATION is not in THIS policy's refusal set.
        val allowed = operations(policy) {
            read<Unit, Unit>("cred.peek") {
                mcpOnly(reason = "agent helper") { mcp("peek_credential") }
                categories(OperationCategory.CREDENTIAL_ROTATION)
                handle { _, _ -> }
            }
        }
        assertEquals(listOf("peek_credential"), allowed.mcpToolNames())
    }

    @Test
    fun `surface and policy tokens are stable wire values`() {
        assertEquals(listOf("rest", "mcp", "internal"), Surface.entries.map { it.token })
        assertEquals("default", CategorySurfacePolicy.default.name)
        assertEquals("permissive", PermissiveSurfacePolicy.name)
        assertEquals(5, CategorySurfacePolicy.defaultRefusals.size)
    }
}
