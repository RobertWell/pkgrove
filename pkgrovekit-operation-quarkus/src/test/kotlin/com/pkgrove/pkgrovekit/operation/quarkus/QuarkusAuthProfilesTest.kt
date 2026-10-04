package com.pkgrove.pkgrovekit.operation.quarkus

import com.pkgrove.pkgrovekit.operation.AuthRequest
import com.pkgrove.pkgrovekit.operation.Surface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The framework-identity seam (HEL-602). REST reads an ambient, already-verified
 * identity; MCP reads a credential the host extracted itself — and both produce
 * the same [com.pkgrove.pkgrovekit.operation.CallerContext].
 */
class QuarkusAuthProfilesTest {

    private class Claims(
        override val subject: String?,
        override val roles: Set<String> = emptySet(),
        override val scopes: Set<String> = emptySet(),
        override val clientId: String? = null,
        private val claims: Map<String, String> = emptyMap(),
    ) : IdentityClaims {
        override fun claim(name: String): String? = claims[name]
    }

    private fun request(surface: Surface = Surface.REST) =
        AuthRequest(surface, "plan.get", "c-1", mapOf("clientIp" to "10.0.0.1"))

    @Test
    fun `the ambient profile maps a verified identity to a caller context`() {
        val profile = QuarkusAuthProfiles.ambient(auditedClaims = setOf("tenant")) {
            Claims(
                subject = "alice",
                roles = setOf("user"),
                scopes = setOf("plans:read"),
                clientId = "ios-app",
                claims = mapOf("tenant" to "t-1", "unwanted" to "x"),
            )
        }
        val caller = profile.authenticate(null, request())!!
        assertEquals("alice", caller.subjectId)
        assertEquals(Surface.REST, caller.surface)
        assertEquals(setOf("plans:read"), caller.scopes)
        assertEquals(setOf("user"), caller.roles)
        assertEquals("ios-app", caller.clientId)
        assertEquals("c-1", caller.correlationId)
        assertEquals("quarkus-security-identity", caller.authProfileName)
        // only the claims the deployment asked to audit are copied
        assertEquals(mapOf("tenant" to "t-1"), caller.claims)
        assertEquals(mapOf("clientIp" to "10.0.0.1"), caller.attributes)
        assertEquals("framework-verified SecurityIdentity (OIDC/JWT)", profile.credentialDescription)
    }

    @Test
    fun `an anonymous or subject-less identity is unauthenticated`() {
        assertNull(QuarkusAuthProfiles.ambient { null }.authenticate(null, request()))
        assertNull(QuarkusAuthProfiles.ambient { Claims(null) }.authenticate(null, request()))
        // A blank subject cannot be audited or authorized, so it is not admitted.
        assertNull(QuarkusAuthProfiles.ambient { Claims("  ") }.authenticate(null, request()))
    }

    @Test
    fun `a missing audited claim is simply absent, not blank`() {
        val profile = QuarkusAuthProfiles.ambient(auditedClaims = setOf("tenant")) { Claims("alice") }
        assertEquals(emptyMap<String, String>(), profile.authenticate(null, request())!!.claims)
    }

    @Test
    fun `the credential profile resolves a host-extracted token`() {
        val profile = QuarkusAuthProfiles.credential("mcp-session", "MCP session token") { token ->
            if (token == "good") Claims("alice", scopes = setOf("plans:read"), clientId = "agent") else null
        }
        val caller = profile.authenticate("good", request(Surface.MCP))!!
        assertEquals("alice", caller.subjectId)
        assertEquals(Surface.MCP, caller.surface)
        assertEquals("agent", caller.clientId)
        assertEquals("mcp-session", caller.authProfileName)
        assertEquals("MCP session token", profile.credentialDescription)

        assertNull(profile.authenticate("bad", request(Surface.MCP)))
        assertNull(profile.authenticate(null, request(Surface.MCP)))
        assertNull(profile.authenticate("  ", request(Surface.MCP)))
        // a credential of the wrong SHAPE is an auth failure, not a 500
        assertNull(profile.authenticate(42, request(Surface.MCP)))
    }

    @Test
    fun `the two profiles produce the same caller context for one subject`() {
        val viaAmbient = QuarkusAuthProfiles.ambient("rest") {
            Claims("alice", scopes = setOf("plans:read"))
        }.authenticate(null, request(Surface.REST))!!
        val viaCredential = QuarkusAuthProfiles.credential("mcp") {
            Claims("alice", scopes = setOf("plans:read"))
        }.authenticate("token", request(Surface.REST))!!
        assertEquals(
            viaAmbient.copy(authProfileName = ""),
            viaCredential.copy(authProfileName = ""),
        )
    }

    @Test
    fun `the internal-job profile grants its declared scopes and refuses remote surfaces`() {
        val profile = QuarkusAuthProfiles.internalJob("reindexer", setOf("plans:write"))
        val caller = profile.authenticate(null, request(Surface.INTERNAL))!!
        assertEquals("reindexer", caller.subjectId)
        assertEquals(setOf("plans:write"), caller.scopes)
        assertEquals("internal-job", caller.authProfileName)
        assertEquals("none (in-process job)", profile.credentialDescription)

        assertNull(profile.authenticate(null, request(Surface.REST)))
        assertNull(profile.authenticate(null, request(Surface.MCP)))
    }

    @Test
    fun `the claims interface defaults keep a minimal implementation honest`() {
        val minimal = object : IdentityClaims {
            override val subject: String get() = "bob"
        }
        assertEquals(emptySet<String>(), minimal.roles)
        assertEquals(emptySet<String>(), minimal.scopes)
        assertNull(minimal.clientId)
        assertNull(minimal.claim("anything"))
    }
}
