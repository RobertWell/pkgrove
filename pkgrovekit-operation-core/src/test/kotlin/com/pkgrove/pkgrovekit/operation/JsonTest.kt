package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The dependency-free JSON tree (HEL-602). It exists so operation-core can stay
 * zero-dependency, so it is tested as a real parser, not as an afterthought.
 */
class JsonTest {

    @Test
    fun `parses every value kind`() {
        val parsed = Json.parse(
            """{"s":"x","n":19.99,"b":true,"nil":null,"arr":[1,"two",false,null],"obj":{"k":"v"}}""",
        ) as Json.Obj
        assertEquals("x", parsed.string("s"))
        assertEquals("19.99", parsed.decimalString("n"))
        assertTrue(parsed.bool("b"))
        assertEquals(Json.Null, parsed.fields["nil"])
        assertEquals(4, parsed.array("arr").size)
        assertEquals("v", parsed.nested("obj").string("k"))
    }

    @Test
    fun `keeps number text lossless so money never round-trips through a float`() {
        val parsed = Json.parse("""{"amount":19.990}""") as Json.Obj
        assertEquals("19.990", parsed.decimalString("amount"))
        assertEquals("""{"amount":19.990}""", Json.write(parsed))
    }

    @Test
    fun `parses negative exponent and leading-minus numbers`() {
        val parsed = Json.parse("""{"a":-1,"b":1e-3,"c":2E+4,"d":-0.5}""") as Json.Obj
        assertEquals("-1", parsed.decimalString("a"))
        assertEquals("1e-3", parsed.decimalString("b"))
        assertEquals("2E+4", parsed.decimalString("c"))
        assertEquals("-0.5", parsed.decimalString("d"))
    }

    @Test
    fun `parses and renders escapes`() {
        // Built from escapes rather than literals so the expectation is readable:
        // quote, backslash, solidus, newline, carriage return, tab, backspace, form feed.
        val source = "\"a\\\"b\\\\c\\nd\\re\\tf\\bg\\fh\\/i\""
        val parsed = Json.parse(source) as Json.Str
        assertEquals("a\"b\\c\nd\re\tf\bg\u000Ch/i", parsed.value)
        // The solidus escape is optional on output; every other escape is required.
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\bg\\fh/i\"", Json.write(parsed))
    }

    @Test
    fun `renders unicode escapes for other control characters`() {
        assertEquals("\"\\u0001\"", Json.write(Json.of("\u0001")))
    }

    @Test
    fun `parses a unicode escape`() {
        assertEquals("A", (Json.parse("\"\\u0041\"") as Json.Str).value)
    }

    @Test
    fun `parses empty containers and whitespace`() {
        assertEquals(Json.emptyObject, Json.parse(" { } "))
        assertEquals(Json.Arr(emptyList()), Json.parse("\t[\n]\r"))
    }

    @Test
    fun `writes every value kind`() {
        val value = Json.obj(
            "s" to Json.of("x"),
            "i" to Json.of(1),
            "l" to Json.of(2L),
            "b" to Json.of(false),
            "nil" to Json.Null,
            "arr" to Json.arr(listOf(Json.of("a"), Json.of(1))),
        )
        assertEquals("""{"s":"x","i":1,"l":2,"b":false,"nil":null,"arr":["a",1]}""", Json.write(value))
    }

    @Test
    fun `malformed input fails typed with a field-attributable error`() {
        val cases = listOf(
            "",
            "{",
            "{]",
            """{"a"}""",
            """{"a":}""",
            """{"a":1,}""",
            "[1,",
            "[1 2]",
            """{"a":1} trailing""",
            "tru",
            "-",
            """"unterminated""",
            """"bad\q"""",
            """"bad\u00"""",
            """"bad\uZZZZ"""",
            "@",
        )
        cases.forEach { bad ->
            val failure = assertThrows<JsonDecodeException>(bad) { Json.parse(bad) }
            assertTrue(failure.code == "malformed_json", "unexpected code for '$bad': ${failure.code}")
        }
    }

    @Test
    fun `accessors name the offending field`() {
        val obj = Json.parse("""{"planId":"p-1","count":3,"nested":{"a":1},"arr":[],"flag":true}""") as Json.Obj

        assertEquals("required", assertThrows<JsonDecodeException> { obj.string("missing") }.code)
        assertEquals("missing", assertThrows<JsonDecodeException> { obj.string("missing") }.field)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.string("count") }.code)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.bool("count") }.code)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.nested("count") }.code)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.array("count") }.code)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.decimalString("flag") }.code)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.int("planId") }.code)
        assertEquals("type", assertThrows<JsonDecodeException> { obj.long("planId") }.code)
    }

    @Test
    fun `optional accessors treat explicit null as absent`() {
        val obj = Json.parse("""{"a":null,"b":"x","n":2,"flag":false}""") as Json.Obj
        assertFalse(obj.has("a"))
        assertNull(obj.opt("a"))
        assertNull(obj.stringOrNull("a"))
        assertNull(obj.intOrNull("a"))
        assertNull(obj.decimalStringOrNull("a"))
        assertNull(obj.boolOrNull("a"))
        assertEquals("x", obj.stringOrNull("b"))
        assertEquals(2, obj.intOrNull("n"))
        assertEquals("2", obj.decimalStringOrNull("n"))
        assertEquals(false, obj.boolOrNull("flag"))
        assertEquals(2L, obj.long("n"))
    }

    @Test
    fun `a quoted number is accepted as a decimal because the client was being careful`() {
        val obj = Json.parse("""{"amount":"19.99"}""") as Json.Obj
        assertEquals("19.99", obj.decimalString("amount"))
    }

    @Test
    fun `asObject coerces null and rejects a scalar payload`() {
        assertEquals(Json.emptyObject, Json.Null.asObject())
        val obj = Json.emptyObject
        assertSame(obj, obj.asObject())
        assertEquals("type", assertThrows<JsonDecodeException> { Json.of("x").asObject() }.code)
    }

    @Test
    fun `type names are reported for every kind`() {
        assertEquals("null", Json.Null.typeName())
        assertEquals("boolean", Json.of(true).typeName())
        assertEquals("number", Json.of(1).typeName())
        assertEquals("string", Json.of("x").typeName())
        assertEquals("array", Json.arr(emptyList()).typeName())
        assertEquals("object", Json.emptyObject.typeName())
    }

    @Test
    fun `a blank number is rejected at construction`() {
        assertThrows<IllegalArgumentException> { Json.Num(" ") }
    }
}
