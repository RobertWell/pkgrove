package com.pkgrove.pkgrovekit.it

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.pkgrove.pkgrovekit.jdbc.DatabaseKey
import com.pkgrove.pkgrovekit.jdbc.Databases
import com.pkgrove.pkgrovekit.oracle.OracleDialect
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
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HEL-129 (review — Oracle matrix rows): the real-pool lifecycle matrix
 * duplicated against a LIVE Oracle. Same guarantees as the Postgres
 * [RealPoolLifecycleIT], proven with Oracle-native mechanics:
 *
 *  - RETURN: physical-connection identity via `SYS_CONTEXT('USERENV','SID')`;
 *  - EVICTION: the session is killed mid-transaction from an admin connection
 *    (`ALTER SYSTEM KILL SESSION '<sid>,<serial#>' IMMEDIATE`) so rollback is
 *    impossible → the HEL-128 invalidator (`HikariDataSource::evictConnection`)
 *    must run, the next lease must be a healthy DIFFERENT session, and the
 *    uncommitted insert must have died with the session;
 *  - cancellation during in-flight pooled JDBC work, retry, and shutdown-drain
 *    all end with Hikari's own activeConnections at 0.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleRealPoolLifecycleIT {

    private object Ora : DatabaseKey("ora-pooled")

    private lateinit var oracle: OracleContainer
    private lateinit var pool: HikariDataSource
    private val evictions = AtomicInteger(0)

    @BeforeAll
    fun start() {
        oracle = OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withUsername("test").withPassword("test")
        oracle.start()
        pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = oracle.jdbcUrl
            username = oracle.username
            password = oracle.password
            maximumPoolSize = 4
            minimumIdle = 1
        })
        pool.connection.use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE src (id NUMBER(18), name VARCHAR2(50))")
                st.execute("INSERT INTO src SELECT level, 'n'||level FROM dual CONNECT BY level <= 50")
            }
            // no explicit commit: the connection is in auto-commit mode (ORA-17273
            // if forced) and Oracle auto-commits DDL regardless.
        }
    }

    @AfterAll
    fun stop() {
        runCatching { pool.close() }
        runCatching { oracle.stop() }
    }

    private fun registry(maxConnections: Int = 4) = Databases.build {
        applicationOwned(Ora, pool, OracleDialect, maxConnections = maxConnections,
                         invalidator = { c -> evictions.incrementAndGet(); pool.evictConnection(c) })
    }

    private fun sid(c: Connection): String = c.createStatement().use { st ->
        st.executeQuery("SELECT SYS_CONTEXT('USERENV','SID') FROM dual").use { rs ->
            rs.next(); rs.getString(1)
        }
    }

    /** admin connection (container SYSTEM user) for session control. */
    private fun admin(): Connection =
        java.sql.DriverManager.getConnection(oracle.jdbcUrl, "system", oracle.password)

    private fun activeInPool(): Int = pool.hikariPoolMXBean.activeConnections

    @Test
    fun `lease end RETURNS the same physical session to the pool`() {
        registry(maxConnections = 1).use { dbs ->
            val s1 = dbs.withConnection(Ora) { sid(it) }
            val s2 = dbs.withConnection(Ora) { sid(it) }
            assertEquals(s1, s2, "pool did not return/reuse the physical Oracle session")
            assertEquals(0, activeInPool())
        }
    }

    @Test
    fun `killed mid-transaction session is EVICTED not returned as healthy`() {
        val before = evictions.get()
        registry().use { dbs ->
            var deadSid = ""
            assertThrows(Exception::class.java) {
                dbs.withConnection(Ora) { c ->
                    c.autoCommit = false
                    deadSid = sid(c)
                    c.createStatement().use { it.execute("INSERT INTO src VALUES (999, 'doomed')") }
                    // kill this session from SYSTEM — rollback becomes impossible
                    admin().use { a ->
                        val serial = a.createStatement().use { st ->
                            st.executeQuery(
                                "SELECT serial# FROM v\$session WHERE sid = $deadSid").use { rs ->
                                rs.next(); rs.getString(1)
                            }
                        }
                        a.createStatement().use {
                            it.execute("ALTER SYSTEM KILL SESSION '$deadSid,$serial' IMMEDIATE")
                        }
                    }
                    // next statement on the killed session fails -> block throws
                    c.createStatement().use { it.execute("SELECT 1 FROM dual") }
                }
            }
            assertEquals(before + 1, evictions.get(), "invalidator (evictConnection) not called")
            assertEquals(1L, dbs.metrics().single().discardedConnections)
            // NEXT lease: healthy, different session, fully usable
            val newSid = dbs.withConnection(Ora) { c ->
                val s = sid(c)
                c.createStatement().use { it.execute("SELECT 1 FROM dual") }
                s
            }
            assertNotEquals(deadSid, newSid)
            assertEquals(0, activeInPool())
            // the uncommitted insert died with its session
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
    fun `cancellation during in-flight pooled Oracle work releases pool and leases`() = runBlocking {
        registry().use { dbs ->
            val processed = AtomicInteger(0)
            val slow = Workflows.from(Ora, "SELECT * FROM src")
                .transform { r -> processed.incrementAndGet(); Thread.sleep(100); r }
                .to(Ora, OracleDialect, "SLOW_SINK",
                    Transfer.Options(readBatchSize = 1, fetchSize = 1))
            val job = launch { Workflows.executeStructured(listOf(slow), dbs) }
            delay(400)
            job.cancel()
            job.join()
            assertTrue(processed.get() < 25, "processed ${processed.get()} rows after cancel")
            assertEquals(0L, dbs.metrics().single().activeLeases)
            assertEquals(0, activeInPool())
        }
    }

    @Test
    fun `retry after a transient failure succeeds on the same pooled registry`() {
        registry().use { dbs ->
            var attempts = 0
            fun work(): Long = dbs.withConnection(Ora) { c ->
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
            dbs.withConnection(Ora) { c ->
                inFlight.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                c.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM src").use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }
        inFlight.await(5, TimeUnit.SECONDS)
        dbs.close()
        assertThrows(IllegalStateException::class.java) { dbs.withConnection(Ora) { } }
        proceed.countDown()
        assertEquals(50L, done.get(10, TimeUnit.SECONDS))
        ex.shutdown(); ex.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(0, activeInPool())
        pool.connection.use { c -> c.createStatement().use { it.execute("SELECT 1 FROM dual") } }
    }
}
