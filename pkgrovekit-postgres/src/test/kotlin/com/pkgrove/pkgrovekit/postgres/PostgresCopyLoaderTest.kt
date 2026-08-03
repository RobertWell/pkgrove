package com.pkgrove.pkgrovekit.postgres

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.BulkSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection

/** HEL-161: the COPY CSV serializer must round-trip every shape
 *  [PostgresDialect.bindValue] can emit, and [PostgresCopyLoader.supports]
 *  must refuse with typed reasons (never fail late inside COPY). */
class PostgresCopyLoaderTest {

    private fun csv(vararg values: Any?): String {
        val sb = StringBuilder()
        PostgresCopyLoader.appendCsvRow(sb, values.toList())
        return sb.toString()
    }

    // ── serialization ───────────────────────────────────────────────────────

    @Test
    fun `null is an unquoted empty field and empty string is quoted`() {
        // pg CSV: unquoted empty = NULL; quoted empty = '' — they MUST differ
        assertEquals(",\"\"\n", csv(null, ""))
    }

    @Test
    fun `plain values join with commas and end with newline`() {
        assertEquals("1,abc,true\n", csv(1L, "abc", true))
    }

    @Test
    fun `big decimal never uses scientific notation`() {
        assertEquals("0.00000001\n", csv(BigDecimal("1E-8")))
        assertEquals("120000000\n", csv(BigDecimal("1.2E+8")))
    }

    @Test
    fun `delimiter quote and newline characters force quoting with doubled quotes`() {
        assertEquals("\"a,b\"\n", csv("a,b"))
        assertEquals("\"say \"\"hi\"\"\"\n", csv("say \"hi\""))
        assertEquals("\"line1\nline2\"\n", csv("line1\nline2"))
        assertEquals("\"cr\rhere\"\n", csv("cr\rhere"))
    }

    @Test
    fun `unicode text passes through unquoted when unremarkable`() {
        assertEquals("標籤-7\n", csv("標籤-7"))
    }

    @Test
    fun `pgobject serializes its value including json with embedded quotes`() {
        val json = PGobject().apply { type = "jsonb"; value = """{"k":"v","n":1}""" }
        assertEquals("\"{\"\"k\"\":\"\"v\"\",\"\"n\"\":1}\"\n", csv(json))
    }

    @Test
    fun `timestamps and uuids use their pg-parsable toString`() {
        val ts = java.sql.Timestamp.valueOf("2026-07-01 09:30:00.123")
        val uuid = java.util.UUID.fromString("6f873bc6-83f5-4ea1-93f6-4f3bca621077")
        assertEquals("2026-07-01 09:30:00.123,6f873bc6-83f5-4ea1-93f6-4f3bca621077\n", csv(ts, uuid))
    }

    // ── supports() refusals ─────────────────────────────────────────────────

    private fun fakeConnection(wrapsPg: Boolean): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader,
                               arrayOf(Connection::class.java)) { _, m, _ ->
            when (m.name) {
                "isWrapperFor" -> wrapsPg
                else -> throw UnsupportedOperationException(m.name)
            }
        } as Connection

    @Test
    fun `binary columns are refused with the column named`() {
        val schema = Schema(listOf(
            Column("id", ValueKind.NUMERIC, "int8", precision = 19),
            Column("payload", ValueKind.BINARY, "bytea")))
        val s = PostgresCopyLoader.supports(fakeConnection(wrapsPg = true), schema)
        assertTrue(s is BulkSupport.No)
        assertTrue((s as BulkSupport.No).reason.contains("payload"))
    }

    @Test
    fun `non-pgjdbc connections are refused and pg connections accepted`() {
        val schema = Schema(listOf(Column("id", ValueKind.NUMERIC, "int8", precision = 19)))
        assertTrue(PostgresCopyLoader.supports(fakeConnection(false), schema) is BulkSupport.No)
        assertEquals(BulkSupport.Yes, PostgresCopyLoader.supports(fakeConnection(true), schema))
    }
}
