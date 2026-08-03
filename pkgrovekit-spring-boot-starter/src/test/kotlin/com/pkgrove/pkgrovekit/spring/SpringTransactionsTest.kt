package com.pkgrove.pkgrovekit.spring

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.RetrySafety
import com.pkgrove.pkgrovekit.jdbc.TransactionPolicy
import com.pkgrove.pkgrovekit.jdbc.TransactionState
import com.pkgrove.pkgrovekit.jdbc.TransactionalWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Path

/**
 * (d) JoinExisting under a REAL Spring transaction: DataSourceTransactionManager
 * + TransactionTemplate own commit/rollback; PkgroveKit only appends work on the
 * transaction-bound connection obtained via [SpringTransactions.joinCurrent].
 */
class SpringTransactionsTest {

    @field:TempDir
    lateinit var tempDir: Path

    private val schema = Schema(listOf(
        Column("id", ValueKind.NUMERIC, "BIGINT", precision = 19, scale = 0),
        Column("name", ValueKind.TEXT, "VARCHAR"),
    ))

    private val dml = """INSERT INTO "t" ("id", "name") VALUES (?, ?)"""

    private fun batch(vararg rows: Pair<Long, String>) =
        RowBatch(schema, rows.map { (id, name) -> Row(schema, listOf(id, name)) })

    @Test
    fun `joined write - spring commit persists, spring rollback leaves zero rows`() {
        val pool = duckPool(tempDir.resolve("tx.db"), "txPool")
        try {
            pool.exec("CREATE TABLE t (id BIGINT, name VARCHAR)")
            val transactions = TransactionTemplate(DataSourceTransactionManager(pool))

            // commit case: Spring's commit publishes the joined rows
            val outcome = transactions.execute {
                SpringTransactions.joinCurrent(pool) { connection ->
                    TransactionalWriter.write(connection, dml,
                        sequenceOf(batch(1L to "a", 2L to "b")),
                        TransactionPolicy.JoinExisting)
                }
            }!!
            assertEquals(TransactionState.PENDING_IN_CALLER_TRANSACTION, outcome.state)
            assertEquals(RetrySafety.CALLER_OWNED, outcome.retrySafety)
            assertEquals(0L, outcome.committedRows)   // PkgroveKit committed NOTHING itself
            assertEquals(2L, pool.count("t"))         // ...Spring's commit did

            // rollback case: Spring's rollback erases the joined rows entirely
            transactions.execute { status ->
                SpringTransactions.joinCurrent(pool) { connection ->
                    TransactionalWriter.write(connection, dml,
                        sequenceOf(batch(3L to "c")),
                        TransactionPolicy.JoinExisting)
                }
                status.setRollbackOnly()
            }
            assertEquals(2L, pool.count("t"))         // row 3 never became durable
        } finally {
            pool.close()
        }
    }

    @Test
    fun `no active transaction - fails before any connection is fetched`() {
        val pool = duckPool(tempDir.resolve("notx.db"), "noTxPool")
        try {
            val counting = CountingDataSource(pool)
            assertThrows(MissingSpringTransactionException::class.java) {
                SpringTransactions.joinCurrent(counting) { error("block must not run") }
            }
            assertEquals(0, counting.connectionsServed.get())            // pool never touched
            assertEquals(0, pool.hikariPoolMXBean.activeConnections)     // Hikari agrees
        } finally {
            pool.close()
        }
    }
}
