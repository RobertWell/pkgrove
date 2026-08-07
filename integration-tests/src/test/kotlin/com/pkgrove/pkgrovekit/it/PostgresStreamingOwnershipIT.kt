package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.jdbc.ConnectionOwnership
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.StreamingContract
import com.pkgrove.pkgrovekit.jdbc.StreamingUnavailableException
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-256 ownership acceptance against the REAL pgjdbc driver: PkgroveKit
 * configures what it owns, never mutates what it does not, and refuses rather
 * than silently buffer (HEL-128 ownership preserved).
 *
 * The deterministic decision-table for these branches is
 * `pkgrovekit-jdbc`'s StreamingOwnershipTest; what this adds is that the
 * decisions are made against pgjdbc's actual behavior and product metadata,
 * not a stand-in.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStreamingOwnershipIT {

    private lateinit var pg: PostgreSQLContainer<*>

    @BeforeAll
    fun setUp() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        connect().use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE t (id BIGINT PRIMARY KEY, name TEXT)")
                st.execute("INSERT INTO t SELECT g, 'n' || g FROM generate_series(1, 500) g")
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.stop()
    }

    private fun connect(): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    private val sql = "SELECT id, name FROM t ORDER BY id"

    @Test
    fun `pgjdbc is detected as cursor-requiring from its own metadata`() {
        connect().use { c ->
            assertSame(StreamingContract.POSTGRES, StreamingContract.of(c),
                "detection must key on the driver, with no dialect declared")
            assertSame(StreamingContract.POSTGRES, PostgresDialect.streaming,
                "and the declared dialect must agree with detection")
        }
    }

    @Test
    fun `a leased connection is restored exactly as found - success path`() {
        connect().use { c ->
            assertTrue(c.autoCommit)
            JdbcReader.open(c, sql, emptyList(),
                            JdbcReader.ReadOptions(ownership = ConnectionOwnership.LEASED)).use { s ->
                assertFalse(c.autoCommit, "pgjdbc needs autoCommit off before it will open a cursor")
                assertTrue(s.streaming)
                assertEquals(500, s.toList().size)
            }
            assertTrue(c.autoCommit, "restored on success")
            // And the connection is still usable, not stuck in a transaction.
            c.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs -> assertTrue(rs.next()) }
            }
        }
    }

    @Test
    fun `a leased connection is restored exactly as found - exception path`() {
        connect().use { c ->
            assertTrue(c.autoCommit)
            assertThrows(IllegalStateException::class.java) {
                JdbcReader.open(c, sql, emptyList(),
                                JdbcReader.ReadOptions(ownership = ConnectionOwnership.LEASED)).use {
                    throw IllegalStateException("consumer failed mid-stream")
                }
            }
            assertTrue(c.autoCommit, "restored on the exception path")
            c.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs -> assertTrue(rs.next()) }
            }
        }
    }

    @Test
    fun `a caller-owned connection in autocommit is refused at open`() {
        connect().use { c ->
            assertTrue(c.autoCommit)
            val e = assertThrows(StreamingUnavailableException::class.java) {
                JdbcReader.open(c, sql, emptyList(),
                                JdbcReader.ReadOptions(ownership = ConnectionOwnership.CALLER_OWNED))
            }
            assertTrue(e.message.orEmpty().contains("autoCommit"), e.message.orEmpty())
            assertTrue(c.autoCommit, "a refusal mutates nothing")
        }
    }

    @Test
    fun `a caller-owned connection inside a transaction is not refused and is left open`() {
        connect().use { c ->
            c.autoCommit = false          // the caller's transaction, e.g. JoinExisting
            JdbcReader.open(c, sql, emptyList(),
                            JdbcReader.ReadOptions(ownership = ConnectionOwnership.CALLER_OWNED)).use { s ->
                assertTrue(s.streaming, "already out of autocommit — pgjdbc streams")
                assertEquals(500, s.toList().size)
            }
            assertFalse(c.autoCommit, "PkgroveKit must not end a transaction it does not own")
            c.rollback()
        }
    }

    @Test
    fun `a same-connection transfer warns that it could not stream`() {
        // Source and target are one physical connection, so the target writer's
        // commit would close a server-side cursor. Streaming is impossible; the
        // report must SAY so rather than imply bounded memory.
        connect().use { c ->
            val report = Transfer.run(
                source = c, sourceSql = sql, sourceParams = emptyList(),
                target = c, targetDialect = PostgresDialect, targetTable = "t_copy",
                options = Transfer.Options(mode = com.pkgrove.pkgrovekit.jdbc.SqlDialect.TargetMode.CREATE))
            assertEquals(500L, report.rowsAffected)
            val w = report.warnings.single { it.code == "not-streaming" }
            assertTrue(w.message.contains("NOT bounded"), w.message)
            assertTrue(c.autoCommit, "the shared connection was never taken over")
        }
    }

    @Test
    fun `a two-connection transfer streams the source`() {
        connect().use { src ->
            connect().use { dst ->
                val report = Transfer.run(
                    source = src, sourceSql = sql, sourceParams = emptyList(),
                    target = dst, targetDialect = PostgresDialect, targetTable = "t_copy2",
                    options = Transfer.Options(mode = com.pkgrove.pkgrovekit.jdbc.SqlDialect.TargetMode.CREATE))
                assertEquals(500L, report.rowsAffected)
                assertTrue(report.warnings.none { it.code == "not-streaming" },
                    "separate connections stream; nothing to warn about: ${report.warnings}")
                assertTrue(src.autoCommit, "the source lease is restored")
            }
        }
    }
}
