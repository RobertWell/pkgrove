package com.pkgrove.pkgrovekit.quarkus.it

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.quarkus.PkgroveKitDatabaseKey
import com.pkgrove.pkgrovekit.transfer.Relay
import com.pkgrove.pkgrovekit.transfer.TransferOutcome
import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import io.quarkus.agroal.DataSource as NamedDataSource

/**
 * HEL-172 gap 3: adapter-specific cancellation/exception cleanup with ZERO
 * leaked Agroal leases — measured on the REAL Agroal gauge
 * (`getMetrics().activeCount()`, enabled by [AgroalMetricsProfile] and proven
 * non-vacuous via acquireCount), not inferred from "the pool still works".
 * Three paths through the CDI-produced [Relay] over live Agroal/H2:
 *
 *  - mid-write FAILURE (primary-key violation while appending) → typed
 *    Failed outcome, all-or-nothing rollback, no active lease left behind,
 *    maxUsedCount stays sane (a same-database transfer holds exactly one
 *    lease), and the pool completes a healthy transfer immediately after;
 *  - mid-transfer CANCELLATION (token cancelled from inside the row stream
 *    after >1 read batch) → typed Cancelled outcome, open work rolled back,
 *    zero rows durable, zero active leases;
 *  - PRE-cancelled token → typed Cancelled outcome BEFORE any lease/effect
 *    (sink table never even established), then the very same plan re-executes
 *    to Completed, proving both plan reusability and pool health.
 */
@QuarkusTest
@TestProfile(AgroalMetricsProfile::class)
class QuarkusCancellationLeakTest {

    @Inject
    lateinit var relay: Relay

    @Inject
    lateinit var defaultDs: AgroalDataSource

    @Inject
    @NamedDataSource("warehouse")
    lateinit var warehouseDs: AgroalDataSource

    private val main = PkgroveKitDatabaseKey("main")

    // ── mid-write failure: constraint violation during the append ───────────

    @Test
    fun midWriteFailureReleasesEveryLeaseAndThePoolStaysHealthy() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS LEAK_FAIL_SRC",
            "DROP TABLE IF EXISTS LEAK_FAIL_SINK",
            "DROP TABLE IF EXISTS LEAK_FAIL_HEALTHY",
            "CREATE TABLE LEAK_FAIL_SRC (ID BIGINT NOT NULL, VAL VARCHAR(32) NOT NULL)",
            "INSERT INTO LEAK_FAIL_SRC VALUES (1, 'a'), (2, 'dup'), (3, 'c')",
            "CREATE TABLE LEAK_FAIL_SINK (ID BIGINT PRIMARY KEY, VAL VARCHAR(32) NOT NULL)",
            // ID=2 already present → the appended batch violates the PK mid-write
            "INSERT INTO LEAK_FAIL_SINK VALUES (2, 'existing')")
        try {
            val maxUsedBefore = defaultDs.metrics.maxUsedCount()

            val failing = relay.transfer("leak-fail") {
                from(main) { query("SELECT ID, VAL FROM LEAK_FAIL_SRC ORDER BY ID") }
                to(main, "LEAK_FAIL_SINK") { mode(SqlDialect.TargetMode.APPEND) }
            }
            val outcome = relay.execute(failing)
            assertTrue(outcome is TransferOutcome.Failed,
                "PK violation with nothing committed must be Failed, got $outcome")

            // all-or-nothing rollback: only the pre-existing row survived
            assertEquals(1, queryInt(defaultDs, "SELECT COUNT(*) FROM LEAK_FAIL_SINK"))

            // real metrics (guard against a vacuous default-0 gauge), no leak,
            // and lease usage stayed sane: a same-database transfer needs ONE lease
            assertTrue(defaultDs.metrics.acquireCount() > 0,
                "Agroal metrics look disabled — the lease assertions would be vacuous")
            assertEquals(0L, defaultDs.metrics.activeCount(),
                "leaked lease after a failed transfer")
            assertTrue(defaultDs.metrics.maxUsedCount() <= maxUsedBefore + 1,
                "failed transfer used more than its single expected lease: " +
                    "before=$maxUsedBefore now=${defaultDs.metrics.maxUsedCount()}")
            assertEquals(0L, warehouseDs.metrics.activeCount())

            // the pool immediately completes a healthy transfer afterwards
            assertCompleted(relay.execute(relay.transfer("leak-fail-healthy") {
                from(main) { query("SELECT ID, VAL FROM LEAK_FAIL_SRC ORDER BY ID") }
                to(main, "LEAK_FAIL_HEALTHY")
            }))
            assertEquals(3, queryInt(defaultDs, "SELECT COUNT(*) FROM LEAK_FAIL_HEALTHY"))
            assertEquals(0L, defaultDs.metrics.activeCount())
        } finally {
            exec(defaultDs,
                "DROP TABLE IF EXISTS LEAK_FAIL_SRC",
                "DROP TABLE IF EXISTS LEAK_FAIL_SINK",
                "DROP TABLE IF EXISTS LEAK_FAIL_HEALTHY")
        }
    }

    // ── mid-transfer cancellation: token cancelled from inside the stream ───

    @Test
    fun midTransferCancellationRollsBackAndLeaksNoLease() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS LEAK_MID_SRC",
            "DROP TABLE IF EXISTS LEAK_MID_SINK",
            "CREATE TABLE LEAK_MID_SRC (ID BIGINT NOT NULL)",
            // > 1 read batch (default readBatchSize 1000) so the cancel lands
            // at a REAL mid-write batch boundary, not before the first row
            "INSERT INTO LEAK_MID_SRC SELECT X FROM SYSTEM_RANGE(1, 2500)")
        try {
            val token = CancelToken()
            val seen = AtomicLong()
            val plan = relay.transfer("leak-mid-cancel") {
                from(main) { query("SELECT ID FROM LEAK_MID_SRC ORDER BY ID") }
                // pure pass-through transform that trips the token mid-stream:
                // batch 1 (rows 1..1000) is staged, the cancel fires while
                // batch 2 is being prepared, and the writer observes it at the
                // next batch boundary (cooperative checkpoint)
                transform { row ->
                    if (seen.incrementAndGet() >= 1500L) token.cancel()
                    row
                }
                to(main, "LEAK_MID_SINK")
            }
            val outcome = relay.execute(plan, token)
            assertTrue(outcome is TransferOutcome.Cancelled,
                "cancellation must surface as the typed Cancelled outcome, got $outcome")

            // all-or-nothing default: the open transaction was rolled back —
            // nothing durable (the CREATEd sink exists, but holds zero rows)
            assertEquals(0, queryInt(defaultDs, "SELECT COUNT(*) FROM LEAK_MID_SINK"))

            assertTrue(defaultDs.metrics.acquireCount() > 0)
            assertEquals(0L, defaultDs.metrics.activeCount(),
                "leaked lease after a cancelled transfer")
        } finally {
            exec(defaultDs,
                "DROP TABLE IF EXISTS LEAK_MID_SRC",
                "DROP TABLE IF EXISTS LEAK_MID_SINK")
        }
    }

    // ── pre-cancelled token: typed Cancelled before any lease or effect ─────

    @Test
    fun preCancelledTokenYieldsCancelledBeforeAnyEffectThenThePlanStillRuns() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS LEAK_PRE_SRC",
            "DROP TABLE IF EXISTS LEAK_PRE_SINK",
            "CREATE TABLE LEAK_PRE_SRC (ID BIGINT NOT NULL)",
            "INSERT INTO LEAK_PRE_SRC VALUES (42)")
        try {
            val token = CancelToken()
            token.cancel()
            val plan = relay.transfer("leak-pre-cancel") {
                from(main) { query("SELECT ID FROM LEAK_PRE_SRC") }
                to(main, "LEAK_PRE_SINK")
            }

            val outcome = relay.execute(plan, token)
            assertTrue(outcome is TransferOutcome.Cancelled,
                "pre-cancelled token must yield Cancelled, got $outcome")
            // cancelled before any write began → no checkpoint to resume from
            assertNull((outcome as TransferOutcome.Cancelled).checkpoint)
            // pre-effect: the sink table was never even established
            assertThrows<SQLException> {
                queryInt(defaultDs, "SELECT COUNT(*) FROM LEAK_PRE_SINK")
            }
            assertEquals(0L, defaultDs.metrics.activeCount(),
                "a cancelled waiter must lease nothing")

            // the SAME immutable plan then executes cleanly without the token
            assertCompleted(relay.execute(plan))
            assertEquals(1, queryInt(defaultDs, "SELECT COUNT(*) FROM LEAK_PRE_SINK"))
            assertEquals(0L, defaultDs.metrics.activeCount())
        } finally {
            exec(defaultDs,
                "DROP TABLE IF EXISTS LEAK_PRE_SRC",
                "DROP TABLE IF EXISTS LEAK_PRE_SINK")
        }
    }

    // ── helpers (same style as PkgroveKitQuarkusAdapterTest) ────────────────

    private fun assertCompleted(outcome: TransferOutcome) {
        if (outcome is TransferOutcome.Completed) return
        val cause = when (outcome) {
            is TransferOutcome.Failed -> outcome.cause
            is TransferOutcome.Rejected -> outcome.cause
            is TransferOutcome.Partial -> outcome.cause
            else -> null
        }
        throw AssertionError("expected Completed, got $outcome", cause)
    }

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
}
