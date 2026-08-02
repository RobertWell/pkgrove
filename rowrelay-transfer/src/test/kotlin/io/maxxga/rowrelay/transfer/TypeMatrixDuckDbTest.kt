package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.ConversionException
import io.maxxga.rowrelay.core.ConversionPolicy
import io.maxxga.rowrelay.core.Schema
import io.maxxga.rowrelay.core.ValueKind
import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbc.SqlDialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-168: the portion of the column-type matrix that does not require a live
 * Oracle — a DuckDB → DuckDB round trip over the boundary fixtures the acceptance
 * calls out (NULL, empty, Unicode, max precision/scale, fractional temporal,
 * large text/binary, binary zero bytes) plus the unsupported-type error contract.
 * The live Oracle↔DuckDB matrix rides in integration-tests (OracleTypeMatrixIT).
 *
 * Everything goes through the same read → infer schema → typeFor → bindValue →
 * write → read pipeline the transfer uses, so a value that survives here is
 * proven not silently truncated, rounded, tz-shifted, or stringified.
 */
class TypeMatrixDuckDbTest {

    private lateinit var source: Connection
    private lateinit var target: Connection

    @BeforeEach
    fun setUp() {
        source = DriverManager.getConnection("jdbc:duckdb:")
        target = DriverManager.getConnection("jdbc:duckdb:")
    }

    @AfterEach
    fun tearDown() { source.close(); target.close() }

    private fun readBack(sql: String) =
        JdbcReader.open(target, sql).use { it.toList() }

    @Test
    fun `text boundaries — null, empty, unicode, large payload round-trip intact`() {
        val big = "行".repeat(50_000)   // 50k multibyte chars
        source.createStatement().use { st ->
            st.execute("CREATE TABLE s (id BIGINT, v VARCHAR)")
        }
        source.prepareStatement("INSERT INTO s VALUES (?, ?)").use { ps ->
            ps.setLong(1, 1); ps.setString(2, "");            ps.addBatch()   // empty string (NOT null in DuckDB)
            ps.setLong(1, 2); ps.setString(2, "café ☃ 標籤"); ps.addBatch()   // unicode
            ps.setLong(1, 3); ps.setString(2, big);           ps.addBatch()   // large payload
            ps.setLong(1, 4); ps.setNull(2, java.sql.Types.VARCHAR); ps.addBatch()  // NULL
            ps.executeBatch()
        }
        val r = Transfer.run(source, "SELECT * FROM s ORDER BY id", emptyList(),
            target, DuckDbDialect, "t", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        assertEquals(4L, r.rowsAffected)
        val rows = readBack("SELECT v FROM t ORDER BY id")
        assertEquals("", rows[0]["v"])                 // empty preserved, not turned into null
        assertEquals("café ☃ 標籤", rows[1]["v"])       // unicode intact
        assertEquals(big, rows[2]["v"])                // full 50k payload, not truncated
        assertNull(rows[3]["v"])                        // null preserved
    }

    @Test
    fun `numeric boundaries — max precision, big integers, doubles round-trip without rounding`() {
        source.createStatement().use { st ->
            st.execute("CREATE TABLE s (id BIGINT, dec DECIMAL(38,10), big BIGINT, dbl DOUBLE)")
            st.execute("""INSERT INTO s VALUES
                (1, 1234567890123456789.0123456789, 9223372036854775807, 3.141592653589793),
                (2, -0.0000000001, -9223372036854775808, -2.5),
                (3, NULL, NULL, NULL)""")
        }
        Transfer.run(source, "SELECT * FROM s ORDER BY id", emptyList(),
            target, DuckDbDialect, "t", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        val rows = readBack("SELECT * FROM t ORDER BY id")
        // full 38-digit precision, no rounding
        assertEquals(0, BigDecimal("1234567890123456789.0123456789").compareTo(rows[0]["dec"] as BigDecimal))
        assertEquals(Long.MAX_VALUE, rows[0]["big"])                  // 9223372036854775807
        assertEquals(Long.MIN_VALUE, rows[1]["big"])                  // -9223372036854775808
        assertEquals(3.141592653589793, rows[0]["dbl"])
        assertNull(rows[2]["dec"]); assertNull(rows[2]["big"]); assertNull(rows[2]["dbl"])
    }

    @Test
    fun `boolean round-trips true, false, and null`() {
        source.createStatement().use { st ->
            st.execute("CREATE TABLE s (id BIGINT, flag BOOLEAN)")
            st.execute("INSERT INTO s VALUES (1, true), (2, false), (3, NULL)")
        }
        Transfer.run(source, "SELECT * FROM s ORDER BY id", emptyList(),
            target, DuckDbDialect, "t", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        val rows = readBack("SELECT flag FROM t ORDER BY id")
        assertEquals(true, rows[0]["flag"]); assertEquals(false, rows[1]["flag"]); assertNull(rows[2]["flag"])
    }

    @Test
    fun `temporal boundaries — fractional precision, date, time, timestamp, tz preserved`() {
        source.createStatement().use { st ->
            st.execute("CREATE TABLE s (id BIGINT, d DATE, t TIME, ts TIMESTAMP, tz TIMESTAMPTZ)")
            st.execute("""INSERT INTO s VALUES
                (1, DATE '2026-08-02', TIME '13:45:30.123456',
                    TIMESTAMP '2026-08-02 13:45:30.123456', TIMESTAMPTZ '2026-08-02 13:45:30.123456+02:00'),
                (2, NULL, NULL, NULL, NULL)""")
        }
        Transfer.run(source, "SELECT * FROM s ORDER BY id", emptyList(),
            target, DuckDbDialect, "t", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        val rows = readBack("SELECT * FROM t ORDER BY id")
        assertEquals(java.time.LocalDate.of(2026, 8, 2), rows[0]["d"])
        // microsecond fraction preserved, not truncated to seconds
        assertEquals(java.time.LocalTime.parse("13:45:30.123456"), rows[0]["t"])
        assertEquals(java.time.LocalDateTime.parse("2026-08-02T13:45:30.123456"), rows[0]["ts"])
        // the tz-aware value survives as an instant-equivalent (offset normalized)
        val tz = rows[0]["tz"]
        assertTrue(tz is java.time.OffsetDateTime, "TIMESTAMPTZ carries offset, got ${tz?.javaClass}")
        assertEquals(java.time.OffsetDateTime.parse("2026-08-02T13:45:30.123456+02:00").toInstant(),
            (tz as java.time.OffsetDateTime).toInstant())
        assertNull(rows[1]["d"]); assertNull(rows[1]["tz"])
    }

    @Test
    fun `binary boundaries — bytes, zero byte, empty, null round-trip byte-exact`() {
        source.createStatement().use { st ->
            st.execute("CREATE TABLE s (id BIGINT, b BLOB)")
            st.execute("""INSERT INTO s VALUES
                (1, '\xDE\xAD\xBE\xEF'::BLOB),
                (2, '\x00\x00'::BLOB),
                (3, ''::BLOB),
                (4, NULL)""")
        }
        Transfer.run(source, "SELECT * FROM s ORDER BY id", emptyList(),
            target, DuckDbDialect, "t", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        val rows = readBack("SELECT b FROM t ORDER BY id")
        assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            rows[0]["b"] as ByteArray)
        assertArrayEquals(byteArrayOf(0, 0), rows[1]["b"] as ByteArray)   // zero bytes are not NUL-terminated
        assertArrayEquals(ByteArray(0), rows[2]["b"] as ByteArray)        // empty blob, distinct from null
        assertNull(rows[3]["b"])
    }

    @Test
    fun `unsupported OTHER type is rejected with an actionable error (not silent coercion)`() {
        val schema = Schema(listOf(
            Column("id", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("geom", ValueKind.OTHER, "GEOMETRY")))
        val ex = assertThrows(ConversionException::class.java) {
            DuckDbDialect.createTableDdl("t", schema, SqlDialect.TargetMode.CREATE)
        }
        val msg = ex.message ?: ""
        // names the column, its kind, the source type, and the adapter path
        assertTrue("geom" in msg, msg)
        assertTrue("GEOMETRY" in msg, msg)
        assertTrue("OTHER" in msg, msg)
        assertTrue("ConversionPolicy.STRINGIFY" in msg, msg)
        assertEquals("geom", ex.column)

        // and REJECT via adaptSchema carries the same actionable detail
        val ex2 = assertThrows(ConversionException::class.java) {
            DuckDbDialect.adaptSchema(schema, ConversionPolicy.REJECT) {}
        }
        assertTrue("geom" in (ex2.message ?: ""))
        assertTrue("GEOMETRY" in (ex2.message ?: ""))
    }

    @Test
    fun `STRINGIFY adapter path carries the unsupported column as text with a warning`() {
        val schema = Schema(listOf(
            Column("id", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("geom", ValueKind.OTHER, "GEOMETRY")))
        val warnings = mutableListOf<String>()
        val adapted = DuckDbDialect.adaptSchema(schema, ConversionPolicy.STRINGIFY) { warnings += it.code }
        assertEquals(ValueKind.TEXT, adapted["geom"].kind)   // now a text column
        assertTrue("stringified" in warnings)                 // and it warned, not silent
    }
}
