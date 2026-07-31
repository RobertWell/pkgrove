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
    fun `uncertain transaction state is invalidated and counted`() {
        val pool = ds()
        Databases.build { applicationOwned(Analytics, pool) }.use { dbs ->
            assertThrows(RuntimeException::class.java) {
                dbs.withConnection(Analytics) { c ->
                    c.autoCommit = false
                    c.createStatement().use { it.execute("CREATE TABLE x (i INT)") }
                    throw RuntimeException("mid-transaction failure")
                }
            }
            assertEquals(1L, dbs.metrics().single().discardedConnections)
            assertEquals(0, pool.open.get())
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
