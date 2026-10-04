package com.pkgrove.pkgrovekit.quarkus.it

import com.pkgrove.pkgrovekit.operation.HttpMethod
import com.pkgrove.pkgrovekit.operation.OperationCatalog
import com.pkgrove.pkgrovekit.operation.OperationPipeline
import com.pkgrove.pkgrovekit.operation.call
import com.pkgrove.pkgrovekit.operation.array
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.ParityCheck
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.mcp.McpCallResult
import com.pkgrove.pkgrovekit.operation.mcp.McpDispatcher
import com.pkgrove.pkgrovekit.operation.nested
import com.pkgrove.pkgrovekit.operation.quarkus.OperationRegistryProducer
import com.pkgrove.pkgrovekit.operation.rest.RestDispatcher
import com.pkgrove.pkgrovekit.operation.rest.RestRequest
import com.pkgrove.pkgrovekit.operation.string
import com.pkgrove.pkgrovekit.quarkus.it.operations.MCP_SESSION
import com.pkgrove.pkgrovekit.quarkus.it.operations.REST_BEARER
import com.pkgrove.pkgrovekit.quarkus.it.operations.TravelAuth
import com.pkgrove.pkgrovekit.quarkus.it.operations.TravelDomain
import com.pkgrove.pkgrovekit.operation.OperationDeclarationException
import com.pkgrove.pkgrovekit.operation.OperationModule
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HEL-602 real-framework proof: the declarative operation catalogue wired
 * through a LIVE Arc container.
 *
 * The plain-JUnit module suites prove the logic; this proves the parts only a
 * container can: the registry is produced from CDI-discovered [OperationModule]
 * beans, the startup classification check actually runs, and REST and MCP —
 * with DIFFERENT credentials — route to one handler.
 */
@QuarkusTest
class OperationSurfacesTest {

    @Inject
    lateinit var registry: OperationRegistry

    @Inject
    lateinit var rest: RestDispatcher

    @Inject
    lateinit var mcp: McpDispatcher

    @Inject
    lateinit var domain: TravelDomain

    @Inject
    lateinit var auth: TravelAuth

    @Inject
    lateinit var pipeline: OperationPipeline

    @Inject
    lateinit var producer: OperationRegistryProducer

    @Inject
    lateinit var modules: Instance<OperationModule>

    @BeforeEach
    fun authenticateRest() {
        domain.invocations.clear()
        auth.restIdentity.set(REST_BEARER)
    }

    @AfterEach
    fun clearIdentity() {
        auth.restIdentity.remove()
    }

    // --- CDI wiring -----------------------------------------------------

    @Test
    fun `the registry is produced from the CDI-discovered operation module`() {
        assertNotNull(registry)
        assertEquals(
            listOf("plan.get", "actual.set", "admin.credentials.rotate", "plan.explain"),
            registry.all().map { it.id },
        )
        assertEquals(
            mapOf(
                "plan.get" to "both",
                "actual.set" to "both",
                "admin.credentials.rotate" to "rest_only",
                "plan.explain" to "mcp_only",
            ),
            registry.classification(),
        )
    }

    @Test
    fun `the application boots, which is itself the startup classification check passing`() {
        // The @Observes @Initialized(ApplicationScoped) observer in
        // OperationRegistryProducer BUILT this catalogue during boot. Had any
        // operation declared no surface, or two operations claimed one MCP tool
        // name, this container would not have started and no test in this class
        // would run.
        assertTrue(registry.all().isNotEmpty())
        assertEquals("default", registry.policy.name)
    }

    // --- one handler, two surfaces, two credentials ----------------------

    @Test
    fun `REST and MCP use different credentials and reach the same handler`() {
        val viaRest = rest.dispatch(
            RestRequest(HttpMethod.GET, "/v1/plans/p-1", credential = null, correlationId = "c-rest"),
        )
        assertEquals(200, viaRest.status)
        val restBody = Json.parse(viaRest.body) as Json.Obj

        val viaMcp = mcp.call("get_plan", """{"planId":"p-1"}""", MCP_SESSION, correlationId = "c-mcp")
        val mcpBody = Json.parse((viaMcp as McpCallResult.Ok).content) as Json.Obj

        // Same plan, same title, same viewer — the agent acts for alice.
        assertEquals(restBody, mcpBody)
        assertEquals("alice", restBody.string("viewer"))

        // ...and the handler ran twice, once per surface, knowing which.
        assertEquals(
            listOf(
                "get:p-1:surface=rest:subject=alice",
                "get:p-1:surface=mcp:subject=alice",
            ),
            domain.invocations.toList(),
        )
    }

    @Test
    fun `each surface rejects the other surface's credential`() {
        // The MCP session id is not a REST identity.
        auth.restIdentity.set(MCP_SESSION)
        assertEquals(401, rest.dispatch(RestRequest(HttpMethod.GET, "/v1/plans/p-1")).status)

        // ...and the REST bearer is not an MCP session id.
        val result = mcp.call("get_plan", """{"planId":"p-1"}""", REST_BEARER) as McpCallResult.Error
        assertEquals("unauthenticated", result.code)
        assertTrue(domain.invocations.isEmpty())
    }

    @Test
    fun `validation is identical on both surfaces for the same bad input`() {
        val badState = """{"state":"NOPE","amount":1.005}"""

        val viaRest = rest.dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/p-1/items/i-2/actual",
                body = badState,
            ),
        )
        val viaMcp = mcp.call(
            "set_item_actual",
            """{"planId":"p-1","itemId":"i-2","state":"NOPE","amount":1.005}""",
            MCP_SESSION,
        )

        assertEquals(400, viaRest.status)
        assertEquals(400, (viaMcp as McpCallResult.Error).httpStatus)

        // The SAME code/field/message triple, in the same order, from one validator.
        val restDetails = details(Json.parse(viaRest.body) as Json.Obj)
        val mcpDetails = details(Json.parse(viaMcp.content) as Json.Obj)
        assertEquals(restDetails, mcpDetails)
        assertEquals(
            listOf("state|one_of", "amount|scale"),
            restDetails.map { "${it.first}|${it.second}" },
        )
        assertTrue(domain.invocations.isEmpty())
    }

    @Test
    fun `an idempotency key makes a retry safe on either surface`() {
        val arguments = """{"planId":"p-1","itemId":"i-2","state":"DONE","opId":"op-shared"}"""

        val first = rest.dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/p-1/items/i-2/actual",
                body = """{"state":"DONE","opId":"op-shared"}""",
            ),
        )
        assertEquals(201, first.status)

        // The agent retries the SAME logical operation through MCP: the stored
        // outcome is replayed rather than the write being applied twice.
        val retry = mcp.call("set_item_actual", arguments, MCP_SESSION) as McpCallResult.Ok
        assertTrue(retry.replayed)
        assertEquals(first.body, retry.content)
        assertEquals(1, domain.invocations.count { it.startsWith("set:") })
    }

    // --- the security proof, in a live container -------------------------

    @Test
    fun `the sensitive restOnly operation is reachable over REST`() {
        val response = rest.dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/admin/credentials/rotate",
                body = """{"service":"minio"}""",
            ),
        )
        assertEquals(201, response.status)
        assertEquals(listOf("ROTATE:minio:surface=rest"), domain.invocations.toList())
    }

    @Test
    fun `and is invisible and uncallable over MCP`() {
        assertFalse(mcp.toolsList().any { it.name.contains("rotate") })
        assertEquals(
            listOf("get_plan", "set_item_actual", "explain_plan"),
            mcp.toolsList().map { it.name },
        )
        listOf("admin.credentials.rotate", "admin_credentials_rotate", "rotate_credentials", "rotate").forEach {
            assertFalse(mcp.handles(it), it)
            assertEquals(
                "not_found",
                (mcp.call(it, """{"service":"minio"}""", MCP_SESSION) as McpCallResult.Error).code,
                it,
            )
        }
        assertTrue(domain.invocations.isEmpty(), domain.invocations.toString())
    }

    @Test
    fun `the mcpOnly helper has no HTTP route`() {
        assertFalse(rest.routes().any { it.path.contains("explain") })
        assertEquals(404, rest.dispatch(RestRequest(HttpMethod.GET, "/v1/plans/p-1/explain")).status)
        assertTrue(mcp.handles("explain_plan"))
    }

    @Test
    fun `the parity check passes against the live tool listing`() {
        ParityCheck.assertConsistent(
            registry,
            liveMcpToolNames = mcp.toolsList().map { it.name },
            legacyMcpToolNames = listOf("legacy_search", "legacy_export"),
            legacyRestPaths = listOf("GET /v1/legacy/plans"),
            knownOperationIds = registry.all().map { it.id },
        )
    }

    @Test
    fun `a legacy MCP tool that shadows a declared tool fails fast`() {
        val failure = runCatching { mcp.assertNoOverlap(listOf("get_plan")) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("get_plan"), failure.message)
    }

    @Test
    fun `an internal-only credential is not a bypass of the declared scopes`() {
        // The internal-job profile grants plans:write only, so an in-process
        // caller still cannot perform a plans:read operation. "Internal" is a
        // surface, not an exemption.
        val outcome = pipeline.call(
            operation = registry.byId("plan.get")!!,
            surface = Surface.INTERNAL,
            payload = Json.obj("planId" to Json.of("p-1")),
        )
        assertEquals("forbidden", outcome.errorOrNull()!!.code)
        assertTrue(domain.invocations.isEmpty())
    }

    @Test
    fun `the gallery catalogue exports the live wiring`() {
        val catalogue = OperationCatalog.toJson(registry, auth.profiles())
        assertEquals("rest-bearer", catalogue.nested("authProfiles").string("rest"))
        assertEquals("mcp-session", catalogue.nested("authProfiles").string("mcp"))
        assertEquals(4, catalogue.array("operations").size)
        val exclusions = catalogue.array("exclusions").map { it as Json.Obj }
        assertEquals(
            "Sensitive admin operation must not be callable by AI/MCP clients",
            exclusions.first { it.string("operationId") == "admin.credentials.rotate" }.string("reason"),
        )
    }

    @Test
    fun `the real startup bean rejects an undeclared surface inside the live container`() {
        // The boot gate itself, exercised through the REAL CDI bean and the REAL
        // Instance<OperationModule> this container resolves — with one bad module
        // added. The JVM-exit half cannot be asserted from inside a @QuarkusTest
        // (asserting a failed boot needs QuarkusUnitTest from
        // quarkus-junit5-internal, which this repository does not lock), so what
        // is proven here is that the bean the startup observer runs throws, and
        // that it names the operation and the contributing beans.
        val undeclared = OperationModule { builder ->
            builder.read<Unit, Unit>("plan.list") { handle { _, _ -> } }
        }
        val failure = assertThrows<OperationDeclarationException> {
            producer.validateAtStartup(
                Any(),
                SyntheticInstance(modules.toList() + undeclared),
                SyntheticInstance(emptyList()),
                SyntheticInstance(emptyList()),
            )
        }
        assertTrue(failure.message!!.contains("plan.list"), failure.message)
        assertTrue(failure.message!!.contains("declares NO surface"), failure.message)
        assertTrue(failure.message!!.contains("TravelOperationModule"), failure.message)

        // ...and the same bean accepts the catalogue this container DID boot with.
        producer.validateAtStartup(
            Any(),
            SyntheticInstance(modules.toList()),
            SyntheticInstance(emptyList()),
            SyntheticInstance(emptyList()),
        )
    }

    @Test
    fun `the configured category property names a known key`() {
        // Guards the property name the application.properties documentation uses.
        assertEquals("pkgrovekit.operations.mcp-refused-categories", OperationRegistryProducer.CATEGORIES_PROPERTY)
    }

    private fun details(body: Json.Obj): List<Pair<String, String>> =
        (body.fields["details"] as Json.Arr).items
            .map { it as Json.Obj }
            .map { it.string("field") to it.string("code") }
}
