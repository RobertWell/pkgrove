package com.pkgrove.pkgrovekit.jdbi

import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-160: the first-class JDBI transfer facade. A JDBI consumer transfers into a
 * table on a [org.jdbi.v3.core.Handle] with the SAME transaction semantics as
 * [JdbiBatchWriter], without reaching into `handle.connection`.
 */
class JdbiTransferTest {

    @field:org.junit.jupiter.api.io.TempDir
    lateinit var tempDir: java.nio.file.Path

    private lateinit var conn: Connection
    private lateinit var jdbi: Jdbi

    @BeforeEach
    fun setUp() {
        val url = "jdbc:duckdb:${tempDir.resolve("t.db")}"
        conn = DriverManager.getConnection(url)
        conn.createStatement().use { st ->
            st.execute("CREATE TABLE src (id BIGINT, name VARCHAR)")
            st.execute("INSERT INTO src SELECT range, 'row-' || range FROM range(10)")
        }
        jdbi = Jdbi.create(url)
    }

    @AfterEach
    fun tearDown() { conn.close() }

    private fun rowCount(table: String): Long =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
        }

    private fun tableExists(table: String): Boolean =
        try { rowCount(table); true } catch (e: Exception) { false }

    @Test
    fun `transfer outside a transaction creates and fills the target`() {
        val report = jdbi.withHandle<com.pkgrove.pkgrovekit.core.OperationReport, Exception> { h ->
            JdbiTransfer.run(conn, "SELECT id, name FROM src ORDER BY id", emptyMap(),
                h, DuckDbDialect, "sink", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        }
        assertTrue(report.completed)
        assertEquals(10L, report.rowsAffected)
        assertEquals(10L, rowCount("sink"))
    }

    @Test
    fun `transfer inside a caller transaction is atomic — commits with the caller`() {
        jdbi.useHandle<Exception> { h ->
            h.useTransaction<Exception> { txh ->
                val report = JdbiTransfer.run(conn, "SELECT id, name FROM src ORDER BY id",
                    emptyMap(), txh, DuckDbDialect, "sink_tx",
                    Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
                assertTrue(report.completed)
                assertEquals(10L, report.rowsAffected)
            }  // caller commits here
        }
        assertEquals(10L, rowCount("sink_tx"))
    }

    @Test
    fun `transfer inside a caller transaction rolls back with the caller — nothing persists`() {
        class Boom : RuntimeException()
        assertThrows(Boom::class.java) {
            jdbi.useHandle<Exception> { h ->
                h.useTransaction<Exception> { txh ->
                    JdbiTransfer.run(conn, "SELECT id, name FROM src ORDER BY id",
                        emptyMap(), txh, DuckDbDialect, "sink_rb",
                        Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
                    throw Boom()  // caller aborts -> JDBI rolls the transaction back
                }
            }
        }
        // the CREATE + inserts were part of the caller's transaction, so both are gone.
        assertFalse(tableExists("sink_rb"), "rolled-back transfer must leave no table")
    }

    @Test
    fun `per-chunk inside a caller transaction is rejected BEFORE the target is created`() {
        jdbi.useHandle<Exception> { h ->
            h.useTransaction<Exception> { txh ->
                assertThrows(IllegalArgumentException::class.java) {
                    JdbiTransfer.run(conn, "SELECT id, name FROM src", emptyMap(),
                        txh, DuckDbDialect, "sink_pc",
                        Transfer.Options(mode = SqlDialect.TargetMode.CREATE,
                            commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(2)))
                }
            }
        }
        // fail-early: no half-built target table left behind
        assertFalse(tableExists("sink_pc"), "rejected transfer must not have created the table")
    }

    @Test
    fun `jdbi facade transfers the same rows as the raw connection path`() {
        // JDBC path into sink_a
        Transfer.run(conn, "SELECT id, name FROM src ORDER BY id", emptyList(),
            conn, DuckDbDialect, "sink_a", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        // JDBI path into sink_b
        jdbi.useHandle<Exception> { h ->
            JdbiTransfer.run(conn, "SELECT id, name FROM src ORDER BY id", emptyMap(),
                h, DuckDbDialect, "sink_b", Transfer.Options(mode = SqlDialect.TargetMode.CREATE))
        }
        val a = conn.createStatement().use { st ->
            st.executeQuery("SELECT id, name FROM sink_a ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) } } }
        val b = conn.createStatement().use { st ->
            st.executeQuery("SELECT id, name FROM sink_b ORDER BY id").use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1) to rs.getString(2)) } } }
        assertEquals(a, b)
        assertEquals(10, a.size)
    }
}
