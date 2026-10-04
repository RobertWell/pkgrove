package com.pkgrove.pkgrovekit.operation.quarkus

import com.pkgrove.pkgrovekit.operation.CategorySurfacePolicy
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.OperationCategory
import com.pkgrove.pkgrovekit.operation.OperationDeclarationException
import com.pkgrove.pkgrovekit.operation.OperationModule
import com.pkgrove.pkgrovekit.operation.PermissiveSurfacePolicy
import com.pkgrove.pkgrovekit.operation.SurfacePolicy
import com.pkgrove.pkgrovekit.operation.string
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The CDI wiring (HEL-602): the registry is assembled from discovered modules
 * and the fail-closed check runs at STARTUP, so a bad declaration is a boot
 * failure rather than a 500 on the first production request.
 */
class OperationRegistryProducerTest {

    private val producer = OperationRegistryProducer()

    private data class Thing(val id: String)

    private val plansModule = OperationModule { builder ->
        builder.decoder<Thing> { Thing(it.string("id")) }
        builder.encoder<Thing> { Json.obj("id" to Json.of(it.id)) }
        builder.read<Thing, Thing>("plan.get") {
            both { rest GET "/v1/plans/{id}"; mcp("get_plan") }
            requires("plans:read")
            input { field("id", "string", required = true) }
            handle { _, input -> input }
        }
    }

    private val adminModule = OperationModule { builder ->
        builder.write<Unit, Unit>("admin.credentials.rotate") {
            restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
                rest POST "/v1/admin/credentials/rotate"
            }
            categories(OperationCategory.CREDENTIAL_ROTATION)
            handle { _, _ -> }
        }
    }

    /** The defect the startup check exists to catch. */
    private val undeclaredSurfaceModule = OperationModule { builder ->
        builder.read<Unit, Unit>("plan.list") { handle { _, _ -> } }
    }

    private val collidingModule = OperationModule { builder ->
        builder.read<Unit, Unit>("plan.fetch") {
            mcpOnly(reason = "duplicate on purpose") { mcp("get_plan") }
            handle { _, _ -> }
        }
    }

    private fun noConfig() = FakeInstance<Config>(emptyList())

    private fun config(vararg pairs: Pair<String, String>) =
        FakeInstance<Config>(listOf(MapConfig(pairs.toMap())))

    private fun noPolicy() = FakeInstance<SurfacePolicy>(emptyList())

    @Test
    fun `the registry is assembled from every discovered module`() {
        val registry = producer.registry(
            FakeInstance(listOf(plansModule, adminModule)),
            noPolicy(),
            noConfig(),
        )
        assertEquals(listOf("plan.get", "admin.credentials.rotate"), registry.all().map { it.id })
        assertEquals(listOf("get_plan"), registry.mcpToolNames())
        assertEquals("default", registry.policy.name)
    }

    @Test
    fun `no modules produces an empty registry rather than a failure`() {
        val registry = producer.registry(FakeInstance(emptyList()), noPolicy(), noConfig())
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `an undeclared surface fails at startup, naming the operation and the beans`() {
        val failure = assertThrows<OperationDeclarationException> {
            producer.validateAtStartup(
                Any(),
                FakeInstance(listOf(plansModule, undeclaredSurfaceModule)),
                noPolicy(),
                noConfig(),
            )
        }
        assertTrue(failure.message!!.contains("plan.list"), failure.message)
        assertTrue(failure.message!!.contains("declares NO surface"), failure.message)
        // the bean names are what make a one-line fix possible
        assertTrue(failure.message!!.contains("Contributing OperationModule beans:"), failure.message)
    }

    @Test
    fun `a duplicate MCP tool across two modules fails at startup`() {
        val failure = assertThrows<OperationDeclarationException> {
            producer.validateAtStartup(
                Any(),
                FakeInstance(listOf(plansModule, collidingModule)),
                noPolicy(),
                noConfig(),
            )
        }
        assertTrue(failure.message!!.contains("DUPLICATE MCP TOOL 'get_plan'"), failure.message)
    }

    @Test
    fun `the startup check and the producer build the same registry`() {
        val modules = FakeInstance(listOf(plansModule, adminModule))
        producer.validateAtStartup(Any(), modules, noPolicy(), noConfig())
        assertEquals(
            producer.registry(modules, noPolicy(), noConfig()).classification(),
            producer.build(modules, noPolicy(), noConfig()).classification(),
        )
    }

    @Test
    fun `an application-supplied policy bean wins over the default`() {
        val registry = producer.build(
            FakeInstance(listOf(plansModule)),
            FakeInstance(listOf(PermissiveSurfacePolicy)),
            noConfig(),
        )
        assertEquals("permissive", registry.policy.name)
    }

    @Test
    fun `two policy beans are a hard failure, not a coin toss`() {
        val failure = assertThrows<OperationDeclarationException> {
            producer.resolvePolicy(
                FakeInstance(listOf(PermissiveSurfacePolicy, CategorySurfacePolicy.default)),
                noConfig(),
            )
        }
        assertTrue(failure.message!!.contains("more than one SurfacePolicy bean"), failure.message)
    }

    @Test
    fun `the refused categories may be configured`() {
        val policy = producer.resolvePolicy(
            noPolicy(),
            config(
                OperationRegistryProducer.CATEGORIES_PROPERTY to "bulk_export, DESTRUCTIVE_DELETE",
            ),
        ) as CategorySurfacePolicy
        assertEquals("configured", policy.name)
        assertEquals(
            setOf(OperationCategory.BULK_EXPORT, OperationCategory.DESTRUCTIVE_DELETE),
            policy.refusedForMcp,
        )
    }

    @Test
    fun `an unknown configured category is a startup failure that lists the known ones`() {
        val failure = assertThrows<OperationDeclarationException> {
            producer.resolvePolicy(
                noPolicy(),
                config(OperationRegistryProducer.CATEGORIES_PROPERTY to "nonsense"),
            )
        }
        assertTrue(failure.message!!.contains("unknown category 'nonsense'"), failure.message)
        assertTrue(failure.message!!.contains("credential_rotation"), failure.message)
    }

    @Test
    fun `an empty configured category list falls back to the library default`() {
        assertEquals(
            "configured",
            producer.resolvePolicy(
                noPolicy(),
                config(OperationRegistryProducer.CATEGORIES_PROPERTY to " , "),
            ).name,
        )
        assertEquals("default", producer.resolvePolicy(noPolicy(), noConfig()).name)
        assertEquals(
            "default",
            producer.resolvePolicy(noPolicy(), FakeInstance(listOf(MapConfig(emptyMap())))).name,
        )
    }

    @Test
    fun `an ambiguous Config is ignored rather than guessed`() {
        val ambiguous = FakeInstance<Config>(listOf(MapConfig(emptyMap()), MapConfig(emptyMap())))
        assertEquals("default", producer.resolvePolicy(noPolicy(), ambiguous).name)
    }

    @Test
    fun `requiring operations turns an empty catalogue into a boot failure`() {
        val required = config(OperationRegistryProducer.REQUIRE_OPERATIONS_PROPERTY to "true")
        val failure = assertThrows<IllegalStateException> {
            producer.validateAtStartup(Any(), FakeInstance(emptyList()), noPolicy(), required)
        }
        assertTrue(failure.message!!.contains("no operations were declared"), failure.message)

        // ...and does not complain when a module did contribute
        producer.validateAtStartup(Any(), FakeInstance(listOf(plansModule)), noPolicy(), required)
        // ...nor when the flag is absent
        producer.validateAtStartup(Any(), FakeInstance(emptyList()), noPolicy(), noConfig())
    }

    @Test
    fun `a policy-refused MCP exposure fails at startup`() {
        val agentCallableRotation = OperationModule { builder ->
            builder.write<Unit, Unit>("admin.credentials.rotate") {
                both { rest POST "/v1/admin/credentials/rotate"; mcp("rotate_credentials") }
                categories(OperationCategory.CREDENTIAL_ROTATION)
                handle { _, _ -> }
            }
        }
        val failure = assertThrows<OperationDeclarationException> {
            producer.validateAtStartup(Any(), FakeInstance(listOf(agentCallableRotation)), noPolicy(), noConfig())
        }
        assertTrue(failure.message!!.contains("POLICY REFUSAL"), failure.message)
    }
}
