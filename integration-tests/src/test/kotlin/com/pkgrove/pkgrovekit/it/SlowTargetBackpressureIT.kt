package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.ValueReader
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
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
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * HEL-129 test-matrix follow-up: the DEDICATED slow-target backpressure test.
 *
 * Claim under test: transfer memory is bounded BY CONSTRUCTION — one read
 * batch in flight at a time — so a slow sink throttles the SOURCE instead of
 * letting rows pile up in memory. We instrument both ends of the pipe against
 * a live Postgres and assert the bound at every write step, not just at the
 * end.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowTargetBackpressureIT {

    private val rows = 2_000
    private val batch = 50
    private val payloadBytes = 10_000   // ~20 MB total; ~0.5 MB per in-flight batch

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var conn: Connection

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        conn = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        conn.createStatement().use { st ->
            st.execute("CREATE TABLE src (id BIGINT, payload TEXT)")
            st.execute("INSERT INTO src SELECT g, repeat('x', $payloadBytes) FROM generate_series(1, $rows) g")
        }
    }

    @AfterAll
    fun stop() { runCatching { conn.close() }; runCatching { pg.stop() } }

    @Test
    fun `slow sink throttles the source - rows in flight never exceed one batch`() {
        val rowsRead = AtomicInteger(0)
        val rowsWritten = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        // Count every row the moment it is MATERIALIZED from the JDBC cursor.
        val countingReader = object : ValueReader {
            override fun read(rs: ResultSet, index: Int, column: Column,
                              warn: (DataWarning) -> Unit): Any? {
                if (index == 1) rowsRead.incrementAndGet()
                return ValueReader.DEFAULT.read(rs, index, column, warn)
            }
        }

        // A SLOW writer: sleeps per batch, and asserts the backpressure bound
        // BEFORE consuming each batch — reads may lead writes by at most one
        // read batch (the one being handed over) plus the JDBC fetch window.
        val bound = 2 * batch
        val slowWriter = Transfer.TargetWriter { dml, batches, options ->
            JdbcBatchWriter.write(conn, dml, batches.map { b ->
                val lead = rowsRead.get() - rowsWritten.get()
                maxInFlight.updateAndGet { maxOf(it, lead) }
                assertTrue(lead <= bound) {
                    "backpressure violated: $lead rows materialized ahead of the sink (bound $bound)"
                }
                Thread.sleep(20)          // ~40x slower than the read side
                rowsWritten.addAndGet(b.size)
                b
            }, options)
        }

        val before = usedHeap()
        val report = Transfer.runToWriter(
            conn, "SELECT * FROM src ORDER BY id", emptyMap(),
            conn, PostgresDialect, "slow_sink",
            Transfer.Options(readBatchSize = batch, fetchSize = batch,
                             sourceValueReader = countingReader),
            slowWriter)
        val heapGrowth = usedHeap() - before

        assertTrue(report.completed)
        assertEquals(rows.toLong(), report.rowsAffected)
        assertEquals(rows, rowsRead.get())
        assertTrue(maxInFlight.get() in 1..bound) {
            "expected bounded lead, saw max in-flight ${maxInFlight.get()}"
        }
        // Coarse memory ceiling: the whole corpus is ~20 MB; a pipeline that
        // buffered the source would retain it all. Generous slack (8 MB) keeps
        // this non-flaky while still refuting whole-corpus buffering.
        val corpus = rows.toLong() * payloadBytes
        assertTrue(heapGrowth < corpus / 2 + 8 * 1024 * 1024) {
            "heap grew ${heapGrowth / 1024 / 1024} MB for a ${corpus / 1024 / 1024} MB corpus — not streaming"
        }
        conn.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM \"slow_sink\"").use { rs ->
                rs.next(); assertEquals(rows.toLong(), rs.getLong(1))
            }
        }
    }

    private fun usedHeap(): Long {
        System.gc()
        Thread.sleep(50)
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }
}
