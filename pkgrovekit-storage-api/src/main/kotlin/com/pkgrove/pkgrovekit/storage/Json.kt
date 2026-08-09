package com.pkgrove.pkgrovekit.storage

/**
 * Minimal strict JSON emit/parse for manifests, checkpoints and JSONL rows.
 * INTERNAL — this module has a zero-external-dependency contract (HEL-236:
 * "storage-api must stay lightweight"), so a JSON library is not an option and
 * the needed subset (objects/arrays/strings/numbers/booleans/null, UTF-8,
 * `\uXXXX` escapes) is small enough to own. Not a general-purpose parser: no
 * comments, no trailing commas, numbers surface as [String] so callers decide
 * precision (manifests carry longs; rows carry BigDecimal).
 */
internal object Json {

    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** Parse one JSON document. Values: Map<String,Any?>, List<Any?>, String, RawNumber, Boolean, null. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        require(p.atEnd()) { "trailing content after JSON value at offset ${p.pos}" }
        return v
    }

    /** A number token, undecoded — caller converts (toLong/BigDecimal). */
    data class RawNumber(val text: String)

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length
        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            require(!atEnd()) { "unexpected end of JSON" }
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else error("unexpected '$c' at offset $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, pos)) { "invalid literal at offset $pos" }
            pos += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(peek() == ':') { "expected ':' at offset $pos" }
                pos++
                out[key] = parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> error("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out += parseValue()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> error("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            require(peek() == '"') { "expected string at offset $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                require(!atEnd()) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(!atEnd()) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad \\u escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape '\\$e' at offset $pos")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): RawNumber {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val token = s.substring(start, pos)
            require(token.isNotEmpty() && token != "-") { "invalid number at offset $start" }
            return RawNumber(token)
        }

        private fun peek(): Char {
            require(!atEnd()) { "unexpected end of JSON" }
            return s[pos]
        }

        private fun error(msg: String): Nothing = throw IllegalArgumentException(msg)
    }
}
