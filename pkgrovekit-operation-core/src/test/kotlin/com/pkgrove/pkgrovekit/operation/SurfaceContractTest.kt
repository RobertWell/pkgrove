package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The parity test utility consumers inherit (HEL-602). It is the library's own
 * answer to "prove REST and MCP agree", so it is itself tested on the cases that
 * matter: identical answers, a changed status, a changed error code, a changed
 * field, and a reworded message (which must NOT be an alarm).
 */
class SurfaceContractTest {

    private fun ok(payload: String) = SurfaceAnswer.of(200, payload)

    private fun err(status: Int, code: String, message: String, field: String?, detailCode: String?) =
        SurfaceAnswer.of(
            status,
            Json.write(
                Json.obj(
                    "error" to Json.of(code),
                    "message" to Json.of(message),
                    "details" to Json.arr(
                        if (field == null) {
                            emptyList()
                        } else {
                            listOf(
                                Json.obj(
                                    "field" to Json.of(field),
                                    "code" to Json.of(detailCode.orEmpty()),
                                    "message" to Json.of(message),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )

    @Test
    fun `identical success answers agree`() {
        val payload = """{"planId":"p-1","revision":1}"""
        assertEquals(emptyList<String>(), SurfaceContract.differences(ok(payload), ok(payload)))
        SurfaceContract.assertAgree("actual.set", ok(payload), ok(payload))
    }

    @Test
    fun `a differing success payload is reported`() {
        val problems = SurfaceContract.differences(
            ok("""{"revision":1}"""),
            ok("""{"revision":2}"""),
        )
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("success payload differs"), problems.single())
    }

    @Test
    fun `identical failures agree, and a reworded message is not an alarm`() {
        val rest = err(400, "invalid", "field 'state' must be one of A, B", "state", "one_of")
        val mcp = err(400, "invalid", "state must be A or B", "state", "one_of")
        // The prose differs; the SEMANTICS do not. Flagging this would train
        // people to ignore the check.
        assertEquals(emptyList<String>(), SurfaceContract.differences(rest, mcp))
    }

    @Test
    fun `a differing status is reported`() {
        val problems = SurfaceContract.differences(
            err(400, "invalid", "m", "state", "one_of"),
            err(422, "invalid", "m", "state", "one_of"),
        )
        assertTrue(problems.any { it.contains("status differs: REST 400 vs MCP 422") }, problems.toString())
    }

    @Test
    fun `a differing error code is reported and stops there`() {
        val problems = SurfaceContract.differences(
            err(400, "invalid", "m", "state", "one_of"),
            err(400, "failed", "m", "other", "boom"),
        )
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("error code differs: REST invalid vs MCP failed"), problems.single())
    }

    @Test
    fun `a success on one surface and a failure on the other is reported`() {
        val problems = SurfaceContract.differences(
            ok("""{"revision":1}"""),
            err(200, "invalid", "m", "state", "one_of"),
        )
        assertTrue(problems.single().contains("error code differs: REST (none) vs MCP invalid"), problems.single())
    }

    @Test
    fun `a differing error field or detail code is reported`() {
        val differentField = SurfaceContract.differences(
            err(400, "invalid", "m", "state", "one_of"),
            err(400, "invalid", "m", "amount", "one_of"),
        )
        assertTrue(differentField.single().contains("error details differ"), differentField.single())

        val differentCode = SurfaceContract.differences(
            err(400, "invalid", "m", "state", "one_of"),
            err(400, "invalid", "m", "state", "required"),
        )
        assertTrue(differentCode.single().contains("error details differ"), differentCode.single())
    }

    @Test
    fun `an error with no details compares cleanly`() {
        val rest = err(404, "not_found", "no plan 'p-9'", null, null)
        val mcp = err(404, "not_found", "plan p-9 does not exist", null, null)
        assertEquals(emptyList<String>(), SurfaceContract.differences(rest, mcp))
    }

    @Test
    fun `a non-object payload is compared whole`() {
        val a = SurfaceAnswer(200, Json.of("x"))
        val b = SurfaceAnswer(200, Json.of("y"))
        assertEquals(listOf("payload differs (non-object)"), SurfaceContract.differences(a, b))
        assertEquals(emptyList<String>(), SurfaceContract.differences(a, a))
    }

    @Test
    fun `an absent or blank body is the empty object`() {
        assertEquals(SurfaceAnswer(200, Json.emptyObject), SurfaceAnswer.of(200, null))
        assertEquals(SurfaceAnswer(204, Json.emptyObject), SurfaceAnswer.of(204, "   "))
    }

    @Test
    fun `assertAgree names the call and every difference`() {
        val failure = assertThrows<AssertionError> {
            SurfaceContract.assertAgree(
                "actual.set with a bad state",
                err(400, "invalid", "m", "state", "one_of"),
                err(422, "invalid", "m", "amount", "scale"),
            )
        }
        assertTrue(failure.message!!.contains("actual.set with a bad state"), failure.message)
        assertTrue(failure.message!!.contains("2 difference(s)"), failure.message)
        assertTrue(failure.message!!.contains("status differs"), failure.message)
        assertTrue(failure.message!!.contains("error details differ"), failure.message)
    }
}
