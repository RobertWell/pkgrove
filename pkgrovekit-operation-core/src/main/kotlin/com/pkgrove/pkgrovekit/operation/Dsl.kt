package com.pkgrove.pkgrovekit.operation

import kotlin.reflect.KProperty1

/**
 * Declares an [OperationRegistry] (HEL-602).
 *
 * The whole contract of one operation — id, kind, surfaces, scopes, validation,
 * idempotency, handler — fits on one screen, and the surfaces are declared
 * rather than implied:
 *
 * ```kotlin
 * val ops = operations {
 *     decoder<GetPlan> { GetPlan(it.string("planId")) }
 *     encoder<PlanDetail> { Json.obj("planId" to Json.of(it.planId)) }
 *
 *     read<GetPlan, PlanDetail>("plan.get") {
 *         both { rest GET "/v1/plans/{planId}"; mcp("get_plan") }
 *         requires("plans:read")
 *         handle(plans::get)
 *     }
 *
 *     write<SetActual, MutationResponse>("actual.set") {
 *         both { rest POST "/v1/plans/{planId}/items/{itemId}/actual"; mcp("set_item_actual") }
 *         requires("plans:write")
 *         validate { field(SetActual::state) { required() } }
 *         idempotent(SetActual::opId)
 *         handle(actualTrip::setActual)
 *     }
 *
 *     write<RotateCreds, Unit>("admin.credentials.rotate") {
 *         restOnly(reason = "Sensitive admin operation must not be callable by AI/MCP clients") {
 *             rest POST "/v1/admin/credentials/rotate"
 *         }
 *         requires("admin")
 *         handle(admin::rotate)
 *     }
 * }
 * ```
 *
 * The declaration is VALIDATED here, not at first call: an undeclared surface, a
 * duplicate id/path/tool name, a blank reason or a policy-refused MCP exposure
 * throws before the registry exists.
 */
public fun operations(
    policy: SurfacePolicy = CategorySurfacePolicy.default,
    declare: OperationsBuilder.() -> Unit,
): OperationRegistry = OperationRegistry.of(OperationsBuilder().apply(declare).operations(), policy)

/**
 * A unit of operation declaration that can be discovered and composed — the
 * seam the Quarkus adapter wires CDI beans into, and the way a large app splits
 * its catalogue by bounded context instead of one 2000-line file.
 */
public fun interface OperationModule {
    /** Contributes this module's operations (and codecs) to [builder]. */
    public fun contribute(builder: OperationsBuilder)
}

/** Assembles one registry from [modules], applying [policy] to the whole set. */
public fun operationsFrom(
    modules: Iterable<OperationModule>,
    policy: SurfacePolicy = CategorySurfacePolicy.default,
): OperationRegistry = operations(policy) { modules.forEach { it.contribute(this) } }

/** Collects operations and codecs. See [operations]. */
public class OperationsBuilder internal constructor() {

    @PublishedApi
    internal val declared: MutableList<Operation<*, *>> = mutableListOf()

    @PublishedApi
    internal val decoders: MutableMap<Class<*>, (Json) -> Any> = LinkedHashMap()

    @PublishedApi
    internal val encoders: MutableMap<Class<*>, (Any?) -> Json> = LinkedHashMap()

    internal fun operations(): List<Operation<*, *>> = declared.toList()

    /**
     * Registers how input type [T] is read from a transport payload.
     *
     * Core is zero-dependency, so there is no reflective binder: decoding is one
     * explicit line per input type, shared by every operation that takes it (and
     * by both surfaces). Field-attributing accessors like [string] / [int] turn
     * a malformed payload into an `invalid` error naming the field.
     */
    public inline fun <reified T : Any> decoder(noinline decode: (Json.Obj) -> T) {
        decoders[T::class.java] = { payload -> decode(payload.asObject()) }
    }

    /** Registers how output type [T] is rendered to a transport payload. */
    @Suppress("UNCHECKED_CAST")
    public inline fun <reified T : Any> encoder(noinline encode: (T) -> Json) {
        encoders[T::class.java] = { value -> encode(value as T) }
    }

    /** Declares a side-effect-free operation. */
    public inline fun <reified I : Any, reified O : Any> read(
        id: String,
        noinline declare: OperationBuilder<I, O>.() -> Unit,
    ): Unit = add(OperationKind.READ, id, I::class.java, O::class.java, declare)

    /** Declares a state-mutating operation. */
    public inline fun <reified I : Any, reified O : Any> write(
        id: String,
        noinline declare: OperationBuilder<I, O>.() -> Unit,
    ): Unit = add(OperationKind.WRITE, id, I::class.java, O::class.java, declare)

    @PublishedApi
    internal fun <I : Any, O : Any> add(
        kind: OperationKind,
        id: String,
        inputType: Class<I>,
        outputType: Class<O>,
        declare: OperationBuilder<I, O>.() -> Unit,
    ) {
        declared += OperationBuilder(id, kind, inputType, outputType)
            .apply(declare)
            .build(decoders, encoders)
    }
}

/** Declares one operation. See [operations] for the shape. */
public class OperationBuilder<I : Any, O : Any> internal constructor(
    private val id: String,
    private val kind: OperationKind,
    private val inputType: Class<I>,
    private val outputType: Class<O>,
) {
    private var exposure: SurfaceExposure? = null
    private var exposureDeclarations = 0
    private var scopes: Set<String> = emptySet()
    private var categories: Set<OperationCategory> = emptySet()
    private var overrideReason: String? = null
    private var status: OperationStatus = OperationStatus.STABLE
    private var description: String = ""
    private var tags: Set<String> = emptySet()
    private var inputSchema: OperationSchema = OperationSchema.none
    private var outputSchema: OperationSchema = OperationSchema.none
    private val examples = mutableListOf<OperationExample>()
    private var decoderOverride: ((Json) -> I)? = null
    private var encoderOverride: ((O) -> Json)? = null
    private var validationScope: ValidationScope<I>? = null
    private var idempotencyKey: ((I) -> String?)? = null
    private var handler: ((CallerContext, I) -> O)? = null
    private var audited = true
    private var metered = true

    // --- surface classification (exactly one, no default) -------------------

    /** Exposed on REST and MCP. */
    public fun both(declare: SurfaceScope.() -> Unit) {
        val scope = SurfaceScope().apply(declare)
        val rest = scope.restOrNull()
            ?: fail("both { } requires a REST binding, e.g. `rest GET \"/v1/plans/{planId}\"`")
        val mcp = scope.mcpOrNull()
            ?: fail("both { } requires an MCP binding, e.g. `mcp(\"get_plan\")`")
        declareExposure(SurfaceExposure.Both(rest, mcp))
    }

    /** REST only — deliberately not callable by an AI/MCP client. */
    public fun restOnly(reason: String, declare: SurfaceScope.() -> Unit) {
        val scope = SurfaceScope().apply(declare)
        val rest = scope.restOrNull() ?: fail("restOnly { } requires a REST binding")
        if (scope.mcpOrNull() != null) fail("restOnly { } must not declare an MCP binding")
        declareExposure(SurfaceExposure.RestOnly(rest, reason))
    }

    /** MCP only — an agent-specific helper with no HTTP route. */
    public fun mcpOnly(reason: String, declare: SurfaceScope.() -> Unit) {
        val scope = SurfaceScope().apply(declare)
        val mcp = scope.mcpOrNull() ?: fail("mcpOnly { } requires an MCP binding")
        if (scope.restOrNull() != null) fail("mcpOnly { } must not declare a REST binding")
        declareExposure(SurfaceExposure.McpOnly(mcp, reason))
    }

    /** Neither surface — in-process callers only. */
    public fun internalOnly(reason: String) {
        declareExposure(SurfaceExposure.InternalOnly(reason))
    }

    // --- authorization, metadata, behaviour ---------------------------------

    /** Operation-level scopes/permissions the caller must hold. */
    public fun requires(vararg scopes: String) {
        val set = scopes.toSet()
        require(set.none { it.isBlank() }) { "operation '$id': a required scope must not be blank" }
        this.scopes = this.scopes + set
    }

    /** Risk categories a [SurfacePolicy] reasons about. */
    public fun categories(vararg categories: OperationCategory) {
        this.categories = this.categories + categories.toSet()
    }

    /**
     * Explicitly overrides a [SurfacePolicy] MCP refusal, with a reason. The
     * reason is mandatory: an override nobody can explain is a defect.
     */
    public fun mcpOverride(reason: String) {
        require(reason.isNotBlank()) { "operation '$id': mcpOverride(reason = ...) requires a non-blank reason" }
        overrideReason = reason
    }

    /** Lifecycle status (default [OperationStatus.STABLE]). */
    public fun status(status: OperationStatus) {
        this.status = status
    }

    /** Human description — becomes the MCP tool description an agent reads. */
    public fun describe(description: String) {
        this.description = description
    }

    /** Free-form tags for grouping in the Gallery. */
    public fun tags(vararg tags: String) {
        this.tags = this.tags + tags.toSet()
    }

    /** Declares the input schema (MCP `inputSchema`, Gallery docs). */
    public fun input(description: String = "", declare: SchemaScope.() -> Unit) {
        inputSchema = OperationSchema(SchemaScope().apply(declare).fields(), description)
    }

    /** Declares the output schema. */
    public fun output(description: String = "", declare: SchemaScope.() -> Unit) {
        outputSchema = OperationSchema(SchemaScope().apply(declare).fields(), description)
    }

    /** A worked example for the Gallery descriptor. */
    public fun example(title: String, input: Json, output: Json? = null) {
        examples += OperationExample(title, input, output)
    }

    /** Operation-specific decoder, overriding the registry-wide one for [I]. */
    public fun decode(decode: (Json.Obj) -> I) {
        decoderOverride = { payload -> decode(payload.asObject()) }
    }

    /** Operation-specific encoder, overriding the registry-wide one for [O]. */
    public fun encode(encode: (O) -> Json) {
        encoderOverride = encode
    }

    /** Transport-neutral validation rules. See [ValidationScope]. */
    public fun validate(declare: ValidationScope<I>.() -> Unit) {
        validationScope = ValidationScope<I>().apply(declare)
    }

    /**
     * Marks the operation idempotent, keyed on [property] (a client-supplied
     * operation id). A repeat with the same key replays the first outcome
     * instead of applying the mutation twice — which matters most for MCP, where
     * a retrying agent is the normal case, not the exception.
     */
    public fun idempotent(property: KProperty1<I, Any?>) {
        idempotencyKey = { input -> property.get(input)?.toString() }
    }

    /** Marks the operation idempotent with a computed key. */
    public fun idempotentBy(extract: (I) -> String?) {
        idempotencyKey = extract
    }

    /** Whether to emit an audit record (default `true`). */
    public fun audited(audited: Boolean) {
        this.audited = audited
    }

    /** Whether to emit metrics (default `true`). */
    public fun metered(metered: Boolean) {
        this.metered = metered
    }

    /** The domain behaviour: `(caller, input) -> output`. */
    public fun handle(handler: (CallerContext, I) -> O) {
        this.handler = handler
    }

    /** The domain behaviour for handlers that do not need the caller. */
    public fun handleInput(handler: (I) -> O) {
        this.handler = { _, input -> handler(input) }
    }

    // --- build ---------------------------------------------------------------

    private fun declareExposure(exposure: SurfaceExposure) {
        exposureDeclarations++
        if (exposureDeclarations > 1) {
            fail(
                "declares its surface $exposureDeclarations times — exactly ONE of " +
                    "both { } / restOnly(..) { } / mcpOnly(..) { } / internalOnly(..) is allowed",
            )
        }
        this.exposure = exposure
    }

    private fun fail(why: String): Nothing = throw OperationDeclarationException("operation '$id' $why")

    @Suppress("UNCHECKED_CAST")
    internal fun build(
        decoders: Map<Class<*>, (Json) -> Any>,
        encoders: Map<Class<*>, (Any?) -> Json>,
    ): Operation<I, O> {
        require(id.isNotBlank()) { "an operation id must not be blank" }
        if (!ID.matches(id)) {
            fail("has an invalid id — ids are dot-separated lower-case segments, e.g. 'plan.get'")
        }
        val exposure = this.exposure
            ?: fail(
                "declares NO surface. Every operation must declare exactly one of " +
                    "both { } / restOnly(reason) { } / mcpOnly(reason) { } / internalOnly(reason) — " +
                    "there is no default, by design",
            )
        val handler = this.handler ?: fail("declares no handle { } — there is nothing to execute")

        val validation = validationScope
        val validator: (I) -> List<ValidationError> = validation?.build() ?: { emptyList() }
        if (validation != null && inputSchema.fields.isNotEmpty()) {
            val unknown = validation.referencedFields() - inputSchema.names()
            if (unknown.isNotEmpty()) {
                fail(
                    "validates field(s) ${unknown.sorted()} that its declared input schema does not " +
                        "contain ${inputSchema.names().sorted()} — the schema and the rules have drifted",
                )
            }
        }

        val decoder: ((Json) -> I)? = decoderOverride
            ?: (decoders[inputType] as ((Json) -> I)?)
            ?: if (inputType == Unit::class.java) ({ _ -> Unit as I }) else null
        val encoder: ((O) -> Json)? = encoderOverride
            ?: (encoders[outputType] as ((O) -> Json)?)
            ?: if (outputType == Unit::class.java) ({ _ -> Json.emptyObject }) else null

        // Fail-closed codecs: an operation reachable from a transport that cannot
        // read its input (or render its output) would fail at CALL time, in
        // production, on the first agent request. Catch it at construction.
        val remote = exposure.restBinding() != null || exposure.mcpBinding() != null
        if (remote && decoder == null) {
            fail(
                "is exposed on a transport but has no decoder for ${inputType.simpleName} — " +
                    "add `decoder<${inputType.simpleName}> { }` to the registry or `decode { }` here",
            )
        }
        if (remote && encoder == null) {
            fail(
                "is exposed on a transport but has no encoder for ${outputType.simpleName} — " +
                    "add `encoder<${outputType.simpleName}> { }` to the registry or `encode { }` here",
            )
        }

        return Operation(
            id = id,
            kind = kind,
            exposure = exposure,
            inputTypeName = inputType.simpleName,
            outputTypeName = outputType.simpleName,
            requiredScopes = scopes,
            categories = categories,
            mcpPolicyOverrideReason = overrideReason,
            status = status,
            description = description,
            tags = tags,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            examples = examples.toList(),
            decoder = decoder,
            encoder = encoder,
            validator = validator,
            validationSummary = validation?.summaryLines() ?: emptyList(),
            idempotencyKey = idempotencyKey,
            handler = handler,
            audited = audited,
            metered = metered,
        )
    }

    private companion object {
        val ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*")
    }
}

/**
 * Declares the REST and MCP bindings of one operation.
 *
 * The method words are MEMBER infix extensions on [RestSlot], so
 * `rest GET "/v1/plans/{planId}"` reads as the issue specifies and needs no
 * import at the call site — they exist only inside a surface block.
 */
public class SurfaceScope internal constructor() {

    private var restBinding: RestBinding? = null
    private var mcpBinding: McpBinding? = null

    internal fun restOrNull(): RestBinding? = restBinding

    internal fun mcpOrNull(): McpBinding? = mcpBinding

    /** Receiver for the infix method forms: `rest GET "/v1/plans/{planId}"`. */
    public val rest: RestSlot get() = RestSlot

    /** Receiver for the infix tool form: `mcp tool "get_plan"`. */
    public val mcp: McpSlot get() = McpSlot

    /** Explicit REST binding, for callers who prefer named arguments. */
    public fun rest(method: HttpMethod, path: String) {
        bindRest(RestBinding(method, path))
    }

    /** MCP tool binding, with the description an agent reads. */
    public fun mcp(toolName: String, description: String = "") {
        bindMcp(McpBinding(toolName, description))
    }

    /** `rest GET "/v1/plans/{planId}"`. */
    public infix fun RestSlot.GET(path: String): Unit = bindRest(RestBinding(HttpMethod.GET, path))

    /** `rest POST "/v1/plans"`. */
    public infix fun RestSlot.POST(path: String): Unit = bindRest(RestBinding(HttpMethod.POST, path))

    /** `rest PUT "/v1/plans/{planId}"`. */
    public infix fun RestSlot.PUT(path: String): Unit = bindRest(RestBinding(HttpMethod.PUT, path))

    /** `rest PATCH "/v1/plans/{planId}"`. */
    public infix fun RestSlot.PATCH(path: String): Unit = bindRest(RestBinding(HttpMethod.PATCH, path))

    /** `rest DELETE "/v1/plans/{planId}"`. */
    public infix fun RestSlot.DELETE(path: String): Unit = bindRest(RestBinding(HttpMethod.DELETE, path))

    /**
     * `mcp tool "get_plan"` — the closest valid Kotlin to the issue's
     * `mcp "get_plan"` (an infix call needs an explicit receiver).
     * `mcp("get_plan")` is equivalent and takes a description.
     */
    public infix fun McpSlot.tool(toolName: String): Unit = bindMcp(McpBinding(toolName))

    private fun bindRest(binding: RestBinding) {
        check(restBinding == null) { "a surface block declares at most one REST binding (already $restBinding)" }
        restBinding = binding
    }

    private fun bindMcp(binding: McpBinding) {
        check(mcpBinding == null) { "a surface block declares at most one MCP binding (already $mcpBinding)" }
        mcpBinding = binding
    }
}

/** Infix receiver for REST method bindings — only meaningful inside [SurfaceScope]. */
public object RestSlot

/** Infix receiver for the MCP tool binding — only meaningful inside [SurfaceScope]. */
public object McpSlot

/** Declares schema fields. */
public class SchemaScope internal constructor() {
    private val fields = mutableListOf<SchemaField>()

    internal fun fields(): List<SchemaField> = fields.toList()

    /** One field of the schema. */
    public fun field(
        name: String,
        type: String,
        required: Boolean = false,
        description: String = "",
        allowed: List<String> = emptyList(),
    ) {
        fields += SchemaField(name, type, required, description, allowed)
    }
}

/**
 * A declaration-time defect: an undeclared surface, a duplicate binding, a blank
 * reason, a missing handler/codec, a policy refusal.
 *
 * Thrown while BUILDING the registry — which is why the Quarkus adapter can turn
 * it into a boot failure instead of a 500 on the first production request.
 */
public class OperationDeclarationException(message: String) : IllegalStateException(message)
