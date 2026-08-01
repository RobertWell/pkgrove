package io.maxxga.rowrelay.it

import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbi.JdbiReader
import io.maxxga.rowrelay.transfer.Transfer
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * The README's Kotlin quick-start examples, COMPILED AND RUN in CI (HEL-123
 * documentation gate: examples can never silently rot). Each test method body
 * is the exact snippet the README shows — keep them in sync.
 */
class QuickStartExamples {

    @field:TempDir
    lateinit var tempDir: Path

    private fun demoDb(url: String) {
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE trades (id BIGINT, symbol VARCHAR, price DECIMAL(10,2))")
                st.execute("INSERT INTO trades SELECT range, 'SYM' || range, range * 1.5 FROM range(50)")
            }
        }
    }

    @Test
    fun `quick start - read a query without DTOs (JDBC)`() {
        val url = "jdbc:duckdb:${tempDir.resolve("q.db")}"
        demoDb(url)
        // --- README: quick-start-jdbc ---
        DriverManager.getConnection(url).use { connection ->
            JdbcReader.open(connection, "SELECT * FROM trades WHERE id < ?", listOf(10L)).use { rows ->
                println(rows.schema)                    // runtime-discovered columns
                for (row in rows) {
                    val symbol: Any? = row["symbol"]    // access by name, no DTO
                    check(symbol != null)
                }
            }
        }
        // --- end README ---
    }

    @Test
    fun `quick start - named parameters through JDBI`() {
        val url = "jdbc:duckdb:${tempDir.resolve("j.db")}"
        demoDb(url)
        // --- README: quick-start-jdbi ---
        val jdbi = Jdbi.create(url)
        val batch = jdbi.withHandle<io.maxxga.rowrelay.core.RowBatch, Exception> { handle ->
            JdbiReader.readAll(handle, "SELECT * FROM trades WHERE symbol = :sym",
                               mapOf("sym" to "SYM7"))
        }
        check(batch.rows.single()["id"] == 7L)
        // --- end README ---
        assertEquals(1, batch.size)
    }

    @Test
    fun `quick start - transfer a query result into another database`() {
        val srcUrl = "jdbc:duckdb:${tempDir.resolve("s.db")}"
        val dstUrl = "jdbc:duckdb:${tempDir.resolve("d.db")}"
        demoDb(srcUrl)
        // --- README: quick-start-transfer ---
        DriverManager.getConnection(srcUrl).use { source ->
            DriverManager.getConnection(dstUrl).use { target ->
                val report = Transfer.run(
                    source, "SELECT * FROM trades WHERE id < ?", listOf(25L),
                    target, DuckDbDialect, "trades_copy",
                    Transfer.Options(
                        readBatchSize = 10,
                        commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1)))
                check(report.completed && report.rowsAffected == 25L)
            }
        }
        // --- end README ---
        DriverManager.getConnection(dstUrl).use { c ->
            c.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM \"trades_copy\"")
                rs.next()
                assertTrue(rs.getLong(1) == 25L)
            }
        }
    }

    // The golden-path: registered databases + an immutable flow + the managed
    // structured executor — consumer code carries no Connection, commit,
    // rollback, or thread choreography (README: golden-path).
    private object Source : io.maxxga.rowrelay.jdbc.DatabaseKey("source")
    private object Target : io.maxxga.rowrelay.jdbc.DatabaseKey("target")

    private fun ds(url: String): javax.sql.DataSource =
        java.lang.reflect.Proxy.newProxyInstance(
            javax.sql.DataSource::class.java.classLoader,
            arrayOf(javax.sql.DataSource::class.java)
        ) { _, m, _ ->
            if (m.name == "getConnection") DriverManager.getConnection(url)
            else throw UnsupportedOperationException(m.name)
        } as javax.sql.DataSource

    @Test
    fun `golden path - managed workflow with the structured executor`() = kotlinx.coroutines.runBlocking {
        val srcUrl = "jdbc:duckdb:${tempDir.resolve("g_s.db")}"
        val dstUrl = "jdbc:duckdb:${tempDir.resolve("g_d.db")}"
        demoDb(srcUrl)
        // --- README: golden-path ---
        io.maxxga.rowrelay.jdbc.Databases.build {
            applicationOwned(Source, ds(srcUrl))
            applicationOwned(Target, ds(dstUrl))
        }.use { databases ->
            val flow = io.maxxga.rowrelay.transfer.Workflows
                .from(Source, "SELECT id, symbol, price FROM trades WHERE price > :min",
                      mapOf("min" to 30.0))
                .to(Target, DuckDbDialect, "big_trades")

            val outcome = io.maxxga.rowrelay.transfer.Workflows.executeStructured(
                listOf(flow), databases)

            check(outcome is io.maxxga.rowrelay.core.WorkflowOutcome.Completed)
        }
        // --- end README ---
        DriverManager.getConnection(dstUrl).use { c ->
            c.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM \"big_trades\"")
                rs.next(); assertTrue(rs.getLong(1) > 0)
            }
        }
    }

    // HEL-125: the canonical homepage example — the Relay DSL golden path.
    // Pure transformation, defined apart from any I/O (unit-testable alone):
    private fun normalizeCustomer(row: io.maxxga.rowrelay.core.Row): io.maxxga.rowrelay.core.Row =
        io.maxxga.rowrelay.core.Row(row.schema, row.schema.columns.map { c ->
            val v = row[c.name]
            if (c.name.equals("symbol", true)) (v as String).lowercase() else v
        })

    @Test
    fun `homepage golden path - relay transfer DSL with typed outcome`() {
        val srcUrl = "jdbc:duckdb:${tempDir.resolve("r_s.db")}"
        val dstUrl = "jdbc:duckdb:${tempDir.resolve("r_d.db")}"
        demoDb(srcUrl)
        // an upsert syncs into an EXISTING keyed table (schema-migration tool's job)
        DriverManager.getConnection(dstUrl).use { c ->
            c.createStatement().use {
                it.execute("CREATE TABLE trades (id BIGINT PRIMARY KEY, ticker VARCHAR, price DOUBLE)")
            }
        }
        // --- README: relay-golden-path ---
        io.maxxga.rowrelay.transfer.Relay.build {
            database(Source, ds(srcUrl), DuckDbDialect)      // configured ONCE, at startup
            database(Target, ds(dstUrl), DuckDbDialect)
        }.use { relay ->
            val trades = relay.transfer("synchronize-trades") {
                from(Source) {
                    query("""
                        select id, symbol, price
                        from trades
                        where price >= :floor
                    """.trimIndent())
                    bind("floor", 10.0)
                }
                transform(::normalizeCustomer)               // pure, unit-testable
                to(Target, table = "trades") {
                    rename("symbol", "ticker")               // mapping by NAME
                    upsertBy("id")                           // idempotent identity
                }
            }

            when (val outcome = relay.execute(trades)) {
                is io.maxxga.rowrelay.transfer.TransferOutcome.Completed ->
                    check(outcome.report.rowsAffected > 0)
                is io.maxxga.rowrelay.transfer.TransferOutcome.Partial ->
                    error("resume from ${outcome.checkpoint.committedRows}")
                is io.maxxga.rowrelay.transfer.TransferOutcome.Rejected ->
                    error("plan rejected: ${outcome.reason}")
                is io.maxxga.rowrelay.transfer.TransferOutcome.Failed ->
                    throw outcome.cause
                is io.maxxga.rowrelay.transfer.TransferOutcome.Cancelled ->
                    error("cancelled")
            }
        }
        // --- end README ---
        DriverManager.getConnection(dstUrl).use { c ->
            c.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM \"trades\" WHERE \"ticker\" = lower(\"ticker\")")
                rs.next(); assertTrue(rs.getLong(1) > 0)     // transform + rename applied
            }
        }
    }
}
