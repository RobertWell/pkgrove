package com.pkgrove.pkgrovekit.duckdb

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.BulkLoadException
import com.pkgrove.pkgrovekit.jdbc.BulkLoadOptions
import com.pkgrove.pkgrovekit.jdbc.BulkSupport
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * HEL-234 (issue §3: DuckDbAppenderLoader): success, null/type handling across
 * every append route, typed refusals, partial-failure rollback, cancellation,
 * and autoCommit restoration — against a REAL in-memory DuckDB.
 */
class DuckDbAppenderLoaderTest {

    private lateinit var conn: Connection

    @BeforeEach
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:duckdb:")
    }

    @AfterEach
    fun tearDown() { conn.close() }

    private fun col(name: String, kind: ValueKind = ValueKind.NUMERIC) =
        Column(name, kind, kind.name)

    private fun count(table: String): Long = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT count(*) FROM $table"); rs.next(); rs.getLong(1)
    }

    // --- supports(): typed refusals ------------------------------------------

    @Test
    fun `refuses binary columns — the appender has no blob append`() {
        val s = Schema(listOf(col("id"), col("payload", ValueKind.BINARY)))
        val no = DuckDbAppenderLoader.supports(conn, "t", s) as BulkSupport.No
        assertTrue("payload" in no.reason && "batched path" in no.reason, no.reason)
    }

    @Test
    fun `refuses a non-DuckDB connection`() {
        val alien = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, _ ->
            when (m.name) {
                "isWrapperFor" -> false
                else -> throw UnsupportedOperationException(m.name)
            }
        } as Connection
        val no = DuckDbAppenderLoader.supports(alien, "t", Schema(listOf(col("id")))) as BulkSupport.No
        assertTrue("not a DuckDB connection" in no.reason, no.reason)
    }

    @Test
    fun `refuses a missing table — shape cannot be checked`() {
        val no = DuckDbAppenderLoader.supports(conn, "nope", Schema(listOf(col("id")))) as BulkSupport.No
        assertTrue("not found" in no.reason, no.reason)
    }

    @Test
    fun `refuses positional mismatch — extra or reordered physical columns`() {
        conn.createStatement().use { it.execute("CREATE TABLE shaped (id BIGINT, extra VARCHAR)") }
        val no = DuckDbAppenderLoader.supports(conn, "shaped", Schema(listOf(col("id")))) as BulkSupport.No
        assertTrue("positional" in no.reason, no.reason)
    }

    @Test
    fun `accepts a positionally matching table case-insensitively`() {
        conn.createStatement().use { it.execute("CREATE TABLE shaped (ID BIGINT, NAME VARCHAR)") }
        val s = Schema(listOf(col("id"), col("name", ValueKind.TEXT)))
        assertEquals(BulkSupport.Yes, DuckDbAppenderLoader.supports(conn, "shaped", s))
    }

    // --- bulkLoad(): success across every append route -----------------------

    @Test
    fun `streams every supported value shape and commits once`() {
        conn.createStatement().use {
            it.execute("""CREATE TABLE wide (
                b BOOLEAN, ti TINYINT, si SMALLINT, i INTEGER, bi BIGINT,
                f FLOAT, d DOUBLE, dec DECIMAL(10,2), ts TIMESTAMP,
                tsv TIMESTAMP, s VARCHAR, dt DATE, n VARCHAR)""")
        }
        val schema = Schema(listOf(
            col("b", ValueKind.BOOLEAN), col("ti"), col("si"), col("i"), col("bi"),
            col("f"), col("d"), col("dec"), col("ts", ValueKind.TEMPORAL),
            col("tsv", ValueKind.TEMPORAL), col("s", ValueKind.TEXT),
            col("dt", ValueKind.TEMPORAL), col("n", ValueKind.TEXT),
        ))
        val ldt = LocalDateTime.of(2026, 8, 9, 1, 2, 3)
        val row = Row(schema, listOf(
            true, 1.toByte(), 2.toShort(), 3, 4L,
            1.5f, 2.5, BigDecimal("99.42"), ldt,
            java.sql.Timestamp.valueOf(ldt), "text",
            LocalDate.of(2026, 8, 9),        // else-route: string-cast into DATE
            null,                            // null route
        ))
        val progress = mutableListOf<Pair<Int, Long>>()
        val report = DuckDbAppenderLoader.bulkLoad(
            conn, "wide", schema,
            sequenceOf(RowBatch(schema, listOf(row)), RowBatch(schema, listOf(row))),
            BulkLoadOptions(onProgress = { b, r -> progress += b to r }),
        )
        assertEquals(2L, report.rowsAffected)
        assertEquals(2, report.batches)
        assertTrue(report.completed)
        assertEquals(listOf(0 to 1L, 1 to 2L), progress)
        assertEquals(2L, count("wide"))
        assertTrue(conn.autoCommit, "autoCommit must be restored")
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT b, dec, ts, s, dt, n FROM wide LIMIT 1")
            rs.next()
            assertEquals(true, rs.getBoolean(1))
            assertEquals(0, BigDecimal("99.42").compareTo(rs.getBigDecimal(2)))
            assertEquals(ldt, rs.getTimestamp(3).toLocalDateTime())
            assertEquals("text", rs.getString(4))
            assertEquals(LocalDate.of(2026, 8, 9), rs.getDate(5).toLocalDate())
            rs.getString(6); assertTrue(rs.wasNull())
        }
    }

    // --- bulkLoad(): failure and cancellation --------------------------------

    @Test
    fun `partial failure rolls everything back — nothing committed`() {
        conn.createStatement().use { it.execute("CREATE TABLE strict (id BIGINT NOT NULL)") }
        val schema = Schema(listOf(col("id")))
        val ex = assertThrows<BulkLoadException> {
            DuckDbAppenderLoader.bulkLoad(conn, "strict", schema, sequenceOf(
                RowBatch(schema, listOf(Row(schema, listOf(1L as Any?)))),
                RowBatch(schema, listOf(Row(schema, listOf(null)))),   // NOT NULL trap
            ))
        }
        assertEquals(0L, ex.report.rowsAffected, "all-or-nothing: report must claim nothing")
        assertEquals(false, ex.report.completed)
        assertEquals(0L, count("strict"), "rollback must discard streamed rows")
        assertTrue(conn.autoCommit, "autoCommit must be restored after failure")
    }

    @Test
    fun `cancellation aborts the load and commits nothing`() {
        conn.createStatement().use { it.execute("CREATE TABLE c (id BIGINT)") }
        val schema = Schema(listOf(col("id")))
        val token = CancelToken.none()
        val batches = sequence {
            yield(RowBatch(schema, listOf(Row(schema, listOf(1L as Any?)))))
            token.cancel()
            yield(RowBatch(schema, listOf(Row(schema, listOf(2L as Any?)))))
        }
        val ex = assertThrows<BulkLoadException> {
            DuckDbAppenderLoader.bulkLoad(conn, "c", schema, batches,
                BulkLoadOptions(cancelToken = token))
        }
        assertTrue(ex.cause is OperationCancelledException, "${ex.cause}")
        assertEquals(0L, count("c"))
    }

    @Test
    fun `loader reports its engine name`() {
        assertEquals("duckdb-appender", DuckDbAppenderLoader.name)
    }
}
