package com.pkgrove.pkgrovekit.operation

/**
 * Where a call entered the system (HEL-602).
 *
 * This is NOT decoration: it is the axis the fail-closed surface classification
 * turns on, and every audit record carries it. "Which operations can an AI agent
 * reach?" is answerable because [MCP] is a first-class value, not an inference.
 */
public enum class Surface {
    /** A human-facing / machine-facing HTTP API call. */
    REST,

    /** An AI agent via the Model Context Protocol. */
    MCP,

    /** In-process: a batch job, scheduler, or another module. Never remotely reachable. */
    INTERNAL,
    ;

    /** Lowercase wire token used in audit records and descriptors. */
    public val token: String get() = name.lowercase()
}

/**
 * The authenticated caller, normalised across surfaces.
 *
 * REST and MCP generally present DIFFERENT credentials (a user's bearer token
 * vs an agent session token). Their [AuthProfile]s differ; what they produce
 * does not. Everything downstream — authorization, idempotency, the handler,
 * the audit record — sees one shape, so a handler cannot accidentally behave
 * differently for an agent than for a person.
 */
public data class CallerContext(
    /** Stable subject identifier (user id, service id). */
    public val subjectId: String,
    /** Surface the call arrived on. */
    public val surface: Surface,
    /** Scopes/permissions the credential carries. */
    public val scopes: Set<String> = emptySet(),
    /** Roles, where the deployment models them separately from scopes. */
    public val roles: Set<String> = emptySet(),
    /** Remaining claims, for domain authorization and audit. */
    public val claims: Map<String, String> = emptyMap(),
    /**
     * Client/credential origin: an OAuth client id, an agent name, an API-key
     * label. Nullable — an internal caller legitimately has none. Audit records
     * attribute to `surface + clientId`, which is how "the agent did it" is
     * distinguishable from "the user did it" after the fact.
     */
    public val clientId: String? = null,
    /** Correlation id tying every stage of one call together. */
    public val correlationId: String = "",
    /** Free-form request attributes carried into the audit record. */
    public val attributes: Map<String, String> = emptyMap(),
    /** Name of the [AuthProfile] that produced this context. */
    public val authProfileName: String = "",
) {
    /** True when the caller holds every scope in [required]. */
    public fun holdsAll(required: Set<String>): Boolean = required.all { it in scopes || it in roles }

    /** Scopes in [required] the caller does not hold. */
    public fun missing(required: Set<String>): Set<String> =
        required.filterNot { it in scopes || it in roles }.toSet()
}

/** What an [AuthProfile] is told about the call it is authenticating. */
public data class AuthRequest(
    public val surface: Surface,
    public val operationId: String,
    public val correlationId: String,
    public val attributes: Map<String, String> = emptyMap(),
)

/**
 * A surface-specific function from a RAW credential to a [CallerContext].
 *
 * `C` is whatever the transport actually hands over — a bearer string, a parsed
 * JWT, an MCP session object. Returning `null` means "not authenticated" and
 * becomes [OperationError.Unauthenticated]; it must never mean "authenticated
 * with no scopes", which is how fail-open auth bugs are written.
 */
public interface AuthProfile<in C> {
    /** Stable profile name; appears in descriptors and audit records. */
    public val name: String

    /** Short description of the credential shape, for the Gallery descriptor. */
    public val credentialDescription: String get() = ""

    /** Resolves [credential] to a caller, or `null` when it is not usable. */
    public fun authenticate(credential: C?, request: AuthRequest): CallerContext?
}

/**
 * An [AuthProfile] the pipeline can call with the erased `Any?` credential it
 * carries. Produced by [authProfile]; a credential of the wrong TYPE resolves to
 * `null` (unauthenticated) rather than throwing, because a transport presenting
 * the wrong credential shape is an auth failure, not a server fault.
 */
public typealias ErasedAuthProfile = AuthProfile<Any?>

/**
 * Builds an [ErasedAuthProfile] for credential type [C].
 *
 * ```kotlin
 * val restProfile = authProfile<String>("rest-bearer", "Authorization: Bearer <jwt>") { token, req ->
 *     jwt.verify(token)?.let { CallerContext(it.subject, req.surface, it.scopes, correlationId = req.correlationId) }
 * }
 * ```
 */
public inline fun <reified C : Any> authProfile(
    name: String,
    credentialDescription: String = "",
    crossinline authenticate: (C, AuthRequest) -> CallerContext?,
): ErasedAuthProfile {
    require(name.isNotBlank()) { "an AuthProfile needs a non-blank name" }
    val profileName = name
    val described = credentialDescription
    return object : ErasedAuthProfile {
        override val name: String get() = profileName

        override val credentialDescription: String get() = described

        override fun authenticate(credential: Any?, request: AuthRequest): CallerContext? {
            // A credential of the wrong SHAPE is an auth failure, not a 500 —
            // the transport presented something this profile cannot read.
            val typed = credential as? C ?: return null
            // The surface, correlation id and profile name are stamped HERE so a
            // profile implementation cannot forget them (or lie about them).
            return authenticate(typed, request)?.copy(
                surface = request.surface,
                correlationId = request.correlationId,
                authProfileName = profileName,
            )
        }
    }
}

/**
 * Per-surface auth profiles. REST and MCP deliberately get different entries —
 * different credentials, one [CallerContext].
 *
 * A surface with NO profile is UNAUTHENTICATABLE: calls on it fail
 * [OperationError.Unauthenticated]. That is the fail-closed default; forgetting
 * to configure MCP auth must not silently admit every agent.
 */
public class AuthProfiles private constructor(
    private val bySurface: Map<Surface, ErasedAuthProfile>,
) {
    /** The profile for [surface], or `null` when none is configured. */
    public fun forSurface(surface: Surface): ErasedAuthProfile? = bySurface[surface]

    /** Configured profile names by surface — for descriptors and diagnostics. */
    public fun names(): Map<Surface, String> = bySurface.mapValues { it.value.name }

    public companion object {
        /** Builds a profile set. */
        public fun of(vararg entries: Pair<Surface, ErasedAuthProfile>): AuthProfiles =
            AuthProfiles(LinkedHashMap<Surface, ErasedAuthProfile>().apply {
                entries.forEach { (surface, profile) ->
                    require(put(surface, profile) == null) {
                        "duplicate AuthProfile for surface $surface"
                    }
                }
            })

        /** One profile used for every surface (the simple single-credential case). */
        public fun uniform(profile: ErasedAuthProfile): AuthProfiles =
            AuthProfiles(Surface.entries.associateWith { profile })

        /**
         * No credentials are checked at all. Intended for INTERNAL-only
         * registries and tests; named so that it cannot appear in a production
         * wiring diff without being noticed.
         */
        public fun trustingInternalOnly(subjectId: String = "internal"): AuthProfiles =
            AuthProfiles(
                mapOf(
                    Surface.INTERNAL to object : ErasedAuthProfile {
                        override val name: String get() = "internal-trusted"
                        override val credentialDescription: String get() = "none (in-process caller)"
                        override fun authenticate(credential: Any?, request: AuthRequest): CallerContext? =
                            CallerContext(
                                subjectId = subjectId,
                                surface = Surface.INTERNAL,
                                correlationId = request.correlationId,
                                authProfileName = "internal-trusted",
                            )
                    },
                ),
            )
    }
}
