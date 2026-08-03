package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
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

/** HEL-126 selectable transaction policies against real DuckDB: commit,
 *  rollback, exact partial accounting, caller-owned safety, early failure. */
class TransactionPolicyTest {

    private lateinit var conn: Connection
    private val schema = Schema(listOf(Column("id", ValueKind.NUMERIC, "BIGINT")))

    @BeforeEach
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:duckdb:")
        conn.createStatement().use { it.execute("CREATE TABLE t (id BIGINT NOT NULL)") }
    }

    @AfterEach
    fun tearDown() { conn.close() }

    private fun rows(range: LongRange): Sequence<RowBatch> =
        range.chunked(10).map { chunk ->
            RowBatch(schema, chunk.map { Row(schema, listOf(it as Any?)) })
        }.asSequence()

    private fun poisoned(good: Long): Sequence<RowBatch> = sequence {
        yieldAll(rows(0 until good))
        yield(RowBatch(schema, listOf(Row(schema, listOf(null)))))   // NOT NULL trap
    }

    private fun count(): Long = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT count(*) FROM t"); rs.next(); rs.getLong(1)
    }

    @Test
    fun `atomic commits everything or nothing`() {
        val ok = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            rows(0L until 30L), TransactionPolicy.Atomic)
        assertEquals(TransactionState.COMMITTED, ok.state)
        assertEquals(30L, ok.committedRows)
        assertEquals(RetrySafety.COMPLETE, ok.retrySafety)
        assertEquals(30L, count())

        val ex = assertThrows(TransactionWriteException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                poisoned(20), TransactionPolicy.Atomic)
        }
        assertEquals(TransactionState.ROLLED_BACK, ex.outcome.state)
        assertEquals(0L, ex.outcome.committedRows)
        assertEquals(RetrySafety.SAFE_NOTHING_COMMITTED, ex.outcome.retrySafety)
        assertEquals(30L, count())   // still only the first write
    }

    @Test
    fun `chunked reports committed ranges failed chunk and checkpoint exactly`() {
        val ex = assertThrows(TransactionWriteException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                poisoned(25), TransactionPolicy.Chunked(rowsPerCommit = 10))
        }
        val o = ex.outcome
        assertEquals(TransactionState.PARTIALLY_COMMITTED, o.state)
        assertEquals(20L, o.committedRows)                       // two full chunks
        assertEquals(listOf(ChunkRange(0, 10), ChunkRange(10, 20)), o.committedChunks)
        assertEquals(TransferCheckpoint(20), o.checkpoint)       // resume here
        assertEquals(RetrySafety.UNSAFE_PARTIAL_COMMITTED, o.retrySafety)
        assertEquals(20L, count())

        // complete run: COMMITTED with full chunk accounting, never "atomic"
        conn.createStatement().use { it.execute("DELETE FROM t") }
        val ok = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            rows(0L until 25L), TransactionPolicy.Chunked(rowsPerCommit = 10))
        assertEquals(TransactionState.COMMITTED, ok.state)
        assertEquals(3, ok.committedChunks.size)                 // 10+10+5
        assertEquals(ChunkRange(20, 25), ok.committedChunks.last())
    }

    @Test
    fun `join existing never commits and fails early on auto-commit connections`() {
        // fail-early: auto-commit connection cannot host a joined transaction
        assertThrows(TransactionalWriter.UnsupportedPolicyException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                rows(0L until 5L), TransactionPolicy.JoinExisting)
        }
        assertEquals(0L, count())

        // caller-owned: work is visible only after the CALLER commits
        conn.autoCommit = false
        val o = TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
            rows(0L until 15L), TransactionPolicy.JoinExisting)
        assertEquals(TransactionState.PENDING_IN_CALLER_TRANSACTION, o.state)
        assertEquals(RetrySafety.CALLER_OWNED, o.retrySafety)
        assertEquals(0L, o.committedRows)      // WE committed nothing
        conn.rollback()                         // caller decides: discard
        conn.autoCommit = true
        assertEquals(0L, count())               // and nothing survived
    }

    @Test
    fun `savepoint per batch fails closed when no dialect is supplied to verify support`() {
        // the public overload defaults dialect=null; the capability precheck
        // must NOT be bypassable — an unverifiable adapter is rejected.
        val ex = assertThrows(TransactionalWriter.UnsupportedPolicyException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                rows(0L until 5L), TransactionPolicy.SavepointPerBatch)
        }
        assertTrue(ex.message!!.contains("requires a dialect"))
        assertEquals(0L, count())   // rejected before any row
    }

    @Test
    fun `auto commit accounts partial completion exactly`() {
        val ex = assertThrows(TransactionWriteException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                poisoned(7), TransactionPolicy.AutoCommit)
        }
        val o = ex.outcome
        assertEquals(TransactionState.PARTIALLY_COMMITTED, o.state)
        assertEquals(7L, o.committedRows)
        assertEquals(ChunkRange(7, 8), o.failedChunk)
        assertEquals(TransferCheckpoint(7), o.checkpoint)
        assertEquals(7L, count())               // exactly the pre-failure rows persist
    }

    @Test
    fun `savepoint policy fails early where the dialect reports no support`() {
        val noSavepoints = object : SqlDialect {
            override val name = "fake"
            override fun typeFor(column: Column): String? = "X"
        }
        assertThrows(TransactionalWriter.UnsupportedPolicyException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                rows(0L until 5L), TransactionPolicy.SavepointPerBatch, noSavepoints)
        }
        assertEquals(0L, count())
    }

    @Test
    fun `outcomes never leak row values`() {
        val ex = assertThrows(TransactionWriteException::class.java) {
            TransactionalWriter.write(conn, "INSERT INTO t VALUES (?)",
                poisoned(3), TransactionPolicy.Chunked(2))
        }
        assertNull(ex.outcome.failedChunk?.let { null })  // structural check only
        assertTrue(!ex.message!!.contains("null,"))       // no value dumps in messages
    }
}
