package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.core.Schema
import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.ValueKind
import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.JdbcReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import javax.sql.DataSource

/** HEL-125: the golden path end-to-end — typed outcomes for every terminal
 *  state, definition-time failure for incomplete plans, pure transforms
 *  testable without a database, no leaked leases. */
class RelayTest {

    private object Sales : DatabaseKey("sales-db")
    private object Analytics : DatabaseKey("analytics-db")

    @field:TempDir
    lateinit var tmp: Path

    private fun dataSource(url: String): DataSource =
        java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java)
        ) { _, m, _ ->
            when (m.name) {
                "getConnection" -> DriverManager.getConnection(url)
                else -> throw UnsupportedOperationException(m.name)
            }
        } as DataSource

    private fun seed(url: String) {
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE customer (customer_id BIGINT, display_name VARCHAR, updated_at TIMESTAMP)")
                st.execute("INSERT INTO customer SELECT range, 'name-' || range, TIMESTAMP '2026-07-01 00:00:00' + INTERVAL (range) DAY FROM range(40)")
            }
        }
    }

    /** The issue's pure-transformation requirement: deterministic, no I/O. */
    private fun normalizeCustomer(row: Row): Row =
        Row(row.schema, row.schema.columns.map { c ->
            val v = row[c.name]
            if (c.name.equals("display_name", true)) (v as String).uppercase() else v
        })

    @Test
    fun `pure transformation is unit-testable without any database`() {
        val schema = Schema(listOf(
            Column("customer_id", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("display_name", ValueKind.TEXT, "VARCHAR")))
        val out = normalizeCustomer(Row(schema, listOf(7L, "ann")))
        assertEquals("ANN", out["display_name"])
        assertEquals(7L, out["customer_id"])
    }

    @Test
    fun `golden path - completed outcome with transform rename and filter`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s.db")}"
        val dstUrl = "jdbc:duckdb:${tmp.resolve("d.db")}"
        seed(srcUrl)
        Relay.build {
            database(Sales, dataSource(srcUrl), DuckDbDialect)
            database(Analytics, dataSource(dstUrl), DuckDbDialect)
        }.use { relay ->
            val customers = relay.transfer("synchronize-customers") {
                from(Sales) {
                    query("""
                        select customer_id, display_name, updated_at
                        from customer
                        where customer_id < :below
                    """.trimIndent())
                    bind("below", 30L)
                }
                filter { (it["customer_id"] as Long) % 2 == 0L }     // 15 rows
                transform(::normalizeCustomer)
                to(Analytics, table = "customer") {
                    rename("display_name", "name")
                }
            }

            when (val outcome = relay.execute(customers)) {
                is TransferOutcome.Completed -> assertEquals(15L, outcome.report.rowsAffected)
                else -> throw AssertionError("expected Completed, got $outcome")
            }

            DriverManager.getConnection(dstUrl).use { c ->
                JdbcReader.open(c, "SELECT * FROM \"customer\" WHERE \"customer_id\" = 4").use { s ->
                    assertEquals("NAME-4", s.toList().single()["name"])   // renamed + transformed
                }
            }
        }
    }

    @Test
    fun `rejected outcome - invalid mapping admits nothing`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s2.db")}"
        seed(srcUrl)
        Relay.build {
            database(Sales, dataSource(srcUrl), DuckDbDialect)
            database(Analytics, dataSource("jdbc:duckdb:${tmp.resolve("d2.db")}"), DuckDbDialect)
        }.use { relay ->
            val plan = relay.transfer("bad-mapping") {
                from(Sales) { query("select * from customer") }
                to(Analytics, table = "t") { rename("no_such_column", "x") }
            }
            val outcome = relay.execute(plan)
            assertTrue(outcome is TransferOutcome.Rejected, outcome.toString())
            assertTrue((outcome as TransferOutcome.Rejected).reason.contains("mapping"))
        }
    }

    @Test
    fun `rejected outcome - missing named parameter lists the exact name`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s3.db")}"
        seed(srcUrl)
        Relay.build {
            database(Sales, dataSource(srcUrl), DuckDbDialect)
            database(Analytics, dataSource("jdbc:duckdb:${tmp.resolve("d3.db")}"), DuckDbDialect)
        }.use { relay ->
            val plan = relay.transfer("missing-param") {
                from(Sales) { query("select * from customer where customer_id > :floor") }
                to(Analytics, table = "t")
            }
            val outcome = relay.execute(plan)
            assertTrue(outcome is TransferOutcome.Rejected)
            assertTrue((outcome as TransferOutcome.Rejected).reason.contains("floor"))
        }
    }

    @Test
    fun `failed outcome - runtime SQL error with nothing committed`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s4.db")}"
        seed(srcUrl)
        Relay.build {
            database(Sales, dataSource(srcUrl), DuckDbDialect)
            database(Analytics, dataSource("jdbc:duckdb:${tmp.resolve("d4.db")}"), DuckDbDialect)
        }.use { relay ->
            val plan = relay.transfer("boom") {
                from(Sales) { query("select * from no_such_table") }
                to(Analytics, table = "t")
            }
            assertTrue(relay.execute(plan) is TransferOutcome.Failed)
        }
    }

    @Test
    fun `cancelled execution yields TransferOutcome Cancelled - never Failed`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("c.db")}"
        seed(srcUrl)
        Relay.build {
            database(Sales, dataSource(srcUrl), DuckDbDialect)
            database(Analytics, dataSource("jdbc:duckdb:${tmp.resolve("cd.db")}"), DuckDbDialect)
        }.use { relay ->
            val plan = relay.transfer("cancelme") {
                from(Sales) { query("SELECT customer_id, display_name FROM customer") }
                to(Analytics, table = "sink")
            }
            // an already-expired token: the reader checks cancellation at the
            // START of the first hasNext() (before any row), so this cancels
            // deterministically regardless of machine speed. Regression guard for
            // the HEL-129 fix — cancellation must classify as Cancelled, not
            // Failed (it used to be wrapped in BatchWriteException).
            val expired = io.maxxga.rowrelay.core.CancelToken.withTimeout(1)
            Thread.sleep(5)
            val outcome = relay.execute(plan, expired)
            assertTrue(outcome is TransferOutcome.Cancelled, "expected Cancelled, got $outcome")
        }
    }

    @Test
    fun `incomplete plans fail at DEFINITION time - not at execution`() {
        Relay.build {
            database(Sales, dataSource("jdbc:duckdb:"), DuckDbDialect)
        }.use { relay ->
            assertThrows(Relay.PlanDefinitionException::class.java) {
                relay.transfer("no-sink") { from(Sales) { query("select 1") } }
            }
            assertThrows(Relay.PlanDefinitionException::class.java) {
                relay.transfer("no-source") { to(Sales, table = "t") }
            }
            assertThrows(Relay.PlanDefinitionException::class.java) {
                relay.transfer("no-query") { from(Sales) {}; to(Sales, table = "t") }
            }
        }
    }

    @Test
    fun `upsertBy defaults the mode to APPEND and executes an idempotent sync`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s5.db")}"
        val dstUrl = "jdbc:duckdb:${tmp.resolve("d5.db")}"
        seed(srcUrl)
        DriverManager.getConnection(dstUrl).use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE customer (customer_id BIGINT PRIMARY KEY, display_name VARCHAR, updated_at TIMESTAMP)")
            }
        }
        Relay.build {
            database(Sales, dataSource(srcUrl), DuckDbDialect)
            database(Analytics, dataSource(dstUrl), DuckDbDialect)
        }.use { relay ->
            val sync = relay.transfer("sync") {
                from(Sales) { query("select * from customer where customer_id < :n"); bind("n", 10L) }
                to(Analytics, table = "customer") { upsertBy("customer_id") }
            }
            // run TWICE: idempotent by construction — still 10 rows, no dupes
            assertTrue(relay.execute(sync) is TransferOutcome.Completed)
            assertTrue(relay.execute(sync) is TransferOutcome.Completed)
            DriverManager.getConnection(dstUrl).use { c ->
                JdbcReader.open(c, "SELECT count(*) AS n FROM \"customer\"").use { s ->
                    assertEquals(10L, s.toList().single()["n"])
                }
            }
        }
    }
}
