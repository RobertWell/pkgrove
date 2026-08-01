package io.maxxga.rowrelay.it

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.OperationCancelledException
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.Databases
import io.maxxga.rowrelay.postgres.PostgresDialect
import io.maxxga.rowrelay.transfer.Transfer
import io.maxxga.rowrelay.transfer.Workflows
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assertions.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * HEL-129: the release-critical connection-lifecycle guarantees, proven under
 * REAL concurrency against a LIVE pooled Postgres — the layer unit tests (fake
 * DataSources) can't reach. Every scenario ends by asserting NO lease leaked
 * (activeLeases == 0). Skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleStressIT {

    private object Pg : DatabaseKey("pg")

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var ds: DataSource

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        // proxy DataSource over DriverManager — the pgjdbc driver is
        // testRuntimeOnly (not on the compile classpath), so we can't name
        // PGSimpleDataSource here; each getConnection() hands out a fresh
        // real connection to the live container.
        ds = java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java)
        ) { _, m, _ ->
            when (m.name) {
                "getConnection" -> java.sql.DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
                else -> throw UnsupportedOperationException(m.name)
            }
        } as DataSource
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE src (id BIGINT, name VARCHAR)")
                st.execute("INSERT INTO src SELECT g, 'n'||g FROM generate_series(1,50) g")
            }
        }
    }

    @AfterAll
    fun stop() { runCatching { pg.stop() } }

    private fun registry(maxConnections: Int, timeoutMs: Long = 30_000) =
        Databases.build {
            applicationOwned(Pg, ds, PostgresDialect,
                             maxConnections = maxConnections, acquisitionTimeoutMillis = timeoutMs)
        }

    private fun leases(dbs: Databases) = dbs.metrics().single { it.key == "pg" }

    // 1) High concurrency against a real DB is bounded by the lease budget and
    //    leaks nothing — 24 tasks, budget 4 → peak ≤ 4, all succeed, active → 0.
    @Test
    fun `concurrent load is bounded by the budget and leaks no leases`() {
        registry(maxConnections = 4).use { dbs ->
            val pool = Executors.newFixedThreadPool(24)
            try {
                val start = CountDownLatch(1)
                val ok = AtomicInteger(0)
                val futures = (0 until 24).map {
                    pool.submit {
                        start.await()
                        dbs.withConnection(Pg) { c ->
                            c.createStatement().use { st ->
                                st.executeQuery("SELECT count(*) FROM src").use { rs -> rs.next(); rs.getInt(1) }
                            }
                            Thread.sleep(40)   // hold the lease briefly to force contention
                        }
                        ok.incrementAndGet()
                    }
                }
                start.countDown()
                futures.forEach { it.get(60, TimeUnit.SECONDS) }
                assertEquals(24, ok.get())
                val m = leases(dbs)
                assertTrue(m.maxConcurrentLeases in 1..4, "peak ${m.maxConcurrentLeases} must respect budget 4")
                assertEquals(0L, m.activeLeases, "no lease leaked")
            } finally { pool.shutdownNow() }
        }
    }

    // 2) Budget exhaustion fails BOUNDED (AcquisitionTimeoutException within the
    //    timeout), never hangs — the single lease is held by a live pg_sleep.
    @Test
    fun `exhausted budget times out bounded, does not hang, and is counted`() {
        registry(maxConnections = 1, timeoutMs = 1_200).use { dbs ->
            val holding = CountDownLatch(1)
            val holder = Thread {
                dbs.withConnection(Pg) { c ->
                    holding.countDown()
                    c.createStatement().use { it.execute("SELECT pg_sleep(4)") }
                }
            }.apply { isDaemon = true; start() }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val t0 = System.nanoTime()
            assertThrows(Databases.AcquisitionTimeoutException::class.java) {
                dbs.withConnection(Pg) { c -> c.createStatement().use { it.execute("SELECT 1") } }
            }
            val waitedMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue(waitedMs in 1_000..3_500, "should fail near the 1.2s timeout, waited ${waitedMs}ms")
            assertEquals(1L, leases(dbs).timedOutAcquisitions)
            holder.join(10_000)
            assertEquals(0L, leases(dbs).activeLeases, "holder's lease released")
        }
    }

    // 3) Cancelling a large in-progress transfer stops cooperatively (the
    //    writer checks the token between batches), rolls back, and releases the
    //    lease — cancellation is honored mid-flight on a live DB, never a hang.
    @Test
    fun `cancellation mid-transfer rolls back and releases the lease`() {
        registry(maxConnections = 2).use { dbs ->
            java.sql.DriverManager.getConnection("jdbc:duckdb:").use { duck ->
                duck.createStatement().use {
                    it.execute("CREATE TABLE big AS SELECT range AS id, 'n'||range AS name FROM range(200000)")
                }
                ds.connection.use { c ->
                    c.createStatement().use {
                        it.execute("DROP TABLE IF EXISTS cancel_sink")
                        it.execute("CREATE TABLE cancel_sink (id BIGINT, name VARCHAR)")
                    }
                }
                // 500ms budget vs a 200k-row write (many batches, well over 500ms)
                // guarantees the between-batch cancel check fires mid-transfer.
                val cancel = CancelToken.withTimeout(500)
                val t0 = System.nanoTime()
                assertThrows(OperationCancelledException::class.java) {
                    dbs.withConnection(Pg, cancel) { pgc ->
                        Transfer.run(duck, "SELECT id, name FROM big", emptyMap<String, Any?>(),
                                     pgc, PostgresDialect, "cancel_sink",
                                     Transfer.Options(mode = io.maxxga.rowrelay.jdbc.SqlDialect.TargetMode.APPEND,
                                                      readBatchSize = 1000, cancelToken = cancel))
                    }
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue(ms < 60_000, "cancellation should stop mid-flight, took ${ms}ms")
                assertEquals(0L, leases(dbs).activeLeases, "cancelled lease released")
                // AllOrNothing default → the aborted transfer left nothing committed
                ds.connection.use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT count(*) FROM cancel_sink").use { rs -> rs.next()
                            assertEquals(0, rs.getInt(1), "cancelled transfer rolled back") }
                    }
                }
            }
        }
    }

    // 4) Independent parallel flows: one poisoned, one clean. The failure is
    //    retained per-flow (never hidden), the good flow commits, nothing leaks —
    //    the supervised-style guarantee on a live DB via the bounded executor.
    @Test
    fun `parallel flows retain per-flow failure and success with no leak`() {
        registry(maxConnections = 4).use { dbs ->
            ds.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP TABLE IF EXISTS good_sink")
                    st.execute("CREATE TABLE good_sink (id BIGINT, name VARCHAR)")
                    // poison target: NOT NULL column the source can't satisfy on APPEND
                    st.execute("DROP TABLE IF EXISTS poison_sink")
                    st.execute("CREATE TABLE poison_sink (id BIGINT, missing VARCHAR NOT NULL)")
                }
            }
            val flows = listOf(
                Workflows.from(Pg, "SELECT id, name FROM src")
                    .to(Pg, PostgresDialect, "good_sink",
                        Transfer.Options(mode = io.maxxga.rowrelay.jdbc.SqlDialect.TargetMode.APPEND)),
                Workflows.from(Pg, "SELECT id, name FROM src")
                    .to(Pg, PostgresDialect, "poison_sink",
                        Transfer.Options(mode = io.maxxga.rowrelay.jdbc.SqlDialect.TargetMode.APPEND)),
            )
            val results = Workflows.ParallelExecutor(maxConcurrentFlows = 2).execute(flows, dbs)
            assertEquals(1, results.count { it.succeeded }, "the clean flow committed")
            assertEquals(1, results.count { it.error != null }, "the poison flow's failure is retained, not hidden")
            assertEquals(0L, leases(dbs).activeLeases, "both leases released")
            ds.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM good_sink").use { rs -> rs.next()
                        assertEquals(50, rs.getInt(1)) }
                }
            }
        }
    }
}
