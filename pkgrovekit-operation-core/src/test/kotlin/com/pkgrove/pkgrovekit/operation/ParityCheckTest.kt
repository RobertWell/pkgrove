package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The coexistence check (HEL-602): a declarative registry living beside a
 * host's hand-written MCP tool list and REST resources is the normal migration
 * state, and the only state in which a withheld operation can quietly reappear.
 */
class ParityCheckTest {

    private val registry = sampleRegistry()
    private val liveTools = listOf("get_plan", "set_item_actual", "explain_recommendation")

    @Test
    fun `a consistent deployment reports nothing`() {
        assertEquals(emptyList<ParityViolation>(), ParityCheck.run(registry, liveMcpToolNames = liveTools))
        ParityCheck.assertConsistent(registry, liveMcpToolNames = liveTools)
    }

    @Test
    fun `an operation the host expects but never declared is reported`() {
        val violations = ParityCheck.run(registry, knownOperationIds = listOf("plan.get", "plan.archive"))
        assertEquals(1, violations.size)
        assertEquals(ParityViolationKind.UNREGISTERED_OPERATION, violations.single().kind)
        assertEquals("plan.archive", violations.single().subject)
    }

    @Test
    fun `a tool name served by both registries is reported`() {
        val violations = ParityCheck.run(
            registry,
            legacyMcpToolNames = listOf("get_plan", "legacy_search"),
        )
        assertEquals(1, violations.size)
        assertEquals(ParityViolationKind.DUPLICATE_MCP_TOOL, violations.single().kind)
        assertEquals("get_plan", violations.single().subject)
        assertTrue(violations.single().detail.contains("two tools with one name"))
    }

    @Test
    fun `a route served by both the registry and a legacy resource is reported`() {
        val violations = ParityCheck.run(
            registry,
            legacyRestPaths = listOf("  GET /v1/plans/{planId}  ", "GET /v1/legacy"),
        )
        assertEquals(1, violations.size)
        assertEquals(ParityViolationKind.DUPLICATE_REST_PATH, violations.single().kind)
        assertEquals("GET /v1/plans/{planId}", violations.single().subject)
    }

    @Test
    fun `a withheld operation appearing in the live MCP listing is reported`() {
        // All three plausible shapes a hand-written tool for a withheld
        // operation would carry: the id, the id with underscores, the path tail.
        listOf("admin.credentials.rotate", "admin_credentials_rotate", "rotate").forEach { leak ->
            val violations = ParityCheck.run(registry, liveMcpToolNames = liveTools + leak)
            val restricted = violations.filter {
                it.kind == ParityViolationKind.RESTRICTED_OPERATION_IN_MCP_LISTING
            }
            assertEquals(1, restricted.size, leak)
            assertEquals("admin.credentials.rotate", restricted.single().subject)
            assertTrue(
                restricted.single().detail.contains("Sensitive admin operation"),
                restricted.single().detail,
            )
        }
    }

    @Test
    fun `an internal-only operation leaking into MCP is reported too`() {
        val violations = ParityCheck.run(registry, liveMcpToolNames = liveTools + "plan_reindex")
        assertEquals(
            listOf("plan.reindex"),
            violations.filter { it.kind == ParityViolationKind.RESTRICTED_OPERATION_IN_MCP_LISTING }
                .map { it.subject },
        )
    }

    @Test
    fun `a declared tool missing from the live listing is reported`() {
        val violations = ParityCheck.run(registry, liveMcpToolNames = listOf("get_plan"))
        assertEquals(
            setOf("set_item_actual", "explain_recommendation"),
            violations.filter { it.kind == ParityViolationKind.MISSING_FROM_MCP_LISTING }.map { it.subject }.toSet(),
        )
    }

    @Test
    fun `the live-listing checks are skipped when no listing is supplied`() {
        assertEquals(emptyList<ParityViolation>(), ParityCheck.run(registry))
    }

    @Test
    fun `a tightened policy is reported without shipping a build that refuses to start`() {
        // plan.get and actual.set are MCP-exposed and untagged today. Asking
        // "what if we refuse DESTRUCTIVE_DELETE?" changes nothing...
        assertEquals(
            emptyList<ParityViolation>(),
            ParityCheck.run(
                registry,
                policy = CategorySurfacePolicy(setOf(OperationCategory.DESTRUCTIVE_DELETE), "tight"),
            ),
        )

        // ...but a policy that refuses an MCP-exposed operation's category does,
        // and it says so BEFORE the stricter policy is wired in (where the
        // registry would simply refuse to build).
        val tagged = operations(PermissiveSurfacePolicy) {
            read<ExplainReq, Explanation>("recommendation.explain") {
                mcpOnly(reason = "Agent-specific reasoning helper") { mcp("explain_recommendation") }
                categories(OperationCategory.BULK_EXPORT)
                decode { ExplainReq(it.string("recommendationId")) }
                encode { Json.obj("text" to Json.of(it.text)) }
                handle { _, _ -> Explanation("because") }
            }
        }
        val violations = ParityCheck.run(
            tagged,
            policy = CategorySurfacePolicy(setOf(OperationCategory.BULK_EXPORT), "export-guard"),
        )
        assertEquals(1, violations.size)
        assertEquals(ParityViolationKind.POLICY_REFUSED_MCP_EXPOSURE, violations.single().kind)
        assertEquals("recommendation.explain", violations.single().subject)
        assertTrue(violations.single().detail.contains("export-guard"), violations.single().detail)

        // An explicit override silences it, as it does at construction time.
        val overridden = operations(PermissiveSurfacePolicy) {
            read<ExplainReq, Explanation>("recommendation.explain") {
                mcpOnly(reason = "Agent-specific reasoning helper") { mcp("explain_recommendation") }
                categories(OperationCategory.BULK_EXPORT)
                mcpOverride(reason = "returns one sentence, never a dataset")
                decode { ExplainReq(it.string("recommendationId")) }
                encode { Json.obj("text" to Json.of(it.text)) }
                handle { _, _ -> Explanation("because") }
            }
        }
        assertEquals(
            emptyList<ParityViolation>(),
            ParityCheck.run(
                overridden,
                policy = CategorySurfacePolicy(setOf(OperationCategory.BULK_EXPORT), "export-guard"),
            ),
        )
    }

    @Test
    fun `assertConsistent throws with every violation listed`() {
        val failure = assertThrows<OperationDeclarationException> {
            ParityCheck.assertConsistent(
                registry,
                liveMcpToolNames = liveTools + "admin_credentials_rotate",
                legacyMcpToolNames = listOf("get_plan"),
                knownOperationIds = listOf("plan.archive"),
            )
        }
        assertTrue(failure.message!!.contains("3 violation(s)"), failure.message)
        assertTrue(failure.message!!.contains("UNREGISTERED_OPERATION"), failure.message)
        assertTrue(failure.message!!.contains("DUPLICATE_MCP_TOOL"), failure.message)
        assertTrue(failure.message!!.contains("RESTRICTED_OPERATION_IN_MCP_LISTING"), failure.message)
    }

    @Test
    fun `a violation renders readably`() {
        val violation = ParityViolation(ParityViolationKind.DUPLICATE_MCP_TOOL, "get_plan", "detail text")
        assertEquals("DUPLICATE_MCP_TOOL 'get_plan': detail text", violation.toString())
    }
}
