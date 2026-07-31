package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.DataWarning
import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.core.RowBatch
import io.maxxga.rowrelay.core.Schema
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
 */
object JdbcReader {

    data class ReadOptions(
        val fetchSize: Int = 1_000,
        /** Statement-level timeout, seconds; 0 = none. */
        val queryTimeoutSeconds: Int = 0,
        val cancelToken: CancelToken = CancelToken.none(),
        val valueReader: ValueReader = ValueReader.DEFAULT,
    ) {
        init {
            require(fetchSize > 0) { "fetchSize must be positive" }
        }
    }

    /**
     * Open a streaming read. [params] bind positionally (1-based order).
     * The returned stream exposes [RowStream.schema] immediately.
     */
    @JvmStatic
    @JvmOverloads
    fun open(connection: Connection, sql: String, params: List<Any?> = emptyList(),
             options: ReadOptions = ReadOptions()): RowStream {
        val st = connection.prepareStatement(sql)
        try {
            st.fetchSize = options.fetchSize
            if (options.queryTimeoutSeconds > 0) st.queryTimeout = options.queryTimeoutSeconds
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            val rs = st.executeQuery()
            return RowStream(st, rs, options)
        } catch (e: Exception) {
            runCatching { st.close() }
            throw e
        }
    }

    class RowStream internal constructor(
        private val statement: PreparedStatement,
        private val resultSet: ResultSet,
        private val options: ReadOptions,
    ) : AutoCloseable, Iterator<Row> {

        val schema: Schema = JdbcSchemas.fromMetaData(resultSet.metaData)

        private val warningsList = mutableListOf<DataWarning>()
        val warnings: List<DataWarning> get() = warningsList.toList()

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

        override fun close() {
            runCatching { resultSet.close() }
            runCatching { statement.close() }
        }
    }
}
