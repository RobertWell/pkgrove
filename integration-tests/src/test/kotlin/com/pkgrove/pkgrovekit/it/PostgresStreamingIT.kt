package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-256 acceptance: on a Postgres source a large read streams with BOUNDED
 * HEAP without the caller pre-configuring the connection.
 *
 * This is the regression gate for the defect. pgjdbc opens a server-side cursor
 * only when the connection is out of autocommit; in autocommit it ignores
 * `Statement.fetchSize` and materializes the entire result set client-side,
 * silently. Before the fix, `JdbcReader` set fetchSize and nothing else, so the
 * library's "bounded memory by construction" claim held only by luck of caller
 * configuration — and a framework-managed DataSource (Quarkus/Agroal,
 * Spring/Hikari) hands out autoCommit=true.
 *
 * Deliberately written against the read API AS IT EXISTED BEFORE the fix — a
 * plain connection at its driver default, `ReadOptions(fetchSize = ...)`, no
 * ownership or dialect argument. So this exact source also compiles against the
 * pre-fix `JdbcReader`, where it FAILS on retained heap. That is what makes it
 * a regression test rather than a description of the new code.
 *
 * The measured quantity is RETAINED heap while the read runs, against a result
 * set several times the assertion threshold: buffering cannot come in under the
 * bar by being lucky, and streaming cannot exceed it by being unlucky.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStreamingIT {

    /** ~205 MiB of result set: 200k rows x 1 KiB of TEXT. */
    private val rows = 200_000
    private val payloadBytes = 1_024
    private val resultSetMiB = (rows.toLong() * payloadBytes) shr 20

    /** Bounded means a few in-flight batches, not a fraction of the result set.
     *  ~3x headroom over what streaming actually retains, ~3x under what
     *  buffering must retain — a gap no measurement noise closes. */
    private val maxRetainedMiB = 64L

    private lateinit var pg: PostgreSQLContainer<*>

    @BeforeAll
    fun setUp() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        connect().use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE wide_payload (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)")
                st.execute("""
                    INSERT INTO wide_payload
                    SELECT g, repeat(md5(g::text), ${payloadBytes / 32})
                    FROM generate_series(1, $rows) g""")
            }
        }
    }

    @AfterAll
    fun tearDown() {
        if (::pg.isInitialized) pg.stop()
    }

    private fun connect(): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    @Test
    fun `a large Postgres read is bounded without the caller configuring anything`() {
        var firstBatchMs = -1L
        var rowsRead = 0L
        var retained = 0L

        // Checkpoints at 25/50/75% of the way through the scan. The measurement
        // has to be the LIVE set, not `totalMemory - freeMemory`: a streaming
        // read allocates the whole result set over its lifetime as short-lived
        // garbage, so raw used-heap tracks the result-set size on both the
        // streaming AND the buffering path and discriminates nothing. Collecting
        // first is what separates "still holding it" from "already dropped it" —
        // and only the driver's buffer survives a collection.
        val checkpoints = listOf(rows / 4L, rows / 2L, rows * 3L / 4)

        val base = liveHeapMiB()
        connect().use { c ->
            // The whole point: NOTHING is done to this connection. It is at the
            // driver default (autoCommit=true), exactly as a Quarkus/Agroal or
            // Spring/Hikari managed DataSource would hand it over.
            assertTrue(c.autoCommit, "precondition: an unconfigured connection is in autocommit")
            val t0 = System.nanoTime()
            JdbcReader.open(
                c, "SELECT id, payload FROM wide_payload ORDER BY id", emptyList(),
                JdbcReader.ReadOptions(fetchSize = 1_000)
            ).use { stream ->
                for (b in stream.batches(1_000)) {
                    if (firstBatchMs < 0) firstBatchMs = (System.nanoTime() - t0) / 1_000_000
                    // Every batch is dropped immediately, so anything still LIVE
                    // at a checkpoint belongs to the driver, not the consumer —
                    // which is exactly what is under test.
                    rowsRead += b.size
                    if (rowsRead in checkpoints) {
                        retained = maxOf(retained, liveHeapMiB() - base)
                    }
                }
            }
        }

        println("MEASURE HEL-256 bounded read: resultSetMiB=$resultSetMiB " +
                "liveRetainedMiB=$retained timeToFirstBatchMs=$firstBatchMs")

        assertEquals(rows.toLong(), rowsRead, "the whole result set must still be delivered")
        assertTrue(retained < maxRetainedMiB) {
            "read retained ${retained}MiB LIVE of a ${resultSetMiB}MiB result set — the driver " +
            "is BUFFERING, not streaming. pgjdbc ignores fetchSize unless the connection is out " +
            "of autocommit; JdbcReader must arrange that itself (HEL-256)."
        }
    }

    /** Live (post-collection) heap in MiB. Two passes because the first
     *  collection can leave recently-promoted garbage behind. */
    private fun liveHeapMiB(): Long {
        val rt = Runtime.getRuntime()
        repeat(2) { System.gc(); Thread.sleep(40) }
        return (rt.totalMemory() - rt.freeMemory()) shr 20
    }
}
