package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.DataWarning

/**
 * Named-parameter SQL for the direct JDBC path (HEL-119): callers write
 * `:user_name`, never positional `?`. The compilation to JDBC placeholders is
 * an internal detail — diagnostics stay name-based, values are NEVER
 * interpolated into SQL, and bind values are never logged by this class.
 *
 * Parsing is state-aware: `:name` inside single-quoted strings, double-quoted
 * identifiers, `--` line comments, or `/* */` block comments is left alone,
 * as is the `::` cast operator and lone `:` characters.
 */
class NamedSql private constructor(
    /** The compiled SQL with `?` placeholders (internal form). */
    val sql: String,
    /** Parameter name per placeholder position, in order; repeats allowed. */
    val positions: List<String>,
) {

    /** Distinct parameter names, in first-appearance order. */
    val names: List<String> = positions.distinct()

    /** How to treat entries in the bind map that the SQL never uses. */
    enum class UnusedParamPolicy { REJECT, WARN, IGNORE }

    class MissingParametersException(val missing: List<String>) :
        IllegalArgumentException("missing required parameters: ${missing.joinToString(", ")}")

    class UnusedParametersException(val unused: List<String>) :
        IllegalArgumentException("unused parameters: ${unused.joinToString(", ")}")

    /**
     * Produce the positional bind list for [params]. Missing names throw
     * [MissingParametersException] naming EXACTLY what is absent (a present
     * key with a null value is a valid null bind, not a missing parameter).
     * Unused entries follow [unusedPolicy]; warnings carry names only, never
     * values.
     */
    fun bind(params: Map<String, Any?>,
             unusedPolicy: UnusedParamPolicy = UnusedParamPolicy.WARN,
             warn: (DataWarning) -> Unit = {}): List<Any?> {
        val missing = names.filter { it !in params }
        if (missing.isNotEmpty()) throw MissingParametersException(missing)
        val unused = params.keys.filter { it !in names }
        if (unused.isNotEmpty()) when (unusedPolicy) {
            UnusedParamPolicy.REJECT -> throw UnusedParametersException(unused)
            UnusedParamPolicy.WARN -> unused.forEach {
                warn(DataWarning("unused-parameter", "parameter not referenced by the SQL", it))
            }
            UnusedParamPolicy.IGNORE -> {}
        }
        return positions.map { params[it] }
    }

    companion object {

        private fun isNameStart(c: Char) = c.isLetter() || c == '_'
        private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_'

        /** Parse `:name` SQL into its internal positional form. */
        @JvmStatic
        fun parse(namedSql: String): NamedSql {
            val out = StringBuilder(namedSql.length)
            val positions = mutableListOf<String>()
            var i = 0
            val n = namedSql.length
            while (i < n) {
                val c = namedSql[i]
                when {
                    // single-quoted string literal ('' escapes)
                    c == '\'' -> {
                        val end = skipQuoted(namedSql, i, '\'')
                        out.append(namedSql, i, end); i = end
                    }
                    // double-quoted identifier ("" escapes)
                    c == '"' -> {
                        val end = skipQuoted(namedSql, i, '"')
                        out.append(namedSql, i, end); i = end
                    }
                    // -- line comment
                    c == '-' && i + 1 < n && namedSql[i + 1] == '-' -> {
                        val end = namedSql.indexOf('\n', i).let { if (it < 0) n else it }
                        out.append(namedSql, i, end); i = end
                    }
                    // /* block comment */ (no nesting, like SQL)
                    c == '/' && i + 1 < n && namedSql[i + 1] == '*' -> {
                        val close = namedSql.indexOf("*/", i + 2)
                        val end = if (close < 0) n else close + 2
                        out.append(namedSql, i, end); i = end
                    }
                    // :: cast operator — not a parameter
                    c == ':' && i + 1 < n && namedSql[i + 1] == ':' -> {
                        out.append("::"); i += 2
                    }
                    // :name parameter
                    c == ':' && i + 1 < n && isNameStart(namedSql[i + 1]) -> {
                        var j = i + 1
                        while (j < n && isNameChar(namedSql[j])) j++
                        positions += namedSql.substring(i + 1, j)
                        out.append('?'); i = j
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return NamedSql(out.toString(), positions)
        }

        private fun skipQuoted(s: String, start: Int, quote: Char): Int {
            var i = start + 1
            while (i < s.length) {
                if (s[i] == quote) {
                    if (i + 1 < s.length && s[i + 1] == quote) { i += 2; continue }  // escaped
                    return i + 1
                }
                i++
            }
            return s.length  // unterminated: pass through; the database reports it
        }
    }
}
