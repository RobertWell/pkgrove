package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.jdbc.DatabaseKey
import com.pkgrove.pkgrovekit.jdbc.Databases
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-234 (owner mandate 2026-08-09): the scheduled SOAK gate. A bounded
 * long-running loop that repeatedly transfers a 200k-row dataset from an
 * embedded DuckDB into a LIVE pooled Postgres and HARD-FAILS on the leak /
 * boundedness properties a single-shot integration test cannot see:
 *
 *  - CONNECTION LEAK — `activeLeases` must be 0 after EVERY iteration, and the
 *    Postgres-side session count must end at its post-warmup baseline (a leaked
 *    physical connection shows up server-side even when lease accounting lies);
 *  - THREAD LEAK — live JVM threads must end within a small delta of the
 *    post-warmup baseline (an executor/timer leaked per-iteration compounds);
 *  - HEAP BOUNDEDNESS — post-GC retained heap must stay under an absolute
 *    ceiling AND must not TREND upward (last-third average vs first-third
 *    average), so a slow per-iteration retention fails even below the ceiling.
 *
 * Duration is bounded by -Dpkgrovekit.soak.minutes (default 2 for local/proof
 * runs; the scheduled CI tier passes 12 — see .gitlab-ci.yml `stress-soak`).
 * Every iteration is appended to build/soak/soak-trend.csv, the RETAINED trend
 * artifact the scheduled pipeline archives (365 days) so drift across nights
 * is comparable, not just within one run. Skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferSoakIT {

    private object Pg : DatabaseKey("pg-soak")

    private companion object {
        const val ROWS = 200_000
        const val HEAP_CEILING_MB = 256.0     // absolute post-GC ceiling under the pinned 512m max heap
        const val HEAP_TREND_SLACK_MB = 32.0  // last-third avg may exceed first-third avg by at most this
        const val THREAD_LEAK_DELTA = 8       // testcontainers/JDBC keep a few background threads
        const val SESSION_LEAK_DELTA = 2      // pool may lazily grow toward maximumPoolSize
        const val MIN_ITERATIONS = 6          // below this the trend maths is meaningless
    }

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var pool: HikariDataSource
    private lateinit var duck: Connection

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
            maximumPoolSize = 4
            minimumIdle = 1
        })
        duck = DriverManager.getConnection("jdbc:duckdb:")
        duck.createStatement().use {
            it.execute("CREATE TABLE big AS SELECT range AS id, 'n'||range AS name FROM range($ROWS)")
        }
    }

    @AfterAll
    fun stop() {
        runCatching { duck.close() }
        runCatching { pool.close() }
        runCatching { pg.stop() }
    }

    /** Post-GC retained heap in MB — the boundedness signal (not instantaneous usage). */
    private fun retainedHeapMb(): Double {
        repeat(2) { System.gc(); Thread.sleep(50) }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1_048_576.0
    }

    private fun dbSessions(): Int = pool.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    @Test
    fun `soak transfer loop stays leak-free and heap-bounded`() {
        val minutes = System.getProperty("pkgrovekit.soak.minutes")?.toDouble() ?: 2.0
        val deadline = System.nanoTime() + (minutes * 60e9).toLong()
        val threads = ManagementFactory.getThreadMXBean()

        val trendDir = Paths.get("build", "soak")
        Files.createDirectories(trendDir)
        val trend = trendDir.resolve("soak-trend.csv")
        val samples = mutableListOf<String>()
        val heap = mutableListOf<Double>()

        Databases.build {
            applicationOwned(Pg, pool, PostgresDialect, maxConnections = 4, acquisitionTimeoutMillis = 30_000)
        }.use { dbs ->
            var iteration = 0
            var threadBaseline = -1
            var sessionBaseline = -1
            val t0 = System.nanoTime()
            while (System.nanoTime() < deadline || iteration < MIN_ITERATIONS) {
                iteration++
                dbs.withConnection(Pg) { pgc ->
                    // CREATE_OR_REPLACE emits `CREATE OR REPLACE TABLE` (DuckDB
                    // syntax) which Postgres rejects — first live soak run proved
                    // it. Drop + CREATE keeps the same per-iteration semantics on
                    // a dialect that has no single-statement replace.
                    pgc.createStatement().use { it.execute("DROP TABLE IF EXISTS soak_sink") }
                    Transfer.run(
                        duck, "SELECT id, name FROM big", emptyMap<String, Any?>(),
                        pgc, PostgresDialect, "soak_sink",
                        Transfer.Options(mode = SqlDialect.TargetMode.CREATE, readBatchSize = 5_000),
                    )
                }
                // correctness every lap — a soak that silently truncates proves nothing
                pool.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM soak_sink").use { rs ->
                            rs.next(); assertEquals(ROWS, rs.getInt(1), "iteration $iteration truncated the transfer")
                        }
                    }
                }
                // LEAK GATE (leases): checked EVERY iteration — first leak fails fast
                val active = dbs.metrics().single { it.key == "pg-soak" }.activeLeases
                assertEquals(0L, active, "CONNECTION LEAK: activeLeases=$active after iteration $iteration")

                val heapMb = retainedHeapMb()
                val liveThreads = threads.threadCount
                val sessions = dbSessions()
                heap += heapMb
                samples += "%d,%.1f,%.1f,%d,%d,%d,%d".format(
                    iteration, (System.nanoTime() - t0) / 1e9, heapMb, liveThreads, sessions, active, ROWS,
                )
                if (iteration == 1) { threadBaseline = liveThreads; sessionBaseline = sessions }
            }

            // trend artifact is written even if the gates below fail (CI archives when:always)
            Files.write(trend, listOf("iteration,elapsed_s,retained_heap_mb,live_threads,db_sessions,active_leases,rows") + samples)
            println("soak: $iteration iterations x $ROWS rows in ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s; trend -> $trend")

            assertTrue(iteration >= MIN_ITERATIONS, "only $iteration iterations — too few for a trend verdict")

            // HEAP BOUNDEDNESS: absolute ceiling + no upward trend
            val maxHeap = heap.max()
            assertTrue(maxHeap < HEAP_CEILING_MB,
                "HEAP UNBOUNDED: post-GC retained heap peaked at ${"%.1f".format(maxHeap)} MB (ceiling $HEAP_CEILING_MB MB)")
            val third = heap.size / 3
            val firstThird = heap.take(third).average()
            val lastThird = heap.takeLast(third).average()
            assertTrue(lastThird <= firstThird + HEAP_TREND_SLACK_MB,
                "HEAP GROWTH TREND: last-third avg ${"%.1f".format(lastThird)} MB vs first-third avg " +
                    "${"%.1f".format(firstThird)} MB (slack $HEAP_TREND_SLACK_MB MB) — per-iteration retention leak")

            // THREAD LEAK: final live threads within delta of the post-warmup baseline
            val finalThreads = threads.threadCount
            assertTrue(finalThreads <= threadBaseline + THREAD_LEAK_DELTA,
                "THREAD LEAK: $finalThreads live threads vs baseline $threadBaseline (+$THREAD_LEAK_DELTA allowed)")

            // CONNECTION LEAK (server side): sessions back at the post-warmup baseline
            val finalSessions = dbSessions()
            assertTrue(finalSessions <= sessionBaseline + SESSION_LEAK_DELTA,
                "CONNECTION LEAK (server-side): $finalSessions Postgres sessions vs baseline $sessionBaseline " +
                    "(+$SESSION_LEAK_DELTA allowed)")
        }
    }
}
