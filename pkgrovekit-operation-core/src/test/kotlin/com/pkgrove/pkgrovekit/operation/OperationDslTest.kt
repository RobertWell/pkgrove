package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The declaration DSL and the shape it produces (HEL-602). */
class OperationDslTest {

    @Test
    fun `the one-screen declaration produces the declared catalogue`() {
        val registry = sampleRegistry()

        assertEquals(
            listOf("plan.get", "actual.set", "admin.credentials.rotate", "recommendation.explain", "plan.reindex"),
            registry.all().map { it.id },
        )
        assertEquals(
            mapOf(
                "plan.get" to "both",
                "actual.set" to "both",
                "admin.credentials.rotate" to "rest_only",
                "recommendation.explain" to "mcp_only",
                "plan.reindex" to "internal_only",
            ),
            registry.classification(),
        )
    }

    @Test
    fun `read and write kinds are recorded`() {
        val registry = sampleRegistry()
        assertEquals(OperationKind.READ, registry.byId("plan.get")!!.kind)
        assertEquals(OperationKind.WRITE, registry.byId("actual.set")!!.kind)
        assertEquals(OperationKind.READ, registry.byId("recommendation.explain")!!.kind)
    }

    @Test
    fun `the infix rest form and the explicit form produce the same binding`() {
        val infix = operations(PermissiveSurfacePolicy) {
            read<GetPlan, PlanDetail>("plan.get") {
                both { rest GET "/v1/plans/{planId}"; mcp tool "get_plan" }
                decode { GetPlan(it.string("planId")) }
                encode { Json.obj("planId" to Json.of(it.planId)) }
                handle { _, input -> PlanDetail(input.planId, "t") }
            }
        }
        val explicit = operations(PermissiveSurfacePolicy) {
            read<GetPlan, PlanDetail>("plan.get") {
                both {
                    rest(HttpMethod.GET, "/v1/plans/{planId}")
                    mcp("get_plan")
                }
                decode { GetPlan(it.string("planId")) }
                encode { Json.obj("planId" to Json.of(it.planId)) }
                handle { _, input -> PlanDetail(input.planId, "t") }
            }
        }
        assertEquals(
            infix.byId("plan.get")!!.exposure.restBinding(),
            explicit.byId("plan.get")!!.exposure.restBinding(),
        )
        assertEquals("get_plan", explicit.byId("plan.get")!!.exposure.mcpBinding()!!.toolName)
    }

    @Test
    fun `every REST method word binds`() {
        HttpMethod.entries.forEach { method ->
            val registry = operations(PermissiveSurfacePolicy) {
                write<Unit, Unit>("thing.touch") {
                    restOnly(reason = "method matrix") {
                        when (method) {
                            HttpMethod.GET -> rest GET "/v1/things"
                            HttpMethod.POST -> rest POST "/v1/things"
                            HttpMethod.PUT -> rest PUT "/v1/things"
                            HttpMethod.PATCH -> rest PATCH "/v1/things"
                            HttpMethod.DELETE -> rest DELETE "/v1/things"
                        }
                    }
                    handle { _, _ -> }
                }
            }
            assertEquals(method, registry.byId("thing.touch")!!.exposure.restBinding()!!.method)
        }
    }

    @Test
    fun `a Unit input and output need no codec because there is nothing to carry`() {
        val registry = operations(PermissiveSurfacePolicy) {
            write<Unit, Unit>("cache.flush") {
                restOnly(reason = "operational endpoint") { rest POST "/v1/admin/cache/flush" }
                handle { _, _ -> }
            }
        }
        val op = registry.byId("cache.flush")!!
        assertNotNull(op.decoder)
        assertEquals(Json.emptyObject, op.renderOutput(Unit))
    }

    @Test
    fun `an operation records its input and output type names`() {
        val op = sampleRegistry().byId("actual.set")!!
        assertEquals("SetActual", op.inputTypeName)
        assertEquals("MutationResponse", op.outputTypeName)
    }

    @Test
    fun `scopes accumulate and blank scopes are refused`() {
        val registry = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("a.b") {
                internalOnly(reason = "test")
                requires("x")
                requires("y", "z")
                handle { _, _ -> }
            }
        }
        assertEquals(setOf("x", "y", "z"), registry.byId("a.b")!!.requiredScopes)

        assertThrows<IllegalArgumentException> {
            operations(PermissiveSurfacePolicy) {
                read<Unit, Unit>("a.b") {
                    internalOnly(reason = "test")
                    requires(" ")
                    handle { _, _ -> }
                }
            }
        }
    }

    @Test
    fun `idempotency is opt-in, by property or by computed key`() {
        val registry = operations(PermissiveSurfacePolicy) {
            write<SetActual, Unit>("by.property") {
                internalOnly(reason = "test")
                idempotent(SetActual::opId)
                handle { _, _ -> }
            }
            write<SetActual, Unit>("by.lambda") {
                internalOnly(reason = "test")
                idempotentBy { "${it.planId}/${it.itemId}" }
                handle { _, _ -> }
            }
            write<SetActual, Unit>("not.idempotent") {
                internalOnly(reason = "test")
                handle { _, _ -> }
            }
        }
        val input = SetActual("p-1", "i-2", "DONE", null, null, "op-7")
        assertEquals("op-7", keyOf(registry.byId("by.property")!!, input))
        assertEquals("p-1/i-2", keyOf(registry.byId("by.lambda")!!, input))
        assertFalse(registry.byId("not.idempotent")!!.idempotent)
        assertNull(registry.byId("not.idempotent")!!.idempotencyKey)
    }

    @Test
    fun `status, tags, description, examples and capability flags are carried`() {
        val registry = operations(PermissiveSurfacePolicy) {
            read<Unit, Unit>("legacy.thing") {
                internalOnly(reason = "test")
                status(OperationStatus.DEPRECATED)
                describe("an old thing")
                tags("legacy", "cleanup")
                example("empty", Json.emptyObject, Json.emptyObject)
                audited(false)
                metered(false)
                handle { _, _ -> }
            }
        }
        val op = registry.byId("legacy.thing")!!
        assertEquals(OperationStatus.DEPRECATED, op.status)
        assertEquals("an old thing", op.description)
        assertEquals(setOf("legacy", "cleanup"), op.tags)
        assertEquals(1, op.examples.size)
        assertFalse(op.audited)
        assertFalse(op.metered)
        assertTrue(op.toString().contains("legacy.thing"))
    }

    @Test
    fun `internalOnly operations are reachable only in process`() {
        val op = sampleRegistry().byId("plan.reindex")!!
        assertTrue(op.exposedOn(Surface.INTERNAL))
        assertFalse(op.exposedOn(Surface.REST))
        assertFalse(op.exposedOn(Surface.MCP))
        assertNull(op.exposure.restBinding())
        assertNull(op.exposure.mcpBinding())
        assertEquals("batch job", op.exposure.reason)
    }

    @Test
    fun `a both-exposed operation is reachable on every surface`() {
        val op = sampleRegistry().byId("plan.get")!!
        assertTrue(op.exposedOn(Surface.REST))
        assertTrue(op.exposedOn(Surface.MCP))
        assertTrue(op.exposedOn(Surface.INTERNAL))
        assertNull(op.exposure.reason)
    }

    @Test
    fun `an operation module contributes to a shared registry`() {
        val plans = OperationModule { builder ->
            builder.read<Unit, Unit>("plans.ping") {
                internalOnly(reason = "health")
                handle { _, _ -> }
            }
        }
        val admin = OperationModule { builder ->
            builder.read<Unit, Unit>("admin.ping") {
                internalOnly(reason = "health")
                handle { _, _ -> }
            }
        }
        val registry = operationsFrom(listOf(plans, admin), PermissiveSurfacePolicy)
        assertEquals(listOf("plans.ping", "admin.ping"), registry.all().map { it.id })
    }

    private fun keyOf(op: Operation<*, *>, input: Any?): String? {
        @Suppress("UNCHECKED_CAST")
        return (op as Operation<Any?, Any?>).idempotencyKey?.invoke(input)
    }
}
