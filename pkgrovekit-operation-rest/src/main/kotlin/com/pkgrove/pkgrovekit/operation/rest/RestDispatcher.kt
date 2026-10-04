package com.pkgrove.pkgrovekit.operation.rest

import com.pkgrove.pkgrovekit.operation.HttpMethod
import com.pkgrove.pkgrovekit.operation.Json
import com.pkgrove.pkgrovekit.operation.JsonDecodeException
import com.pkgrove.pkgrovekit.operation.Operation
import com.pkgrove.pkgrovekit.operation.OperationError
import com.pkgrove.pkgrovekit.operation.OperationOutcome
import com.pkgrove.pkgrovekit.operation.OperationPipeline
import com.pkgrove.pkgrovekit.operation.OperationRegistry
import com.pkgrove.pkgrovekit.operation.RestBinding
import com.pkgrove.pkgrovekit.operation.RestPathTemplate
import com.pkgrove.pkgrovekit.operation.template
import com.pkgrove.pkgrovekit.operation.Surface
import com.pkgrove.pkgrovekit.operation.SurfaceAnswer
import com.pkgrove.pkgrovekit.operation.ValidationError
import com.pkgrove.pkgrovekit.operation.call
import com.pkgrove.pkgrovekit.operation.renderOutput
import com.pkgrove.pkgrovekit.operation.httpStatus
import com.pkgrove.pkgrovekit.operation.toWire

/** One inbound HTTP call, reduced to what an operation needs (HEL-602). */
public data class RestRequest(
    public val method: HttpMethod,
    /** The CONCRETE request path, e.g. `/v1/plans/p-1/items/i-2/actual`. */
    public val path: String,
    public val headers: Map<String, String> = emptyMap(),
    public val query: Map<String, String> = emptyMap(),
    /** Raw request body; `null` or blank for a bodyless method. */
    public val body: String? = null,
    /**
     * The raw credential, already extracted by the host (a bearer token, a
     * parsed identity). Left to the host because only it knows whether the
     * credential arrives in a header, a cookie or a framework-managed identity.
     */
    public val credential: Any? = null,
    public val correlationId: String = "",
    /** Extra attributes to carry into the audit record (client ip, user agent). */
    public val attributes: Map<String, String> = emptyMap(),
)

/** The response to write back. */
public data class RestResponse(
    public val status: Int,
    public val body: String,
    public val contentType: String = "application/json",
) {
    /** True for 2xx. */
    public val successful: Boolean get() = status in 200..299
}

/**
 * Runs an [OperationRegistry] over an HTTP surface (HEL-602).
 *
 * Framework-free by design: the host owns its JAX-RS/Vert.x/Ktor resource
 * classes and delegates `(method, path, headers, body)` here. A host resource is
 * then a short, uninteresting delegate, and this module pins no REST API version
 * onto the consumer.
 *
 * Only REST-exposed operations are routable. An operation declared `mcpOnly` or
 * `internalOnly` has no binding, so [dispatch] cannot reach it — the same
 * structural guarantee the MCP adapter gives in the other direction.
 */
public class RestDispatcher(
    private val registry: OperationRegistry,
    private val pipeline: OperationPipeline,
    /** Response body renderer for errors; override to match a host's envelope. */
    private val renderError: (OperationError) -> String = { Json.write(it.toWire().toJson()) },
) {

    private val routes: List<Route> = registry.restBindings().map { (binding, op) -> Route(binding, op) }

    /** The routes this dispatcher serves — what a host registers, and a doc source. */
    public fun routes(): List<RestBinding> = routes.map { it.binding }

    /** True when some operation claims [method] + [path]. */
    public fun handles(method: HttpMethod, path: String): Boolean = match(method, path) != null

    /**
     * Resolves the route, merges path/query/body into one payload, runs the
     * pipeline and maps the outcome to a status + body.
     */
    public fun dispatch(request: RestRequest): RestResponse {
        val matched = match(request.method, request.path)
            ?: return error(
                OperationError.NotFound("no operation is bound to ${request.method} ${request.path}"),
            )

        val payload = try {
            mergePayload(request, matched.pathParameters)
        } catch (e: JsonDecodeException) {
            return error(OperationError.Invalid(listOf(e.asValidationError())))
        }

        val outcome = pipeline.call(
            operation = matched.route.operation,
            surface = Surface.REST,
            payload = payload,
            credential = request.credential,
            correlationId = request.correlationId,
            attributes = request.attributes,
        )

        return when (outcome) {
            is OperationOutcome.Failure -> error(outcome.error)
            is OperationOutcome.Success -> RestResponse(
                status = successStatus(matched.route.operation, outcome),
                body = Json.write(matched.route.operation.renderOutput(outcome.output)),
            )
        }
    }

    private fun error(error: OperationError): RestResponse =
        RestResponse(error.httpStatus(), renderError(error))

    /**
     * 201 for a non-idempotent POST that created something, 200 otherwise.
     *
     * A REPLAYED idempotent write returns 200, not 201: the resource was created
     * by the earlier call, and telling a retrying client "created" again is how
     * duplicate-detection logic downstream gets confused.
     */
    private fun successStatus(operation: Operation<*, *>, outcome: OperationOutcome.Success): Int {
        val binding = operation.exposure.restBinding()
        return if (binding?.method == HttpMethod.POST && !outcome.replayed) 201 else 200
    }

    // --- routing -------------------------------------------------------------

    private class Route(val binding: RestBinding, val operation: Operation<*, *>) {
        val template: RestPathTemplate = binding.template()
    }

    private class Matched(val route: Route, val pathParameters: Map<String, String>)

    private fun match(method: HttpMethod, path: String): Matched? {
        for (route in routes) {
            if (route.binding.method != method) continue
            val params = route.template.match(path) ?: continue
            return Matched(route, params)
        }
        return null
    }

    /**
     * Builds the single payload the operation decoder sees.
     *
     * Precedence is body < query < PATH. The path is the most authoritative
     * because it is what the route matched on: a body claiming a different
     * `planId` than the URL must not win, or a caller could address one plan and
     * mutate another.
     */
    private fun mergePayload(request: RestRequest, pathParameters: Map<String, String>): Json.Obj {
        val merged = LinkedHashMap<String, Json>()
        val body = request.body
        if (!body.isNullOrBlank()) {
            when (val parsed = Json.parse(body)) {
                is Json.Obj -> merged.putAll(parsed.fields)
                else -> throw JsonDecodeException(
                    "",
                    "type",
                    "the request body must be a JSON object",
                )
            }
        }
        request.query.forEach { (k, v) -> merged[k] = Json.of(v) }
        pathParameters.forEach { (k, v) -> merged[k] = Json.of(v) }
        return Json.Obj(merged)
    }
}

/**
 * This response as a normalised [SurfaceAnswer], for
 * [com.pkgrove.pkgrovekit.operation.SurfaceContract] — the parity test utility.
 */
public fun RestResponse.asSurfaceAnswer(): SurfaceAnswer = SurfaceAnswer.of(status, body)

/** Validation detail rendering, exposed for a host that keeps its own envelope. */
public fun List<ValidationError>.toJson(): Json.Arr = Json.arr(
    map {
        Json.obj(
            "field" to Json.of(it.field),
            "code" to Json.of(it.code),
            "message" to Json.of(it.message),
        )
    },
)
