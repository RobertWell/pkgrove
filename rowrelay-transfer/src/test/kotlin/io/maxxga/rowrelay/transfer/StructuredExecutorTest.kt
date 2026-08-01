package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.WorkflowOutcome
import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.Databases
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import javax.sql.DataSource

/** HEL-167: the coroutine structured executor — bounded concurrency + per-db
 *  budget, typed outcomes, fail-fast vs supervised, no leaked leases. */
class StructuredExecutorTest {

    private object Src : DatabaseKey("src-db")
    private object Dst : DatabaseKey("dst-db")

    @field:TempDir lateinit var tmp: Path

    private fun dataSource(url: String): DataSource =
        java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java)
        ) { _, m, _ ->
            if (m.name == "getConnection") DriverManager.getConnection(url)
            else throw UnsupportedOperationException(m.name)
        } as DataSource

    private fun seed(url: String) {
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS src (id BIGINT, name VARCHAR)")
                st.execute("INSERT INTO src SELECT range, 'n' || range FROM range(50)")
            }
        }
    }

    @Test
    fun `completes bounded by maxConcurrency and per-db budget, leaks nothing`() = runBlocking {
        val s = "jdbc:duckdb:${tmp.resolve("s.db")}"; val d = "jdbc:duckdb:${tmp.resolve("d.db")}"
        seed(s)
        Databases.build {
            applicationOwned(Src, dataSource(s), maxConnections = 2)
            applicationOwned(Dst, dataSource(d), maxConnections = 2)
        }.use { dbs ->
            val flows = (0 until 4).map { i ->
                Workflows.from(Src, "SELECT * FROM src WHERE id % 4 = :m", mapOf("m" to i.toLong()))
                    .to(Dst, DuckDbDialect, "part_$i")
            }
            val outcome = Workflows.executeStructured(flows, dbs, maxConcurrency = 2)
            assertTrue(outcome is WorkflowOutcome.Completed, outcome.toString())
            val results = (outcome as WorkflowOutcome.Completed).value
            assertEquals(50L, results.sumOf { it.report!!.rowsAffected })
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })   // no leaks
        }
    }

    @Test
    fun `supervised retains successes AND names the failure - never hidden`() = runBlocking {
        val s = "jdbc:duckdb:${tmp.resolve("s2.db")}"; val d = "jdbc:duckdb:${tmp.resolve("d2.db")}"
        seed(s)
        Databases.build {
            applicationOwned(Src, dataSource(s)); applicationOwned(Dst, dataSource(d))
        }.use { dbs ->
            val good = (0 until 3).map { i ->
                Workflows.from(Src, "SELECT * FROM src WHERE id % 3 = :m", mapOf("m" to i.toLong()))
                    .to(Dst, DuckDbDialect, "ok_$i")
            }
            val bad = Workflows.from(Src, "SELECT * FROM does_not_exist").to(Dst, DuckDbDialect, "boom")
            val outcome = Workflows.executeStructured(good + bad, dbs,
                policy = Workflows.BranchPolicy.SUPERVISED)

            assertTrue(outcome is WorkflowOutcome.Partial, outcome.toString())
            val p = outcome as WorkflowOutcome.Partial
            assertEquals(1, p.failures.size)
            assertEquals("boom", p.failures.single().branch)         // the failure is NAMED
            assertEquals(3, p.value!!.count { it.succeeded })        // successes retained
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })
        }
    }

    @Test
    fun `fail-fast returns Failed on the first failure and leaks nothing`() = runBlocking {
        val s = "jdbc:duckdb:${tmp.resolve("s3.db")}"; val d = "jdbc:duckdb:${tmp.resolve("d3.db")}"
        seed(s)
        Databases.build {
            applicationOwned(Src, dataSource(s)); applicationOwned(Dst, dataSource(d))
        }.use { dbs ->
            val good = Workflows.from(Src, "SELECT * FROM src").to(Dst, DuckDbDialect, "ok")
            val bad = Workflows.from(Src, "SELECT * FROM does_not_exist").to(Dst, DuckDbDialect, "boom")
            val outcome = Workflows.executeStructured(listOf(good, bad), dbs,
                policy = Workflows.BranchPolicy.FAIL_FAST)
            assertTrue(outcome is WorkflowOutcome.Failed, outcome.toString())
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })   // cancellation cleaned up
        }
    }
}
