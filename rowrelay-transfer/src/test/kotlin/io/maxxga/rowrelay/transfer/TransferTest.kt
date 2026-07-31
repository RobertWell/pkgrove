package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbc.SqlDialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager

/** End-to-end transfer between two REAL databases (separate DuckDB instances):
 *  SQL-in/data-out, schema inference, target modes, append, batches, progress,
 *  and honest failure on an incompatible target. */
class TransferTest {

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
                SELECT range, '標籤-' || range, range * 2.25,
                       TIMESTAMP '2026-07-01 09:00:00' + INTERVAL (range) HOUR, range % 3 = 0
                FROM range(95)""")
        }
    }

    @AfterEach
    fun tearDown() { source.close(); target.close() }

    private fun targetCount(table: String): Long =
        target.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM \"$table\"")
            rs.next(); rs.getLong(1)
        }

    @Test
    fun `sql-in data-out transfer with inferred schema and unicode fidelity`() {
        val progress = mutableListOf<Pair<Int, Long>>()
        val report = Transfer.run(
            source, "SELECT * FROM src WHERE id < ? ORDER BY id", listOf(60L),
            target, DuckDbDialect, "dest",
            Transfer.Options(readBatchSize = 25,
                             onProgress = { b, r -> progress += b to r }))
        assertTrue(report.completed)
        assertEquals(60L, report.rowsAffected)
        assertEquals(3, report.batches)              // 25+25+10
        assertEquals(listOf(0 to 25L, 1 to 50L, 2 to 60L), progress)
        assertEquals(60L, targetCount("dest"))

        JdbcReader.open(target, "SELECT * FROM \"dest\" WHERE \"id\" = 7").use { s ->
            val row = s.toList().single()
            assertEquals("標籤-7", row["label"])                       // unicode intact
            assertEquals(0, BigDecimal("15.75").compareTo(row["price"] as BigDecimal))
            assertEquals(true, row["ok"] != null)
        }
    }

    @Test
    fun `append mode writes into an existing compatible table`() {
        Transfer.run(source, "SELECT * FROM src WHERE id < 10", emptyList(),
                     target, DuckDbDialect, "dest")
        val report = Transfer.run(
            source, "SELECT * FROM src WHERE id >= 10 AND id < 30", emptyList(),
            target, DuckDbDialect, "dest",
            Transfer.Options(mode = SqlDialect.TargetMode.APPEND))
        assertTrue(report.completed)
        assertEquals(30L, targetCount("dest"))
    }

    @Test
    fun `fail-if-exists refuses to clobber and replace clobbers explicitly`() {
        Transfer.run(source, "SELECT id FROM src LIMIT 1", emptyList(),
                     target, DuckDbDialect, "dest")
        assertThrows(Exception::class.java) {
            Transfer.run(source, "SELECT id FROM src LIMIT 1", emptyList(),
                         target, DuckDbDialect, "dest",
                         Transfer.Options(mode = SqlDialect.TargetMode.FAIL_IF_EXISTS))
        }
        val report = Transfer.run(
            source, "SELECT id, label FROM src WHERE id < 5", emptyList(),
            target, DuckDbDialect, "dest",
            Transfer.Options(mode = SqlDialect.TargetMode.CREATE_OR_REPLACE))
        assertTrue(report.completed)
        assertEquals(5L, targetCount("dest"))
    }

    @Test
    fun `incompatible append surfaces a failed report not silence`() {
        target.createStatement().use {
            it.execute("CREATE TABLE dest (only_one_col VARCHAR)")
        }
        val ex = assertThrows(JdbcBatchWriter.BatchWriteException::class.java) {
            Transfer.run(source, "SELECT * FROM src LIMIT 10", emptyList(),
                         target, DuckDbDialect, "dest",
                         Transfer.Options(mode = SqlDialect.TargetMode.APPEND))
        }
        assertEquals(false, ex.report.completed)
        assertEquals(0L, ex.report.rowsAffected)
    }

    @Test
    fun `per-chunk commit preserves completed chunks across a mid-transfer failure`() {
        // target table narrower than needed appears only after some chunks: use
        // a NOT NULL trap fired by a poisoned source row instead.
        source.createStatement().use {
            it.execute("INSERT INTO src VALUES (NULL, 'poison', 1.0, NULL, false)")
        }
        target.createStatement().use {
            it.execute("CREATE TABLE dest (id BIGINT NOT NULL, label VARCHAR, " +
                       "price DECIMAL(10,2), ts TIMESTAMP, ok BOOLEAN)")
        }
        val ex = assertThrows(JdbcBatchWriter.BatchWriteException::class.java) {
            Transfer.run(
                source, "SELECT * FROM src ORDER BY id NULLS LAST", emptyList(),
                target, DuckDbDialect, "dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND, readBatchSize = 20,
                                 commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1)))
        }
        // 95 good rows in batches of 20 -> 4 full chunks (80) committed; the
        // 5th batch (15 good + poison) fails and rolls back.
        assertEquals(80L, ex.report.rowsAffected)
        assertEquals(4, ex.report.failedBatchIndex)
        assertEquals(80L, targetCount("dest"))
    }
}
