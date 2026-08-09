package com.pkgrove.pkgrovekit.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The internal manifest/checkpoint JSON codec: strictness is the feature. */
class JsonTest {

    @Test
    fun `quote escapes exactly what JSON requires`() {
        assertEquals("\"plain\"", Json.quote("plain"))
        assertEquals("\"a\\\"b\\\\c\"", Json.quote("a\"b\\c"))
        assertEquals("\"n\\nr\\rt\\t\"", Json.quote("n\nr\rt\t"))
        assertEquals("\"ctl\\u0001\"", Json.quote("ctl\u0001"))
        assertEquals("\"中文\"", Json.quote("中文"))
    }

    @Test
    fun `parses the full value zoo`() {
        val v = Json.parse(
            """  {"s":"str","n":-12.5e2,"i":42,"t":true,"f":false,"z":null,
                  "arr":[1,"two",[],{}],"obj":{"nested":{"deep":"v"}},"esc":"a\/b\b\fA"} """,
        ) as Map<*, *>
        assertEquals("str", v["s"])
        assertEquals("-12.5e2", (v["n"] as Json.RawNumber).text)
        assertEquals("42", (v["i"] as Json.RawNumber).text)
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertNull(v["z"])
        val arr = v["arr"] as List<*>
        assertEquals(4, arr.size)
        assertEquals("two", arr[1])
        assertEquals(emptyList<Any?>(), arr[2])
        assertEquals(emptyMap<String, Any?>(), arr[3])
        assertEquals("v", ((v["obj"] as Map<*, *>)["nested"] as Map<*, *>)["deep"])
        assertEquals("a/b\b\u000CA", v["esc"])
        // top-level scalars
        assertEquals(true, Json.parse("true"))
        assertNull(Json.parse("null"))
        assertEquals("7", (Json.parse(" 7 ") as Json.RawNumber).text)
        assertEquals("bare", Json.parse("\"bare\""))
    }

    @Test
    fun `rejects malformed documents`() {
        val bad = listOf(
            "", "   ", "{", "[", "\"unterminated", "{\"a\"1}", "{\"a\":1,}",
            "[1 2]", "{\"a\":}", "tru", "-", "\"bad\\q\"", "\"bad\\u00\"",
            "{\"a\":1}x", "{1:2}", "nul", "@",
        )
        bad.forEach { doc ->
            assertThrows(IllegalArgumentException::class.java, { Json.parse(doc) },
                         "expected rejection of: $doc")
        }
    }

    @Test
    fun `checksum helpers agree between whole-buffer and streaming forms`() {
        val bytes = ByteArray(100_000) { (it * 13).toByte() }
        val whole = Checksum.sha256(bytes)
        val streamed = Checksum.sha256Of(ContentSource.of(bytes))
        assertEquals(whole, streamed)
        assertEquals(ChecksumAlgorithm.SHA256, whole.algorithm)
        // single-byte read path of the verifying stream also verifies
        val v = ChecksumVerifyingInputStream(bytes.inputStream(), whole, ObjectKey("k/v"))
        var b = v.read()
        var n = 0L
        while (b >= 0) {
            n++
            b = v.read()
        }
        assertEquals(100_000L, n)
        v.close()

        // wrong expectation caught on the single-byte path too
        val bad = ChecksumVerifyingInputStream(
            bytes.inputStream(), Checksum.sha256("no".toByteArray()), ObjectKey("k/v"),
        )
        assertThrows(ChecksumMismatchException::class.java) {
            while (bad.read() >= 0) { /* drain */ }
        }
        // non-sha256 expectations are refused up front
        assertThrows(IllegalArgumentException::class.java) {
            ChecksumVerifyingInputStream(
                bytes.inputStream(), Checksum(ChecksumAlgorithm.CRC32C, "AAAA"), ObjectKey("k/v"),
            )
        }
    }

    @Test
    fun `manifest parse rejects structural drift`() {
        val store = InMemoryObjectStore()
        val schema = com.pkgrove.pkgrovekit.core.Schema(
            listOf(com.pkgrove.pkgrovekit.core.Column("id", com.pkgrove.pkgrovekit.core.ValueKind.NUMERIC, "INT")),
        )
        val manifest = DatasetManifest(
            formatId = "jsonl-v1", runId = "r", createdAt = java.time.Instant.parse("2026-08-09T00:00:00Z"),
            schema = schema, parts = emptyList(), totalRows = 0,
        )
        assertEquals(manifest, DatasetManifest.parse(manifest.toJson()))
        val cases = listOf(
            "[]", // not an object
            "{}", // no schema
            manifest.toJson().replace("\"totalRows\":0", "\"totalRows\":\"zero\""),
            manifest.toJson().replace("\"runId\":\"r\"", "\"runId\":3"),
            manifest.toJson().replace("\"parts\":[]", "\"noParts\":[]"),
        )
        cases.forEach { doc ->
            assertThrows(IllegalArgumentException::class.java, { DatasetManifest.parse(doc) },
                         "expected rejection of: $doc")
        }
        assertTrue(store.list("").none()) // nothing touched storage
    }
}
