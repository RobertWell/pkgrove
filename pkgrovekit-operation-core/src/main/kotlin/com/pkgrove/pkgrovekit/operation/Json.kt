package com.pkgrove.pkgrovekit.operation

/**
 * Minimal, dependency-free JSON value model (HEL-602).
 *
 * `pkgrovekit-operation-core` is a ZERO-DEPENDENCY module like `pkgrovekit-core`
 * — it must not drag Jackson (or any other binding library) onto a consumer's
 * classpath, because the first consumers already own their own, possibly
 * different, JSON stack. The transport-neutral operation model therefore speaks
 * this tiny tree: the REST adapter parses a request body into it, the MCP
 * adapter parses `arguments` into it, and both run the SAME decoder, validator
 * and handler afterwards. That is what makes "identical validation semantics
 * across REST and MCP" a structural property rather than a promise.
 *
 * Numbers are kept as their LOSSLESS SOURCE TEXT ([Num.text]). Money is the
 * motivating case: parsing `19.99` into a `Double` and re-emitting it is how
 * financial APIs acquire rounding bugs. Callers convert explicitly
 * ([decimalString], [int], [long]).
 */
public sealed interface Json {

    /** JSON `null`. Distinct from "absent" — see [opt]. */
    public data object Null : Json

    /** JSON `true`/`false`. */
    public data class Bool(public val value: Boolean) : Json

    /** JSON number, retained as source text so no precision is lost in transit. */
    public data class Num(public val text: String) : Json {
        init {
            require(text.isNotBlank()) { "Json.Num text must not be blank" }
        }
    }

    /** JSON string. */
    public data class Str(public val value: String) : Json

    /** JSON array. */
    public data class Arr(public val items: List<Json>) : Json

    /** JSON object. Field order is preserved so rendered output is stable. */
    public data class Obj(public val fields: Map<String, Json>) : Json

    public companion object {

        /** The empty object — the canonical "no payload" input/output. */
        public val emptyObject: Obj = Obj(emptyMap())

        /** Builds an object from pairs, preserving declaration order. */
        public fun obj(vararg pairs: Pair<String, Json>): Obj =
            Obj(LinkedHashMap<String, Json>().apply { pairs.forEach { (k, v) -> put(k, v) } })

        /** Builds an array. */
        public fun arr(items: List<Json>): Arr = Arr(items)

        /** Convenience constructors, so call sites read as data not ceremony. */
        public fun of(value: String): Str = Str(value)

        public fun of(value: Boolean): Bool = Bool(value)

        public fun of(value: Int): Num = Num(value.toString())

        public fun of(value: Long): Num = Num(value.toString())

        /** Renders [value] as compact JSON text. */
        public fun write(value: Json): String = StringBuilder().also { render(value, it) }.toString()

        /**
         * Parses JSON [text]. Throws [JsonDecodeException] on malformed input so
         * a bad request body becomes a typed `Invalid` outcome rather than an
         * opaque 500.
         */
        public fun parse(text: String): Json {
            val p = Parser(text)
            p.skipWhitespace()
            val value = p.readValue()
            p.skipWhitespace()
            if (!p.exhausted()) p.fail("trailing content after the top-level JSON value")
            return value
        }

        private fun render(value: Json, out: StringBuilder) {
            when (value) {
                is Null -> out.append("null")
                is Bool -> out.append(if (value.value) "true" else "false")
                is Num -> out.append(value.text)
                is Str -> escape(value.value, out)
                is Arr -> {
                    out.append('[')
                    value.items.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        render(item, out)
                    }
                    out.append(']')
                }
                is Obj -> {
                    out.append('{')
                    var first = true
                    value.fields.forEach { (k, v) ->
                        if (!first) out.append(',')
                        first = false
                        escape(k, out)
                        out.append(':')
                        render(v, out)
                    }
                    out.append('}')
                }
            }
        }

        private fun escape(raw: String, out: StringBuilder) {
            out.append('"')
            for (c in raw) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c == '\b' -> out.append("\\b")
                    c == '\u000C' -> out.append("\\f")
                    c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }

    /** Hand-rolled recursive-descent parser — small enough to audit, zero deps. */
    private class Parser(private val src: String) {
        private var i = 0

        fun exhausted(): Boolean = i >= src.length

        fun fail(why: String): Nothing =
            throw JsonDecodeException(field = "", code = "malformed_json", message = "$why (at offset $i)")

        fun skipWhitespace() {
            while (i < src.length && (src[i] == ' ' || src[i] == '\t' || src[i] == '\n' || src[i] == '\r')) i++
        }

        fun readValue(): Json {
            if (exhausted()) fail("unexpected end of input")
            return when (val c = src[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Str(readString())
                't' -> readLiteral("true", Bool(true))
                'f' -> readLiteral("false", Bool(false))
                'n' -> readLiteral("null", Null)
                else ->
                    if (c == '-' || c in '0'..'9') readNumber()
                    else fail("unexpected character '$c'")
            }
        }

        private fun readLiteral(literal: String, value: Json): Json {
            if (!src.startsWith(literal, i)) fail("expected '$literal'")
            i += literal.length
            return value
        }

        private fun readObject(): Obj {
            i++ // '{'
            val fields = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (!exhausted() && src[i] == '}') {
                i++
                return Obj(fields)
            }
            while (true) {
                skipWhitespace()
                if (exhausted() || src[i] != '"') fail("expected a quoted object key")
                val key = readString()
                skipWhitespace()
                if (exhausted() || src[i] != ':') fail("expected ':' after object key '$key'")
                i++
                skipWhitespace()
                fields[key] = readValue()
                skipWhitespace()
                if (exhausted()) fail("unterminated object")
                when (src[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return Obj(fields)
                    }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(): Arr {
            i++ // '['
            val items = ArrayList<Json>()
            skipWhitespace()
            if (!exhausted() && src[i] == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                if (exhausted()) fail("unterminated array")
                when (src[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return Arr(items)
                    }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (exhausted()) fail("unterminated string")
                when (val c = src[i]) {
                    '"' -> {
                        i++
                        return sb.toString()
                    }
                    '\\' -> {
                        i++
                        if (exhausted()) fail("unterminated escape sequence")
                        when (val e = src[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 >= src.length) fail("truncated unicode escape")
                                val hex = src.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: fail("invalid unicode escape '$hex'")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("invalid escape of '$e'")
                        }
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun readNumber(): Num {
            val start = i
            if (src[i] == '-') i++
            while (i < src.length && src[i] in '0'..'9') i++
            if (i < src.length && src[i] == '.') {
                i++
                while (i < src.length && src[i] in '0'..'9') i++
            }
            if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                i++
                if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                while (i < src.length && src[i] in '0'..'9') i++
            }
            val text = src.substring(start, i)
            if (text.isEmpty() || text == "-") fail("malformed number")
            return Num(text)
        }
    }
}

/**
 * A field-attributable decode failure. The pipeline turns this into
 * [OperationError.Invalid], so a client gets `{field, code, message}` and not a
 * stack trace — identically on REST and on MCP.
 */
public class JsonDecodeException(
    public val field: String,
    public val code: String,
    override val message: String,
) : RuntimeException(message) {
    /** The transport-neutral error this decode failure represents. */
    public fun asValidationError(): ValidationError = ValidationError(field, code, message)
}

// ---------------------------------------------------------------------------
// Typed, field-attributing accessors. Every failure names the field, which is
// what lets a decoder stay a one-liner and still produce good client errors.
// ---------------------------------------------------------------------------

/** The field, or `null` when absent OR explicitly JSON `null`. */
public fun Json.Obj.opt(name: String): Json? = fields[name]?.takeIf { it !is Json.Null }

/** True when the field is present and not JSON `null`. */
public fun Json.Obj.has(name: String): Boolean = opt(name) != null

private fun Json.Obj.demand(name: String): Json =
    opt(name) ?: throw JsonDecodeException(name, "required", "field '$name' is required")

private fun wrongType(name: String, expected: String, actual: Json): Nothing =
    throw JsonDecodeException(
        name,
        "type",
        "field '$name' must be $expected but was ${actual.typeName()}",
    )

/** The JSON type name used in decode/schema messages. */
public fun Json.typeName(): String = when (this) {
    is Json.Null -> "null"
    is Json.Bool -> "boolean"
    is Json.Num -> "number"
    is Json.Str -> "string"
    is Json.Arr -> "array"
    is Json.Obj -> "object"
}

/** Required string field. */
public fun Json.Obj.string(name: String): String =
    when (val v = demand(name)) {
        is Json.Str -> v.value
        else -> wrongType(name, "a string", v)
    }

/** Optional string field. */
public fun Json.Obj.stringOrNull(name: String): String? = if (has(name)) string(name) else null

/** Required boolean field. */
public fun Json.Obj.bool(name: String): Boolean =
    when (val v = demand(name)) {
        is Json.Bool -> v.value
        else -> wrongType(name, "a boolean", v)
    }

/** Optional boolean field. */
public fun Json.Obj.boolOrNull(name: String): Boolean? = if (has(name)) bool(name) else null

/**
 * Required numeric field as its LOSSLESS source text — the right accessor for
 * money, which must never round-trip through a binary float.
 */
public fun Json.Obj.decimalString(name: String): String =
    when (val v = demand(name)) {
        is Json.Num -> v.text
        // A client that sends money as a quoted string is being careful, not
        // wrong; accept it rather than forcing precision loss on them.
        is Json.Str -> v.value
        else -> wrongType(name, "a number", v)
    }

/** Optional numeric field as lossless source text. */
public fun Json.Obj.decimalStringOrNull(name: String): String? =
    if (has(name)) decimalString(name) else null

/** Required integer field. */
public fun Json.Obj.int(name: String): Int =
    decimalString(name).let {
        it.toIntOrNull()
            ?: throw JsonDecodeException(name, "type", "field '$name' must be an integer but was '$it'")
    }

/** Optional integer field. */
public fun Json.Obj.intOrNull(name: String): Int? = if (has(name)) int(name) else null

/** Required long field. */
public fun Json.Obj.long(name: String): Long =
    decimalString(name).let {
        it.toLongOrNull()
            ?: throw JsonDecodeException(name, "type", "field '$name' must be a long but was '$it'")
    }

/** Required nested object field. */
public fun Json.Obj.nested(name: String): Json.Obj =
    when (val v = demand(name)) {
        is Json.Obj -> v
        else -> wrongType(name, "an object", v)
    }

/** Required array field. */
public fun Json.Obj.array(name: String): List<Json> =
    when (val v = demand(name)) {
        is Json.Arr -> v.items
        else -> wrongType(name, "an array", v)
    }

/** Coerces any [Json] to an object; a non-object payload is a decode failure. */
public fun Json.asObject(): Json.Obj = when (this) {
    is Json.Obj -> this
    is Json.Null -> Json.emptyObject
    else -> throw JsonDecodeException("", "type", "payload must be a JSON object but was ${typeName()}")
}
