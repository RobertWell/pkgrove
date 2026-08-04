package com.pkgrove.pkgrovekit.quarkus.it

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.RetrySafety
import com.pkgrove.pkgrovekit.jdbc.TransactionPolicy
import com.pkgrove.pkgrovekit.jdbc.TransactionState
import com.pkgrove.pkgrovekit.jdbc.TransactionalWriter
import io.agroal.api.AgroalDataSource
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.sql.DataSource

/**
 * HEL-172 gap 1: [TransactionPolicy.JoinExisting] under a REAL Quarkus/JTA
 * caller-owned transaction. Narayana (via quarkus-narayana-jta, pulled in by
 * quarkus-agroal) owns begin/commit/rollback through [QuarkusTransaction];
 * Agroal auto-enlists every connection acquired while the JTA transaction is
 * active; PkgroveKit only appends work and never commits — mirroring the
 * accepted Spring-side evidence (SpringTransactionsTest) on the Quarkus stack.
 *
 * JoinExisting-vs-JTA contract note (the known pgjdbc gotcha does NOT apply
 * here): [TransactionalWriter] validates exactly `connection.autoCommit ==
 * false` before touching any row. Agroal 2.5's enlistment path
 * (ConnectionHandler.transactionStart, verified against the agroal-pool 2.5
 * bytecode) sets the UNDERLYING connection's autoCommit to false the moment
 * the handler joins the transaction, and ConnectionWrapper.getAutoCommit()
 * delegates straight to that underlying connection — so the logical handle
 * handed out inside an active QuarkusTransaction reports autoCommit=false and
 * JoinExisting's precondition genuinely holds. (The raw XAConnection path in
 * pkgrovekit-jta's JtaCoordinator needs an explicit autoCommit toggle because
 * pgjdbc's logical handle keeps reporting true — Agroal's wrapper does not
 * have that defect.) The assertions below pin this contract; if it ever
 * regressed, the test fails loudly instead of masking it.
 *
 * Ordering matters with Agroal enlistment: acquire the connection AFTER
 * begin() (deferred enlistment is unsupported) and close the wrapper BEFORE
 * commit/rollback (Agroal defers the physical return to transaction
 * completion).
 */
@QuarkusTest
class QuarkusJtaJoinExistingTest {

    @Inject
    lateinit var defaultDs: AgroalDataSource

    private val schema = Schema(listOf(
        Column("ID", ValueKind.NUMERIC, "BIGINT", precision = 19, scale = 0),
        Column("NAME", ValueKind.TEXT, "VARCHAR"),
    ))

    private fun batch(vararg rows: Pair<Long, String>) =
        RowBatch(schema, rows.map { (id, name) -> Row(schema, listOf(id, name)) })

    // ── commit: the caller's JTA commit publishes the joined rows ───────────

    @Test
    fun jtaCommitPublishesRowsJoinedViaJoinExisting() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS JTA_JOIN_COMMIT",
            "CREATE TABLE JTA_JOIN_COMMIT (ID BIGINT NOT NULL, NAME VARCHAR(64) NOT NULL)")
        try {
            QuarkusTransaction.begin()
            try {
                defaultDs.connection.use { c ->
                    // the enlisted Agroal handle reports autoCommit=false — the
                    // exact state JoinExisting validates (see class KDoc).
                    assertFalse(c.autoCommit,
                        "Agroal-enlisted connection must report autoCommit=false inside a JTA tx")
                    val outcome = TransactionalWriter.write(
                        c, "INSERT INTO JTA_JOIN_COMMIT (ID, NAME) VALUES (?, ?)",
                        sequenceOf(batch(1L to "ada", 2L to "grace")),
                        TransactionPolicy.JoinExisting)
                    // PkgroveKit committed NOTHING itself; the fate is the caller's
                    assertEquals(TransactionState.PENDING_IN_CALLER_TRANSACTION, outcome.state)
                    assertEquals(RetrySafety.CALLER_OWNED, outcome.retrySafety)
                    assertEquals(0L, outcome.committedRows)
                }
                QuarkusTransaction.commit()
            } catch (t: Throwable) {
                if (QuarkusTransaction.isActive()) QuarkusTransaction.rollback()
                throw t
            }
            // durable AFTER the caller's commit, observed from a fresh connection
            assertEquals(2, queryInt(defaultDs, "SELECT COUNT(*) FROM JTA_JOIN_COMMIT"))
            assertEquals(listOf("ada", "grace"),
                queryStrings(defaultDs, "SELECT NAME FROM JTA_JOIN_COMMIT ORDER BY ID"))
        } finally {
            exec(defaultDs, "DROP TABLE IF EXISTS JTA_JOIN_COMMIT")
        }
    }

    // ── rollback: the caller's JTA rollback erases the joined rows ──────────

    @Test
    fun jtaRollbackErasesRowsJoinedViaJoinExisting() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS JTA_JOIN_ROLLBACK",
            "CREATE TABLE JTA_JOIN_ROLLBACK (ID BIGINT NOT NULL, NAME VARCHAR(64) NOT NULL)")
        try {
            QuarkusTransaction.begin()
            try {
                defaultDs.connection.use { c ->
                    assertFalse(c.autoCommit)
                    val outcome = TransactionalWriter.write(
                        c, "INSERT INTO JTA_JOIN_ROLLBACK (ID, NAME) VALUES (?, ?)",
                        sequenceOf(batch(3L to "edsger")),
                        TransactionPolicy.JoinExisting)
                    assertEquals(TransactionState.PENDING_IN_CALLER_TRANSACTION, outcome.state)
                }
            } finally {
                // caller-owned decision: roll the whole transaction back
                if (QuarkusTransaction.isActive()) QuarkusTransaction.rollback()
            }
            assertEquals(0, queryInt(defaultDs, "SELECT COUNT(*) FROM JTA_JOIN_ROLLBACK"),
                "JTA rollback must leave zero rows — PkgroveKit never committed on its own")
        } finally {
            exec(defaultDs, "DROP TABLE IF EXISTS JTA_JOIN_ROLLBACK")
        }
    }

    // ── pre-effect rejection: no surrounding transaction → typed refusal ────

    @Test
    fun joinExistingOutsideAnyTransactionIsRefusedBeforeAnyEffect() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS JTA_JOIN_REFUSE",
            "CREATE TABLE JTA_JOIN_REFUSE (ID BIGINT NOT NULL, NAME VARCHAR(64) NOT NULL)")
        try {
            assertFalse(QuarkusTransaction.isActive(), "test precondition: no active JTA tx")
            defaultDs.connection.use { c ->
                // outside a JTA tx the pooled connection is a plain auto-commit one
                assertTrue(c.autoCommit)
                val ex = assertThrows<TransactionalWriter.UnsupportedPolicyException> {
                    TransactionalWriter.write(
                        c, "INSERT INTO JTA_JOIN_REFUSE (ID, NAME) VALUES (?, ?)",
                        sequenceOf(batch(9L to "never")),
                        TransactionPolicy.JoinExisting)
                }
                assertTrue(ex.message!!.contains("caller-owned transaction"),
                    "refusal must name the missing caller-owned transaction: ${ex.message}")
            }
            // pre-effect: the refusal happened before any row was processed
            assertEquals(0, queryInt(defaultDs, "SELECT COUNT(*) FROM JTA_JOIN_REFUSE"))
        } finally {
            exec(defaultDs, "DROP TABLE IF EXISTS JTA_JOIN_REFUSE")
        }
    }

    // ── helpers (same style as PkgroveKitQuarkusAdapterTest) ────────────────

    private fun exec(ds: DataSource, vararg sql: String) {
        ds.connection.use { c ->
            c.createStatement().use { st -> sql.forEach { st.execute(it) } }
        }
    }

    private fun queryInt(ds: DataSource, sql: String): Int =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    private fun queryStrings(ds: DataSource, sql: String): List<String> =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
}
