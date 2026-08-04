package com.pkgrove.pkgrovekit.quarkus.it

import com.pkgrove.pkgrovekit.quarkus.PkgroveKitDatabaseKey
import com.pkgrove.pkgrovekit.transfer.Relay
import com.pkgrove.pkgrovekit.transfer.TransferOutcome
import io.agroal.api.AgroalDataSource
import io.quarkus.arc.Arc
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import io.quarkus.agroal.DataSource as NamedDataSource

/**
 * HEL-172 gap 2: application-context shutdown/disposer behavior, exercised
 * INSIDE the test. The container-managed [Relay] singleton is destroyed
 * through Arc, which runs the producer's disposer
 * ([com.pkgrove.pkgrovekit.quarkus.PkgroveKitProducer.close] — `@Disposes` →
 * `Relay.close()` → `Databases.close()`), and the test then proves BOTH sides
 * of the ownership contract:
 *
 *  1. the disposer really ran — the destroyed Relay's registry is closed, so
 *     executing a plan on it fails with the registry's "is closed" refusal
 *     (a no-op destroy would make this assertion fail);
 *  2. APPLICATION_OWNED means never closed by PkgroveKit — both Quarkus
 *     Agroal pools still hand out live connections afterwards, with ZERO
 *     active leases (real Agroal metrics via [AgroalMetricsProfile], proven
 *     non-vacuous by acquireCount > 0);
 *  3. the container recovers — a fresh producer-built Relay (new singleton
 *     instance) completes a transfer over the very same pools.
 */
@QuarkusTest
@TestProfile(AgroalMetricsProfile::class)
class QuarkusDisposerShutdownTest {

    @Inject
    lateinit var defaultDs: AgroalDataSource

    @Inject
    @NamedDataSource("warehouse")
    lateinit var warehouseDs: AgroalDataSource

    private val main = PkgroveKitDatabaseKey("main")

    @Test
    fun destroyingTheRelayBeanRunsTheDisposerAndLeavesAgroalPoolsOpen() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS DISP_SRC",
            "DROP TABLE IF EXISTS DISP_SINK_PRE",
            "DROP TABLE IF EXISTS DISP_SINK_CLOSED",
            "DROP TABLE IF EXISTS DISP_SINK_POST",
            "CREATE TABLE DISP_SRC (ID INT NOT NULL)",
            "INSERT INTO DISP_SRC VALUES (1), (2)")
        try {
            val container = Arc.container()
            val handle = container.instance(Relay::class.java)
            val relay = handle.get()

            // sanity: the produced singleton works before disposal
            assertCompleted(relay.execute(relay.transfer("disposer-pre") {
                from(main) { query("SELECT ID FROM DISP_SRC ORDER BY ID") }
                to(main, "DISP_SINK_PRE")
            }))
            assertEquals(2, queryInt(defaultDs, "SELECT COUNT(*) FROM DISP_SINK_PRE"))

            // ── run the disposer INSIDE the test: destroy the singleton ─────
            handle.destroy()

            // 1. proof the @Disposes path executed: Relay.close() closed the
            //    registry, so the destroyed instance refuses execution. (Plan
            //    DEFINITION is pure and still works; EXECUTION hits the closed
            //    registry's guard.)
            val afterClose = relay.execute(relay.transfer("disposer-closed") {
                from(main) { query("SELECT ID FROM DISP_SRC") }
                to(main, "DISP_SINK_CLOSED")
            })
            assertTrue(afterClose is TransferOutcome.Failed,
                "destroyed Relay must refuse execution, got $afterClose")
            val cause = (afterClose as TransferOutcome.Failed).cause
            assertTrue(cause.message.orEmpty().contains("closed"),
                "expected the registry's closed-guard, got: $cause")

            // 2. APPLICATION_OWNED pools were NOT closed by the disposer: both
            //    Quarkus datasources still serve live connections...
            for (ds in listOf(defaultDs, warehouseDs)) {
                ds.connection.use { c ->
                    assertTrue(c.isValid(5), "pool handed out an invalid connection after disposal")
                    assertEquals(1, c.createStatement().use { st ->
                        st.executeQuery("SELECT 1").use { rs -> rs.next(); rs.getInt(1) }
                    })
                }
            }
            //    ...and with zero active leases. Metrics are REAL in this
            //    profile — guard against a vacuous default-0 gauge first.
            assertTrue(defaultDs.metrics.acquireCount() > 0,
                "Agroal metrics look disabled — the lease assertions would be vacuous")
            assertEquals(0L, defaultDs.metrics.activeCount(),
                "leaked lease on the default pool after Relay disposal")
            assertEquals(0L, warehouseDs.metrics.activeCount(),
                "leaked lease on the warehouse pool after Relay disposal")

            // 3. container recovery: the producer builds a FRESH singleton that
            //    transfers over the same (still-open) pools.
            val fresh = container.instance(Relay::class.java).get()
            assertNotSame(relay, fresh, "expected a new producer-built Relay after destroy")
            assertCompleted(fresh.execute(fresh.transfer("disposer-post") {
                from(main) { query("SELECT ID FROM DISP_SRC ORDER BY ID") }
                to(main, "DISP_SINK_POST")
            }))
            assertEquals(2, queryInt(defaultDs, "SELECT COUNT(*) FROM DISP_SINK_POST"))
            assertEquals(0L, defaultDs.metrics.activeCount())
        } finally {
            exec(defaultDs,
                "DROP TABLE IF EXISTS DISP_SRC",
                "DROP TABLE IF EXISTS DISP_SINK_PRE",
                "DROP TABLE IF EXISTS DISP_SINK_CLOSED",
                "DROP TABLE IF EXISTS DISP_SINK_POST")
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
