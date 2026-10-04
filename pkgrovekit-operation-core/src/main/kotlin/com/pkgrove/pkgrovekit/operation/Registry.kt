package com.pkgrove.pkgrovekit.operation

/**
 * The immutable, fail-closed catalogue of declared operations (HEL-602).
 *
 * Construction is the gate. A registry cannot exist with:
 *  * an operation that declared no surface, or declared more than one;
 *  * a blank `reason` on a restricted surface;
 *  * a duplicate operation id;
 *  * a duplicate REST `(method, path)`;
 *  * a duplicate MCP tool name;
 *  * an MCP exposure the [SurfacePolicy] refuses and nobody overrode;
 *  * a REST path template whose `{param}` set is not covered by the operation's
 *    declared input schema (when a schema is declared).
 *
 * Every violation names the offender. Nothing is mutable afterwards: the maps
 * are defensive copies and the operation list is unmodifiable, so a transport
 * adapter cannot be handed a registry and then have an operation appear in it.
 */
public class OperationRegistry private constructor(
    private val ordered: List<Operation<*, *>>,
    private val byId: Map<String, Operation<*, *>>,
    private val byRest: Map<RestBinding, Operation<*, *>>,
    private val byMcpTool: Map<String, Operation<*, *>>,
    /** The policy this registry was built under. */
    public val policy: SurfacePolicy,
) {

    /** Every operation, in declaration order. Unmodifiable. */
    public fun all(): List<Operation<*, *>> = ordered

    /** Lookup by stable id. */
    public fun byId(id: String): Operation<*, *>? = byId[id]

    /** Lookup by REST method + path TEMPLATE (not a concrete request path). */
    public fun byRest(method: HttpMethod, path: String): Operation<*, *>? = byRest[RestBinding(method, path)]

    /** Lookup by MCP tool name. */
    public fun byMcpTool(toolName: String): Operation<*, *>? = byMcpTool[toolName]

    /** Operations reachable on [surface]. */
    public fun exposedOn(surface: Surface): List<Operation<*, *>> = ordered.filter { surface in it.exposure.surfaces }

    /** Every REST binding, in declaration order — what a host registers routes for. */
    public fun restBindings(): List<Pair<RestBinding, Operation<*, *>>> =
        ordered.mapNotNull { op -> op.exposure.restBinding()?.let { it to op } }

    /** Every MCP tool name, in declaration order. */
    public fun mcpToolNames(): List<String> = ordered.mapNotNull { it.exposure.mcpBinding()?.toolName }

    /**
     * The operations deliberately withheld from MCP, with their reasons — the
     * list a security review reads, and the list a CI test can assert against so
     * an exclusion cannot be quietly deleted.
     */
    public fun restOnlyExclusions(): List<SurfaceExclusion> =
        ordered.filter { it.exposure is SurfaceExposure.RestOnly }
            .map { SurfaceExclusion(it.id, "rest_only", it.exposure.reason.orEmpty()) }

    /** Every operation withheld from at least one surface, with its reason. */
    public fun exclusions(): List<SurfaceExclusion> =
        ordered.filter { it.exposure !is SurfaceExposure.Both }
            .map { SurfaceExclusion(it.id, it.exposure.token(), it.exposure.reason.orEmpty()) }

    /** id -> surface classification token, for CI reports and documentation. */
    public fun classification(): Map<String, String> = ordered.associate { it.id to it.exposure.token() }

    /**
     * Gallery metadata for every operation. See [OperationDescriptor].
     *
     * [profiles], when supplied, adds the per-surface auth profile names each
     * operation can actually be reached with.
     */
    public fun describe(profiles: AuthProfiles? = null): List<OperationDescriptor> =
        ordered.map { it.describe(policy, profiles) }

    override fun toString(): String =
        "OperationRegistry(${ordered.size} operations, policy=${policy.name})"

    public companion object {

        /** Builds and VALIDATES a registry. Prefer the [operations] DSL. */
        public fun of(
            operations: List<Operation<*, *>>,
            policy: SurfacePolicy = CategorySurfacePolicy.default,
        ): OperationRegistry {
            val violations = mutableListOf<String>()

            val byId = LinkedHashMap<String, Operation<*, *>>()
            operations.forEach { op ->
                val existing = byId.put(op.id, op)
                if (existing != null) {
                    violations += "DUPLICATE OPERATION ID '${op.id}' is declared more than once"
                }
            }

            val byRest = LinkedHashMap<RestBinding, Operation<*, *>>()
            val byMcp = LinkedHashMap<String, Operation<*, *>>()
            operations.forEach { op ->
                op.exposure.restBinding()?.let { binding ->
                    val existing = byRest.put(binding, op)
                    if (existing != null) {
                        violations += "DUPLICATE REST BINDING '$binding' is claimed by both " +
                            "'${existing.id}' and '${op.id}'"
                    }
                    // A path template parameter the input schema never mentions can
                    // only arrive as a silently-dropped value at request time.
                    if (op.inputSchema.fields.isNotEmpty()) {
                        val missing = binding.pathParameters() - op.inputSchema.names()
                        if (missing.isNotEmpty()) {
                            violations += "REST PATH PARAM '${op.id}' binds '$binding' whose " +
                                "parameter(s) ${missing.sorted()} are absent from its declared input schema"
                        }
                    }
                }
                op.exposure.mcpBinding()?.let { binding ->
                    val existing = byMcp.put(binding.toolName, op)
                    if (existing != null) {
                        violations += "DUPLICATE MCP TOOL '${binding.toolName}' is claimed by both " +
                            "'${existing.id}' and '${op.id}'"
                    }
                }
            }

            // Policy: an MCP-exposed operation in a refused category needs an
            // explicit, reasoned override — otherwise the registry refuses to exist.
            operations.filter { Surface.MCP in it.exposure.surfaces }.forEach { op ->
                val refusal = policy.refuseMcp(op)
                if (refusal != null && op.mcpPolicyOverrideReason == null) {
                    violations += "POLICY REFUSAL '${op.id}' is exposed to MCP but $refusal. " +
                        "Either restrict it (restOnly(reason = ...)) or declare " +
                        "mcpOverride(reason = \"...\") saying why it is safe"
                }
            }

            if (violations.isNotEmpty()) {
                throw OperationDeclarationException(
                    "Operation registry is invalid — ${violations.size} problem(s):\n" +
                        violations.joinToString("\n") { "  - $it" },
                )
            }

            return OperationRegistry(
                ordered = java.util.Collections.unmodifiableList(operations.toList()),
                byId = java.util.Collections.unmodifiableMap(byId),
                byRest = java.util.Collections.unmodifiableMap(byRest),
                byMcpTool = java.util.Collections.unmodifiableMap(byMcp),
                policy = policy,
            )
        }
    }
}

/** One operation deliberately withheld from a surface, and why. */
public data class SurfaceExclusion(
    public val operationId: String,
    /** `rest_only`, `mcp_only` or `internal_only`. */
    public val classification: String,
    public val reason: String,
)
