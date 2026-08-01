package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.OperationCancelledException
import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.core.RowBatch
import io.maxxga.rowrelay.core.ValueKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime

/** JDBC path against a REAL database (in-memory DuckDB): streaming, schema
 *  discovery, batches, bounded memory shape, batch writes + commit policies. */
class JdbcPathTest {

    private lateinit var conn: Connection

    @BeforeEach
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:duckdb:")
        conn.createStatement().use { st ->
            st.execute("""CREATE TABLE t (id BIGINT NOT NULL, name VARCHAR,
                          price DECIMAL(10,2), ts TIMESTAMP, d DATE, ok BOOLEAN)""")
            st.execute("""INSERT INTO t
                SELECT range, 'row-' || range, range * 1.5,
                       TIMESTAMP '2026-01-01 00:00:00' + INTERVAL (range) MINUTE,
                       DATE '2026-01-01' + INTERVAL (range) DAY, range % 2 = 0
                FROM range(100)""")
        }
    }

    @AfterEach
    fun tearDown() { conn.close() }

    @Test
    fun `schema discovery without dtos`() {
        JdbcReader.open(conn, "SELECT * FROM t LIMIT 1").use { rows ->
            val s = rows.schema
            assertEquals(listOf("id", "name", "price", "ts", "d", "ok"),
                         s.columns.map { it.name })
            assertEquals(ValueKind.NUMERIC, s["id"].kind)
            assertEquals(ValueKind.TEXT, s["name"].kind)
            assertEquals(10, s["price"].precision)
            assertEquals(2, s["price"].scale)
            assertEquals(ValueKind.TEMPORAL, s["ts"].kind)
            assertEquals(false, s["ts"].timeZoned)
            assertEquals(ValueKind.BOOLEAN, s["ok"].kind)
            // nullability is the DRIVER's report and tri-state by design;
            // DuckDB reports result-set columns as nullable even under NOT
            // NULL — assert the field is populated, not the driver's opinion.
            assertTrue(s["id"].nullable != null)
        }
    }

    @Test
    fun `parameterized read normalizes values to jdk types`() {
        JdbcReader.open(conn, "SELECT * FROM t WHERE id = ?", listOf(3L)).use { rows ->
            val r = rows.toList().single()
            assertEquals(3L, r["id"])
            assertEquals("row-3", r["name"])
            assertEquals(0, BigDecimal("4.50").compareTo(r["price"] as BigDecimal))
            assertTrue(r["ts"] is LocalDateTime)
            assertTrue(r["d"] is LocalDate)
            assertEquals(false, r["ok"])
            assertTrue(rows.warnings.isEmpty())
        }
    }

    @Test
    fun `streaming batches hold one batch at a time and cover all rows`() {
        JdbcReader.open(conn, "SELECT * FROM t ORDER BY id",
                        options = JdbcReader.ReadOptions(fetchSize = 8)).use { rows ->
            val sizes = rows.batches(30).map { it.size }.toList()
            assertEquals(listOf(30, 30, 30, 10), sizes)
            assertEquals(100L, rows.rowsRead)
        }
    }

    @Test
    fun `cancellation aborts mid-stream`() {
        val token = CancelToken.none()
        JdbcReader.open(conn, "SELECT * FROM t ORDER BY id",
                        options = JdbcReader.ReadOptions(cancelToken = token)).use { rows ->
            repeat(5) { rows.next() }
            token.cancel()
            assertThrows(OperationCancelledException::class.java) { rows.hasNext() }
        }
    }

    private fun sourceBatches(n: Int, batchSize: Int): Pair<io.maxxga.rowrelay.core.Schema, List<RowBatch>> {
        JdbcReader.open(conn, "SELECT id, name FROM t ORDER BY id LIMIT ?", listOf(n)).use { rows ->
            val batches = rows.batches(batchSize).toList()
            return rows.schema to batches
        }
    }

    @Test
    fun `batch write all-or-nothing commits everything`() {
        conn.createStatement().use { it.execute("CREATE TABLE sink (id BIGINT, name VARCHAR)") }
        val (_, batches) = sourceBatches(50, 12)
        val report = JdbcBatchWriter.write(conn, "INSERT INTO sink VALUES (?, ?)",
                                           batches.asSequence())
        assertTrue(report.completed)
        assertEquals(50L, report.rowsAffected)
        assertEquals(5, report.batches)
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sink")
            rs.next(); assertEquals(50L, rs.getLong(1))
        }
    }

    @Test
    fun `failed batch rolls back and identifies the row range — all-or-nothing`() {
        conn.createStatement().use { it.execute("CREATE TABLE sink (id BIGINT NOT NULL, name VARCHAR)") }
        val (schema, good) = sourceBatches(20, 10)
        val poison = RowBatch(schema, listOf(Row(schema, listOf(null, "bad"))))
        val ex = assertThrows(JdbcBatchWriter.BatchWriteException::class.java) {
            JdbcBatchWriter.write(conn, "INSERT INTO sink VALUES (?, ?)",
                                  (good + poison).asSequence())
        }
        assertFalse(ex.report.completed)
        assertEquals(2, ex.report.failedBatchIndex)
        assertEquals(20L until 21L, ex.report.failedRowRange)
        assertEquals(0L, ex.report.rowsAffected)   // nothing committed
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sink")
            rs.next(); assertEquals(0L, rs.getLong(1))
        }
    }

    @Test
    fun `per-chunk commit preserves completed chunks on failure`() {
        conn.createStatement().use { it.execute("CREATE TABLE sink (id BIGINT NOT NULL, name VARCHAR)") }
        val (schema, good) = sourceBatches(20, 10)   // 2 batches of 10
        val poison = RowBatch(schema, listOf(Row(schema, listOf(null, "bad"))))
        val ex = assertThrows(JdbcBatchWriter.BatchWriteException::class.java) {
            JdbcBatchWriter.write(
                conn, "INSERT INTO sink VALUES (?, ?)", (good + poison).asSequence(),
                JdbcBatchWriter.WriteOptions(
                    commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1)))
        }
        assertEquals(20L, ex.report.rowsAffected)   // both good chunks committed
        assertEquals(2, ex.report.failedBatchIndex)
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sink")
            rs.next(); assertEquals(20L, rs.getLong(1))
        }
    }

    @Test
    fun `write cancellation reports the open chunk honestly`() {
        conn.createStatement().use { it.execute("CREATE TABLE sink (id BIGINT, name VARCHAR)") }
        val (schema, batches) = sourceBatches(30, 10)
        val token = CancelToken.none()
        var seen = 0
        val cancellingBatches = batches.asSequence().map {
            if (seen++ == 1) token.cancel()
            it
        }
        // HEL-129: write cancellation propagates as OperationCancelledException
        // (UNWRAPPED — distinguishable from a batch-write failure), carrying the
        // honest partial report so a caller can classify it as cancelled and
        // resume from the durably-committed rows.
        val ex = assertThrows(OperationCancelledException::class.java) {
            JdbcBatchWriter.write(conn, "INSERT INTO sink VALUES (?, ?)", cancellingBatches,
                                  JdbcBatchWriter.WriteOptions(cancelToken = token))
        }
        assertNotNull(ex.report)
        assertFalse(ex.report!!.completed)
    }
}
