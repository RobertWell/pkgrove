package com.pkgrove.pkgrovekit.operation

/**
 * One transport-neutral validation failure (HEL-602).
 *
 * [field] is the input field path (`""` for whole-payload rules), [code] is a
 * STABLE machine token a client may branch on, [message] is human text. The
 * same triple is rendered by the REST adapter and by the MCP adapter, so an
 * agent and a mobile client see the same error for the same mistake.
 */
public data class ValidationError(
    public val field: String,
    public val code: String,
    public val message: String,
)

/**
 * The closed set of ways an operation can fail. Adapters MAP these — they never
 * invent their own statuses — which is why REST and MCP cannot drift apart.
 */
public sealed class OperationError {

    /** The stable machine token for this failure. */
    public abstract val code: String

    /** Human-readable text, safe to return to a caller. */
    public abstract val message: String

    /** No usable credential: the auth profile produced no [CallerContext]. */
    public data class Unauthenticated(
        override val message: String = "no valid credential was presented",
    ) : OperationError() {
        override val code: String get() = "unauthenticated"
    }

    /** Authenticated, but the caller lacks a required scope/permission. */
    public data class Forbidden(
        override val message: String,
        /** Scopes the operation requires that the caller does not hold. */
        public val missingScopes: Set<String> = emptySet(),
    ) : OperationError() {
        override val code: String get() = "forbidden"
    }

    /** The input failed decoding or validation. Always carries the field detail. */
    public data class Invalid(
        public val errors: List<ValidationError>,
        override val message: String = "the request is not valid",
    ) : OperationError() {
        init {
            require(errors.isNotEmpty()) { "OperationError.Invalid requires at least one ValidationError" }
        }

        override val code: String get() = "invalid"
    }

    /** The addressed entity does not exist (or the caller may not know it does). */
    public data class NotFound(
        override val message: String,
    ) : OperationError() {
        override val code: String get() = "not_found"
    }

    /** The request conflicts with current state (version clash, duplicate, ...). */
    public data class Conflict(
        override val message: String,
    ) : OperationError() {
        override val code: String get() = "conflict"
    }

    /** The handler failed. The [cause] is for logs — never for the wire. */
    public data class Failed(
        override val message: String,
        public val cause: Throwable? = null,
    ) : OperationError() {
        override val code: String get() = "failed"
    }
}

/** Raised by a handler to fail the operation with an explicit [OperationError]. */
public class OperationException(public val error: OperationError) : RuntimeException(error.message)

/** Fails the current handler with [OperationError.NotFound]. */
public fun notFound(message: String): Nothing = throw OperationException(OperationError.NotFound(message))

/** Fails the current handler with [OperationError.Conflict]. */
public fun conflict(message: String): Nothing = throw OperationException(OperationError.Conflict(message))

/** Fails the current handler with [OperationError.Forbidden] (domain authorization). */
public fun forbidden(message: String, missingScopes: Set<String> = emptySet()): Nothing =
    throw OperationException(OperationError.Forbidden(message, missingScopes))

/** Fails the current handler with [OperationError.Invalid]. */
public fun invalid(errors: List<ValidationError>): Nothing =
    throw OperationException(OperationError.Invalid(errors))

/**
 * The single wire shape every adapter renders.
 *
 * Centralising it here is the mechanism behind "same semantic code/field/message
 * on REST and MCP": the adapters differ only in the envelope they put this in
 * (HTTP status + body vs MCP tool error content).
 */
public data class WireError(
    public val code: String,
    public val message: String,
    public val details: List<ValidationError>,
) {
    /** Canonical JSON body: `{"error":..,"message":..,"details":[..]}`. */
    public fun toJson(): Json.Obj = Json.obj(
        "error" to Json.of(code),
        "message" to Json.of(message),
        "details" to Json.arr(
            details.map {
                Json.obj(
                    "field" to Json.of(it.field),
                    "code" to Json.of(it.code),
                    "message" to Json.of(it.message),
                )
            },
        ),
    )
}

/** The wire rendering of this error — shared by every adapter. */
public fun OperationError.toWire(): WireError = when (this) {
    is OperationError.Invalid -> WireError(code, message, errors)
    is OperationError.Forbidden ->
        WireError(
            code,
            message,
            missingScopes.map { ValidationError("", "missing_scope", it) },
        )
    else -> WireError(code, message, emptyList())
}

/**
 * The HTTP status an error maps to. Lives in core (not in the REST adapter) so
 * the MCP adapter can report the SAME status token in its error content — some
 * agent frameworks branch on it, and a divergence here would be a parity bug.
 */
public fun OperationError.httpStatus(): Int = when (this) {
    is OperationError.Unauthenticated -> 401
    is OperationError.Forbidden -> 403
    is OperationError.Invalid -> 400
    is OperationError.NotFound -> 404
    is OperationError.Conflict -> 409
    is OperationError.Failed -> 500
}
