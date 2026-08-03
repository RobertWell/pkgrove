package com.pkgrove.pkgrovekit.it

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.pkgrove.pkgrovekit.jdbc.DatabaseKey
import com.pkgrove.pkgrovekit.jdbc.Databases
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
import com.pkgrove.pkgrovekit.transfer.Workflows
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HEL-129 (review): the lifecycle matrix against a REAL connection pool
 * (HikariCP) and a LIVE Postgres — a per-acquisition DriverManager proxy
 * cannot prove pool return or eviction. What only a real pool can show:
 *
 *  - RETURN: `Connection.close()` inside a lease scope returns the SAME
 *    physical connection to the pool (proven by backend PID identity);
 *  - EVICTION: a broken connection (backend killed mid-transaction, rollback
 *    impossible) goes through the registered `HikariDataSource::evictConnection`
 *    invalidator, and the NEXT lease gets a healthy connection instead of the
 *    corpse;
 *  - cancellation during in-flight JDBC work, retry, and shutdown all end
 *    with the pool's activeConnections back at 0 (Hikari's own accounting,
 *    not just PkgroveKit's lease metrics).
 *
 * (Cleanup-failure VISIBILITY — CleanupException / suppressed — is proven at
 * the unit layer with fault-injecting DataSources; a healthy Hikari cannot be
 * made to fail close() deterministically.)
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealPoolLifecycleIT {

    private object Pg : DatabaseKey("pg-pooled")

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var pool: HikariDataSource
    private val evictions = AtomicInteger(0)

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
        pool.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE src (id BIGINT, name VARCHAR)")
                st.execute("INSERT INTO src SELECT g, 'n'||g FROM generate_series(1,50) g")
            }
        }
    }

    @AfterAll
    fun stop() {
        runCatching { pool.close() }
        runCatching { pg.stop() }
    }

    private fun registry(maxConnections: Int = 4) = Databases.build {
        applicationOwned(Pg, pool, PostgresDialect, maxConnections = maxConnections,
                         invalidator = { c -> evictions.incrementAndGet(); pool.evictConnection(c) })
    }

    private fun pid(c: Connection): Int = c.createStatement().use { st ->
        st.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
    }

    private fun activeInPool(): Int = pool.hikariPoolMXBean.activeConnections

    @Test
    fun `lease end RETURNS the same physical connection to the pool`() {
        registry(maxConnections = 1).use { dbs ->
            val pid1 = dbs.withConnection(Pg) { pid(it) }
            val pid2 = dbs.withConnection(Pg) { pid(it) }
            // real pool semantics: close() returned the connection, the next
            // lease reuses the SAME physical backend — not a fresh connection.
            assertEquals(pid1, pid2, "pool did not return/reuse the physical connection")
            assertEquals(0, activeInPool())
        }
    }

    @Test
    fun `broken mid-transaction connection is EVICTED not returned as healthy`() {
        val before = evictions.get()
        registry().use { dbs ->
            var deadPid = -1
            assertThrows(Exception::class.java) {
                dbs.withConnection(Pg) { c ->
                    c.autoCommit = false
                    deadPid = pid(c)
                    c.createStatement().use { it.execute("INSERT INTO src VALUES (999, 'doomed')") }
                    // kill this connection's backend from an admin connection —
                    // rollback becomes impossible, transaction state unrecoverable
                    pool.connection.use { admin ->
                        admin.createStatement().use {
                            it.execute("SELECT pg_terminate_backend($deadPid)")
                        }
                    }
                    // next statement on the dead connection fails -> block throws
                    c.createStatement().use { it.execute("SELECT 1") }
                }
            }
            // the corpse went through Hikari's eviction hook, not a silent return
            assertEquals(before + 1, evictions.get(), "invalidator (evictConnection) not called")
            assertEquals(1L, dbs.metrics().single().discardedConnections)
            // and the NEXT lease is a healthy, DIFFERENT physical connection
            val newPid = dbs.withConnection(Pg) { c ->
                val p = pid(c)
                c.createStatement().use { it.execute("SELECT 1") }   // fully usable
                p
            }
            assertNotEquals(deadPid, newPid)
            assertEquals(0, activeInPool())
            // the uncommitted INSERT died with its backend — nothing half-applied
            pool.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM src WHERE id = 999").use { rs ->
                        rs.next(); assertEquals(0, rs.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `cancellation during in-flight pooled JDBC work releases pool and leases`() = runBlocking {
        registry().use { dbs ->
            val processed = AtomicInteger(0)
            val slow = Workflows.from(Pg, "SELECT * FROM src")
                .transform { r -> processed.incrementAndGet(); Thread.sleep(100); r }
                .to(Pg, PostgresDialect, "slow_sink",
                    Transfer.Options(readBatchSize = 1, fetchSize = 1))
            val job = launch { Workflows.executeStructured(listOf(slow), dbs) }
            delay(400)
            job.cancel()
            job.join()
            assertTrue(processed.get() < 25, "processed ${processed.get()} rows after cancel")
            assertEquals(0L, dbs.metrics().single().activeLeases)
            assertEquals(0, activeInPool())   // Hikari's own accounting agrees
        }
    }

    @Test
    fun `retry after a transient failure succeeds on the same pooled registry`() {
        registry().use { dbs ->
            var attempts = 0
            fun work(): Long = dbs.withConnection(Pg) { c ->
                attempts++
                if (attempts == 1) throw java.sql.SQLException("transient")
                c.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM src").use { rs -> rs.next(); rs.getLong(1) }
                }
            }
            assertThrows(java.sql.SQLException::class.java) { work() }
            assertEquals(50L, work())
            assertEquals(0, activeInPool())
            assertEquals(0L, dbs.metrics().single().activeLeases)
        }
    }

    @Test
    fun `shutdown mid-flight drains without leaking pooled connections`() {
        val dbs = registry(maxConnections = 2)
        val inFlight = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val ex = Executors.newSingleThreadExecutor()
        val done = ex.submit<Long> {
            dbs.withConnection(Pg) { c ->
                inFlight.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                c.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM src").use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }
        inFlight.await(5, TimeUnit.SECONDS)
        dbs.close()                                          // shutdown mid-flight
        assertThrows(IllegalStateException::class.java) {    // new leases refused
            dbs.withConnection(Pg) { }
        }
        proceed.countDown()
        assertEquals(50L, done.get(10, TimeUnit.SECONDS))    // in-flight completed
        ex.shutdown(); ex.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(0, activeInPool())                      // nothing leaked
        // APPLICATION_OWNED: the pool itself is untouched by registry close
        pool.connection.use { c -> c.createStatement().use { it.execute("SELECT 1") } }
    }
}
