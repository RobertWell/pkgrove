package com.pkgrove.pkgrovekit.jdbi

import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The JDBI entry point against real DuckDB, including the issue's equivalence
 * requirement: the SAME data through the JDBC path and the JDBI path must
 * produce equal schemas and equal rows.
 */
class JdbiPathTest {

    @field:org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    private lateinit var conn: Connection
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun setUp() {
        // file-backed DuckDB: JDBI owns (and closes) its own connections while
        // the test's JDBC connection addresses the same database.
        val url = "jdbc:duckdb:${tempDir.resolve("t.db")}"
        conn = DriverManager.getConnection(url)
        conn.createStatement().use { st ->
            st.execute("""CREATE TABLE t (id BIGINT, name VARCHAR, price DECIMAL(10,2),
                          ts TIMESTAMP, ok BOOLEAN)""")
            st.execute("""INSERT INTO t
                SELECT range, 'row-' || range, range * 1.5,
                       TIMESTAMP '2026-01-01 00:00:00' + INTERVAL (range) MINUTE, range % 2 = 0
                FROM range(40)""")
        }
        jdbi = Jdbi.create(url)
    }

    @AfterEach
    fun tearDown() { conn.close() }

    @Test
    fun `jdbi and jdbc paths produce equivalent schema and rows`() {
        val viaJdbc = JdbcReader.open(conn, "SELECT * FROM t ORDER BY id").use { s ->
            s.schema to s.toList().map { it.values }
        }
        // fresh handle AFTER the jdbc read (shared connection)
        val viaJdbi = jdbi.withHandle<Pair<com.pkgrove.pkgrovekit.core.Schema, List<List<Any?>>>, Exception> { h ->
            JdbiReader.read(h, "SELECT * FROM t ORDER BY id") { s ->
                s.schema to s.toList().map { it.values }
            }
        }
        assertEquals(viaJdbc.first, viaJdbi.first)     // Schema equality
        assertEquals(viaJdbc.second, viaJdbi.second)   // value-for-value equality
    }

    @Test
    fun `named parameters bind through normal jdbi behavior`() {
        jdbi.useHandle<Exception> { h ->
            val batch = JdbiReader.readAll(
                h, "SELECT name FROM t WHERE id = :id", mapOf("id" to 7L))
            assertEquals(1, batch.size)
            assertEquals("row-7", batch.rows[0]["name"])
        }
    }

    @Test
    fun `jdbi streaming batches cover all rows`() {
        jdbi.useHandle<Exception> { h ->
            JdbiReader.read(h, "SELECT * FROM t ORDER BY id") { s ->
                val sizes = s.batches(15).map { it.size }.toList()
                assertEquals(listOf(15, 15, 10), sizes)
                assertEquals(40L, s.rowsRead)
            }
        }
    }

    @Test
    fun `writer outside a transaction delegates full commit-policy semantics`() {
        conn.createStatement().use { it.execute("CREATE TABLE sink (id BIGINT, name VARCHAR)") }
        jdbi.useHandle<Exception> { h ->
            val batches = JdbiReader.read(h, "SELECT id, name FROM t ORDER BY id") { s ->
                s.batches(10).toList()
            }
            val report = JdbiBatchWriter.write(
                h, "INSERT INTO sink VALUES (?, ?)", batches.asSequence(),
                JdbcBatchWriter.WriteOptions(
                    commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(2)))
            assertTrue(report.completed)
            assertEquals(40L, report.rowsAffected)
        }
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sink")
            rs.next(); assertEquals(40L, rs.getLong(1))
        }
    }

    @Test
    fun `writer inside a caller transaction appends without committing and rejects per-chunk`() {
        conn.createStatement().use { it.execute("CREATE TABLE sink (id BIGINT, name VARCHAR)") }
        jdbi.useHandle<Exception> { h ->
            val batches = JdbiReader.read(h, "SELECT id, name FROM t LIMIT 10") { s ->
                s.batches(5).toList()
            }
            h.useTransaction<Exception> { txh ->
                // per-chunk inside the caller's transaction: rejected loudly
                assertThrows(IllegalArgumentException::class.java) {
                    JdbiBatchWriter.write(
                        txh, "INSERT INTO sink VALUES (?, ?)", batches.asSequence(),
                        JdbcBatchWriter.WriteOptions(
                            commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1)))
                }
                // all-or-nothing: appends into the caller's transaction
                val report = JdbiBatchWriter.write(
                    txh, "INSERT INTO sink VALUES (?, ?)", batches.asSequence())
                assertTrue(report.completed)
                assertEquals(10L, report.rowsAffected)
            }
        }
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sink")
            rs.next(); assertEquals(10L, rs.getLong(1))
        }
    }
}
