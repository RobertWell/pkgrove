package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.Databases
import io.maxxga.rowrelay.jdbc.JdbcReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/** HEL-125: flow definitions are inert data; executors acquire leases via the
 *  HEL-128 registry; transforms/filters compose; parallel fan-out respects
 *  budgets. File-backed DuckDB so multiple connections see one database. */
class WorkflowTest {

    private object Src : DatabaseKey("src-db")
    private object Dst : DatabaseKey("dst-db")

    @field:TempDir
    lateinit var tmp: Path

    private fun dataSource(url: String): DataSource =
        java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java)
        ) { _, m, args ->
            when (m.name) {
                "getConnection" -> if (args == null) DriverManager.getConnection(url)
                                   else DriverManager.getConnection(url)
                else -> throw UnsupportedOperationException(m.name)
            }
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
    fun `sequential flow with transform filter and named params`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s.db")}"
        val dstUrl = "jdbc:duckdb:${tmp.resolve("d.db")}"
        seed(srcUrl)
        Databases.build {
            applicationOwned(Src, dataSource(srcUrl))
            applicationOwned(Dst, dataSource(dstUrl))
        }.use { dbs ->
            val flow = Workflows
                .from(Src, "SELECT * FROM src WHERE id < :below", mapOf("below" to 30L))
                .filter { (it["id"] as Long) % 2 == 0L }          // 15 even rows
                .transform { r -> Row(r.schema, listOf(r["id"], (r["name"] as String).uppercase())) }
                .to(Dst, DuckDbDialect, "dest")

            val results = Workflows.SequentialExecutor.execute(listOf(flow), dbs)
            val r = results.single()
            assertTrue(r.succeeded, r.error?.toString() ?: "")
            assertEquals(15L, r.report!!.rowsAffected)
            // leases all returned
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })

            DriverManager.getConnection(dstUrl).use { c ->
                JdbcReader.open(c, "SELECT * FROM \"dest\" WHERE \"id\" = 4").use { s ->
                    assertEquals("N4", s.toList().single()["name"])   // transform applied
                }
            }
        }
    }

    @Test
    fun `parallel executor runs independent flows within budgets and leaks nothing`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s2.db")}"
        val dstUrl = "jdbc:duckdb:${tmp.resolve("d2.db")}"
        seed(srcUrl)
        Databases.build {
            applicationOwned(Src, dataSource(srcUrl), maxConnections = 2)
            applicationOwned(Dst, dataSource(dstUrl), maxConnections = 2)
        }.use { dbs ->
            val flows = (0 until 4).map { i ->
                Workflows.from(Src, "SELECT * FROM src WHERE id % 4 = :m", mapOf("m" to i.toLong()))
                    .to(Dst, DuckDbDialect, "part_$i")
            }
            val results = Workflows.ParallelExecutor(maxConcurrentFlows = 4).execute(flows, dbs)
            assertTrue(results.all { it.succeeded },
                       results.mapNotNull { it.error }.joinToString())
            assertEquals(50L, results.sumOf { it.report!!.rowsAffected })
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })   // no leaks
        }
    }

    @Test
    fun `flow definitions carry keys not connections`() {
        val flow = Workflows.from(Src, "SELECT 1").to(Dst, DuckDbDialect, "t")
        // the definition is plain data: keys + sql + options — assertable and
        // serializable-by-content; no Connection/DataSource fields exist.
        assertEquals(Src, flow.sourceKey)
        assertEquals(Dst, flow.sinkKey)
        assertTrue(flow.toString().contains("src-db"))
    }

    @Test
    fun `an incomplete flow cannot reach an executor - it is unrepresentable`() {
        // HEL-125 §3: from(...) returns a SourceFlow. An executor accepts only
        // ExecutableFlow, so a flow WITHOUT a sink is a COMPILE error, not a
        // runtime failure — there is no nullable-sink + !! at run time. The line
        // below would not compile (kept as documentation, not executed):
        //     Workflows.SequentialExecutor.execute(listOf(Workflows.from(Src, "SELECT 1")), dbs)
        val incomplete = Workflows.from(Src, "SELECT 1")     // a SourceFlow
        val executable = incomplete.to(Dst, DuckDbDialect, "t")   // only now runnable
        assertTrue(executable is Workflows.ExecutableFlow)
    }
}
