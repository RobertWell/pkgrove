package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Parameterized reads with streaming, bounded memory, and batch consumption
 * (HEL-120 capability 2). The caller owns the [Connection]; the stream owns
 * the statement/result set and MUST be closed (it is [AutoCloseable] and
 * `use {}`-friendly).
 *
 * Memory bound: rows are materialized one at a time off the JDBC cursor with
 * the configured fetch size — consuming via [RowStream.batches] holds at most
 * one batch in memory.
 *
 * That bound is ENFORCED, not assumed (HEL-256). `Statement.fetchSize` alone
 * does not make every driver stream, and the drivers that need more ignore it
 * silently — pgjdbc buffers the entire result set unless the connection is out
 * of autocommit. So each open consults the source's [StreamingContract] and:
 *
 *  - satisfies it on a [ConnectionOwnership.LEASED] connection, restoring the
 *    connection exactly as found when the stream closes;
 *  - REFUSES on a [ConnectionOwnership.CALLER_OWNED] connection it may not
 *    reconfigure ([StreamingUnavailableException]), rather than buffering
 *    silently;
 *  - warns on [ConnectionOwnership.SHARED_WITH_WRITER], where streaming is
 *    genuinely impossible.
 *
 * The memory claim therefore holds by construction on every path that can
 * stream, and says so out loud on the ones that cannot.
 */
object JdbcReader {

    data class ReadOptions(
        val fetchSize: Int = 1_000,
        /** Statement-level timeout, seconds; 0 = none. */
        val queryTimeoutSeconds: Int = 0,
        val cancelToken: CancelToken = CancelToken.none(),
        val valueReader: ValueReader = ValueReader.DEFAULT,
        /**
         * HEL-256: may this read reconfigure the connection to make the driver
         * stream? Default [ConnectionOwnership.LEASED] — the same take-over-
         * and-restore contract [JdbcBatchWriter] already applies to a
         * caller-supplied connection on the write side. Declare
         * [ConnectionOwnership.CALLER_OWNED] for a connection inside a
         * transaction you own.
         */
        val ownership: ConnectionOwnership = ConnectionOwnership.LEASED,
        /**
         * The SOURCE dialect, when known, used solely for its
         * [SqlDialect.streaming] contract. Null (the usual case — a read knows
         * SQL, not vendors) detects the contract from the driver itself.
         */
        val dialect: SqlDialect? = null,
    ) {
        init {
            require(fetchSize > 0) { "fetchSize must be positive" }
        }
    }

    /**
     * Open a streaming read with NAMED parameters (`:user_name`) — the
     * recommended form (HEL-119). The SQL is compiled internally to JDBC
     * placeholders; values are never interpolated or logged. Missing names
     * throw [NamedSql.MissingParametersException] listing exactly what is
     * absent; unused entries follow [unusedPolicy].
     */
    @JvmStatic
    @JvmOverloads
    fun open(connection: Connection, sql: String, namedParams: Map<String, Any?>,
             options: ReadOptions = ReadOptions(),
             unusedPolicy: NamedSql.UnusedParamPolicy = NamedSql.UnusedParamPolicy.WARN): RowStream {
        val named = NamedSql.parse(sql)
        val warnings = mutableListOf<DataWarning>()
        val values = named.bind(namedParams, unusedPolicy) { warnings += it }
        return open(connection, named.sql, values, options).also { stream ->
            warnings.forEach(stream::addWarning)
        }
    }

    /**
     * Open a streaming read with positional [params] (1-based order) — the
     * low-level compatibility form; prefer the named overload.
     */
    @JvmStatic
    @JvmOverloads
    fun open(connection: Connection, sql: String, params: List<Any?> = emptyList(),
             options: ReadOptions = ReadOptions()): RowStream {
        val contract = StreamingContract.of(connection, options.dialect)
        val notStreaming = mutableListOf<DataWarning>()
        // Before prepare/execute, not after: pgjdbc decides cursor-vs-buffer
        // from the connection's state at execute time, so a late take-over
        // would change nothing.
        val restoreAutoCommitTo = enableStreaming(connection, contract, options.ownership) {
            notStreaming += it
        }
        try {
            val st = connection.prepareStatement(sql)
            try {
                // The contract's sentinel wins over the caller's number when the
                // driver has one (MySQL): this statement is ours, so overriding
                // it mutates nothing the caller can observe.
                st.fetchSize = contract.streamingFetchSize ?: options.fetchSize
                if (options.queryTimeoutSeconds > 0) st.queryTimeout = options.queryTimeoutSeconds
                params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
                val rs = st.executeQuery()
                return RowStream(st, rs, options, connection, restoreAutoCommitTo).also { stream ->
                    notStreaming.forEach(stream::addWarning)
                }
            } catch (e: Exception) {
                runCatching { st.close() }
                throw e
            }
        } catch (e: Throwable) {
            // No stream exists to own the take-over, so it is undone here — or
            // the caller's connection would be left sitting in a transaction it
            // never asked for. Cleanup failure rides the primary failure.
            runCatching { restoreConnection(connection, restoreAutoCommitTo) }
                .exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    class RowStream internal constructor(
        private val statement: PreparedStatement,
        private val resultSet: ResultSet,
        private val options: ReadOptions,
        private val connection: Connection,
        /** autoCommit value to put back on close, or null when this read never
         *  touched the connection (HEL-256). */
        private val restoreAutoCommitTo: Boolean?,
    ) : AutoCloseable, Iterator<Row> {

        val schema: Schema = JdbcSchemas.fromMetaData(resultSet.metaData)

        private val warningsList = mutableListOf<DataWarning>()
        val warnings: List<DataWarning> get() = warningsList.toList()

        internal fun addWarning(w: DataWarning) { warningsList += w }

        var rowsRead: Long = 0
            private set

        private var nextRow: Row? = null
        private var finished = false

        override fun hasNext(): Boolean {
            if (nextRow != null) return true
            if (finished) return false
            if (options.cancelToken.isCancelled) {
                // propagate to the driver, then surface cooperatively
                runCatching { statement.cancel() }
                options.cancelToken.throwIfCancelled()
            }
            if (!resultSet.next()) { finished = true; return false }
            val values = (1..schema.size).map { i ->
                options.valueReader.read(resultSet, i, schema[i - 1]) { warningsList += it }
            }
            nextRow = Row(schema, values)
            rowsRead++
            return true
        }

        override fun next(): Row {
            if (!hasNext()) throw NoSuchElementException()
            val r = nextRow!!
            nextRow = null
            return r
        }

        /** Consume the remainder as batches of up to [size] rows each. */
        fun batches(size: Int): Sequence<RowBatch> {
            require(size > 0) { "batch size must be positive" }
            return sequence {
                val buf = ArrayList<Row>(size)
                while (hasNext()) {
                    buf += next()
                    if (buf.size == size) {
                        yield(RowBatch(schema, buf.toList()))
                        buf.clear()
                    }
                }
                if (buf.isNotEmpty()) yield(RowBatch(schema, buf.toList()))
            }
        }

        /** Drain everything into memory — small results only, by intent. */
        fun toList(): List<Row> = asSequence().toList()

        /** True when the driver is genuinely streaming this result set, so the
         *  one-batch-in-flight memory bound actually holds (HEL-256). */
        val streaming: Boolean get() = warningsList.none { it.code == NOT_STREAMING }

        override fun close() {
            runCatching { resultSet.close() }
            runCatching { statement.close() }
            // Restore LAST: the cursor lives INSIDE the transaction this read
            // opened, so it has to be gone before that transaction ends. Unlike
            // the two closes above this is not swallowed — handing a pooled
            // connection back mid-transaction is exactly the silent corruption
            // HEL-128 forbids, so a failed restore must be seen.
            restoreConnection(connection, restoreAutoCommitTo)
        }
    }
}

/** [com.pkgrove.pkgrovekit.core.DataWarning] kind emitted when a read could not
 *  be made to stream and memory is therefore NOT bounded. */
internal const val NOT_STREAMING = "not-streaming"

/**
 * Satisfy [contract] on [connection] so the driver actually streams, or make
 * the failure to do so visible. Returns the autoCommit value the stream must
 * restore on close, or null when nothing was touched.
 */
private fun enableStreaming(connection: Connection, contract: StreamingContract,
                            ownership: ConnectionOwnership,
                            warn: (DataWarning) -> Unit): Boolean? {
    // Nothing to arrange — the fetch size we already set is the whole contract.
    if (!contract.requiresAutoCommitOff) return null
    // Already in a transaction: the driver will stream, and that transaction is
    // not ours to start or end. This is the GOOD case for an enlisted/joined
    // connection, so it is never a refusal and never a mutation.
    if (!connection.autoCommit) return null

    when (ownership) {
        ConnectionOwnership.CALLER_OWNED -> throw StreamingUnavailableException(
            "refusing to read: ${contract.reason}. This connection is declared " +
            "caller-owned, so PkgroveKit will not change its autoCommit setting — and " +
            "reading it as-is would buffer the ENTIRE result set in heap, which is the " +
            "failure this refusal exists to prevent. Fix by either: (a) running the read " +
            "inside your transaction (setAutoCommit(false) before calling, which is " +
            "already true for a JTA-enlisted or Spring-bound connection), or (b) handing " +
            "PkgroveKit a connection it may configure (ConnectionOwnership.LEASED, the " +
            "default).")

        ConnectionOwnership.SHARED_WITH_WRITER -> {
            // Streaming is not available at any price here: the cursor would
            // live in a transaction the target writer commits. Say so instead
            // of breaking mid-stream or overstating the memory bound.
            warn(DataWarning(NOT_STREAMING,
                "read is buffered, not streamed — memory is NOT bounded by fetchSize. " +
                "${contract.reason}, and this connection is also the transfer TARGET, so " +
                "the writer's commit would close a server-side cursor mid-stream. Use " +
                "separate source and target connections to stream."))
            return null
        }

        ConnectionOwnership.LEASED -> {
            // Safe by construction: reaching here means autoCommit was TRUE, so
            // no caller transaction is in flight and there is nothing of theirs
            // for this read transaction — or its rollback — to destroy.
            connection.autoCommit = false
            return true
        }
    }
}

/**
 * Undo [enableStreaming]. The read transaction has to be ENDED before
 * autoCommit can go back on; rollback rather than commit because the only
 * thing in it is our own cursor — and per [enableStreaming] the caller had
 * nothing uncommitted at take-over, so there is nothing to lose.
 */
private fun restoreConnection(connection: Connection, restoreAutoCommitTo: Boolean?) {
    if (restoreAutoCommitTo == null) return
    runCatching { connection.rollback() }
    connection.autoCommit = restoreAutoCommitTo
}
