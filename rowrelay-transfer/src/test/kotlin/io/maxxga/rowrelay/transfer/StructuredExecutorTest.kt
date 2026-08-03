package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.WorkflowOutcome
import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.Databases
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    fun `transaction affinity - same-database writes serialize under a 1-lease budget`() = runBlocking {
        // HEL-167 scenario 7: flows targeting the SAME database can't run truly
        // parallel — the per-db lease budget (maxConnections=1) serializes them,
        // so they never share a connection or interleave a transaction. maxConcurrency
        // says "up to 4 at once", but the budget is the real, safe ceiling.
        val s = "jdbc:duckdb:${tmp.resolve("s4.db")}"; val d = "jdbc:duckdb:${tmp.resolve("d4.db")}"
        seed(s)
        Databases.build {
            applicationOwned(Src, dataSource(s))
            applicationOwned(Dst, dataSource(d), maxConnections = 1)   // affinity: one writer
        }.use { dbs ->
            val flows = (0 until 4).map { i ->
                Workflows.from(Src, "SELECT * FROM src WHERE id % 4 = :m", mapOf("m" to i.toLong()))
                    .to(Dst, DuckDbDialect, "sink_$i")   // same DATABASE, one lease budget
            }
            val outcome = Workflows.executeStructured(flows, dbs, maxConcurrency = 4)
            assertTrue(outcome is WorkflowOutcome.Completed, outcome.toString())
            val total = (outcome as WorkflowOutcome.Completed).value.sumOf { it.report!!.rowsAffected }
            assertEquals(50L, total)
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })
            // affinity proof: maxConcurrency said 4, but the 1-lease budget on the
            // target DB never let more than ONE write run at a time.
            assertEquals(1L, dbs.metrics().single { it.key == "dst-db" }.maxConcurrentLeases)
        }
    }

    @Test
    fun `caller cancellation stops in-flight blocking JDBC work promptly and releases leases`() = runBlocking {
        // HEL-128 review: the coroutine-to-JDBC bridge. The flow's row stream is
        // slowed to ~100ms/row (50 rows ≈ 5s full run) with per-row cooperative
        // checkpoints (readBatchSize=1). Cancelling the caller must (a) propagate
        // the linked scope token into the BLOCKING JDBC work so it aborts at the
        // next checkpoint instead of running to completion, and (b) release
        // every lease. The processed-row counter is the proof of promptness.
        val s = "jdbc:duckdb:${tmp.resolve("s5.db")}"; val d = "jdbc:duckdb:${tmp.resolve("d5.db")}"
        seed(s)
        Databases.build {
            applicationOwned(Src, dataSource(s)); applicationOwned(Dst, dataSource(d))
        }.use { dbs ->
            val processed = java.util.concurrent.atomic.AtomicInteger(0)
            val slow = Workflows.from(Src, "SELECT * FROM src")
                .transform { r -> processed.incrementAndGet(); Thread.sleep(100); r }
                .to(Dst, DuckDbDialect, "slow_sink",
                    Transfer.Options(readBatchSize = 1, fetchSize = 1))
            val job = launch {
                Workflows.executeStructured(listOf(slow), dbs)
            }
            delay(400)
            val t0 = System.nanoTime()
            job.cancel()
            job.join()   // waits for the blocking work to actually stop
            val stopMillis = (System.nanoTime() - t0) / 1_000_000
            // stopped early — nowhere near the 50-row full run
            assertTrue(processed.get() < 25, "processed ${processed.get()} rows; bridge did not stop the work")
            assertTrue(stopMillis < 3_000, "took ${stopMillis}ms to stop after cancel")
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })   // leases released
        }
    }

    @Test
    fun `fail-fast sibling failure cancels the slow branch promptly via the bridge`() = runBlocking {
        val s = "jdbc:duckdb:${tmp.resolve("s6.db")}"; val d = "jdbc:duckdb:${tmp.resolve("d6.db")}"
        seed(s)
        Databases.build {
            applicationOwned(Src, dataSource(s), maxConnections = 2)
            applicationOwned(Dst, dataSource(d), maxConnections = 2)
        }.use { dbs ->
            val processed = java.util.concurrent.atomic.AtomicInteger(0)
            val slow = Workflows.from(Src, "SELECT * FROM src")
                .transform { r -> processed.incrementAndGet(); Thread.sleep(100); r }
                .to(Dst, DuckDbDialect, "slow_sink",
                    Transfer.Options(readBatchSize = 1, fetchSize = 1))
            val bad = Workflows.from(Src, "SELECT * FROM does_not_exist").to(Dst, DuckDbDialect, "boom")
            val outcome = Workflows.executeStructured(listOf(slow, bad), dbs,
                policy = Workflows.BranchPolicy.FAIL_FAST)
            assertTrue(outcome is WorkflowOutcome.Failed, outcome.toString())
            // the slow sibling was stopped by the scope token, not run to completion
            assertTrue(processed.get() < 25, "processed ${processed.get()} rows; sibling kept running")
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
