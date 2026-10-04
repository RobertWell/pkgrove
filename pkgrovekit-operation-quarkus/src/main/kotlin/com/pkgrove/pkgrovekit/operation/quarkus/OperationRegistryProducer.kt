package com.pkgrove.pkgrovekit.operation.quarkus

import com.pkgrove.pkgrovekit.operation.CategorySurfacePolicy
import com.pkgrove.pkgrovekit.operation.OperationCategory
import com.pkgrove.pkgrovekit.operation.OperationDeclarationException
import com.pkgrove.pkgrovekit.operation.OperationModule
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.SurfacePolicy
import com.pkgrove.pkgrovekit.operation.operationsFrom
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Initialized
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.Config

/**
 * Assembles the application's [OperationRegistry] from CDI-discovered
 * [OperationModule] beans (HEL-602).
 *
 * Framework surfaces are `compileOnly`, as in `pkgrovekit-quarkus`: the
 * consuming application provides CDI and MP Config, and nothing from
 * `jakarta.*` / `io.quarkus` leaks transitively onto a consumer's classpath.
 *
 * The startup observer is the point of the module. Building the registry is what
 * runs the fail-closed checks, and doing it at startup means an undeclared
 * surface, a duplicate MCP tool name or a policy-refused agent exposure FAILS
 * THE BOOT — it is not a 500 on the first production request, and not something
 * a reviewer has to notice.
 */
@ApplicationScoped
public open class OperationRegistryProducer {

    /**
     * The single registry for the application.
     *
     * `@Singleton` rather than `@ApplicationScoped`: the registry is immutable
     * and has no lifecycle, so there is nothing for a client proxy to add, and
     * a direct reference keeps it usable from a non-CDI context (a batch main).
     */
    @Produces
    @Singleton
    public open fun registry(
        modules: Instance<OperationModule>,
        policy: Instance<SurfacePolicy>,
        config: Instance<Config>,
    ): OperationRegistry = build(modules, policy, config)

    /**
     * Runs the fail-closed classification check at startup.
     *
     * Uses the CDI-standard `@Initialized(ApplicationScoped)` event rather than
     * `io.quarkus.runtime.StartupEvent`, so this module needs no `quarkus-core`
     * dependency and the same bean works in any CDI container.
     */
    public open fun validateAtStartup(
        @Observes @Initialized(ApplicationScoped::class) event: Any,
        modules: Instance<OperationModule>,
        policy: Instance<SurfacePolicy>,
        config: Instance<Config>,
    ) {
        val registry = build(modules, policy, config)
        // Not a log line: a deployment that wants its surface table in the boot
        // log can observe the same event after this bean and print
        // registry.classification(). Here, success is silence.
        check(registry.all().isNotEmpty() || !requireOperations(config)) {
            "no operations were declared: no OperationModule bean contributed any, and " +
                "`pkgrovekit.operations.require-operations` is true"
        }
    }

    /**
     * Builds the registry from the discovered beans.
     *
     * Exposed (and `open`) so the startup check and the producer share ONE code
     * path — a startup check that validated something other than what the
     * producer builds would be worse than no check.
     */
    public open fun build(
        modules: Instance<OperationModule>,
        policy: Instance<SurfacePolicy>,
        config: Instance<Config>,
    ): OperationRegistry {
        val declared = modules.toList()
        val resolved = resolvePolicy(policy, config)
        return try {
            operationsFrom(declared, resolved)
        } catch (e: OperationDeclarationException) {
            // Re-thrown with the bean names, because "duplicate MCP tool
            // 'get_plan'" is much cheaper to fix when you know which two modules
            // were in the room.
            throw OperationDeclarationException(
                "${e.message}\n\nContributing OperationModule beans: " +
                    declared.joinToString(", ") { it.javaClass.name }.ifEmpty { "(none)" },
            )
        }
    }

    /**
     * The policy: an application-supplied [SurfacePolicy] bean if there is
     * exactly one, else the categories named by
     * `pkgrovekit.operations.mcp-refused-categories`, else the library default.
     *
     * An AMBIGUOUS policy is a hard failure rather than a silent pick — "which
     * of our two policies decides what agents can reach" is not a question to
     * answer by bean-resolution order.
     */
    internal fun resolvePolicy(policy: Instance<SurfacePolicy>, config: Instance<Config>): SurfacePolicy {
        if (policy.isAmbiguous) {
            throw OperationDeclarationException(
                "more than one SurfacePolicy bean is available — exactly one decides which " +
                    "operations AI clients may reach, so declare a single @Alternative/@Priority winner",
            )
        }
        if (!policy.isUnsatisfied) return policy.get()

        val configured = configValue(config, CATEGORIES_PROPERTY)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: return CategorySurfacePolicy.default

        val categories = configured.map { token ->
            OperationCategory.entries.firstOrNull { it.token == token.lowercase() || it.name == token.uppercase() }
                ?: throw OperationDeclarationException(
                    "$CATEGORIES_PROPERTY names unknown category '$token' " +
                        "(known: ${OperationCategory.entries.joinToString(", ") { it.token }})",
                )
        }.toSet()
        return CategorySurfacePolicy(categories, "configured")
    }

    private fun requireOperations(config: Instance<Config>): Boolean =
        configValue(config, REQUIRE_OPERATIONS_PROPERTY)?.trim()?.lowercase() == "true"

    private fun configValue(config: Instance<Config>, property: String): String? {
        if (config.isUnsatisfied || config.isAmbiguous) return null
        return config.get().getOptionalValue(property, String::class.java).orElse(null)
    }

    public companion object {
        /** Comma-separated [OperationCategory] tokens this deployment refuses for MCP. */
        public const val CATEGORIES_PROPERTY: String = "pkgrovekit.operations.mcp-refused-categories"

        /** `true` to fail startup when no [OperationModule] contributed anything. */
        public const val REQUIRE_OPERATIONS_PROPERTY: String = "pkgrovekit.operations.require-operations"
    }
}
