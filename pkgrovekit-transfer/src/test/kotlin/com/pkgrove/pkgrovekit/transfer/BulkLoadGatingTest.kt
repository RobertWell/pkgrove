package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.BulkLoader
import com.pkgrove.pkgrovekit.jdbc.DatabaseKey
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * HEL-161: the opt-in bulk-load path and its gates, against LIVE DuckDB
 * (the Appender loader runs for real here; the Postgres COPY loader has its
 * own live IT). Every refusal must fall back to batched INSERT with a
 * BULK_LOAD_UNAVAILABLE warning — same rows, never a failure.
 */
class BulkLoadGatingTest {

    private lateinit var source: Connection
    private lateinit var target: Connection

    @BeforeEach
    fun setUp() {
        source = DriverManager.getConnection("jdbc:duckdb:")
        target = DriverManager.getConnection("jdbc:duckdb:")
        source.createStatement().use { st ->
            st.execute("""CREATE TABLE src (id BIGINT, label VARCHAR, price DECIMAL(10,2),
                          ts TIMESTAMP, ok BOOLEAN)""")
            st.execute("""INSERT INTO src
                SELECT range, CASE WHEN range % 7 = 0 THEN NULL ELSE '標籤,"x"-' || range END,
                       CASE WHEN range % 5 = 0 THEN NULL ELSE range * 2.25 END,
                       TIMESTAMP '2026-07-01 09:00:00' + INTERVAL (range) HOUR, range % 3 = 0
                FROM range(500)""")
        }
    }

    @AfterEach
    fun tearDown() { source.close(); target.close() }

    private fun readAll(c: Connection, table: String): List<List<Any?>> =
        JdbcReader.open(c, "SELECT * FROM \"$table\" ORDER BY \"id\"").use { s ->
            s.toList().map { it.values }
        }

    private fun bulkWarning(r: com.pkgrove.pkgrovekit.core.OperationReport) =
        r.warnings.filter { it.code == "BULK_LOAD_UNAVAILABLE" }

    @Test
    fun `bulk path loads via the appender with rows identical to the batched path`() {
        val batched = Transfer.run(source, "SELECT * FROM src", emptyList(),
                                   target, DuckDbDialect, "dest_batched")
        val bulk = Transfer.run(source, "SELECT * FROM src", emptyList(),
                                target, DuckDbDialect, "dest_bulk",
                                Transfer.Options(useBulkLoad = true, readBatchSize = 128))
        assertTrue(bulk.completed)
        assertEquals(500L, bulk.rowsAffected)
        assertTrue(bulkWarning(bulk).isEmpty()) { "bulk path must actually be used" }
        assertEquals(4, bulk.batches)                  // 128*3 + 116
        // identical outcome: same rows, same nulls, same values
        assertEquals(readAll(target, "dest_batched"), readAll(target, "dest_bulk"))
        assertEquals(batched.rowsAffected, bulk.rowsAffected)
    }

    @Test
    fun `bulk path reports progress without row values`() {
        val progress = mutableListOf<Pair<Int, Long>>()
        Transfer.run(source, "SELECT * FROM src WHERE id < 300", emptyList(),
                     target, DuckDbDialect, "dest",
                     Transfer.Options(useBulkLoad = true, readBatchSize = 100,
                                      onProgress = { b, r -> progress += b to r }))
        assertEquals(listOf(0 to 100L, 1 to 200L, 2 to 300L), progress)
    }

    @Test
    fun `upsert keys force fallback to the batched upsert with a warning`() {
        target.createStatement().use { st ->
            st.execute("CREATE TABLE dest (id BIGINT PRIMARY KEY, label VARCHAR, price DECIMAL(10,2), ts TIMESTAMP, ok BOOLEAN)")
            st.execute("INSERT INTO dest VALUES (1, 'stale', 0, NULL, false)")
        }
        val report = Transfer.run(
            source, "SELECT * FROM src WHERE id < 10", emptyList(),
            target, DuckDbDialect, "dest",
            Transfer.Options(useBulkLoad = true, upsertKeys = listOf("id"),
                             mode = SqlDialect.TargetMode.APPEND))
        assertTrue(report.completed)
        assertEquals(1, bulkWarning(report).size)
        assertTrue(bulkWarning(report).single().message.contains("upsert"))
        // upsert semantics were honored by the fallback: row 1 was updated, not duplicated
        assertEquals(10, readAll(target, "dest").size)
        JdbcReader.open(target, "SELECT \"label\" FROM \"dest\" WHERE \"id\" = 1").use { s ->
            assertEquals("標籤,\"x\"-1", s.toList().single()["label"])
        }
    }

    @Test
    fun `a dialect without a bulk loader falls back with a warning`() {
        val noBulk = object : SqlDialect by DuckDbDialect {
            override fun bulkLoader(): BulkLoader? = null
        }
        val report = Transfer.run(source, "SELECT * FROM src WHERE id < 20", emptyList(),
                                  target, noBulk, "dest",
                                  Transfer.Options(useBulkLoad = true))
        assertTrue(report.completed)
        assertEquals(20L, report.rowsAffected)
        assertTrue(bulkWarning(report).single().message.contains("no bulk loader"))
    }

    @Test
    fun `a loader schema refusal falls back and still writes every row`() {
        source.createStatement().use { st ->
            st.execute("CREATE TABLE bin_src (id BIGINT, payload BLOB)")
            st.execute("INSERT INTO bin_src SELECT range, encode('pay-' || range) FROM range(25)")
        }
        val report = Transfer.run(source, "SELECT * FROM bin_src", emptyList(),
                                  target, DuckDbDialect, "bin_dest",
                                  Transfer.Options(useBulkLoad = true))
        assertTrue(report.completed)
        assertEquals(25L, report.rowsAffected)
        assertTrue(bulkWarning(report).single().message.contains("BINARY"))
        assertEquals(25, readAll(target, "bin_dest").size)
    }

    @Test
    fun `a caller-supplied writer owns the write path so bulk falls back`() {
        val report = Transfer.runToWriter(
            source, "SELECT * FROM src WHERE id < 15", emptyMap(),
            target, DuckDbDialect, "dest",
            Transfer.Options(useBulkLoad = true),
            Transfer.TargetWriter { dml, b, o -> JdbcBatchWriter.write(target, dml, b, o) })
        assertTrue(report.completed)
        assertEquals(15L, report.rowsAffected)
        assertTrue(bulkWarning(report).single().message.contains("TargetWriter"))
    }

    @Test
    fun `without opting in there is never a bulk warning and never a bulk path`() {
        val report = Transfer.run(source, "SELECT * FROM src WHERE id < 5", emptyList(),
                                  target, DuckDbDialect, "dest")
        assertTrue(report.completed)
        assertTrue(bulkWarning(report).isEmpty())
    }

    @Test
    fun `relay sink bulkLoad flag drives the appender end to end`() {
        // Relay needs DataSources; in-memory DuckDB is per-connection, so lease
        // the live connections through a non-closing proxy.
        fun ds(c: Connection): DataSource =
            java.lang.reflect.Proxy.newProxyInstance(
                DataSource::class.java.classLoader, arrayOf(DataSource::class.java)
            ) { _, m, _ ->
                when (m.name) {
                    "getConnection" -> java.lang.reflect.Proxy.newProxyInstance(
                        Connection::class.java.classLoader, arrayOf(Connection::class.java)
                    ) { _, cm, ca ->
                        if (cm.name == "close") null   // Relay leases must not close ours
                        else try { cm.invoke(c, *(ca ?: emptyArray())) }
                             catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
                    }
                    else -> throw UnsupportedOperationException(m.name)
                }
            } as DataSource

        val sales = object : DatabaseKey("bulk-sales") {}
        val marts = object : DatabaseKey("bulk-marts") {}
        Relay.build {
            database(sales, ds(source), DuckDbDialect)
            database(marts, ds(target), DuckDbDialect)
        }.use { relay ->
            val plan = relay.transfer("bulk-sync") {
                from(sales) { query("SELECT * FROM src WHERE id < :cap"); bind("cap", 100L) }
                to(marts, "relay_bulk") { bulkLoad() }
            }
            val outcome = relay.execute(plan)
            assertTrue(outcome is TransferOutcome.Completed) { "got $outcome" }
            val report = (outcome as TransferOutcome.Completed).report
            assertEquals(100L, report.rowsAffected)
            assertTrue(bulkWarning(report).isEmpty()) { "relay bulkLoad() must use the appender" }
            assertEquals(100, readAll(target, "relay_bulk").size)
        }
    }

    @Test
    fun `appender preserves nulls decimals and timestamps exactly`() {
        Transfer.run(source, "SELECT * FROM src WHERE id IN (0, 5, 7, 35)", emptyList(),
                     target, DuckDbDialect, "fidelity",
                     Transfer.Options(useBulkLoad = true))
        JdbcReader.open(target, "SELECT * FROM \"fidelity\" ORDER BY \"id\"").use { s ->
            val rows = s.toList()
            assertEquals(4, rows.size)
            val r0 = rows[0]   // id 0: label NULL (0%7), price NULL (0%5)
            assertTrue(r0["label"] == null && r0["price"] == null)
            val r5 = rows[1]   // id 5: price NULL, label present with comma+quote
            assertEquals("標籤,\"x\"-5", r5["label"])
            assertTrue(r5["price"] == null)
            val r7 = rows[2]   // id 7: label NULL, price 15.75
            assertTrue(r7["label"] == null)
            assertEquals(0, BigDecimal("15.75").compareTo(r7["price"] as BigDecimal))
            val r35 = rows[3]  // id 35: both null-branches hit (35%7=0, 35%5=0)
            assertTrue(r35["label"] == null && r35["price"] == null)
            val ts35 = when (val v = r35["ts"]) {
                is java.sql.Timestamp -> v.toLocalDateTime()
                is java.time.LocalDateTime -> v
                else -> error("unexpected ts type: $v")
            }
            assertEquals(java.time.LocalDateTime.of(2026, 7, 2, 20, 0), ts35)
            assertFalse(rows.any { it["ts"] == null })
        }
    }
}
