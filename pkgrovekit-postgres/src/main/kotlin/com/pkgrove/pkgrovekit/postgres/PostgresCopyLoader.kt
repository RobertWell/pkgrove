package com.pkgrove.pkgrovekit.postgres

import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.BulkLoadException
import com.pkgrove.pkgrovekit.jdbc.BulkLoadOptions
import com.pkgrove.pkgrovekit.jdbc.BulkLoader
import com.pkgrove.pkgrovekit.jdbc.BulkSupport
import org.postgresql.PGConnection
import java.math.BigDecimal
import java.sql.Connection

/**
 * HEL-161: native Postgres ingest via the COPY protocol (pgjdbc CopyManager).
 * Order-of-magnitude faster than batched INSERT for large loads; identical
 * data contract — rows arrive already bind-adapted by [PostgresDialect], and
 * this loader only SERIALIZES them (CSV text), it never converts.
 *
 * All-or-nothing: the whole load runs in one transaction on the caller's
 * connection (autoCommit taken over and restored, like JdbcBatchWriter);
 * any failure rolls everything back and throws [BulkLoadException].
 *
 * Deliberate limits (typed refusals, the transfer falls back to batched
 * INSERT): non-pgjdbc connections, and BINARY columns (bytea via CSV needs
 * escape-format contortions the batched path handles better).
 */
object PostgresCopyLoader : BulkLoader {

    override val name: String = "postgres-copy"

    // COPY names its columns explicitly, so table shape (extra/default columns,
    // different order) is handled by the server — no physical-shape check needed.
    override fun supports(connection: Connection, table: String, schema: Schema): BulkSupport {
        val binary = schema.columns.firstOrNull { it.kind == ValueKind.BINARY }
        if (binary != null) {
            return BulkSupport.No("column '${binary.name}' is BINARY — COPY CSV cannot carry bytea; use the batched path")
        }
        return if (connection.isWrapperFor(PGConnection::class.java)) BulkSupport.Yes
        else BulkSupport.No("connection is not a pgjdbc connection")
    }

    override fun bulkLoad(connection: Connection, table: String, schema: Schema,
                          batches: Sequence<RowBatch>, options: BulkLoadOptions): OperationReport {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000

        val cols = schema.columns.joinToString(", ") { PostgresDialect.quoteIdent(it.name, "column") }
        val sql = "COPY ${PostgresDialect.quoteIdent(table, "table")} ($cols) FROM STDIN (FORMAT csv)"

        val pg = connection.unwrap(PGConnection::class.java)
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        var rowsStreamed = 0L
        var batchIndex = -1
        try {
            val copy = pg.copyAPI.copyIn(sql)
            try {
                val sb = StringBuilder(1 shl 16)
                for (batch in batches) {
                    batchIndex++
                    options.cancelToken.throwIfCancelled()
                    sb.setLength(0)
                    for (row in batch.rows) {
                        appendCsvRow(sb, row.values)
                    }
                    val bytes = sb.toString().toByteArray(Charsets.UTF_8)
                    copy.writeToCopy(bytes, 0, bytes.size)
                    rowsStreamed += batch.size
                    options.onProgress?.invoke(batchIndex, rowsStreamed)
                }
                val reported = copy.endCopy()
                connection.commit()
                return OperationReport(
                    rowsAffected = if (reported >= 0) reported else rowsStreamed,
                    batches = batchIndex + 1,
                    elapsedMillis = elapsed(),
                    completed = true,
                )
            } catch (t: Throwable) {
                runCatching { if (copy.isActive) copy.cancelCopy() }
                throw t
            }
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            val report = OperationReport(
                rowsAffected = 0, batches = maxOf(batchIndex, 0),
                elapsedMillis = elapsed(), completed = false,
                failedBatchIndex = if (batchIndex >= 0) batchIndex else null,
            )
            throw BulkLoadException(
                "COPY into '$table' failed after streaming $rowsStreamed rows (nothing committed)",
                report, t)
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }
        }
    }

    // ── CSV serialization (pg CSV defaults: ',' delimiter, '"' quote, NULL = empty unquoted)

    internal fun appendCsvRow(sb: StringBuilder, values: List<Any?>) {
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            appendCsvValue(sb, v)
        }
        sb.append('\n')
    }

    private fun appendCsvValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> return                                   // unquoted empty = NULL
            is BigDecimal -> sb.append(v.toPlainString())    // never scientific notation
            is Boolean, is Int, is Long, is Short, is Byte,
            is Float, is Double -> sb.append(v.toString())
            else -> {
                // Strings and everything the dialect bind-adapts to a typed
                // object with a Postgres-parsable text form (java.sql temporals,
                // UUID, PGobject json/array). Empty strings MUST be quoted to
                // stay distinct from NULL.
                val text = textOf(v)
                if (text.isEmpty() || text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                    sb.append('"')
                    text.forEach { ch -> if (ch == '"') sb.append("\"\"") else sb.append(ch) }
                    sb.append('"')
                } else {
                    sb.append(text)
                }
            }
        }
    }

    private fun textOf(v: Any): String = when (v) {
        is org.postgresql.util.PGobject -> v.value ?: ""
        else -> v.toString()   // java.sql.Timestamp/Date/Time, UUID, String — all pg-parsable
    }
}
