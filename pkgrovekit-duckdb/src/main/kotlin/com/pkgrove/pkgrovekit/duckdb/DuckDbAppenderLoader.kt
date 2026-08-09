package com.pkgrove.pkgrovekit.duckdb

import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.BulkLoadException
import com.pkgrove.pkgrovekit.jdbc.BulkLoadOptions
import com.pkgrove.pkgrovekit.jdbc.BulkLoader
import com.pkgrove.pkgrovekit.jdbc.BulkSupport
import org.duckdb.DuckDBAppender
import org.duckdb.DuckDBConnection
import java.math.BigDecimal
import java.sql.Connection

/**
 * HEL-161: native DuckDB ingest via the Appender API — bypasses SQL parsing
 * and prepared-statement binding entirely, which is DuckDB's documented fast
 * path for bulk inserts. Same data contract as the batched path: values arrive
 * already bind-adapted; this loader only appends.
 *
 * All-or-nothing: one transaction on the caller's connection (autoCommit taken
 * over and restored); failure rolls back and throws [BulkLoadException].
 *
 * Typed refusals (transfer falls back to batched INSERT): non-DuckDB
 * connections, and BINARY columns (the 1.x Appender has no blob append).
 */
object DuckDbAppenderLoader : BulkLoader {

    override val name: String = "duckdb-appender"

    override fun supports(connection: Connection, table: String, schema: Schema): BulkSupport {
        val binary = schema.columns.firstOrNull { it.kind == ValueKind.BINARY }
        if (binary != null) {
            return BulkSupport.No("column '${binary.name}' is BINARY — the DuckDB Appender has no blob append; use the batched path")
        }
        if (!connection.isWrapperFor(DuckDBConnection::class.java)) {
            return BulkSupport.No("connection is not a DuckDB connection")
        }
        // The Appender is POSITIONAL over every physical column: an APPEND-mode
        // table with extra/default/generated columns or a different order would
        // silently misalign values. Refuse on any shape difference — the batched
        // INSERT names its columns and handles those tables correctly.
        val physical = physicalColumns(connection, table)
            ?: return BulkSupport.No("table '$table' not found for shape check")
        val wanted = schema.columns.map { it.name.lowercase() }
        if (physical.map { it.lowercase() } != wanted) {
            return BulkSupport.No(
                "table '$table' physical columns ${physical} do not positionally match " +
                "the transfer schema ${schema.columns.map { it.name }} — the Appender is " +
                "positional; extra/default columns or reordering need the batched path")
        }
        return BulkSupport.Yes
    }

    private fun physicalColumns(connection: Connection, table: String): List<String>? =
        try {
            connection.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info('${table.replace("'", "''")}')").use { rs ->
                    buildList { while (rs.next()) add(rs.getString("name")) }
                        .takeIf { it.isNotEmpty() }
                }
            }
        } catch (_: Exception) {
            null
        }

    override fun bulkLoad(connection: Connection, table: String, schema: Schema,
                          batches: Sequence<RowBatch>, options: BulkLoadOptions): OperationReport {
        val start = System.nanoTime()
        fun elapsed() = (System.nanoTime() - start) / 1_000_000

        val duck = connection.unwrap(DuckDBConnection::class.java)
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        // HEL-234: DuckDB opens the JDBC transaction LAZILY on the first
        // statement. Without anchoring it here, the Appender's close()-flush
        // runs in its own autocommit — a cancelled load would then leave the
        // already-streamed rows COMMITTED, breaking the all-or-nothing
        // contract (caught by DuckDbAppenderLoaderTest's cancellation case).
        connection.createStatement().use { it.execute("SELECT 1") }
        var rowsStreamed = 0L
        var batchIndex = -1
        try {
            DuckDBAppender(duck, DuckDBConnection.DEFAULT_SCHEMA, table).use { appender ->
                for (batch in batches) {
                    batchIndex++
                    options.cancelToken.throwIfCancelled()
                    for (row in batch.rows) {
                        appender.beginRow()
                        row.values.forEach { v -> appendValue(appender, v) }
                        appender.endRow()
                    }
                    rowsStreamed += batch.size
                    options.onProgress?.invoke(batchIndex, rowsStreamed)
                }
            } // close() flushes the appender
            connection.commit()
            return OperationReport(
                rowsAffected = rowsStreamed,
                batches = batchIndex + 1,
                elapsedMillis = elapsed(),
                completed = true,
            )
        } catch (t: Throwable) {
            runCatching { connection.rollback() }
            val report = OperationReport(
                rowsAffected = 0, batches = maxOf(batchIndex, 0),
                elapsedMillis = elapsed(), completed = false,
                failedBatchIndex = if (batchIndex >= 0) batchIndex else null,
            )
            throw BulkLoadException(
                "Appender load into '$table' failed after streaming $rowsStreamed rows (nothing committed)",
                report, t)
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }
        }
    }

    private fun appendValue(a: DuckDBAppender, v: Any?) {
        when (v) {
            null -> a.append(null as String?)          // Appender's null route in 1.x
            is Boolean -> a.append(v)
            is Byte -> a.append(v)
            is Short -> a.append(v)
            is Int -> a.append(v)
            is Long -> a.append(v)
            is Float -> a.append(v)
            is Double -> a.append(v)
            is BigDecimal -> a.appendBigDecimal(v)
            is java.time.LocalDateTime -> a.appendLocalDateTime(v)
            is java.sql.Timestamp -> a.appendLocalDateTime(v.toLocalDateTime())
            is String -> a.append(v)
            // LocalDate/LocalTime/OffsetDateTime/UUID and other text-parsable
            // values: DuckDB casts the string into the typed column.
            else -> a.append(v.toString())
        }
    }
}
