package com.pkgrove.pkgrovekit.quarkus.it

import com.pkgrove.pkgrovekit.quarkus.BlockingBoundary
import com.pkgrove.pkgrovekit.quarkus.PkgroveKitDatabaseKey
import com.pkgrove.pkgrovekit.transfer.Relay
import com.pkgrove.pkgrovekit.transfer.TransferOutcome
import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import javax.sql.DataSource
import io.quarkus.agroal.DataSource as NamedDataSource

/**
 * Real-framework proof for pkgrovekit-quarkus (HEL-172): a live Quarkus app
 * with two Agroal/H2 datasources, the CDI-produced [Relay], and actual
 * write+read round trips. Startup-INVALID config coverage (all problems
 * listed) lives in pkgrovekit-quarkus's own unit tests
 * (PkgroveKitQuarkusConfigTest) — the producer throws that exact typed
 * exception at deployment time, and pulling in quarkus-junit5-internal for a
 * QuarkusUnitTest deployment-failure re-proof was deliberately skipped.
 */
@QuarkusTest
class PkgroveKitQuarkusAdapterTest {

    @Inject
    lateinit var relay: Relay

    @Inject
    lateinit var defaultDs: AgroalDataSource

    @Inject
    @NamedDataSource("warehouse")
    lateinit var warehouseDs: AgroalDataSource

    private val main = PkgroveKitDatabaseKey("main")
    private val warehouse = PkgroveKitDatabaseKey("warehouse")

    // ── (a) CDI injection + real write/read through the DEFAULT datasource ──

    @Test
    fun relayWritesAndReadsThroughTheDefaultDatasource() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS SRC_USERS",
            "DROP TABLE IF EXISTS COPIED_USERS",
            "CREATE TABLE SRC_USERS (ID INT NOT NULL, NAME VARCHAR(64) NOT NULL)",
            "INSERT INTO SRC_USERS VALUES (1, 'ada'), (2, 'grace'), (3, 'edsger')")

        val plan = relay.transfer("default-roundtrip") {
            from(main) { query("SELECT ID, NAME FROM SRC_USERS ORDER BY ID") }
            to(main, "COPIED_USERS")
        }
        assertCompleted(relay.execute(plan))

        assertEquals(3, queryInt(defaultDs, "SELECT COUNT(*) FROM COPIED_USERS"))
        assertEquals(listOf("ada", "grace", "edsger"),
            queryStrings(defaultDs, "SELECT NAME FROM COPIED_USERS ORDER BY ID"))
    }

    // ── (b) the NAMED datasource resolves and is usable (cross-database) ────

    @Test
    fun namedWarehouseDatasourceResolvesAndReceivesACrossDatabaseTransfer() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS SRC_EXPORT",
            "CREATE TABLE SRC_EXPORT (ID INT NOT NULL, QTY INT NOT NULL)",
            "INSERT INTO SRC_EXPORT VALUES (10, 7), (20, 9)")
        exec(warehouseDs, "DROP TABLE IF EXISTS IMPORTED_ROWS")

        val plan = relay.transfer("to-warehouse") {
            from(main) { query("SELECT ID, QTY FROM SRC_EXPORT ORDER BY ID") }
            to(warehouse, "IMPORTED_ROWS")
        }
        assertCompleted(relay.execute(plan))

        assertEquals(2, queryInt(warehouseDs, "SELECT COUNT(*) FROM IMPORTED_ROWS"))
        assertEquals(16, queryInt(warehouseDs, "SELECT SUM(QTY) FROM IMPORTED_ROWS"))
        // and it really is a SECOND database — the default one has no such table
        assertThrows<SQLException> {
            queryInt(defaultDs, "SELECT COUNT(*) FROM IMPORTED_ROWS")
        }
    }

    // ── (c) PkgroveKit never closes Agroal — pools stay live after Relay use ─

    @Test
    fun agroalPoolsStillHandOutLiveConnectionsAfterRelayUse() {
        exec(defaultDs,
            "DROP TABLE IF EXISTS SRC_LIVENESS",
            "DROP TABLE IF EXISTS SINK_LIVENESS",
            "CREATE TABLE SRC_LIVENESS (ID INT NOT NULL)",
            "INSERT INTO SRC_LIVENESS VALUES (1)")
        val plan = relay.transfer("liveness") {
            from(main) { query("SELECT ID FROM SRC_LIVENESS") }
            to(main, "SINK_LIVENESS")
        }
        assertCompleted(relay.execute(plan))

        // APPLICATION_OWNED contract: after Relay borrowed + returned
        // connections, BOTH Quarkus pools must still serve fresh live ones.
        for (ds in listOf(defaultDs, warehouseDs)) {
            ds.connection.use { c ->
                assertTrue(c.isValid(5), "pool handed out an invalid connection")
                assertEquals(1, c.createStatement().use { st ->
                    st.executeQuery("SELECT 1").use { rs -> rs.next(); rs.getInt(1) }
                })
            }
        }
    }

    // ── (e) blocking boundary passes on the JUnit worker thread ─────────────

    @Test
    fun blockingBoundaryAllowsTheTestWorkerThread() {
        assertDoesNotThrow { BlockingBoundary.assertBlockingAllowed() }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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

    private fun queryStrings(ds: DataSource, sql: String): List<String> =
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
}
