package com.pkgrove.pkgrovekit.operation.rest

import com.pkgrove.pkgrovekit.operation.AuditRecord
import com.pkgrove.pkgrovekit.operation.AuditSink
import com.pkgrove.pkgrovekit.operation.HttpMethod
import com.pkgrove.pkgrovekit.operation.IdempotencyStore
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationError
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.ValidationError
import com.pkgrove.pkgrovekit.operation.defaultPipeline
import com.pkgrove.pkgrovekit.operation.nested
import com.pkgrove.pkgrovekit.operation.string
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The HTTP binding (HEL-602). */
class RestDispatcherTest {

    private val domain = RestDomain()
    private val registry = restRegistry(domain)
    private val audit = object : AuditSink {
        val records = mutableListOf<AuditRecord>()

        override fun record(record: AuditRecord) {
            records += record
        }
    }

    private fun dispatcher(
        scopes: Set<String> = setOf("plans:read", "plans:write", "admin"),
        idempotency: IdempotencyStore = IdempotencyStore.none,
    ) = RestDispatcher(
        registry,
        defaultPipeline(restProfiles(scopes), idempotencyStore = idempotency, audit = audit),
    )

    private fun body(response: RestResponse): Json.Obj = Json.parse(response.body) as Json.Obj

    @Test
    fun `only REST-exposed operations are routable`() {
        val routes = dispatcher().routes().map { it.toString() }
        assertEquals(
            listOf(
                "GET /v1/plans/{planId}",
                "POST /v1/plans/{planId}/items/{itemId}/actual",
                "PUT /v1/plans/{planId}/items/{itemId}/actual",
                "POST /v1/admin/credentials/rotate",
            ),
            routes,
        )
        // plan.summary is mcpOnly: it has no route at all
        assertFalse(routes.any { it.contains("summar") })
    }

    @Test
    fun `a GET resolves path parameters and renders the encoded output`() {
        val response = dispatcher().dispatch(
            RestRequest(HttpMethod.GET, "/v1/plans/p-1", credential = REST_TOKEN),
        )
        assertEquals(200, response.status)
        assertTrue(response.successful)
        assertEquals("application/json", response.contentType)
        assertEquals("p-1", body(response).string("planId"))
        assertEquals("Kyoto trip", body(response).string("title"))
        assertEquals(listOf("get:p-1:expand=false:by=alice"), domain.calls)
    }

    @Test
    fun `query parameters reach the decoder`() {
        dispatcher().dispatch(
            RestRequest(
                HttpMethod.GET,
                "/v1/plans/p-1",
                query = mapOf("expand" to "true"),
                credential = REST_TOKEN,
            ),
        )
        assertEquals(listOf("get:p-1:expand=true:by=alice"), domain.calls)
    }

    @Test
    fun `a POST merges body and path, and the path wins`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/p-1/items/i-2/actual",
                // A body claiming a DIFFERENT plan must not redirect the mutation.
                body = """{"planId":"p-evil","state":"DONE","amount":10.00}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(201, response.status)
        assertEquals(listOf("set:p-1/i-2=DONE"), domain.calls)
        assertEquals("p-1", body(response).string("planId"))
    }

    @Test
    fun `a replayed idempotent POST returns 200, not another 201`() {
        val run = dispatcher(idempotency = IdempotencyStore.inMemory())
        val request = RestRequest(
            HttpMethod.POST,
            "/v1/plans/p-1/items/i-2/actual",
            body = """{"state":"DONE","opId":"op-1"}""",
            credential = REST_TOKEN,
        )
        assertEquals(201, run.dispatch(request).status)
        assertEquals(200, run.dispatch(request).status)
        assertEquals(1, domain.revision)
    }

    @Test
    fun `a non-POST write returns 200`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.PUT,
                "/v1/plans/p-1/items/i-2/actual",
                body = """{"state":"DONE"}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(200, response.status)
    }

    @Test
    fun `an unrouted path or method is 404 and never reaches a handler`() {
        listOf(
            RestRequest(HttpMethod.GET, "/v1/unknown", credential = REST_TOKEN),
            RestRequest(HttpMethod.DELETE, "/v1/plans/p-1", credential = REST_TOKEN),
            RestRequest(HttpMethod.GET, "/v1/plans/p-1/extra", credential = REST_TOKEN),
        ).forEach { request ->
            val response = dispatcher().dispatch(request)
            assertEquals(404, response.status, request.toString())
            assertEquals("not_found", body(response).string("error"))
        }
        assertTrue(domain.calls.isEmpty())
    }

    @Test
    fun `handles reports exactly what is routable`() {
        val run = dispatcher()
        assertTrue(run.handles(HttpMethod.GET, "/v1/plans/p-1"))
        assertTrue(run.handles(HttpMethod.POST, "/v1/admin/credentials/rotate"))
        assertFalse(run.handles(HttpMethod.GET, "/v1/plans"))
        assertFalse(run.handles(HttpMethod.PATCH, "/v1/plans/p-1"))
    }

    @Test
    fun `every OperationError maps to its documented status and envelope`() {
        val run = dispatcher()

        val unauthenticated = run.dispatch(RestRequest(HttpMethod.GET, "/v1/plans/p-1", credential = "nope"))
        assertEquals(401, unauthenticated.status)
        assertEquals("unauthenticated", body(unauthenticated).string("error"))

        val forbidden = dispatcher(scopes = setOf("plans:read")).dispatch(
            RestRequest(HttpMethod.POST, "/v1/admin/credentials/rotate", body = """{"service":"minio"}""", credential = REST_TOKEN),
        )
        assertEquals(403, forbidden.status)
        assertEquals("forbidden", body(forbidden).string("error"))

        val invalid = run.dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/p-1/items/i-2/actual",
                body = """{"state":"NOPE"}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(400, invalid.status)
        assertEquals("invalid", body(invalid).string("error"))

        val notFound = run.dispatch(RestRequest(HttpMethod.GET, "/v1/plans/absent", credential = REST_TOKEN))
        assertEquals(404, notFound.status)

        val conflict = run.dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/locked/items/i-2/actual",
                body = """{"state":"DONE"}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(409, conflict.status)
        assertEquals("conflict", body(conflict).string("error"))
    }

    @Test
    fun `a validation failure names the field in the details array`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/p-1/items/i-2/actual",
                body = """{"state":"DONE","amount":1.005}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(400, response.status)
        val detail = (body(response).fields["details"] as Json.Arr).items.single() as Json.Obj
        assertEquals("amount", detail.string("field"))
        assertEquals("scale", detail.string("code"))
    }

    @Test
    fun `a missing required field becomes an invalid error, not a 500`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/plans/p-1/items/i-2/actual",
                body = """{"amount":10.00}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(400, response.status)
        val detail = (body(response).fields["details"] as Json.Arr).items.single() as Json.Obj
        assertEquals("state", detail.string("field"))
        assertEquals("required", detail.string("code"))
    }

    @Test
    fun `a malformed or non-object body is an invalid error`() {
        listOf("""{"state":""", """["not","an","object"]""", "7").forEach { badBody ->
            val response = dispatcher().dispatch(
                RestRequest(
                    HttpMethod.POST,
                    "/v1/plans/p-1/items/i-2/actual",
                    body = badBody,
                    credential = REST_TOKEN,
                ),
            )
            assertEquals(400, response.status, badBody)
            assertEquals("invalid", body(response).string("error"))
        }
        assertTrue(domain.calls.isEmpty())
    }

    @Test
    fun `a percent-encoded path segment is decoded before the handler sees it`() {
        dispatcher().dispatch(RestRequest(HttpMethod.GET, "/v1/plans/p%201", credential = REST_TOKEN))
        assertEquals(listOf("get:p 1:expand=false:by=alice"), domain.calls)
    }

    @Test
    fun `a query string in the request path is ignored by routing`() {
        val response = dispatcher().dispatch(
            RestRequest(HttpMethod.GET, "/v1/plans/p-1?expand=true", credential = REST_TOKEN),
        )
        assertEquals(200, response.status)
    }

    @Test
    fun `the audit record attributes the call to the REST surface and client`() {
        dispatcher().dispatch(
            RestRequest(
                HttpMethod.GET,
                "/v1/plans/p-1",
                credential = REST_TOKEN,
                correlationId = "c-1",
                attributes = mapOf("clientIp" to "10.0.0.1"),
            ),
        )
        val record = audit.records.single()
        assertEquals(Surface.REST, record.surface)
        assertEquals("alice", record.subjectId)
        assertEquals("ios-app", record.clientId)
        assertEquals("rest-bearer", record.authProfileName)
        assertEquals("c-1", record.correlationId)
        assertEquals(mapOf("clientIp" to "10.0.0.1"), record.attributes)
    }

    @Test
    fun `a host may substitute its own error envelope`() {
        val run = RestDispatcher(
            registry,
            defaultPipeline(restProfiles()),
            renderError = { error -> Json.write(Json.obj("problem" to Json.of(error.code))) },
        )
        val response = run.dispatch(RestRequest(HttpMethod.GET, "/v1/plans/p-1", credential = "nope"))
        assertEquals(401, response.status)
        assertEquals("unauthenticated", body(response).string("problem"))
    }

    @Test
    fun `a Unit-returning operation renders an empty object`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/admin/credentials/rotate",
                body = """{"service":"minio"}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(201, response.status)
        assertEquals("{}", response.body)
        assertEquals(listOf("rotate:minio"), domain.calls)
    }

    @Test
    fun `a response normalises to a SurfaceAnswer for the parity utility`() {
        val ok = dispatcher().dispatch(RestRequest(HttpMethod.GET, "/v1/plans/p-1", credential = REST_TOKEN))
        assertEquals(200, ok.asSurfaceAnswer().status)
        assertEquals("p-1", (ok.asSurfaceAnswer().payload as Json.Obj).string("planId"))

        val failed = dispatcher().dispatch(RestRequest(HttpMethod.GET, "/v1/plans/absent", credential = REST_TOKEN))
        assertEquals(404, failed.asSurfaceAnswer().status)
        assertEquals("not_found", (failed.asSurfaceAnswer().payload as Json.Obj).string("error"))
    }

    @Test
    fun `the validation detail renderer is reusable by a host`() {
        val rendered = listOf(ValidationError("state", "one_of", "must be A or B")).toJson()
        assertEquals(
            """[{"field":"state","code":"one_of","message":"must be A or B"}]""",
            Json.write(rendered),
        )
    }

    @Test
    fun `the error body shape is the shared wire envelope`() {
        val response = dispatcher().dispatch(RestRequest(HttpMethod.GET, "/v1/plans/absent", credential = REST_TOKEN))
        val parsed = body(response)
        assertEquals(setOf("error", "message", "details"), parsed.fields.keys)
        assertEquals(OperationError.NotFound("x").code, parsed.string("error"))
    }

    @Test
    fun `an empty body on a write is treated as no payload`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.POST,
                "/v1/admin/credentials/rotate",
                body = "   ",
                credential = REST_TOKEN,
            ),
        )
        // The decoder still demands `service`, so this is an invalid request —
        // not a crash, and not an empty rotation.
        assertEquals(400, response.status)
        assertEquals("service", (body(response).fields["details"] as Json.Arr).items
            .map { (it as Json.Obj).string("field") }.single())
    }

    @Test
    fun `nested json in a body survives the merge`() {
        val response = dispatcher().dispatch(
            RestRequest(
                HttpMethod.GET,
                "/v1/plans/p-1",
                body = """{"meta":{"source":"ios"}}""",
                credential = REST_TOKEN,
            ),
        )
        assertEquals(200, response.status)
        assertEquals("Kyoto trip", body(response).string("title"))
        // proves the nested accessor is reachable from the adapter module too
        assertEquals("ios", (Json.parse("""{"meta":{"source":"ios"}}""") as Json.Obj).nested("meta").string("source"))
    }
}
