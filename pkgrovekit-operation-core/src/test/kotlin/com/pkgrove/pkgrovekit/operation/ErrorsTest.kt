package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The single error model both adapters render (HEL-602). The codes and statuses
 * live in core precisely so REST and MCP cannot drift.
 */
class ErrorsTest {

    @Test
    fun `every error has a stable code and HTTP status`() {
        assertEquals(
            listOf(
                "unauthenticated" to 401,
                "forbidden" to 403,
                "invalid" to 400,
                "not_found" to 404,
                "conflict" to 409,
                "failed" to 500,
            ),
            listOf(
                OperationError.Unauthenticated(),
                OperationError.Forbidden("no"),
                OperationError.Invalid(listOf(ValidationError("f", "c", "m"))),
                OperationError.NotFound("gone"),
                OperationError.Conflict("clash"),
                OperationError.Failed("boom"),
            ).map { it.code to it.httpStatus() },
        )
    }

    @Test
    fun `an invalid error renders its field details`() {
        val wire = OperationError.Invalid(
            listOf(ValidationError("state", "one_of", "field 'state' must be one of A, B")),
        ).toWire()
        assertEquals("invalid", wire.code)
        assertEquals("the request is not valid", wire.message)
        assertEquals(
            """{"error":"invalid","message":"the request is not valid","details":""" +
                """[{"field":"state","code":"one_of","message":"field 'state' must be one of A, B"}]}""",
            Json.write(wire.toJson()),
        )
    }

    @Test
    fun `a forbidden error renders its missing scopes as details`() {
        val wire = OperationError.Forbidden("needs more", setOf("plans:write")).toWire()
        assertEquals(listOf(ValidationError("", "missing_scope", "plans:write")), wire.details)
    }

    @Test
    fun `errors without detail render an empty details array`() {
        listOf(
            OperationError.Unauthenticated(),
            OperationError.NotFound("gone"),
            OperationError.Conflict("clash"),
            OperationError.Failed("boom", IllegalStateException("cause")),
        ).forEach { error ->
            val wire = error.toWire()
            assertTrue(wire.details.isEmpty(), error.code)
            assertTrue(Json.write(wire.toJson()).contains(""""details":[]"""))
        }
    }

    @Test
    fun `an invalid error with no validation errors is refused at construction`() {
        assertThrows<IllegalArgumentException> { OperationError.Invalid(emptyList()) }
    }

    @Test
    fun `the cause of a failure is kept for logs and never for the wire`() {
        val cause = IllegalStateException("connection string with a password in it")
        val error = OperationError.Failed("operation failed", cause)
        assertEquals(cause, error.cause)
        assertTrue(!Json.write(error.toWire().toJson()).contains("password"))
    }

    @Test
    fun `an operation exception carries its typed error`() {
        val thrown = assertThrows<OperationException> { notFound("gone") }
        assertEquals("not_found", thrown.error.code)
        assertEquals("gone", thrown.message)
    }
}
