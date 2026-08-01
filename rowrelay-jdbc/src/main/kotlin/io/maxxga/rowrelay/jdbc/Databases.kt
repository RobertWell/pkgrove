package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.OperationCancelledException
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * Typed database identities + ownership-explicit resource lifecycle
 * (HEL-128). The core rule: RowRelay borrows resources for a declared scope,
 * guarantees cleanup on every completion path, and never closes
 * infrastructure it does not own.
 *
 * Ownership modes:
 *  - APPLICATION_OWNED: the app's pool/DataSource; we borrow+return, never
 *    close the pool itself.
 *  - ROWRELAY_MANAGED: we own it (tests, CLI, file DuckDB); [Databases] is
 *    [AutoCloseable] and closes managed resources idempotently, in reverse
 *    registration order.
 *  - Caller-owned connections/transactions never enter the registry at all —
 *    they flow through the JoinExisting transaction policy and are never
 *    committed/closed by RowRelay (see TransactionPolicy).
 */
abstract class DatabaseKey(val keyName: String) {
    override fun toString(): String = keyName
}

class Databases private constructor(
    private val entries: Map<DatabaseKey, Entry>,
    /** registration order, for reverse-order managed close */
    private val order: List<DatabaseKey>,
) : AutoCloseable {

    enum class Ownership { APPLICATION_OWNED, ROWRELAY_MANAGED }

    class RegistrationException(message: String) : IllegalArgumentException(message)
    class AcquisitionTimeoutException(key: DatabaseKey, waitedMillis: Long) :
        RuntimeException("no $key connection lease within ${waitedMillis}ms " +
                         "(budget exhausted — see resource metrics)")

    internal class Entry(
        val key: DatabaseKey,
        val dataSource: DataSource,
        val ownership: Ownership,
        val dialect: SqlDialect?,
        maxConnections: Int,
        val acquisitionTimeoutMillis: Long,
        /** managed-only closer for the underlying resource (e.g. Hikari.close). */
        val managedCloser: (() -> Unit)?,
    ) {
        val budget = Semaphore(maxConnections, true)   // fair: documented ordering
        val active = AtomicLong(0)
        val peak = AtomicLong(0)   // max concurrent leases seen
        val timedOut = AtomicLong(0)
        val discarded = AtomicLong(0)
        val closed = AtomicBoolean(false)
    }

    /** Point-in-time resource metrics for one database identity (no vendor
     *  coupling — plain values the app forwards wherever it likes). */
    data class Metrics(val key: String, val activeLeases: Long, val maxConcurrentLeases: Long,
                       val waiting: Int, val timedOutAcquisitions: Long,
                       val discardedConnections: Long)

    fun metrics(): List<Metrics> = order.map { k ->
        val e = entries.getValue(k)
        Metrics(k.keyName, e.active.get(), e.peak.get(), e.budget.queueLength,
                e.timedOut.get(), e.discarded.get())
    }

    /**
     * Borrow a connection lease for [key] and run [block] with it. Cleanup is
     * guaranteed on success, exception, and cancellation:
     *  - the connection is returned (closed → pool return per the pool
     *    contract) in all paths;
     *  - a connection whose transaction state is uncertain (block threw while
     *    autoCommit was off) is INVALIDATED via rollback-then-close and
     *    counted in metrics, never returned as healthy silently;
     *  - waiting on an exhausted budget respects the acquisition timeout AND
     *    the [cancel] token (a cancelled waiter releases nothing it never
     *    got, and an interrupted acquisition acquires nothing).
     */
    fun <T> withConnection(key: DatabaseKey, cancel: CancelToken = CancelToken.none(),
                           block: (Connection) -> T): T {
        val e = entries[key] ?: throw RegistrationException("unknown database key: $key")
        check(!e.closed.get()) { "$key is closed" }
        val deadline = System.nanoTime() + e.acquisitionTimeoutMillis * 1_000_000
        var leased = false
        while (!leased) {
            cancel.throwIfCancelled()
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) {
                e.timedOut.incrementAndGet()
                throw AcquisitionTimeoutException(key, e.acquisitionTimeoutMillis)
            }
            // poll in slices so cancellation during a long wait stays responsive
            leased = e.budget.tryAcquire(minOf(remainingMs, 200), TimeUnit.MILLISECONDS)
        }
        val nowActive = e.active.incrementAndGet()
        e.peak.updateAndGet { if (nowActive > it) nowActive else it }
        var conn: Connection? = null
        try {
            conn = e.dataSource.connection
            return block(conn)
        } catch (t: Throwable) {
            // uncertain transaction state? invalidate, never return as healthy
            conn?.let { c ->
                runCatching {
                    if (!c.autoCommit) { e.discarded.incrementAndGet(); c.rollback() }
                }
            }
            throw t
        } finally {
            runCatching { conn?.close() }   // pool-return per the pool contract
            e.active.decrementAndGet()
            e.budget.release()
        }
    }

    /**
     * Borrow one connection from EACH key, in deterministic key-name order —
     * the documented deadlock-avoidance strategy: every multi-database
     * acquisition in a process orders identically, so circular waits cannot
     * form. Acquisition failure releases everything already obtained.
     */
    fun <T> withConnections(keys: List<DatabaseKey>, cancel: CancelToken = CancelToken.none(),
                            block: (Map<DatabaseKey, Connection>) -> T): T {
        val sorted = keys.sortedBy { it.keyName }
        fun acquire(i: Int, held: MutableMap<DatabaseKey, Connection>): T =
            if (i == sorted.size) block(held)
            else withConnection(sorted[i], cancel) { c ->
                held[sorted[i]] = c
                acquire(i + 1, held)
            }
        return acquire(0, linkedMapOf())
    }

    /** Close ROWRELAY_MANAGED resources (reverse registration order,
     *  idempotent). APPLICATION_OWNED pools are untouched — not ours. */
    override fun close() {
        for (k in order.reversed()) {
            val e = entries.getValue(k)
            if (e.ownership == Ownership.ROWRELAY_MANAGED &&
                e.closed.compareAndSet(false, true)) {
                runCatching { e.managedCloser?.invoke() }
            } else {
                e.closed.set(true)
            }
        }
    }

    class Builder internal constructor() {
        private val entries = linkedMapOf<DatabaseKey, Entry>()

        /** Register an APPLICATION-OWNED pool: borrowed, never closed. */
        @JvmOverloads
        fun applicationOwned(key: DatabaseKey, dataSource: DataSource,
                             dialect: SqlDialect? = null, maxConnections: Int = 4,
                             acquisitionTimeoutMillis: Long = 30_000) {
            register(Entry(key, dataSource, Ownership.APPLICATION_OWNED, dialect,
                           maxConnections, acquisitionTimeoutMillis, null))
        }

        /** Register a ROWRELAY-MANAGED resource with its closer. */
        @JvmOverloads
        fun managed(key: DatabaseKey, dataSource: DataSource, closer: () -> Unit,
                    dialect: SqlDialect? = null, maxConnections: Int = 4,
                    acquisitionTimeoutMillis: Long = 30_000) {
            register(Entry(key, dataSource, Ownership.ROWRELAY_MANAGED, dialect,
                           maxConnections, acquisitionTimeoutMillis, closer))
        }

        private fun register(e: Entry) {
            if (entries.containsKey(e.key))
                throw RegistrationException("duplicate database registration: ${e.key}")
            require(e.budget.availablePermits() > 0)
            entries[e.key] = e
        }

        internal fun build(): Databases = Databases(entries.toMap(), entries.keys.toList())
    }

    companion object {
        /** `Databases.build { applicationOwned(SalesOracle, pool); ... }` —
         *  registration problems fail HERE, before any execution. */
        fun build(block: Builder.() -> Unit): Databases =
            Builder().apply(block).build()
    }
}
