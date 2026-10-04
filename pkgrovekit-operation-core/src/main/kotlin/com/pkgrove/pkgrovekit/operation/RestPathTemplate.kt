package com.pkgrove.pkgrovekit.operation

/**
 * A `/v1/plans/{planId}/items/{itemId}` path template and its matcher (HEL-602).
 *
 * Lives in core rather than in the REST adapter so the registry's
 * construction-time check ("this template's parameters are absent from the
 * declared input schema") and the dispatcher's request-time matching use ONE
 * implementation. Two parsers for one syntax is how a route silently stops
 * matching what the registry validated.
 */
public class RestPathTemplate(public val path: String) {

    /** The `{name}` parameters, in path order. */
    public val parameters: List<String> = PARAM.findAll(path).map { it.groupValues[1] }.toList()

    private val segments: List<String> = path.trim('/').split('/').filter { it.isNotEmpty() }

    init {
        require(path.startsWith("/")) { "a REST path template must start with '/': '$path'" }
        val duplicates = parameters.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
            "REST path template '$path' declares parameter(s) ${duplicates.sorted()} more than once"
        }
    }

    /** Number of path segments — a cheap pre-filter before matching. */
    public val segmentCount: Int get() = segments.size

    /**
     * Matches a CONCRETE request path, returning the extracted parameters, or
     * `null` when it does not match.
     *
     * An empty path segment never matches a parameter: `/v1/plans//items` must
     * not resolve to plan `""`, which would otherwise authorize against an empty
     * id. Percent-encoding is decoded, so `{planId}` receives `a/b` from `a%2Fb`.
     */
    public fun match(requestPath: String): Map<String, String>? {
        val requested = requestPath.substringBefore('?').trim('/').split('/').filter { it.isNotEmpty() }
        if (requested.size != segments.size) return null
        val params = LinkedHashMap<String, String>()
        for (i in segments.indices) {
            val template = segments[i]
            val actual = requested[i]
            val param = PARAM.matchEntire(template)
            if (param != null) {
                if (actual.isEmpty()) return null
                params[param.groupValues[1]] = decodeSegment(actual)
            } else if (template != actual) {
                return null
            }
        }
        return params
    }

    override fun toString(): String = path

    private fun decodeSegment(raw: String): String =
        if ('%' in raw || '+' in raw) {
            try {
                java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                raw
            }
        } else {
            raw
        }

    public companion object {
        /** The parameter syntax: `{name}`. */
        public val PARAM: Regex = Regex("""\{([A-Za-z][A-Za-z0-9_]*)}""")
    }
}

/** This binding's path template. */
public fun RestBinding.template(): RestPathTemplate = RestPathTemplate(path)
