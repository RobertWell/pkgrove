package com.pkgrove.pkgrovekit.operation.quarkus

import com.pkgrove.pkgrovekit.operation.AuthRequest
import com.pkgrove.pkgrovekit.operation.CallerContext
import com.pkgrove.pkgrovekit.operation.ErasedAuthProfile
import com.pkgrove.pkgrovekit.operation.Surface

/**
 * The claims of an authenticated identity, as the operation layer needs them
 * (HEL-602).
 *
 * This is the seam to a framework's security identity. It exists as an interface
 * rather than a direct `io.quarkus.security.identity.SecurityIdentity`
 * dependency for two reasons:
 *
 *  * `quarkus-security` / `smallrye-jwt` are NOT in this repository's version
 *    catalog or its generated dependency-verification metadata, and the owner
 *    directive is to minimise new external artifacts (that metadata is
 *    regenerated only inside the LAN GitLab CI job);
 *  * OAuth/JWT verification is emphatically NOT reimplemented here. The
 *    framework verifies the token; this layer reads the verified result.
 *
 * Binding it in a consumer is a few lines — see `docs/operations.md`:
 *
 * ```kotlin
 * class QuarkusIdentityClaims(private val identity: SecurityIdentity) : IdentityClaims {
 *     override val subject get() = identity.principal?.name
 *     override val roles get() = identity.roles
 *     override fun claim(name: String) = identity.getAttribute<Any?>(name)?.toString()
 * }
 * ```
 */
public interface IdentityClaims {
    /** The authenticated subject, or `null` when the request is anonymous. */
    public val subject: String?

    /** Roles the framework resolved. */
    public val roles: Set<String> get() = emptySet()

    /** Scopes, if the deployment models them separately from roles. */
    public val scopes: Set<String> get() = emptySet()

    /** The OAuth client / agent identity the credential was issued to. */
    public val clientId: String? get() = null

    /** Any other verified claim, as text. */
    public fun claim(name: String): String? = null
}

/** Builders for [ErasedAuthProfile]s over a framework identity. */
public object QuarkusAuthProfiles {

    /**
     * A profile that reads the AMBIENT, already-verified identity — the usual
     * Quarkus shape, where the framework authenticated the request before the
     * resource method ran and the credential itself never reaches this layer.
     *
     * [claims] is called per request (it is request-scoped state), and a `null`
     * subject means unauthenticated. A BLANK subject is treated the same way: an
     * identity with no subject cannot be audited or authorized, and admitting it
     * is how an anonymous caller acquires an audit trail that names nobody.
     */
    public fun ambient(
        name: String = "quarkus-security-identity",
        credentialDescription: String = "framework-verified SecurityIdentity (OIDC/JWT)",
        /** Extra claim names to copy into [CallerContext.claims] for audit. */
        auditedClaims: Set<String> = emptySet(),
        claims: () -> IdentityClaims?,
    ): ErasedAuthProfile {
        val profileName = name
        val described = credentialDescription
        return object : ErasedAuthProfile {
            override val name: String get() = profileName

            override val credentialDescription: String get() = described

            override fun authenticate(credential: Any?, request: AuthRequest): CallerContext? =
                contextFrom(claims(), request, profileName, auditedClaims)
        }
    }

    /**
     * A profile for a credential the host extracts itself — the MCP case, where
     * the agent session token arrives in the JSON-RPC transport, not in an HTTP
     * `Authorization` header the framework already processed.
     *
     * [resolve] receives the raw credential text and returns claims, or `null`.
     * It must NOT verify a JWT by hand: hand it to the framework's verifier and
     * map the verified result.
     */
    public fun credential(
        name: String,
        credentialDescription: String = "host-extracted credential",
        auditedClaims: Set<String> = emptySet(),
        resolve: (String) -> IdentityClaims?,
    ): ErasedAuthProfile {
        val profileName = name
        val described = credentialDescription
        return object : ErasedAuthProfile {
            override val name: String get() = profileName

            override val credentialDescription: String get() = described

            override fun authenticate(credential: Any?, request: AuthRequest): CallerContext? {
                val text = (credential as? String)?.takeIf { it.isNotBlank() } ?: return null
                return contextFrom(resolve(text), request, profileName, auditedClaims)
            }
        }
    }

    /**
     * A fixed-identity profile for [Surface.INTERNAL] callers (batch jobs,
     * schedulers) that hold the named scopes by construction. It refuses any
     * other surface, so wiring it in cannot accidentally grant a remote caller
     * credential-free access.
     */
    public fun internalJob(
        subjectId: String,
        scopes: Set<String>,
        name: String = "internal-job",
    ): ErasedAuthProfile {
        val profileName = name
        return object : ErasedAuthProfile {
            override val name: String get() = profileName

            override val credentialDescription: String get() = "none (in-process job)"

            override fun authenticate(credential: Any?, request: AuthRequest): CallerContext? =
                if (request.surface != Surface.INTERNAL) {
                    null
                } else {
                    CallerContext(
                        subjectId = subjectId,
                        surface = Surface.INTERNAL,
                        scopes = scopes,
                        correlationId = request.correlationId,
                        attributes = request.attributes,
                        authProfileName = profileName,
                    )
                }
        }
    }

    private fun contextFrom(
        claims: IdentityClaims?,
        request: AuthRequest,
        profileName: String,
        auditedClaims: Set<String>,
    ): CallerContext? {
        val resolved = claims ?: return null
        val subject = resolved.subject?.takeIf { it.isNotBlank() } ?: return null
        return CallerContext(
            subjectId = subject,
            surface = request.surface,
            scopes = resolved.scopes,
            roles = resolved.roles,
            claims = auditedClaims.mapNotNull { key -> resolved.claim(key)?.let { key to it } }.toMap(),
            clientId = resolved.clientId,
            correlationId = request.correlationId,
            attributes = request.attributes,
            authProfileName = profileName,
        )
    }
}
