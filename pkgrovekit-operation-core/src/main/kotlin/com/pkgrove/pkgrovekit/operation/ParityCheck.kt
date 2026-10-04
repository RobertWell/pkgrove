package com.pkgrove.pkgrovekit.operation

/** What kind of parity problem a [ParityViolation] reports. */
public enum class ParityViolationKind {
    /** The host knows an operation id the registry does not declare. */
    UNREGISTERED_OPERATION,

    /** A tool name is claimed by both the registry and a legacy registry. */
    DUPLICATE_MCP_TOOL,

    /** A REST path is claimed by both the registry and a legacy route table. */
    DUPLICATE_REST_PATH,

    /** A REST_ONLY (or INTERNAL_ONLY) operation appears in an MCP tool listing. */
    RESTRICTED_OPERATION_IN_MCP_LISTING,

    /** An MCP-declared operation is absent from the live MCP tool listing. */
    MISSING_FROM_MCP_LISTING,

    /**
     * A policy refuses an MCP exposure that has no override. Only reachable via
     * a `policy` override — the registry's own construction already enforces its
     * own policy — which is exactly the "we tightened the rules; what breaks?"
     * question.
     */
    POLICY_REFUSED_MCP_EXPOSURE,
}

/** One parity problem, naming its subject. */
public data class ParityViolation(
    public val kind: ParityViolationKind,
    /** The offending operation id, tool name or path. */
    public val subject: String,
    public val detail: String,
) {
    override fun toString(): String = "${kind.name} '$subject': $detail"
}

/**
 * Cross-checks a registry against what a host ACTUALLY serves (HEL-602).
 *
 * The registry's own construction checks keep one registry internally
 * consistent. This utility covers the other half — the period where a new
 * declarative registry COEXISTS with a hand-written legacy MCP tool list and a
 * hand-written REST resource, which is exactly when a REST_ONLY operation can
 * quietly reappear as a legacy agent tool.
 *
 * Intended to run as a test in the consuming application's CI:
 *
 * ```kotlin
 * @Test
 * fun `surfaces are consistent`() {
 *     val violations = ParityCheck.run(
 *         registry = ops,
 *         liveMcpToolNames = mcpServer.toolNames(),
 *         legacyMcpToolNames = LegacyTools.names,
 *         legacyRestPaths = LegacyResources.paths,
 *     )
 *     assertTrue(violations.isEmpty()) { violations.joinToString("\n") }
 * }
 * ```
 */
public object ParityCheck {

    /**
     * Returns every parity violation, or an empty list when the surfaces agree.
     *
     * @param registry the declarative registry.
     * @param liveMcpToolNames tool names the host's MCP server actually lists;
     *   `null` to skip the live-listing checks.
     * @param legacyMcpToolNames tool names served by a pre-existing, hand-written
     *   MCP registry that still coexists with this one.
     * @param legacyRestPaths `METHOD /path` strings served by hand-written REST
     *   resources.
     * @param knownOperationIds operation ids the host believes exist (e.g. from
     *   its own documentation or client SDK), checked for registration.
     * @param policy a policy to re-evaluate the MCP exposures against, instead
     *   of the one the registry was built with. The way to ask "if we tighten
     *   the policy, what stops being agent-callable?" WITHOUT first shipping a
     *   build that refuses to start.
     */
    public fun run(
        registry: OperationRegistry,
        liveMcpToolNames: Collection<String>? = null,
        legacyMcpToolNames: Collection<String> = emptyList(),
        legacyRestPaths: Collection<String> = emptyList(),
        knownOperationIds: Collection<String> = emptyList(),
        policy: SurfacePolicy = registry.policy,
    ): List<ParityViolation> {
        val violations = mutableListOf<ParityViolation>()

        knownOperationIds.filter { registry.byId(it) == null }.forEach { id ->
            violations += ParityViolation(
                ParityViolationKind.UNREGISTERED_OPERATION,
                id,
                "the host expects operation '$id' but the registry does not declare it",
            )
        }

        val declaredTools = registry.mcpToolNames().toSet()
        legacyMcpToolNames.filter { it in declaredTools }.forEach { tool ->
            violations += ParityViolation(
                ParityViolationKind.DUPLICATE_MCP_TOOL,
                tool,
                "tool '$tool' is served by BOTH the declarative registry and the legacy MCP registry — " +
                    "an agent would see two tools with one name",
            )
        }

        val declaredPaths = registry.restBindings().map { it.first.toString() }.toSet()
        legacyRestPaths.map { it.trim() }.filter { it in declaredPaths }.forEach { path ->
            violations += ParityViolation(
                ParityViolationKind.DUPLICATE_REST_PATH,
                path,
                "route '$path' is served by BOTH the declarative registry and a legacy resource",
            )
        }

        // The security-critical direction: anything withheld from MCP must not
        // be reachable through MCP by any other route.
        if (liveMcpToolNames != null) {
            val live = liveMcpToolNames.toSet()
            registry.all()
                .filter { Surface.MCP !in it.exposure.surfaces }
                .forEach { op ->
                    // A withheld operation has no tool name of its own, so the
                    // cross-check is on its id and on its REST path tail — the two
                    // shapes a hand-written tool for it would plausibly carry.
                    val suspects = listOfNotNull(
                        op.id,
                        op.id.replace('.', '_'),
                        op.exposure.restBinding()?.path?.trimEnd('/')?.substringAfterLast('/'),
                    )
                    suspects.filter { it in live }.forEach { leak ->
                        violations += ParityViolation(
                            ParityViolationKind.RESTRICTED_OPERATION_IN_MCP_LISTING,
                            op.id,
                            "operation '${op.id}' is ${op.exposure.token()} " +
                                "(reason: ${op.exposure.reason}) but the live MCP listing " +
                                "contains '$leak'",
                        )
                    }
                }

            (declaredTools - live).forEach { tool ->
                violations += ParityViolation(
                    ParityViolationKind.MISSING_FROM_MCP_LISTING,
                    tool,
                    "tool '$tool' is declared for MCP but the live listing does not contain it",
                )
            }
        }

        registry.all()
            .filter { Surface.MCP in it.exposure.surfaces && it.mcpPolicyOverrideReason == null }
            .forEach { op ->
                policy.refuseMcp(op)?.let { refusal ->
                    violations += ParityViolation(
                        ParityViolationKind.POLICY_REFUSED_MCP_EXPOSURE,
                        op.id,
                        refusal,
                    )
                }
            }

        return violations
    }

    /**
     * Throws when [run] finds anything. The form to call from a CI test that
     * should read as an assertion.
     */
    public fun assertConsistent(
        registry: OperationRegistry,
        liveMcpToolNames: Collection<String>? = null,
        legacyMcpToolNames: Collection<String> = emptyList(),
        legacyRestPaths: Collection<String> = emptyList(),
        knownOperationIds: Collection<String> = emptyList(),
        policy: SurfacePolicy = registry.policy,
    ) {
        val violations = run(
            registry,
            liveMcpToolNames,
            legacyMcpToolNames,
            legacyRestPaths,
            knownOperationIds,
            policy,
        )
        if (violations.isNotEmpty()) {
            throw OperationDeclarationException(
                "Surface parity check failed — ${violations.size} violation(s):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
