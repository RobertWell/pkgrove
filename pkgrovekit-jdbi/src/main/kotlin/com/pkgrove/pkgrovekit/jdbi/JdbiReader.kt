package com.pkgrove.pkgrovekit.jdbi

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.jdbc.JdbcSchemas
import com.pkgrove.pkgrovekit.jdbc.ValueReader
import org.jdbi.v3.core.Handle

/**
 * Reads through a normal JDBI [Handle] — named parameters, handle-managed
 * statement lifecycle — while producing EXACTLY the pkgrovekit model the JDBC
 * path produces (same [JdbcSchemas] + [ValueReader] machinery underneath, so
 * "both paths produce equivalent schema and result behavior" holds by
 * construction, not by parallel implementation).
 *
 * JDBI owns the statement/result-set lifetime, so streaming is lambda-scoped:
 * the stream is only valid inside the [read] block. Use [readAll] for small
 * results.
 */
object JdbiReader {

    data class ReadOptions(
        val fetchSize: Int = 1_000,
        val cancelToken: CancelToken = CancelToken.none(),
        val valueReader: ValueReader = ValueReader.DEFAULT,
    )

    /** Lambda-scoped row stream over a JDBI query. */
    class JdbiRowStream internal constructor(
        private val rs: java.sql.ResultSet,
        private val options: ReadOptions,
    ) : Iterator<Row> {
        val schema: Schema = JdbcSchemas.fromMetaData(rs.metaData)

        private val warningsList = mutableListOf<DataWarning>()
        val warnings: List<DataWarning> get() = warningsList.toList()

        var rowsRead: Long = 0
            private set

        private var nextRow: Row? = null
        private var finished = false

        override fun hasNext(): Boolean {
            if (nextRow != null) return true
            if (finished) return false
            options.cancelToken.throwIfCancelled()
            if (!rs.next()) { finished = true; return false }
            val values = (1..schema.size).map { i ->
                options.valueReader.read(rs, i, schema[i - 1]) { warningsList += it }
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

        fun batches(size: Int): Sequence<RowBatch> {
            require(size > 0) { "batch size must be positive" }
            return sequence {
                val buf = ArrayList<Row>(size)
                while (hasNext()) {
                    buf += next()
                    if (buf.size == size) { yield(RowBatch(schema, buf.toList())); buf.clear() }
                }
                if (buf.isNotEmpty()) yield(RowBatch(schema, buf.toList()))
            }
        }

        fun toList(): List<Row> = asSequence().toList()
    }

    /**
     * Execute [sql] with JDBI named parameters and stream rows inside [block].
     * The handle's transaction/lifecycle behavior is untouched.
     */
    @JvmStatic
    @JvmOverloads
    fun <T> read(handle: Handle, sql: String, namedParams: Map<String, Any?> = emptyMap(),
                 options: ReadOptions = ReadOptions(), block: (JdbiRowStream) -> T): T {
        val query = handle.createQuery(sql)
        namedParams.forEach { (k, v) -> query.bind(k, v) }
        query.setFetchSize(options.fetchSize)
        return query.scanResultSet { supplier, _ ->
            block(JdbiRowStream(supplier.get(), options))
        }
    }

    /** Materialize a whole (small) result as one [RowBatch]. */
    @JvmStatic
    @JvmOverloads
    fun readAll(handle: Handle, sql: String, namedParams: Map<String, Any?> = emptyMap(),
                options: ReadOptions = ReadOptions()): RowBatch =
        read(handle, sql, namedParams, options) { stream ->
            RowBatch(stream.schema, stream.toList())
        }
}
