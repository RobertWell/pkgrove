package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-234: the transaction paths TransactionPolicyTest could not reach on
 * DuckDB — [TransactionPolicy.SavepointPerBatch] against REAL JDBC savepoints
 * (H2), the AutoCommit success path, and first-class cancellation through
 * [JdbcBatchWriter] (rollback of the open chunk + honest partial report).
 */
class SavepointAndCancellationTest {

    private lateinit var conn: Connection
    private val schema = Schema(listOf(Column("id", ValueKind.NUMERIC, "BIGINT")))

    /** H2: a real engine with real java.sql.Savepoint support. */
    private val h2Dialect = object : SqlDialect {
        override val name = "h2-test"
        override val supportsSavepoints = true
        override fun typeFor(column: Column): String? = "BIGINT"
    }

    @BeforeEach
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:h2:mem:hel234;DB_CLOSE_DELAY=0")
        conn.createStatement().use { it.execute("CREATE TABLE t (id BIGINT NOT NULL)") }
    }

    @AfterEach
    fun tearDown() { conn.close() }

    private fun rows(range: LongRange, per: Int = 10): Sequence<RowBatch> =
        range.chunked(per).map { chunk ->
            RowBatch(schema, chunk.map { Row(schema, listOf(it as Any?)) })
        }.asSequence()

    private fun poisonedBatch(): RowBatch =
        RowBatch(schema, listOf(Row(schema, listOf(null))))   // NOT NULL trap

    private fun count(): Long = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT count(*) FROM t"); rs.next(); rs.getLong(1)
    }

    // --- SavepointPerBatch over real savepoints ------------------------------

    @Test
    fun `savepoint policy commits every batch when all succeed`() {
        val progress = mutableListOf<Long>()
        val o = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            rows(0L until 30L), TransactionPolicy.SavepointPerBatch, h2Dialect,
            TransactionalWriter.WriteOptions(onProgress = { progress += it }))
        assertEquals(TransactionState.COMMITTED, o.state)
        assertEquals(30L, o.committedRows)
        assertEquals(0L, o.rolledBackRows)
        assertEquals(listOf(ChunkRange(0, 30)), o.committedChunks)
        assertNull(o.failedChunk)
        assertEquals(TransferCheckpoint(30), o.checkpoint)
        assertEquals(RetrySafety.COMPLETE, o.retrySafety)
        assertEquals(listOf(10L, 20L, 30L), progress)
        assertEquals(30L, count())
        assertTrue(conn.autoCommit, "caller's autoCommit must be restored")
    }

    @Test
    fun `savepoint policy discards ONLY the failed batch and commits survivors`() {
        // 2 good batches, then a poisoned one, then one more good batch that
        // must never run (the writer stops at the first failed batch)
        val batches = sequence {
            yieldAll(rows(0L until 20L))
            yield(poisonedBatch())
            yield(RowBatch(schema, listOf(Row(schema, listOf(99L as Any?)))))
        }
        val outcome = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            batches, TransactionPolicy.SavepointPerBatch, h2Dialect)
        assertEquals(TransactionState.PARTIALLY_COMMITTED, outcome.state)
        assertEquals(20L, outcome.committedRows)
        assertEquals(1L, outcome.rolledBackRows)
        assertEquals(ChunkRange(20, 21), outcome.failedChunk)
        assertEquals(TransferCheckpoint(20), outcome.checkpoint)
        assertEquals(RetrySafety.UNSAFE_PARTIAL_COMMITTED, outcome.retrySafety)
        assertEquals(20L, count(), "survivor batches must be durable, poisoned batch discarded")
        assertTrue(conn.autoCommit)
    }

    @Test
    fun `savepoint policy with an immediately poisoned first batch commits nothing`() {
        val outcome = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            sequenceOf(poisonedBatch()), TransactionPolicy.SavepointPerBatch, h2Dialect)
        assertEquals(TransactionState.PARTIALLY_COMMITTED, outcome.state)
        assertEquals(0L, outcome.committedRows)
        assertEquals(RetrySafety.SAFE_NOTHING_COMMITTED, outcome.retrySafety)
        assertEquals(0L, count())
    }

    @Test
    fun `savepoint policy cancellation rolls back cleanly and stays retry-safe`() {
        val token = CancelToken.none()
        val batches = sequence {
            yieldAll(rows(0L until 10L))
            token.cancel()                       // cancel between batches
            yieldAll(rows(10L until 20L))
        }
        val ex = assertThrows(TransactionWriteException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                batches, TransactionPolicy.SavepointPerBatch, h2Dialect,
                TransactionalWriter.WriteOptions(cancelToken = token))
        }
        assertEquals(TransactionState.ROLLED_BACK, ex.outcome.state)
        assertEquals(RetrySafety.SAFE_NOTHING_COMMITTED, ex.outcome.retrySafety)
        assertTrue(ex.cause is OperationCancelledException, "${ex.cause}")
        assertEquals(0L, count(), "single-transaction policy: cancellation discards staged work")
        assertTrue(conn.autoCommit)
    }

    // --- AutoCommit success path ---------------------------------------------

    @Test
    fun `auto commit success reports complete accounting and restores autoCommit`() {
        conn.autoCommit = false                  // prove restore of the PREVIOUS value
        val progress = mutableListOf<Long>()
        val o = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            rows(0L until 12L, per = 4), TransactionPolicy.AutoCommit,
            options = TransactionalWriter.WriteOptions(onProgress = { progress += it }))
        assertEquals(TransactionState.COMMITTED, o.state)
        assertEquals(12L, o.committedRows)
        assertEquals(listOf(ChunkRange(0, 12)), o.committedChunks)
        assertEquals(TransferCheckpoint(12), o.checkpoint)
        assertEquals(RetrySafety.COMPLETE, o.retrySafety)
        assertEquals(listOf(4L, 8L, 12L), progress)
        assertEquals(false, conn.autoCommit, "previous autoCommit=false must be restored")
        conn.commit()
        assertEquals(12L, count())
    }

    // --- JdbcBatchWriter cancellation ----------------------------------------

    @Test
    fun `batch writer cancellation rolls back the open chunk and keeps committed chunks`() {
        val token = CancelToken.none()
        val batches = sequence {
            yieldAll(rows(0L until 20L))         // two batches of 10 = two committed chunks
            token.cancel()
            yieldAll(rows(20L until 30L))        // never executed
        }
        val ex = assertThrows(OperationCancelledException::class.java) {
            JdbcBatchWriter.write(conn, "INSERT INTO t VALUES (?)", batches,
                JdbcBatchWriter.WriteOptions(
                    commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1),
                    cancelToken = token))
        }
        val report = ex.report!!
        assertEquals(20L, report.rowsAffected, "durably committed rows before the cancel")
        assertEquals(false, report.completed)
        assertNull(report.failedRowRange, "no rows were staged in an open chunk")
        assertEquals(20L, count())
        assertTrue(conn.autoCommit)
    }

    @Test
    fun `batch writer cancellation before any batch reports an honest zero`() {
        val token = CancelToken.none().also { it.cancel() }
        val ex = assertThrows(OperationCancelledException::class.java) {
            JdbcBatchWriter.write(conn, "INSERT INTO t VALUES (?)", rows(0L until 10L),
                JdbcBatchWriter.WriteOptions(cancelToken = token))
        }
        val report = ex.report!!
        assertEquals(0L, report.rowsAffected)
        assertEquals(0, report.batches)
        assertEquals(0L, count())
    }

    @Test
    fun `per chunk policy batches multiple batches per commit`() {
        val poisonAfterTwoChunks = sequence {
            yieldAll(rows(0L until 20L, per = 5))   // 4 batches → 2 chunks of 2 batches
            yield(poisonedBatch())
        }
        val ex = assertThrows(JdbcBatchWriter.BatchWriteException::class.java) {
            JdbcBatchWriter.write(conn, "INSERT INTO t VALUES (?)", poisonAfterTwoChunks,
                JdbcBatchWriter.WriteOptions(commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(2)))
        }
        assertEquals(20L, ex.report.rowsAffected)
        assertEquals(4, ex.report.batches)
        assertEquals(20L until 21L, ex.report.failedRowRange)
        assertEquals(20L, count())
    }

    @Test
    fun `per chunk policy rejects a non-positive chunk size`() {
        assertThrows(IllegalArgumentException::class.java) {
            JdbcBatchWriter.CommitPolicy.PerChunk(0)
        }
    }
}
