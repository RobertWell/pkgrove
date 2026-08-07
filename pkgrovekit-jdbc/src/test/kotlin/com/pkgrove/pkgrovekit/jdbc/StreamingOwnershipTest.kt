package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-256: the read path must ENFORCE the source driver's streaming
 * preconditions instead of assuming them, and must do so within the HEL-128
 * ownership model — configure what PkgroveKit owns, never mutate what it does
 * not, and refuse rather than silently buffer.
 *
 * The engine here is DuckDB (needs nothing to stream), driven with an
 * explicitly declared cursor-requiring [StreamingContract]. That is deliberate:
 * it exercises the ownership DECISION on every path, deterministically and with
 * no container, while the live-driver proof that the decision is the right one
 * for pgjdbc lives in `integration-tests` (PostgresStreamingIT).
 */
class StreamingOwnershipTest {

    /** A dialect that declares Postgres' precondition: fetchSize is ignored
     *  unless the connection is out of autocommit. */
    private object CursorDialect : SqlDialect {
        override val name: String = "cursor-test"
        override val streaming: StreamingContract = StreamingContract.POSTGRES
        override fun typeFor(column: Column): String = "VARCHAR"
    }

    private lateinit var conn: Connection

    @BeforeEach
    fun setUp() {
        conn = DriverManager.getConnection("jdbc:duckdb:")
        conn.createStatement().use { st ->
            st.execute("CREATE TABLE t (id BIGINT, name VARCHAR)")
            st.execute("INSERT INTO t VALUES (1,'a'),(2,'b'),(3,'c')")
        }
    }

    @AfterEach
    fun tearDown() = conn.close()

    private fun read(ownership: ConnectionOwnership, sql: String = "SELECT * FROM t ORDER BY id") =
        JdbcReader.ReadOptions(dialect = CursorDialect, ownership = ownership)
            .let { JdbcReader.open(conn, sql, emptyList(), it) }

    // ── Library-owned: fix it, then put it back exactly as found ────────────

    @Test
    fun `leased connection is taken out of autocommit so the driver can stream`() {
        assertTrue(conn.autoCommit, "precondition: the pool/driver default")
        read(ConnectionOwnership.LEASED).use { stream ->
            assertFalse(conn.autoCommit,
                "a cursor-requiring dialect must be given autoCommit=false, or fetchSize is a no-op")
            assertTrue(stream.streaming, "the stream must report itself as genuinely streaming")
            assertEquals(3, stream.toList().size)
        }
        assertTrue(conn.autoCommit, "autoCommit must be restored exactly as found on success")
    }

    @Test
    fun `leased connection is restored when the consumer throws mid-stream`() {
        val boom = assertThrows(IllegalStateException::class.java) {
            read(ConnectionOwnership.LEASED).use {
                assertFalse(conn.autoCommit)
                throw IllegalStateException("boom")
            }
        }
        assertEquals("boom", boom.message)
        assertTrue(conn.autoCommit, "autoCommit must be restored on the exception path too")
    }

    @Test
    fun `a failure during open leaves the connection untouched`() {
        assertThrows(Exception::class.java) {
            read(ConnectionOwnership.LEASED, "SELECT * FROM no_such_table")
        }
        assertTrue(conn.autoCommit,
            "a read that never produced a stream must not leave the connection in a transaction")
    }

    @Test
    fun `a dialect needing nothing never touches the connection`() {
        // DuckDB's real contract: in-process, nothing to arrange.
        JdbcReader.open(conn, "SELECT * FROM t", emptyList(),
                        JdbcReader.ReadOptions(dialect = DuckDbLikeDialect)).use { stream ->
            assertTrue(conn.autoCommit, "no precondition means no mutation")
            assertTrue(stream.streaming)
            assertEquals(3, stream.toList().size)
        }
        assertTrue(conn.autoCommit)
    }

    private object DuckDbLikeDialect : SqlDialect {
        override val name: String = "duckdb-like"
        override val streaming: StreamingContract = StreamingContract.NOT_APPLICABLE
        override fun typeFor(column: Column): String = "VARCHAR"
    }

    // ── Caller-owned: never mutate; refuse rather than buffer silently ──────

    @Test
    fun `caller-owned connection in autocommit is refused, not silently buffered`() {
        assertTrue(conn.autoCommit)
        val e = assertThrows(StreamingUnavailableException::class.java) {
            read(ConnectionOwnership.CALLER_OWNED)
        }
        // The message has to be actionable: name the setting, the reason, and
        // both fixes. A refusal nobody can act on is no better than buffering.
        val m = e.message.orEmpty()
        assertTrue(m.contains("autoCommit"), "must name the setting: $m")
        assertTrue(m.contains("caller-owned"), "must name the ownership that blocked it: $m")
        assertTrue(m.contains("setAutoCommit(false)"), "must name the caller-side fix: $m")
        assertTrue(m.contains("LEASED"), "must name the library-side fix: $m")
        assertTrue(conn.autoCommit, "a refusal must not have mutated anything")
    }

    @Test
    fun `caller-owned connection already in a transaction streams and is left alone`() {
        // The GOOD case, and the one every JTA-enlisted / Spring-bound
        // connection is actually in: autoCommit is already off, which is
        // exactly what the driver needs. It must NOT be refused, and must NOT
        // be committed, rolled back, or restored by us.
        conn.autoCommit = false
        try {
            read(ConnectionOwnership.CALLER_OWNED).use { stream ->
                assertEquals(3, stream.toList().size)
                assertTrue(stream.streaming)
            }
            assertFalse(conn.autoCommit,
                "a caller-owned transaction must still be open — PkgroveKit does not end it")
        } finally {
            conn.rollback()
            conn.autoCommit = true
        }
    }

    // ── Shared with the writer: cannot stream, so say so ────────────────────

    @Test
    fun `a connection shared with the target writer warns instead of claiming bounded memory`() {
        read(ConnectionOwnership.SHARED_WITH_WRITER).use { stream ->
            assertTrue(conn.autoCommit, "must not take over a connection the writer also commits on")
            assertFalse(stream.streaming, "this read is buffered and must admit it")
            val w = stream.warnings.single { it.code == "not-streaming" }
            assertTrue(w.message.contains("NOT bounded"), "the warning must be explicit: ${w.message}")
            assertEquals(3, stream.toList().size)
        }
        assertTrue(conn.autoCommit)
    }

    // ── The per-dialect table itself ────────────────────────────────────────

    @Test
    fun `streaming contracts are detected from the driver, not guessed`() {
        // pgjdbc always reports "PostgreSQL" — the buffering behavior belongs to
        // the DRIVER, so the product name is the correct key.
        assertSame(StreamingContract.POSTGRES, StreamingContract.forProductName("PostgreSQL"))
        assertSame(StreamingContract.MYSQL, StreamingContract.forProductName("MySQL"))
        assertSame(StreamingContract.MYSQL, StreamingContract.forProductName("MariaDB"))
        assertSame(StreamingContract.NOT_APPLICABLE, StreamingContract.forProductName("DuckDB"))
        // Never invent a requirement for a driver we have not audited.
        assertSame(StreamingContract.HONOURS_FETCH_SIZE, StreamingContract.forProductName("Oracle"))
        assertSame(StreamingContract.HONOURS_FETCH_SIZE, StreamingContract.forProductName("Wat"))
        assertSame(StreamingContract.HONOURS_FETCH_SIZE, StreamingContract.forProductName(""))

        assertTrue(StreamingContract.POSTGRES.requiresAutoCommitOff)
        assertEquals(Integer.MIN_VALUE, StreamingContract.MYSQL.streamingFetchSize)
        assertTrue(StreamingContract.HONOURS_FETCH_SIZE.streamsWithFetchSizeAlone)

        // Live detection with no declared dialect — the usual case, since a read
        // knows SQL, not vendors.
        assertTrue(StreamingContract.of(conn).streamsWithFetchSizeAlone,
            "DuckDB needs nothing arranged to stream")
    }
}
