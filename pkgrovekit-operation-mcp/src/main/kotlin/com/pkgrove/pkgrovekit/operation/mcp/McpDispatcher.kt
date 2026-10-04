package com.pkgrove.pkgrovekit.operation.mcp

import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.JsonDecodeException
import com.pkgrove.pkgrovekit.operation.Operation
import com.pkgrove.pkgrovekit.operation.OperationDeclarationException
import com.pkgrove.pkgrovekit.operation.OperationError
import com.pkgrove.pkgrovekit.operation.OperationKind
import com.pkgrove.pkgrovekit.operation.OperationOutcome
import com.pkgrove.pkgrovekit.operation.OperationPipeline
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.OperationStatus
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.SurfaceAnswer
import com.pkgrove.pkgrovekit.operation.call
import com.pkgrove.pkgrovekit.operation.httpStatus
import com.pkgrove.pkgrovekit.operation.renderOutput
import com.pkgrove.pkgrovekit.operation.toWire

/** One MCP tool descriptor, ready to render into a `tools/list` result. */
public data class McpToolDescriptor(
    public val name: String,
    public val description: String,
    public val inputSchema: Json.Obj,
    /** The operation id behind the tool — useful in logs, not sent to agents. */
    public val operationId: String,
) {
    /** The `tools/list` entry shape (MCP protocol 2025-06-18). */
    public fun toJson(): Json.Obj = Json.obj(
        "name" to Json.of(name),
        "description" to Json.of(description),
        "inputSchema" to inputSchema,
    )
}

/** The result of calling a tool. */
public sealed class McpCallResult {

    /** The tool ran. [content] is the JSON text to put in the tool result. */
    public data class Ok(
        public val content: String,
        public val replayed: Boolean = false,
    ) : McpCallResult()

    /**
     * The tool failed. Carries the SAME semantic triple the REST adapter
     * renders — code, field, message — plus the equivalent HTTP status, so an
     * agent framework that branches on either sees one API, not two.
     */
    public data class Error(
        public val code: String,
        public val message: String,
        public val field: String?,
        public val httpStatus: Int,
        public val content: String,
    ) : McpCallResult()
}

/**
 * The MCP ALLOW-LIST over an [OperationRegistry] (HEL-602).
 *
 * Only operations that DECLARED an MCP binding are present. A `restOnly` or
 * `internalOnly` operation has no tool name at all, so it cannot appear in
 * [tools] and cannot be looked up by [find] — the exclusion is structural, not a
 * filter someone can forget to apply.
 */
public class McpToolRegistry(private val registry: OperationRegistry) {

    private val byName: Map<String, Operation<*, *>> = LinkedHashMap<String, Operation<*, *>>().apply {
        registry.all().forEach { op ->
            op.exposure.mcpBinding()?.let { binding -> put(binding.toolName, op) }
        }
    }

    /** Every exposed tool, in declaration order. */
    public fun tools(): List<McpToolDescriptor> = byName.map { (name, op) -> descriptorFor(name, op) }

    /** Tool names only. */
    public fun toolNames(): List<String> = byName.keys.toList()

    /** The operation behind [toolName], or `null` when no tool has that name. */
    public fun find(toolName: String): Operation<*, *>? = byName[toolName]

    /**
     * Fails when a name is served by BOTH this registry and a host's pre-existing
     * hand-written tool list.
     *
     * Coexistence is the normal migration state, and two tools with one name is
     * the failure mode: whichever registry the host checks first silently wins,
     * which is a security decision made by accident.
     */
    public fun assertNoOverlap(legacyToolNames: Collection<String>) {
        val overlap = legacyToolNames.filter { it in byName }.sorted()
        if (overlap.isNotEmpty()) {
            throw OperationDeclarationException(
                "MCP tool name collision with the legacy tool registry: ${overlap.joinToString(", ")}. " +
                    "An agent would see two tools with one name; rename one side before shipping both.",
            )
        }
    }

    private fun descriptorFor(name: String, op: Operation<*, *>): McpToolDescriptor {
        val binding = op.exposure.mcpBinding()
        val described = binding?.description?.ifEmpty { op.description }.orEmpty().ifEmpty { op.id }
        // The status and the write/read nature belong in the description an agent
        // READS: an agent choosing between tools has no other channel for "this
        // one mutates state" or "this one is deprecated".
        val prefix = buildString {
            if (op.kind == OperationKind.WRITE) append("[write] ")
            if (op.status != OperationStatus.STABLE) append("[${op.status.token}] ")
        }
        return McpToolDescriptor(
            name = name,
            description = prefix + described,
            inputSchema = op.inputSchema.toJsonSchema(),
            operationId = op.id,
        )
    }
}

/**
 * Runs an [OperationRegistry] over an MCP tool surface (HEL-602).
 *
 * Transport-neutral: it does NOT depend on any MCP server runtime. The host owns
 * its JSON-RPC transport (the first consumer runs its own Streamable-HTTP server
 * at `/mcp`, protocol 2025-06-18) and calls [toolsList] / [handles] / [call].
 * A quarkiverse-mcp-server adapter can be added later as another thin module
 * over this same class.
 *
 * The pipeline is the SAME object the REST adapter uses, so validation,
 * authorization, idempotency and audit behave identically; only the credential
 * (via the MCP [com.pkgrove.pkgrovekit.operation.AuthProfile]) and the envelope
 * differ.
 */
public class McpDispatcher(
    registry: OperationRegistry,
    private val pipeline: OperationPipeline,
) {

    /** The allow-list this dispatcher serves. */
    public val tools: McpToolRegistry = McpToolRegistry(registry)

    /** The `tools/list` payload. */
    public fun toolsList(): List<McpToolDescriptor> = tools.tools()

    /** The `tools/list` payload as a JSON array. */
    public fun toolsListJson(): Json.Arr = Json.arr(toolsList().map { it.toJson() })

    /** True only for a tool this dispatcher actually exposes. */
    public fun handles(name: String): Boolean = tools.find(name) != null

    /** See [McpToolRegistry.assertNoOverlap]. */
    public fun assertNoOverlap(legacyToolNames: Collection<String>): Unit = tools.assertNoOverlap(legacyToolNames)

    /**
     * Calls [name] with the raw `arguments` JSON text an MCP `tools/call` carries.
     *
     * An unknown tool — including every operation deliberately withheld from MCP
     * — returns a `not_found` error and NEVER reaches a handler.
     */
    public fun call(
        name: String,
        argumentsJson: String?,
        rawCredential: Any? = null,
        correlationId: String = "",
        attributes: Map<String, String> = emptyMap(),
    ): McpCallResult {
        val operation = tools.find(name)
            ?: return error(OperationError.NotFound("no MCP tool named '$name' is exposed"))

        val payload = try {
            if (argumentsJson.isNullOrBlank()) Json.emptyObject else parseArguments(argumentsJson)
        } catch (e: JsonDecodeException) {
            return error(OperationError.Invalid(listOf(e.asValidationError())))
        }

        val outcome = pipeline.call(
            operation = operation,
            surface = Surface.MCP,
            payload = payload,
            credential = rawCredential,
            correlationId = correlationId,
            attributes = attributes,
        )

        return when (outcome) {
            is OperationOutcome.Failure -> error(outcome.error)
            is OperationOutcome.Success -> McpCallResult.Ok(
                content = Json.write(operation.renderOutput(outcome.output)),
                replayed = outcome.replayed,
            )
        }
    }

    private fun parseArguments(argumentsJson: String): Json.Obj =
        when (val parsed = Json.parse(argumentsJson)) {
            is Json.Obj -> parsed
            else -> throw JsonDecodeException("", "type", "tool arguments must be a JSON object")
        }

    private fun error(error: OperationError): McpCallResult.Error {
        val wire = error.toWire()
        return McpCallResult.Error(
            code = wire.code,
            message = wire.message,
            field = wire.details.firstOrNull()?.field?.takeIf { it.isNotEmpty() },
            httpStatus = error.httpStatus(),
            content = Json.write(wire.toJson()),
        )
    }
}

/**
 * This result as a normalised [SurfaceAnswer], for
 * [com.pkgrove.pkgrovekit.operation.SurfaceContract] — the parity test utility.
 *
 * A successful tool call carries no status of its own, so it normalises to 200
 * for comparison; a REST `201` on a create therefore shows up as a status
 * difference, which is correct: the HTTP envelope legitimately differs, and a
 * parity test over a create compares the payloads with that in mind.
 */
public fun McpCallResult.asSurfaceAnswer(): SurfaceAnswer = when (this) {
    is McpCallResult.Ok -> SurfaceAnswer.of(200, content)
    is McpCallResult.Error -> SurfaceAnswer.of(httpStatus, content)
}
