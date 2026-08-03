package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.OperationCancelledException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** HEL-128: ownership, budgets, timeout, cancellation, invalidation, managed
 *  close — with DETERMINISTIC leak assertions via the registry metrics. */
class DatabasesTest {

    private object Analytics : DatabaseKey("analytics")
    private object Working : DatabaseKey("working")

    /** minimal DataSource over DuckDB; counts open connections for leak proof. */
    private class CountingDataSource(private val url: String) : DataSource by dummy() {
        val open = AtomicInteger(0)
        val poolClosed = AtomicBoolean(false)
        override fun getConnection(): Connection {
            check(!poolClosed.get()) { "pool closed" }
            val real = DriverManager.getConnection(url)
            open.incrementAndGet()
            return java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
                if (m.name == "close") { open.decrementAndGet(); real.close(); null }
                else m.invoke(real, *(args ?: emptyArray()))
            } as Connection
        }
        companion object {
            fun dummy(): DataSource = java.lang.reflect.Proxy.newProxyInstance(
                DataSource::class.java.classLoader, arrayOf(DataSource::class.java)
            ) { _, m, _ -> throw UnsupportedOperationException(m.name) } as DataSource
        }
    }

    @field:TempDir
    lateinit var tmp: Path

    private fun ds() = CountingDataSource("jdbc:duckdb:")

    @Test
    fun `leases are returned on success failure and never leak`() {
        val pool = ds()
        Databases.build { applicationOwned(Analytics, pool, maxConnections = 2) }.use { dbs ->
            dbs.withConnection(Analytics) { c ->
                c.createStatement().use { it.execute("SELECT 1") }
            }
            assertThrows(RuntimeException::class.java) {
                dbs.withConnection(Analytics) { throw RuntimeException("boom") }
            }
            // deterministic leak assertion: zero open connections, zero active leases
            assertEquals(0, pool.open.get())
            assertEquals(0L, dbs.metrics().single().activeLeases)
        }
    }

    @Test
    fun `budget bounds concurrency and exhaustion fails bounded not hung`() {
        val pool = ds()
        Databases.build {
            applicationOwned(Analytics, pool, maxConnections = 2,
                             acquisitionTimeoutMillis = 400)
        }.use { dbs ->
            val hold = CountDownLatch(1)
            val started = CountDownLatch(2)
            val ex = Executors.newFixedThreadPool(3)
            repeat(2) {
                ex.submit {
                    dbs.withConnection(Analytics) { started.countDown(); hold.await() }
                }
            }
            started.await(5, TimeUnit.SECONDS)
            // third acquisition: budget exhausted -> bounded, actionable failure
            val t = assertThrows(Databases.AcquisitionTimeoutException::class.java) {
                dbs.withConnection(Analytics) { }
            }
            assertTrue(t.message!!.contains("analytics"))
            assertEquals(1L, dbs.metrics().single().timedOutAcquisitions)
            hold.countDown(); ex.shutdown(); ex.awaitTermination(5, TimeUnit.SECONDS)
            assertEquals(0, pool.open.get())
        }
    }

    @Test
    fun `cancellation while waiting acquires nothing and releases nothing`() {
        val pool = ds()
        Databases.build {
            applicationOwned(Analytics, pool, maxConnections = 1,
                             acquisitionTimeoutMillis = 10_000)
        }.use { dbs ->
            val hold = CountDownLatch(1)
            val started = CountDownLatch(1)
            val ex = Executors.newSingleThreadExecutor()
            ex.submit { dbs.withConnection(Analytics) { started.countDown(); hold.await() } }
            started.await(5, TimeUnit.SECONDS)
            val cancel = CancelToken.none()
            val canceller = Executors.newSingleThreadScheduledExecutor()
            canceller.schedule({ cancel.cancel() }, 300, TimeUnit.MILLISECONDS)
            assertThrows(OperationCancelledException::class.java) {
                dbs.withConnection(Analytics, cancel) { }
            }
            hold.countDown(); ex.shutdown(); canceller.shutdown()
            ex.awaitTermination(5, TimeUnit.SECONDS)
            assertEquals(0, pool.open.get())
            assertEquals(0L, dbs.metrics().single().activeLeases)
        }
    }

    @Test
    fun `uncertain transaction state rolls back and pool-returns a healthy connection`() {
        val pool = ds()
        Databases.build { applicationOwned(Analytics, pool) }.use { dbs ->
            assertThrows(RuntimeException::class.java) {
                dbs.withConnection(Analytics) { c ->
                    c.autoCommit = false
                    c.createStatement().use { it.execute("CREATE TABLE x (i INT)") }
                    throw RuntimeException("mid-transaction failure")
                }
            }
            val m = dbs.metrics().single()
            // rollback SUCCEEDED -> state is certain again -> pool return is
            // correct, and it is counted as a rollback, NOT an invalidation.
            assertEquals(1L, m.rolledBackTransactions)
            assertEquals(0L, m.discardedConnections)
            assertEquals(0, pool.open.get())
        }
    }

    /** wraps a DataSource so rollback and/or close fail on demand. */
    private fun faulty(inner: DataSource, failRollback: Boolean = false,
                       failClose: Boolean = false): DataSource =
        java.lang.reflect.Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, m, args ->
            if (m.name == "getConnection" && (args == null || args.isEmpty())) {
                val real = inner.connection
                java.lang.reflect.Proxy.newProxyInstance(
                    Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, cm, cargs ->
                    when {
                        cm.name == "rollback" && failRollback ->
                            throw java.sql.SQLException("rollback lost connection")
                        cm.name == "close" && failClose ->
                            throw java.sql.SQLException("pool return failed")
                        else -> cm.invoke(real, *(cargs ?: emptyArray()))
                    }
                } as Connection
            } else m.invoke(inner, *(args ?: emptyArray()))
        } as DataSource

    @Test
    fun `failed rollback triggers genuine invalidation via the registered invalidator`() {
        val pool = ds()
        val evicted = AtomicInteger(0)
        Databases.build {
            applicationOwned(Analytics, faulty(pool, failRollback = true),
                             invalidator = { evicted.incrementAndGet() })
        }.use { dbs ->
            val t = assertThrows(RuntimeException::class.java) {
                dbs.withConnection(Analytics) { c ->
                    c.autoCommit = false
                    throw RuntimeException("mid-transaction failure")
                }
            }
            // the broken connection went through the EVICTION hook, not a
            // silent pool return; the rollback failure is not swallowed.
            assertEquals(1, evicted.get())
            assertEquals(1L, dbs.metrics().single().discardedConnections)
            assertTrue(t.suppressed.any { it.message?.contains("rollback lost connection") == true })
        }
    }

    @Test
    fun `cleanup failure after successful work is thrown not swallowed`() {
        val pool = ds()
        Databases.build {
            applicationOwned(Analytics, faulty(pool, failClose = true))
        }.use { dbs ->
            val t = assertThrows(Databases.CleanupException::class.java) {
                dbs.withConnection(Analytics) { c ->
                    c.createStatement().use { it.execute("SELECT 1") }
                    "ok"
                }
            }
            assertTrue(t.cause is java.sql.SQLException)
            val m = dbs.metrics().single()
            assertEquals(1L, m.cleanupFailures)
            assertEquals(0L, m.activeLeases)   // lease still released
            // budget still usable afterwards (release happened exactly once)
            assertThrows(Databases.CleanupException::class.java) {
                dbs.withConnection(Analytics) { }
            }
        }
    }

    @Test
    fun `cleanup failure after failed work rides the primary as suppressed`() {
        val pool = ds()
        Databases.build {
            applicationOwned(Analytics, faulty(pool, failClose = true))
        }.use { dbs ->
            val t = assertThrows(RuntimeException::class.java) {
                dbs.withConnection(Analytics) { throw RuntimeException("primary") }
            }
            assertEquals("primary", t.message)
            assertTrue(t.suppressed.any { it.message?.contains("pool return failed") == true })
            assertEquals(1L, dbs.metrics().single().cleanupFailures)
            assertEquals(0L, dbs.metrics().single().activeLeases)
        }
    }

    @Test
    fun `managed closer failures are aggregated and thrown while every closer still runs`() {
        val secondClosed = AtomicBoolean(false)
        val dbs = Databases.build {
            // reverse-order close: Working closes FIRST (registered last)
            managed(Analytics, ds(), closer = { secondClosed.set(true) })
            managed(Working, ds(), closer = { throw IllegalStateException("closer exploded") })
        }
        val t = assertThrows(Databases.CleanupException::class.java) { dbs.close() }
        assertTrue(t.cause is IllegalStateException)
        assertTrue(secondClosed.get())   // failure did not stop the remaining closers
        // idempotent: second close is a no-op, not a re-throw
        dbs.close()
    }

    @Test
    fun `linked cancel token observes a parent cancel`() {
        val parent = CancelToken.none()
        val linked = CancelToken.linked(CancelToken.none(), parent)
        assertTrue(!linked.isCancelled)
        parent.cancel()
        assertTrue(linked.isCancelled)
        assertThrows(OperationCancelledException::class.java) { linked.throwIfCancelled() }
    }

    @Test
    fun `retry after failure works on a healthy registry with balanced leases`() {
        // HEL-128 matrix: retry. A failed lease-scope leaves the registry fully
        // usable — the retry acquires a fresh lease and succeeds; nothing leaks.
        val pool = ds()
        Databases.build { applicationOwned(Analytics, pool, maxConnections = 1) }.use { dbs ->
            var attempts = 0
            fun work(): String = dbs.withConnection(Analytics) { c ->
                attempts++
                if (attempts == 1) throw java.sql.SQLException("transient failure")
                c.createStatement().use { it.execute("SELECT 1") }
                "ok"
            }
            assertThrows(java.sql.SQLException::class.java) { work() }
            assertEquals("ok", work())   // retry succeeds on the same registry
            assertEquals(2, attempts)
            assertEquals(0, pool.open.get())
            assertEquals(0L, dbs.metrics().single().activeLeases)
        }
    }

    @Test
    fun `shutdown drains - in-flight lease completes while new leases are refused`() {
        // HEL-128 matrix: managed-runtime drain. close() must not yank a live
        // lease out from under running work; it refuses NEW leases and lets the
        // in-flight scope finish and clean up normally.
        val pool = ds()
        val dbs = Databases.build { applicationOwned(Analytics, pool, maxConnections = 2) }
        val inFlight = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<String>()
        val ex = Executors.newSingleThreadExecutor()
        ex.submit {
            result.set(dbs.withConnection(Analytics) { c ->
                inFlight.countDown()
                proceed.await(5, TimeUnit.SECONDS)
                c.createStatement().use { it.execute("SELECT 1") }
                "drained-ok"
            })
        }
        inFlight.await(5, TimeUnit.SECONDS)
        dbs.close()                                         // shutdown mid-flight
        assertThrows(IllegalStateException::class.java) {   // new leases refused
            dbs.withConnection(Analytics) { }
        }
        proceed.countDown()                                 // let the in-flight work finish
        ex.shutdown(); ex.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals("drained-ok", result.get())            // it completed, not aborted
        assertEquals(0, pool.open.get())
        assertEquals(0L, dbs.metrics().single().activeLeases)
    }

    @Test
    fun `abandoning work early inside a lease still cleans up completely`() {
        // HEL-128 matrix: stream abandonment. A block that stops consuming and
        // returns early (or a caller that gives up) exits through the same
        // scope — the connection is returned and the lease released regardless.
        val pool = ds()
        Databases.build { applicationOwned(Analytics, pool) }.use { dbs ->
            val first = dbs.withConnection(Analytics) { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT * FROM range(1000000)").use { rs ->
                        rs.next()          // consume ONE row of a million…
                        rs.getLong(1)      // …then abandon the rest
                    }
                }
            }
            assertEquals(0L, first)
            assertEquals(0, pool.open.get())               // nothing held open
            assertEquals(0L, dbs.metrics().single().activeLeases)
        }
    }

    @Test
    fun `multi-database acquisition orders by key name and releases all on failure`() {
        val a = ds(); val w = ds()
        Databases.build {
            applicationOwned(Working, w, maxConnections = 1)
            applicationOwned(Analytics, a, maxConnections = 1)
        }.use { dbs ->
            dbs.withConnections(listOf(Working, Analytics)) { held ->
                assertEquals(setOf(Working, Analytics), held.keys)
            }
            assertThrows(RuntimeException::class.java) {
                dbs.withConnections(listOf(Working, Analytics)) { throw RuntimeException("x") }
            }
            assertEquals(0, a.open.get()); assertEquals(0, w.open.get())
            assertTrue(dbs.metrics().all { it.activeLeases == 0L })
        }
    }

    @Test
    fun `managed resources close on runtime close but application pools are untouched`() {
        val appPool = ds()
        val managedClosed = AtomicBoolean(false)
        val dbs = Databases.build {
            applicationOwned(Analytics, appPool)
            managed(Working, ds(), closer = { managedClosed.set(true) })
        }
        dbs.close()
        dbs.close()   // idempotent
        assertTrue(managedClosed.get())
        assertTrue(!appPool.poolClosed.get())   // never ours to close
        // closed registry refuses new leases
        assertThrows(IllegalStateException::class.java) {
            dbs.withConnection(Analytics) { }
        }
    }

    @Test
    fun `duplicate registration fails at build time`() {
        assertThrows(Databases.RegistrationException::class.java) {
            Databases.build {
                applicationOwned(Analytics, ds())
                applicationOwned(Analytics, ds())
            }
        }
    }
}
