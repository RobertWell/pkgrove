package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The Gallery descriptor and its JSON export (HEL-602). */
class DescriptorTest {

    private val registry = sampleRegistry()
    private val profiles = sampleProfiles()

    @Test
    fun `a both-exposed operation describes both surfaces`() {
        val d = registry.describe(profiles).first { it.id == "plan.get" }
        assertEquals("read", d.kind)
        assertEquals("stable", d.status)
        assertEquals("both", d.classification)
        assertEquals(listOf("mcp", "rest"), d.surfaces)
        assertNull(d.exclusionReason)
        assertEquals("GET", d.restMethod)
        assertEquals("/v1/plans/{planId}", d.restPath)
        assertEquals("get_plan", d.mcpToolName)
        assertEquals("Reads one travel plan", d.mcpDescription)
        assertEquals("GetPlan", d.inputTypeName)
        assertEquals("PlanDetail", d.outputTypeName)
        assertEquals(listOf("plans:read"), d.requiredScopes)
        assertEquals(mapOf("rest" to "rest-bearer", "mcp" to "mcp-session"), d.authProfiles)
        assertEquals(listOf("plans"), d.tags)
        assertEquals("default", d.policyName)
        assertNull(d.mcpPolicyRefusal)
        assertTrue(d.audited)
        assertTrue(d.metered)
        assertFalse(d.idempotent)
    }

    @Test
    fun `a restOnly operation describes its exclusion and reports no MCP binding`() {
        val d = registry.describe().first { it.id == "admin.credentials.rotate" }
        assertEquals("rest_only", d.classification)
        assertEquals(listOf("rest"), d.surfaces)
        assertEquals("Sensitive admin operation must not be callable by AI/MCP clients", d.exclusionReason)
        assertNull(d.mcpToolName)
        // No MCP binding, so there is nothing for the policy to refuse.
        assertNull(d.mcpPolicyRefusal)
        assertEquals(listOf("credential_rotation"), d.categories)
    }

    @Test
    fun `an mcpOnly operation reports no REST route`() {
        val d = registry.describe(profiles).first { it.id == "recommendation.explain" }
        assertEquals("mcp_only", d.classification)
        assertNull(d.restPath)
        assertNull(d.restMethod)
        assertEquals("explain_recommendation", d.mcpToolName)
        assertEquals("Agent-specific reasoning helper", d.exclusionReason)
        // only the MCP credential is reportable: the REST one cannot reach it
        assertEquals(mapOf("mcp" to "mcp-session"), d.authProfiles)
    }

    @Test
    fun `an internalOnly operation describes neither surface`() {
        val d = registry.describe(profiles).first { it.id == "plan.reindex" }
        assertEquals("internal_only", d.classification)
        assertEquals(listOf("internal"), d.surfaces)
        assertEquals("batch job", d.exclusionReason)
        assertEquals(mapOf("internal" to "internal-job"), d.authProfiles)
    }

    @Test
    fun `an idempotent write reports the capability and its validation rules`() {
        val d = registry.describe().first { it.id == "actual.set" }
        assertTrue(d.idempotent)
        assertEquals("write", d.kind)
        assertTrue(d.validationRules.any { it.contains("one of PLANNED|BOOKED|DONE") }, d.validationRules.toString())
    }

    @Test
    fun `the input schema renders as JSON Schema with required and enum`() {
        val schema = registry.byId("actual.set")!!.inputSchema.toJsonSchema()
        assertEquals(
            """{"type":"object","properties":{"planId":{"type":"string"},"itemId":{"type":"string"},""" +
                """"state":{"type":"string","enum":["PLANNED","BOOKED","DONE"]},"amount":{"type":"number"},""" +
                """"currency":{"type":"string"},"opId":{"type":"string"}},""" +
                """"required":["planId","itemId","state"]}""",
            Json.write(schema),
        )
    }

    @Test
    fun `a schema carries descriptions when they are declared`() {
        val schema = OperationSchema(
            listOf(SchemaField("planId", "string", required = true, description = "the plan")),
            "the plan request",
        )
        assertEquals(
            """{"type":"object","description":"the plan request","properties":""" +
                """{"planId":{"type":"string","description":"the plan"}},"required":["planId"]}""",
            Json.write(schema.toJsonSchema()),
        )
        assertEquals(setOf("planId"), schema.names())
    }

    @Test
    fun `an unknown schema type and a blank field name are refused`() {
        assertThrows<IllegalArgumentException> { SchemaField("x", "decimal") }
        assertThrows<IllegalArgumentException> { SchemaField(" ", "string") }
    }

    @Test
    fun `the no-payload schema renders as an empty object schema`() {
        assertEquals(
            """{"type":"object","properties":{},"required":[]}""",
            Json.write(OperationSchema.none.toJsonSchema()),
        )
    }

    @Test
    fun `the descriptor JSON export carries every documented section`() {
        val json = registry.describe(profiles).first { it.id == "actual.set" }.toJson()
        assertEquals(
            setOf(
                "id", "kind", "status", "description", "tags", "classification", "surfaces",
                "exclusionReason", "rest", "mcp", "input", "output", "examples", "auth",
                "validation", "capabilities", "dsl",
            ),
            json.fields.keys,
        )
        val auth = json.nested("auth")
        assertEquals(listOf("plans:write"), auth.array("requiredScopes").map { (it as Json.Str).value })
        assertEquals("rest-bearer", auth.nested("profiles").string("rest"))
        assertEquals("default", auth.string("policy"))
        assertTrue(json.nested("capabilities").bool("idempotent"))
        assertEquals("POST", json.nested("rest").string("method"))
        assertEquals("set_item_actual", json.nested("mcp").string("name"))
    }

    @Test
    fun `a withheld operation exports null rest or mcp sections`() {
        val restOnly = registry.describe().first { it.id == "admin.credentials.rotate" }.toJson()
        assertEquals(Json.Null, restOnly.fields["mcp"])
        assertEquals("POST", restOnly.nested("rest").string("method"))

        val internalOnly = registry.describe().first { it.id == "plan.reindex" }.toJson()
        assertEquals(Json.Null, internalOnly.fields["rest"])
        assertEquals(Json.Null, internalOnly.fields["mcp"])
    }

    @Test
    fun `an example is exported with its input and optional output`() {
        val withExamples = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("thing.show") {
                internalOnly(reason = "test")
                example("no output", Json.obj("a" to Json.of(1)))
                example("with output", Json.emptyObject, Json.obj("ok" to Json.of(true)))
                handle { _, _ -> }
            }
        }
        val examples = withExamples.describe().single().toJson().array("examples")
        assertEquals(2, examples.size)
        assertEquals(Json.Null, (examples[0] as Json.Obj).fields["output"])
        assertTrue((examples[1] as Json.Obj).nested("output").bool("ok"))
    }

    @Test
    fun `the generated DSL snippet is the declaration it came from`() {
        val snippet = registry.byId("plan.get")!!.dslSnippet()
        assertTrue(snippet.startsWith("read<GetPlan, PlanDetail>(\"plan.get\") {"), snippet)
        assertTrue(snippet.contains("both { rest GET \"/v1/plans/{planId}\"; mcp(\"get_plan\") }"), snippet)
        assertTrue(snippet.contains("requires(\"plans:read\")"), snippet)
        assertTrue(snippet.contains("describe(\"Reads one travel plan\")"), snippet)
        assertTrue(snippet.endsWith("}"), snippet)
    }

    @Test
    fun `the snippet renders each restricted form and the optional clauses`() {
        assertTrue(
            registry.byId("admin.credentials.rotate")!!.dslSnippet().contains(
                "restOnly(reason = \"Sensitive admin operation must not be callable by AI/MCP clients\") " +
                    "{ rest POST \"/v1/admin/credentials/rotate\" }",
            ),
        )
        assertTrue(
            registry.byId("recommendation.explain")!!.dslSnippet()
                .contains("mcpOnly(reason = \"Agent-specific reasoning helper\") { mcp(\"explain_recommendation\") }"),
        )
        assertTrue(registry.byId("plan.reindex")!!.dslSnippet().contains("internalOnly(reason = \"batch job\")"))
        assertTrue(registry.byId("admin.credentials.rotate")!!.dslSnippet().contains("categories(CREDENTIAL_ROTATION)"))
        assertTrue(registry.byId("actual.set")!!.dslSnippet().contains("idempotent("))
        assertTrue(registry.byId("actual.set")!!.dslSnippet().contains("validate {"))

        val experimental = operations(PermissiveSurfacePolicy) {
            write<RotateCreds, Unit>("thing.try") {
                both { rest POST "/v1/things"; mcp("try_thing") }
                status(OperationStatus.EXPERIMENTAL)
                mcpOverride(reason = "harmless")
                categories(OperationCategory.PRIVILEGED_ADMIN)
                decode { RotateCreds(it.string("service")) }
                handle { _, _ -> }
            }
        }
        val snippet = experimental.byId("thing.try")!!.dslSnippet()
        assertTrue(snippet.contains("status(OperationStatus.EXPERIMENTAL)"), snippet)
        assertTrue(snippet.contains("mcpOverride(reason = \"harmless\")"), snippet)
        assertTrue(snippet.startsWith("write<"), snippet)
    }

    @Test
    fun `the catalogue export answers what an agent can reach and what was withheld`() {
        val catalogue = OperationCatalog.toJson(registry, profiles)
        assertEquals("default", catalogue.string("policy"))
        assertEquals(5, catalogue.int("operationCount"))
        assertEquals("rest-bearer", catalogue.nested("authProfiles").string("rest"))
        assertEquals("both", catalogue.nested("classification").string("plan.get"))
        assertEquals("rest_only", catalogue.nested("classification").string("admin.credentials.rotate"))
        assertEquals(
            listOf("get_plan", "set_item_actual", "explain_recommendation"),
            catalogue.array("mcpTools").map { (it as Json.Str).value },
        )
        val exclusions = catalogue.array("exclusions").map { it as Json.Obj }
        assertEquals(3, exclusions.size)
        assertEquals(
            "Sensitive admin operation must not be callable by AI/MCP clients",
            exclusions.first { it.string("operationId") == "admin.credentials.rotate" }.string("reason"),
        )
        assertEquals(5, catalogue.array("operations").size)
    }

    @Test
    fun `the catalogue export is valid JSON text that round-trips`() {
        val text = OperationCatalog.toJsonText(registry, profiles)
        val reparsed = Json.parse(text) as Json.Obj
        assertEquals(5, reparsed.int("operationCount"))
        assertEquals(text, Json.write(reparsed))
    }

    @Test
    fun `the catalogue export works without auth profiles`() {
        val catalogue = OperationCatalog.toJson(registry)
        assertEquals(Json.emptyObject, catalogue.fields["authProfiles"])
        assertTrue(registry.describe().all { it.authProfiles.isEmpty() })
    }

    @Test
    fun `a policy refusal is reported in the descriptor even when overridden`() {
        val overridden = operations {
            write<RotateCreds, Unit>("admin.flag.toggle") {
                both { rest POST "/v1/admin/flags"; mcp("toggle_flag") }
                categories(OperationCategory.PRIVILEGED_ADMIN)
                mcpOverride(reason = "reversible and tenant-scoped")
                decode { RotateCreds(it.string("service")) }
                handle { _, _ -> }
            }
        }
        val d = overridden.describe().single()
        assertTrue(d.mcpPolicyRefusal!!.contains("privileged_admin"), d.mcpPolicyRefusal)
        assertEquals("reversible and tenant-scoped", d.mcpPolicyOverrideReason)
    }

    @Test
    fun `an MCP description falls back to the operation description then to the id`() {
        val registry = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("a.described") {
                mcpOnly(reason = "t") { mcp("a_described") }
                describe("the operation description")
                handle { _, _ -> }
            }
            read<Unit, Unit>("b.bare") {
                mcpOnly(reason = "t") { mcp("b_bare") }
                handle { _, _ -> }
            }
        }
        assertEquals("the operation description", registry.describe()[0].mcpDescription)
        assertEquals("", registry.describe()[1].mcpDescription)
    }
}
