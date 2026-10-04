package com.pkgrove.pkgrovekit.operation.mcp

import com.pkgrove.pkgrovekit.operation.AuditRecord
import com.pkgrove.pkgrovekit.operation.AuditSink
import com.pkgrove.pkgrovekit.operation.IdempotencyStore
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationDeclarationException
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.defaultPipeline
import com.pkgrove.pkgrovekit.operation.nested
import com.pkgrove.pkgrovekit.operation.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The MCP tool binding (HEL-602). */
class McpDispatcherTest {

    private val domain = McpDomain()
    private val registry = mcpRegistry(domain)
    private val audit = object : AuditSink {
        val records = mutableListOf<AuditRecord>()

        override fun record(record: AuditRecord) {
            records += record
        }
    }

    private fun dispatcher(
        scopes: Set<String> = setOf("plans:read", "plans:write", "admin"),
        idempotency: IdempotencyStore = IdempotencyStore.none,
    ) = McpDispatcher(
        registry,
        defaultPipeline(mcpProfiles(scopes), idempotencyStore = idempotency, audit = audit),
    )

    // ---------------------------------------------------------------------
    // The security proof the issue asks for.
    // ---------------------------------------------------------------------

    @Test
    fun `a REST_ONLY sensitive operation is absent from the tool list`() {
        val names = dispatcher().toolsList().map { it.name }
        assertEquals(listOf("get_plan", "set_item_actual", "summarise_plan"), names)
        assertFalse(names.any { it.contains("rotate") }, names.toString())
        assertFalse(names.any { it.contains("credential") }, names.toString())
    }

    @Test
    fun `handles is false for a REST_ONLY operation under every plausible name`() {
        val run = dispatcher()
        listOf(
            "admin.credentials.rotate",
            "admin_credentials_rotate",
            "rotate_credentials",
            "rotate",
        ).forEach { name -> assertFalse(run.handles(name), name) }
    }

    @Test
    fun `calling a REST_ONLY operation returns not_found and never invokes the handler`() {
        val run = dispatcher()
        listOf(
            "admin.credentials.rotate",
            "admin_credentials_rotate",
            "rotate_credentials",
        ).forEach { name ->
            val result = run.call(name, """{"service":"minio"}""", agent)
            val error = result as McpCallResult.Error
            assertEquals("not_found", error.code, name)
            assertEquals(404, error.httpStatus)
        }
        assertTrue(domain.handlerCalls.isEmpty(), domain.handlerCalls.toString())
    }

    @Test
    fun `an internal-only operation is equally unreachable`() {
        val run = dispatcher()
        assertFalse(run.handles("plan.reindex"))
        assertFalse(run.handles("plan_reindex"))
        assertEquals("not_found", (run.call("plan_reindex", "{}", agent) as McpCallResult.Error).code)
        assertTrue(domain.handlerCalls.isEmpty())
    }

    @Test
    fun `the registry itself has no tool entry for a withheld operation`() {
        val tools = McpToolRegistry(registry)
        assertEquals(null, tools.find("rotate_credentials"))
        assertEquals(listOf("get_plan", "set_item_actual", "summarise_plan"), tools.toolNames())
    }

    // ---------------------------------------------------------------------
    // Ordinary behaviour.
    // ---------------------------------------------------------------------

    @Test
    fun `a tool descriptor carries the name, description and input schema`() {
        val descriptor = dispatcher().toolsList().first { it.name == "get_plan" }
        assertEquals("Reads one travel plan", descriptor.description)
        assertEquals("plan.get", descriptor.operationId)
        assertEquals(
            """{"type":"object","properties":{"planId":{"type":"string","description":"the plan id"}},""" +
                """"required":["planId"]}""",
            Json.write(descriptor.inputSchema),
        )
        assertEquals(
            """{"name":"get_plan","description":"Reads one travel plan",""" +
                """"inputSchema":{"type":"object","properties":""" +
                """{"planId":{"type":"string","description":"the plan id"}},"required":["planId"]}}""",
            Json.write(descriptor.toJson()),
        )
    }

    @Test
    fun `a write tool and an experimental tool say so in the description an agent reads`() {
        val tools = dispatcher().toolsList().associateBy { it.name }
        assertEquals("[write] Records what an item actually cost", tools.getValue("set_item_actual").description)
        assertEquals("[experimental] Summarises a plan in prose", tools.getValue("summarise_plan").description)
    }

    @Test
    fun `a tool with neither description falls back to the operation id`() {
        val bare = com.pkgrove.pkgrovekit.operation.operations(
            com.pkgrove.pkgrovekit.operation.PermissiveSurfacePolicy,
        ) {
            read<Unit, Unit>("plan.bare") {
                mcpOnly(reason = "t") { mcp("bare_tool") }
                handle { _, _ -> }
            }
        }
        assertEquals("plan.bare", McpToolRegistry(bare).tools().single().description)
    }

    @Test
    fun `the tools list renders as a JSON array`() {
        val array = dispatcher().toolsListJson()
        assertEquals(3, array.items.size)
        assertEquals("get_plan", (array.items.first() as Json.Obj).string("name"))
    }

    @Test
    fun `a tool call runs the same pipeline and renders the encoded output`() {
        val result = dispatcher().call("get_plan", """{"planId":"p-1"}""", agent) as McpCallResult.Ok
        assertEquals("""{"planId":"p-1","title":"Kyoto trip"}""", result.content)
        assertFalse(result.replayed)
        assertEquals(listOf("get:p-1"), domain.handlerCalls)
    }

    @Test
    fun `absent or blank arguments are treated as an empty object`() {
        listOf(null, "", "   ").forEach { arguments ->
            val result = dispatcher().call("get_plan", arguments, agent) as McpCallResult.Error
            // `planId` is required, so this is an invalid call — not a crash.
            assertEquals("invalid", result.code)
            assertEquals("planId", result.field)
        }
    }

    @Test
    fun `malformed or non-object arguments are an invalid error`() {
        listOf("""{"planId":""", """["p-1"]""", "42").forEach { arguments ->
            val result = dispatcher().call("get_plan", arguments, agent) as McpCallResult.Error
            assertEquals("invalid", result.code, arguments)
            assertEquals(400, result.httpStatus)
        }
        assertTrue(domain.handlerCalls.isEmpty())
    }

    @Test
    fun `validation produces the same semantic error an HTTP client would get`() {
        val result = dispatcher().call(
            "set_item_actual",
            """{"planId":"p-1","itemId":"i-2","state":"NOPE"}""",
            agent,
        ) as McpCallResult.Error
        assertEquals("invalid", result.code)
        assertEquals("state", result.field)
        assertEquals(400, result.httpStatus)
        val content = Json.parse(result.content) as Json.Obj
        assertEquals("invalid", content.string("error"))
        val detail = (content.fields["details"] as Json.Arr).items.single() as Json.Obj
        assertEquals("one_of", detail.string("code"))
        assertEquals("state", detail.string("field"))
        assertTrue(domain.handlerCalls.isEmpty())
    }

    @Test
    fun `an unauthenticated agent session is rejected before the handler`() {
        val result = dispatcher().call("get_plan", """{"planId":"p-1"}""", rawCredential = "a bearer string")
        val error = result as McpCallResult.Error
        assertEquals("unauthenticated", error.code)
        assertEquals(401, error.httpStatus)
        assertTrue(domain.handlerCalls.isEmpty())
    }

    @Test
    fun `an agent missing a scope is forbidden and the missing scope is in the detail`() {
        val weak = AgentSession("planner-agent", "alice", setOf("plans:read"))
        val result = dispatcher().call(
            "set_item_actual",
            """{"planId":"p-1","itemId":"i-2","state":"DONE"}""",
            weak,
        ) as McpCallResult.Error
        assertEquals("forbidden", result.code)
        assertEquals(403, result.httpStatus)
        assertTrue(result.content.contains("plans:write"))
        assertTrue(domain.handlerCalls.isEmpty())
    }

    @Test
    fun `a typed handler failure keeps its status`() {
        val result = dispatcher().call("get_plan", """{"planId":"absent"}""", agent) as McpCallResult.Error
        assertEquals("not_found", result.code)
        assertEquals(404, result.httpStatus)
        assertEquals(null, result.field)
    }

    @Test
    fun `a retrying agent replays instead of applying the write twice`() {
        val run = dispatcher(idempotency = IdempotencyStore.inMemory())
        val arguments = """{"planId":"p-1","itemId":"i-2","state":"DONE","opId":"op-1"}"""
        val first = run.call("set_item_actual", arguments, agent) as McpCallResult.Ok
        val second = run.call("set_item_actual", arguments, agent) as McpCallResult.Ok
        assertFalse(first.replayed)
        assertTrue(second.replayed)
        assertEquals(first.content, second.content)
        assertEquals(1, domain.revision)
    }

    @Test
    fun `the audit record attributes the call to the MCP surface and the agent`() {
        dispatcher().call("get_plan", """{"planId":"p-1"}""", agent, correlationId = "c-9")
        val record = audit.records.single()
        assertEquals(Surface.MCP, record.surface)
        assertEquals("alice", record.subjectId)
        assertEquals("planner-agent", record.clientId)
        assertEquals("mcp-session", record.authProfileName)
        assertEquals("c-9", record.correlationId)
    }

    @Test
    fun `request attributes reach the audit record`() {
        dispatcher().call(
            "get_plan",
            """{"planId":"p-1"}""",
            agent,
            attributes = mapOf("mcpSession" to "s-1"),
        )
        assertEquals(mapOf("mcpSession" to "s-1"), audit.records.single().attributes)
    }

    @Test
    fun `a legacy tool name collision fails fast, naming the tools`() {
        val run = dispatcher()
        val failure = assertThrows<OperationDeclarationException> {
            run.assertNoOverlap(listOf("legacy_search", "get_plan", "set_item_actual"))
        }
        assertTrue(failure.message!!.contains("get_plan"), failure.message)
        assertTrue(failure.message!!.contains("set_item_actual"), failure.message)
        assertTrue(failure.message!!.contains("two tools with one name"), failure.message)
    }

    @Test
    fun `a legacy tool list with no overlap is accepted`() {
        dispatcher().assertNoOverlap(listOf("legacy_search", "legacy_export"))
        McpToolRegistry(registry).assertNoOverlap(emptyList())
    }

    @Test
    fun `the nested accessor is reachable from the adapter module`() {
        assertEquals(
            "p-1",
            (Json.parse("""{"a":{"planId":"p-1"}}""") as Json.Obj).nested("a").string("planId"),
        )
    }
}
