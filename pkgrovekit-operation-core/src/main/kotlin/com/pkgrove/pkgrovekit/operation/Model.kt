package com.pkgrove.pkgrovekit.operation

/** READ operations are side-effect-free; WRITE operations mutate state. */
public enum class OperationKind {
    READ,
    WRITE,
    ;

    public val token: String get() = name.lowercase()
}

/** Lifecycle status, surfaced in the Gallery descriptor and in MCP descriptions. */
public enum class OperationStatus {
    EXPERIMENTAL,
    STABLE,
    DEPRECATED,
    ;

    public val token: String get() = name.lowercase()
}

/** HTTP methods a REST binding may use. */
public enum class HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

/** A REST binding: one method + one path template (`/v1/plans/{planId}`). */
public data class RestBinding(
    public val method: HttpMethod,
    public val path: String,
) {
    init {
        require(path.startsWith("/")) { "REST path must start with '/': '$path'" }
        require(!path.endsWith("/") || path == "/") { "REST path must not end with '/': '$path'" }
    }

    /** `GET /v1/plans/{planId}` — the form used in error messages and docs. */
    override fun toString(): String = "$method $path"

    /** The `{name}` path parameters this template declares, in order. */
    public fun pathParameters(): List<String> = RestPathTemplate(path).parameters
}

/** An MCP binding: the tool name an agent calls, plus the description it reads. */
public data class McpBinding(
    public val toolName: String,
    public val description: String = "",
) {
    init {
        require(toolName.isNotBlank()) { "MCP tool name must not be blank" }
        require(TOOL_NAME.matches(toolName)) {
            "MCP tool name '$toolName' must match ${TOOL_NAME.pattern} " +
                "(agents address tools by a snake_case identifier)"
        }
    }

    internal companion object {
        val TOOL_NAME: Regex = Regex("[a-z][a-z0-9_]*")
    }
}

/**
 * The FAIL-CLOSED surface classification (HEL-602).
 *
 * Every operation declares exactly one of these. There is NO default: the
 * registry refuses to build an operation that declared none. The reason strings
 * on the restricted variants are mandatory and non-blank, because the point of
 * the mechanism is that six months later someone can read WHY an operation is
 * invisible to AI clients without excavating the git history.
 */
public sealed class SurfaceExposure {

    /** The surfaces this classification exposes the operation on. */
    public abstract val surfaces: Set<Surface>

    /** Why the operation is restricted, or `null` for [Both]. */
    public abstract val reason: String?

    /** Exposed on REST and MCP. The ordinary case. */
    public data class Both(
        public val rest: RestBinding,
        public val mcp: McpBinding,
    ) : SurfaceExposure() {
        override val surfaces: Set<Surface> get() = setOf(Surface.REST, Surface.MCP)
        override val reason: String? get() = null
    }

    /** REST only — deliberately NOT callable by an AI/MCP client. */
    public data class RestOnly(
        public val rest: RestBinding,
        override val reason: String,
    ) : SurfaceExposure() {
        init {
            requireReason(reason, "restOnly")
        }

        override val surfaces: Set<Surface> get() = setOf(Surface.REST)
    }

    /** MCP only — an agent-specific helper with no HTTP route. */
    public data class McpOnly(
        public val mcp: McpBinding,
        override val reason: String,
    ) : SurfaceExposure() {
        init {
            requireReason(reason, "mcpOnly")
        }

        override val surfaces: Set<Surface> get() = setOf(Surface.MCP)
    }

    /** Neither surface — in-process callers only (batch jobs, schedulers). */
    public data class InternalOnly(
        override val reason: String,
    ) : SurfaceExposure() {
        init {
            requireReason(reason, "internalOnly")
        }

        override val surfaces: Set<Surface> get() = setOf(Surface.INTERNAL)
    }

    /** The REST binding, when this classification has one. */
    public fun restBinding(): RestBinding? = when (this) {
        is Both -> rest
        is RestOnly -> rest
        else -> null
    }

    /** The MCP binding, when this classification has one. */
    public fun mcpBinding(): McpBinding? = when (this) {
        is Both -> mcp
        is McpOnly -> mcp
        else -> null
    }

    /** Short token used in descriptors and CI reports. */
    public fun token(): String = when (this) {
        is Both -> "both"
        is RestOnly -> "rest_only"
        is McpOnly -> "mcp_only"
        is InternalOnly -> "internal_only"
    }

    internal companion object {
        fun requireReason(reason: String, declaration: String) {
            require(reason.isNotBlank()) {
                "$declaration(reason = ...) requires a NON-BLANK reason — the reason is the " +
                    "whole point of restricting a surface"
            }
        }
    }
}

/**
 * Risk categories a [SurfacePolicy] reasons about.
 *
 * Tagging is declarative; the refusal is the policy's. This is how "sensitive
 * admin operations must not be callable by AI clients" becomes a rule enforced
 * at registry-construction time rather than a code-review habit.
 */
public enum class OperationCategory {
    /** Rotates or issues credentials/secrets. */
    CREDENTIAL_ROTATION,

    /** Privileged administration: tenancy, entitlements, feature flags. */
    PRIVILEGED_ADMIN,

    /** An OAuth/OIDC redirect or token-exchange endpoint. */
    OAUTH_CALLBACK,

    /** Receives third-party webhooks (signature-verified, not scope-verified). */
    WEBHOOK_RECEIVER,

    /** Streams or accepts binary payloads that do not fit a tool result. */
    BINARY_TRANSFER,

    /** Irreversible deletion of user-visible data. */
    DESTRUCTIVE_DELETE,

    /** Bulk export of personal or financial data. */
    BULK_EXPORT,
    ;

    public val token: String get() = name.lowercase()
}

/** One worked example, shown in the Gallery descriptor. */
public data class OperationExample(
    public val title: String,
    public val input: Json,
    public val output: Json? = null,
)

/** One declared field of an operation's input/output schema. */
public data class SchemaField(
    public val name: String,
    /** JSON type: `string`, `number`, `integer`, `boolean`, `object`, `array`. */
    public val type: String,
    public val required: Boolean = false,
    public val description: String = "",
    /** Allowed values, when the field is an enumeration. */
    public val allowed: List<String> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "a schema field needs a name" }
        require(type in TYPES) { "schema field '$name' has unknown type '$type' (expected one of $TYPES)" }
    }

    internal companion object {
        val TYPES: Set<String> = setOf("string", "number", "integer", "boolean", "object", "array", "null")
    }
}

/**
 * A JSON-Schema-shaped description of an operation's input or output.
 *
 * Deliberately NOT derived by reflection: `pkgrovekit-operation-core` is
 * zero-dependency and `kotlin-reflect` is not on this repository's locked
 * classpath, so a schema is DECLARED in the DSL. The declaration is checked
 * against the validation rules at construction time, which catches the usual
 * drift (a rule on a field the schema never mentions).
 */
public data class OperationSchema(
    public val fields: List<SchemaField> = emptyList(),
    public val description: String = "",
) {
    /** Field names, for the cross-check against validation rules. */
    public fun names(): Set<String> = fields.map { it.name }.toSet()

    /** JSON-Schema `object` rendering — exactly what an MCP `inputSchema` needs. */
    public fun toJsonSchema(): Json.Obj {
        val properties = LinkedHashMap<String, Json>()
        fields.forEach { f ->
            val prop = LinkedHashMap<String, Json>()
            prop["type"] = Json.of(f.type)
            if (f.description.isNotEmpty()) prop["description"] = Json.of(f.description)
            if (f.allowed.isNotEmpty()) prop["enum"] = Json.arr(f.allowed.map { Json.of(it) })
            properties[f.name] = Json.Obj(prop)
        }
        val out = LinkedHashMap<String, Json>()
        out["type"] = Json.of("object")
        if (description.isNotEmpty()) out["description"] = Json.of(description)
        out["properties"] = Json.Obj(properties)
        out["required"] = Json.arr(fields.filter { it.required }.map { Json.of(it.name) })
        return Json.Obj(out)
    }

    public companion object {
        /** The schema of an operation that takes (or returns) no payload. */
        public val none: OperationSchema = OperationSchema()
    }
}

/**
 * One declared operation: the single place its identity, surfaces, authorization,
 * validation, idempotency, handler and metadata live (HEL-602).
 *
 * Built only through the [operations] DSL — the constructor is internal because
 * an [OperationRegistry] must be the only way an operation reaches a transport,
 * and the registry is where the fail-closed checks run.
 */
public class Operation<I, O> internal constructor(
    /** Stable, dot-separated id (`plan.get`). Never renamed — clients key on it. */
    public val id: String,
    public val kind: OperationKind,
    public val exposure: SurfaceExposure,
    /** Simple name of the input type, for descriptors. */
    public val inputTypeName: String,
    /** Simple name of the output type, for descriptors. */
    public val outputTypeName: String,
    /** Scopes/permissions the CALLER must hold. Operation-level, not domain-level. */
    public val requiredScopes: Set<String>,
    public val categories: Set<OperationCategory>,
    /** Explicit, reasoned override of a [SurfacePolicy] MCP refusal. */
    public val mcpPolicyOverrideReason: String?,
    public val status: OperationStatus,
    public val description: String,
    public val tags: Set<String>,
    public val inputSchema: OperationSchema,
    public val outputSchema: OperationSchema,
    public val examples: List<OperationExample>,
    /** Transport payload -> typed input. */
    public val decoder: ((Json) -> I)?,
    /** Typed output -> transport payload. */
    public val encoder: ((O) -> Json)?,
    /** Transport-neutral validation. */
    public val validator: (I) -> List<ValidationError>,
    /** Human-readable summary of the validation rules, for the descriptor. */
    public val validationSummary: List<String>,
    /** Optional idempotency-key extractor; `null` means the operation is not idempotent. */
    public val idempotencyKey: ((I) -> String?)?,
    /** The domain behaviour. Everything else in this class exists to protect it. */
    public val handler: (CallerContext, I) -> O,
    public val audited: Boolean,
    public val metered: Boolean,
) {
    /** True when this operation is reachable on [surface]. */
    public fun exposedOn(surface: Surface): Boolean =
        surface == Surface.INTERNAL || surface in exposure.surfaces

    /** Whether an idempotency key can be derived from the input. */
    public val idempotent: Boolean get() = idempotencyKey != null

    override fun toString(): String = "Operation($id, ${kind.token}, ${exposure.token()})"

    @Suppress("UNCHECKED_CAST")
    internal fun decodeErased(payload: Json): Any? =
        (decoder ?: error("operation $id has no decoder")).invoke(payload)

    @Suppress("UNCHECKED_CAST")
    internal fun validateErased(input: Any?): List<ValidationError> = validator(input as I)

    @Suppress("UNCHECKED_CAST")
    internal fun idempotencyKeyErased(input: Any?): String? = idempotencyKey?.invoke(input as I)

    @Suppress("UNCHECKED_CAST")
    internal fun handleErased(caller: CallerContext, input: Any?): Any? = handler(caller, input as I)

    @Suppress("UNCHECKED_CAST")
    internal fun encodeErased(output: Any?): Json =
        (encoder as ((Any?) -> Json)?)?.invoke(output) ?: Json.emptyObject
}

/**
 * Renders a handler output with this operation's encoder — the seam a transport
 * adapter uses, so REST and MCP render the SAME operation output the same way.
 * A `Unit`-returning operation renders `{}`.
 */
public fun Operation<*, *>.renderOutput(output: Any?): Json = encodeErased(output)

/**
 * Decodes a transport payload with this operation's decoder. Normally the
 * pipeline's decode stage does this; exposed for adapters that need the typed
 * input before dispatch (an MCP dry-run, a schema probe).
 */
public fun Operation<*, *>.decodePayload(payload: Json): Any? = decodeErased(payload)
