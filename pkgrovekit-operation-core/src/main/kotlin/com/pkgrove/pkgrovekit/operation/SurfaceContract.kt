package com.pkgrove.pkgrovekit.operation

/**
 * One surface's answer to a call, normalised for comparison (HEL-602).
 *
 * Both adapters already produce everything needed: the REST adapter a status and
 * a body, the MCP adapter an error code/field/message and the equivalent status.
 * [SurfaceContract] compares these rather than the envelopes, because the
 * envelopes are SUPPOSED to differ — what must not differ is the answer.
 */
public data class SurfaceAnswer(
    /** The HTTP status, or its MCP equivalent. */
    public val status: Int,
    /** The parsed payload: the success body, or the `{error, message, details}` envelope. */
    public val payload: Json,
) {
    public companion object {
        /** Builds an answer from a status and raw JSON text. */
        public fun of(status: Int, body: String?): SurfaceAnswer =
            SurfaceAnswer(status, if (body.isNullOrBlank()) Json.emptyObject else Json.parse(body))
    }
}

/**
 * A reusable test utility for running ONE operation contract through BOTH
 * surfaces and proving they agree (HEL-602).
 *
 * Deliberately in core, taking already-normalised [SurfaceAnswer]s rather than
 * the adapters themselves: core depends on neither adapter, and a consumer wires
 * its own dispatchers in a few lines. The point is that the assertion lives in
 * the library, so every consumer checks the same property the same way instead
 * of hand-rolling a comparison that quietly ignores the field that drifted.
 *
 * ```kotlin
 * @Test
 * fun `a bad amount reads the same on both surfaces`() {
 *     SurfaceContract.assertAgree(
 *         "actual.set with a 3-decimal amount",
 *         rest = SurfaceAnswer.of(restResponse.status, restResponse.body),
 *         mcp = mcpResult.asSurfaceAnswer(),
 *     )
 * }
 * ```
 */
public object SurfaceContract {

    /**
     * The differences between two surfaces' answers to the same logical call, or
     * an empty list when they agree.
     *
     * Compares the status, and then — for a failure — the semantic triple
     * (`error` code, each detail's `field` and `code`) rather than the prose, so
     * a reworded message is not a false alarm but a changed CODE is caught. For
     * a success it compares the whole payload: the output is the contract.
     */
    public fun differences(rest: SurfaceAnswer, mcp: SurfaceAnswer): List<String> {
        val problems = mutableListOf<String>()
        if (rest.status != mcp.status) {
            problems += "status differs: REST ${rest.status} vs MCP ${mcp.status}"
        }
        val restBody = rest.payload as? Json.Obj
        val mcpBody = mcp.payload as? Json.Obj
        if (restBody == null || mcpBody == null) {
            if (rest.payload != mcp.payload) problems += "payload differs (non-object)"
            return problems
        }

        val restError = restBody.stringOrNull("error")
        val mcpError = mcpBody.stringOrNull("error")
        if (restError != mcpError) {
            problems += "error code differs: REST ${restError ?: "(none)"} vs MCP ${mcpError ?: "(none)"}"
            return problems
        }

        if (restError == null) {
            // A success: the output payload IS the contract.
            if (restBody != mcpBody) {
                problems += "success payload differs: REST ${Json.write(restBody)} vs MCP ${Json.write(mcpBody)}"
            }
            return problems
        }

        val restDetails = details(restBody)
        val mcpDetails = details(mcpBody)
        if (restDetails != mcpDetails) {
            problems += "error details differ: REST $restDetails vs MCP $mcpDetails"
        }
        return problems
    }

    /** Throws when the two surfaces disagree. [label] names the call under test. */
    public fun assertAgree(label: String, rest: SurfaceAnswer, mcp: SurfaceAnswer) {
        val problems = differences(rest, mcp)
        if (problems.isNotEmpty()) {
            throw AssertionError(
                "REST and MCP disagree on '$label' — ${problems.size} difference(s):\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
    }

    /** The `(field, code)` pairs of an error envelope, in order. */
    private fun details(body: Json.Obj): List<Pair<String, String>> =
        (body.opt("details") as? Json.Arr)?.items
            ?.mapNotNull { it as? Json.Obj }
            ?.map { it.stringOrNull("field").orEmpty() to it.stringOrNull("code").orEmpty() }
            ?: emptyList()
}
