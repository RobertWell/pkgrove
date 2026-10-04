package com.pkgrove.pkgrovekit.operation

/**
 * A deployment-wide rule about which operations may reach an AI/MCP client
 * (HEL-602).
 *
 * The surface classification answers "what did this operation ask for?"; the
 * policy answers "is the deployment willing to grant it?". Both must agree
 * BEFORE the registry exists, so an operation tagged
 * [OperationCategory.CREDENTIAL_ROTATION] cannot become an MCP tool because
 * someone pasted `both { ... }` from the operation above it.
 */
public interface SurfacePolicy {

    /** Policy name, reported in violation messages and in CI output. */
    public val name: String

    /**
     * Returns the reason this operation must NOT be exposed to MCP, or `null`
     * when the policy has no objection. Called for every MCP-exposed operation
     * at registry-construction time.
     */
    public fun refuseMcp(operation: Operation<*, *>): String?
}

/**
 * The default policy: a short list of categories that are never agent-callable
 * without an explicit, reasoned override.
 *
 * The list is deliberately conservative and deliberately small. It is not a
 * threat model — it is the set of mistakes that are easy to make and expensive
 * to discover, with a declared escape hatch (`mcpOverride(reason = ...)`) for
 * the cases where a human has actually thought about it.
 */
public class CategorySurfacePolicy(
    /** Categories refused for MCP exposure. */
    public val refusedForMcp: Set<OperationCategory>,
    override val name: String = "category-surface-policy",
) : SurfacePolicy {

    override fun refuseMcp(operation: Operation<*, *>): String? {
        val hits = operation.categories.intersect(refusedForMcp)
        if (hits.isEmpty()) return null
        return "category ${hits.sortedBy { it.name }.joinToString(", ") { it.token }} " +
            "is refused for MCP exposure by policy '$name'"
    }

    public companion object {
        /** The categories refused by [default]. */
        public val defaultRefusals: Set<OperationCategory> = setOf(
            OperationCategory.CREDENTIAL_ROTATION,
            OperationCategory.PRIVILEGED_ADMIN,
            OperationCategory.OAUTH_CALLBACK,
            OperationCategory.WEBHOOK_RECEIVER,
            OperationCategory.BINARY_TRANSFER,
        )

        /** The policy applied when a registry does not name one. */
        public val default: CategorySurfacePolicy = CategorySurfacePolicy(defaultRefusals, "default")
    }
}

/** A policy that objects to nothing. For tests and for INTERNAL-only registries. */
public object PermissiveSurfacePolicy : SurfacePolicy {
    override val name: String get() = "permissive"

    override fun refuseMcp(operation: Operation<*, *>): String? = null
}
