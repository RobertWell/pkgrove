package com.pkgrove.pkgrovekit.operation

/**
 * Gallery metadata for one operation (HEL-602).
 *
 * Plain Kotlin data with a hand-rolled JSON rendering — core stays
 * zero-dependency, so a consumer's own Jackson/kotlinx version can never
 * conflict with the library's. [toJson] is what a Gallery UI, a generated
 * reference page, or a CI snapshot test consumes.
 *
 * [dslSnippet] is the copyable declaration this descriptor came from. The point
 * is reversibility: someone reading the Gallery can paste the snippet into their
 * own registry and get the same operation, rather than reverse-engineering it
 * from a rendered table.
 */
public data class OperationDescriptor(
    public val id: String,
    public val kind: String,
    public val status: String,
    public val description: String,
    public val tags: List<String>,
    /** `both`, `rest_only`, `mcp_only` or `internal_only`. */
    public val classification: String,
    public val surfaces: List<String>,
    /** Why a surface is withheld; `null` for `both`. */
    public val exclusionReason: String?,
    public val restMethod: String?,
    public val restPath: String?,
    public val mcpToolName: String?,
    public val mcpDescription: String?,
    public val inputTypeName: String,
    public val outputTypeName: String,
    public val inputSchema: OperationSchema,
    public val outputSchema: OperationSchema,
    public val examples: List<OperationExample>,
    public val requiredScopes: List<String>,
    /** Auth profile name per surface token, when the catalogue knows them. */
    public val authProfiles: Map<String, String>,
    public val categories: List<String>,
    /** The policy that was applied, and whether this operation overrode it. */
    public val policyName: String,
    public val mcpPolicyRefusal: String?,
    public val mcpPolicyOverrideReason: String?,
    public val validationRules: List<String>,
    public val idempotent: Boolean,
    public val audited: Boolean,
    public val metered: Boolean,
    public val dslSnippet: String,
) {
    /** JSON-serialisable rendering. */
    public fun toJson(): Json.Obj {
        val out = LinkedHashMap<String, Json>()
        out["id"] = Json.of(id)
        out["kind"] = Json.of(kind)
        out["status"] = Json.of(status)
        out["description"] = Json.of(description)
        out["tags"] = Json.arr(tags.map { Json.of(it) })
        out["classification"] = Json.of(classification)
        out["surfaces"] = Json.arr(surfaces.map { Json.of(it) })
        out["exclusionReason"] = exclusionReason?.let { Json.of(it) } ?: Json.Null
        out["rest"] = if (restPath == null) {
            Json.Null
        } else {
            Json.obj("method" to Json.of(restMethod!!), "path" to Json.of(restPath))
        }
        out["mcp"] = if (mcpToolName == null) {
            Json.Null
        } else {
            Json.obj(
                "name" to Json.of(mcpToolName),
                "description" to Json.of(mcpDescription.orEmpty()),
            )
        }
        out["input"] = Json.obj(
            "type" to Json.of(inputTypeName),
            "schema" to inputSchema.toJsonSchema(),
        )
        out["output"] = Json.obj(
            "type" to Json.of(outputTypeName),
            "schema" to outputSchema.toJsonSchema(),
        )
        out["examples"] = Json.arr(
            examples.map {
                Json.obj(
                    "title" to Json.of(it.title),
                    "input" to it.input,
                    "output" to (it.output ?: Json.Null),
                )
            },
        )
        out["auth"] = Json.obj(
            "requiredScopes" to Json.arr(requiredScopes.map { Json.of(it) }),
            "profiles" to Json.Obj(
                LinkedHashMap<String, Json>().apply {
                    authProfiles.forEach { (surface, name) -> put(surface, Json.of(name)) }
                },
            ),
            "categories" to Json.arr(categories.map { Json.of(it) }),
            "policy" to Json.of(policyName),
            "mcpPolicyRefusal" to (mcpPolicyRefusal?.let { Json.of(it) } ?: Json.Null),
            "mcpPolicyOverrideReason" to (mcpPolicyOverrideReason?.let { Json.of(it) } ?: Json.Null),
        )
        out["validation"] = Json.arr(validationRules.map { Json.of(it) })
        out["capabilities"] = Json.obj(
            "idempotent" to Json.of(idempotent),
            "audited" to Json.of(audited),
            "metered" to Json.of(metered),
        )
        out["dsl"] = Json.of(dslSnippet)
        return Json.Obj(out)
    }
}

/**
 * Builds this operation's Gallery descriptor under [policy].
 *
 * Auth profiles are per-SURFACE, not per operation, so [profiles] is optional
 * and is reported only for the surfaces this operation is actually exposed on —
 * listing a credential an operation cannot be reached with would be worse than
 * listing none.
 */
public fun Operation<*, *>.describe(
    policy: SurfacePolicy = CategorySurfacePolicy.default,
    profiles: AuthProfiles? = null,
): OperationDescriptor {
    val rest = exposure.restBinding()
    val mcp = exposure.mcpBinding()
    return OperationDescriptor(
        id = id,
        kind = kind.token,
        status = status.token,
        description = description,
        tags = tags.sorted(),
        classification = exposure.token(),
        surfaces = exposure.surfaces.map { it.token }.sorted(),
        exclusionReason = exposure.reason,
        restMethod = rest?.method?.name,
        restPath = rest?.path,
        mcpToolName = mcp?.toolName,
        mcpDescription = mcp?.description?.ifEmpty { description },
        inputTypeName = inputTypeName,
        outputTypeName = outputTypeName,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        examples = examples,
        requiredScopes = requiredScopes.sorted(),
        authProfiles = profiles?.names().orEmpty()
            .filterKeys { it in exposure.surfaces }
            .mapKeys { it.key.token },
        categories = categories.map { it.token }.sorted(),
        policyName = policy.name,
        mcpPolicyRefusal = if (mcp != null) policy.refuseMcp(this) else null,
        mcpPolicyOverrideReason = mcpPolicyOverrideReason,
        validationRules = validationSummary,
        idempotent = idempotent,
        audited = audited,
        metered = metered,
        dslSnippet = dslSnippet(),
    )
}

/** Renders the copyable DSL declaration for this operation. */
public fun Operation<*, *>.dslSnippet(): String {
    val sb = StringBuilder()
    val fn = if (kind == OperationKind.READ) "read" else "write"
    sb.append("$fn<$inputTypeName, $outputTypeName>(\"$id\") {\n")
    when (val e = exposure) {
        is SurfaceExposure.Both ->
            sb.append("    both { rest ${e.rest.method} \"${e.rest.path}\"; mcp(\"${e.mcp.toolName}\") }\n")
        is SurfaceExposure.RestOnly ->
            sb.append("    restOnly(reason = \"${e.reason}\") { rest ${e.rest.method} \"${e.rest.path}\" }\n")
        is SurfaceExposure.McpOnly ->
            sb.append("    mcpOnly(reason = \"${e.reason}\") { mcp(\"${e.mcp.toolName}\") }\n")
        is SurfaceExposure.InternalOnly ->
            sb.append("    internalOnly(reason = \"${e.reason}\")\n")
    }
    if (description.isNotEmpty()) sb.append("    describe(\"$description\")\n")
    if (categories.isNotEmpty()) {
        sb.append("    categories(${categories.sortedBy { it.name }.joinToString(", ") { it.name }})\n")
    }
    if (mcpPolicyOverrideReason != null) sb.append("    mcpOverride(reason = \"$mcpPolicyOverrideReason\")\n")
    if (requiredScopes.isNotEmpty()) {
        sb.append("    requires(${requiredScopes.sorted().joinToString(", ") { "\"$it\"" }})\n")
    }
    if (validationSummary.isNotEmpty()) {
        sb.append("    validate { /* ${validationSummary.joinToString("; ")} */ }\n")
    }
    if (idempotent) sb.append("    idempotent(/* client-supplied operation id */)\n")
    if (status != OperationStatus.STABLE) sb.append("    status(OperationStatus.${status.name})\n")
    sb.append("    handle { caller, input -> /* domain behaviour */ }\n")
    sb.append("}")
    return sb.toString()
}

/**
 * The whole catalogue as one JSON document: the per-surface auth profiles, the
 * surface classification table, the withheld operations with their reasons, and
 * every descriptor.
 *
 * This is the artifact to publish (and to snapshot in CI): a reviewer can answer
 * "what can an agent reach, and what did we deliberately keep from it?" by
 * reading one file.
 */
public object OperationCatalog {

    /** Renders [registry]'s catalogue. [profiles] names the credential per surface. */
    public fun toJson(registry: OperationRegistry, profiles: AuthProfiles? = null): Json.Obj {
        val out = LinkedHashMap<String, Json>()
        out["policy"] = Json.of(registry.policy.name)
        out["operationCount"] = Json.of(registry.all().size)
        out["authProfiles"] = Json.Obj(
            LinkedHashMap<String, Json>().apply {
                profiles?.names()?.forEach { (surface, name) -> put(surface.token, Json.of(name)) }
            },
        )
        out["classification"] = Json.Obj(
            LinkedHashMap<String, Json>().apply {
                registry.classification().forEach { (id, token) -> put(id, Json.of(token)) }
            },
        )
        out["exclusions"] = Json.arr(
            registry.exclusions().map {
                Json.obj(
                    "operationId" to Json.of(it.operationId),
                    "classification" to Json.of(it.classification),
                    "reason" to Json.of(it.reason),
                )
            },
        )
        out["mcpTools"] = Json.arr(registry.mcpToolNames().map { Json.of(it) })
        out["operations"] = Json.arr(registry.describe(profiles).map { it.toJson() })
        return Json.Obj(out)
    }

    /** The catalogue as JSON text. */
    public fun toJsonText(registry: OperationRegistry, profiles: AuthProfiles? = null): String =
        Json.write(toJson(registry, profiles))
}
