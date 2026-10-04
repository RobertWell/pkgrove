package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Path templates (HEL-602). One implementation serves both the registry's
 * construction check and the dispatcher's request matching, so this is where
 * both are proven.
 */
class RestPathTemplateTest {

    @Test
    fun `parameters are listed in path order`() {
        val template = RestPathTemplate("/v1/plans/{planId}/items/{itemId}/actual")
        assertEquals(listOf("planId", "itemId"), template.parameters)
        assertEquals(6, template.segmentCount)
        assertEquals("/v1/plans/{planId}/items/{itemId}/actual", template.toString())
    }

    @Test
    fun `a literal template matches only itself`() {
        val template = RestPathTemplate("/v1/plans")
        assertEquals(emptyMap<String, String>(), template.match("/v1/plans"))
        assertEquals(emptyMap<String, String>(), template.match("/v1/plans/"))
        assertNull(template.match("/v1/plan"))
        assertNull(template.match("/v1/plans/p-1"))
    }

    @Test
    fun `parameters are extracted and a query string is ignored`() {
        val template = RestPathTemplate("/v1/plans/{planId}/items/{itemId}")
        assertEquals(
            mapOf("planId" to "p-1", "itemId" to "i-2"),
            template.match("/v1/plans/p-1/items/i-2?expand=true"),
        )
    }

    @Test
    fun `a wrong literal segment does not match`() {
        val template = RestPathTemplate("/v1/plans/{planId}/items")
        assertNull(template.match("/v1/plans/p-1/notes"))
    }

    @Test
    fun `an empty segment never satisfies a parameter`() {
        // Otherwise `/v1/plans//items` would authorize against plan "".
        val template = RestPathTemplate("/v1/plans/{planId}/items")
        assertNull(template.match("/v1/plans//items"))
    }

    @Test
    fun `percent-encoded segments are decoded`() {
        val template = RestPathTemplate("/v1/plans/{planId}")
        assertEquals(mapOf("planId" to "a/b"), template.match("/v1/plans/a%2Fb"))
        assertEquals(mapOf("planId" to "a b"), template.match("/v1/plans/a+b"))
        // A malformed escape is passed through rather than failing the route.
        assertEquals(mapOf("planId" to "a%zz"), template.match("/v1/plans/a%zz"))
    }

    @Test
    fun `a template must start with a slash and must not repeat a parameter`() {
        assertThrows<IllegalArgumentException> { RestPathTemplate("v1/plans") }
        assertThrows<IllegalArgumentException> { RestPathTemplate("/v1/{id}/sub/{id}") }
    }

    @Test
    fun `a binding exposes its template and renders readably`() {
        val binding = RestBinding(HttpMethod.POST, "/v1/plans/{planId}/items/{itemId}/actual")
        assertEquals(listOf("planId", "itemId"), binding.pathParameters())
        assertEquals(listOf("planId", "itemId"), binding.template().parameters)
        assertEquals("POST /v1/plans/{planId}/items/{itemId}/actual", binding.toString())
    }
}
