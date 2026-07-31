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
}
