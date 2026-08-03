package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import java.sql.Connection

/**
 * Optional native bulk-ingest capability a dialect may expose (HEL-161).
 *
 * The batched-INSERT path ([JdbcBatchWriter]) is the universal default; a
 * [BulkLoader] is an OPT-IN fast path for engines with a native ingest protocol
 * (Postgres COPY, DuckDB Appender). Contract parity with the batched path:
 *
 *  - values arrive ALREADY bind-adapted (the transfer pipeline applies
 *    [SqlDialect.bindValue] before the write seam), so conversion/warning
 *    behavior is identical to the batched path;
 *  - all-or-nothing: the loader owns one transaction over the whole load
 *    (mirror of [JdbcBatchWriter.CommitPolicy.AllOrNothing]) — on any failure
 *    everything rolls back and the thrown [BulkLoadException] carries an honest
 *    partial [OperationReport];
 *  - the caller owns the connection; the loader may toggle autoCommit for the
 *    duration but must restore it.
 *
 * Callers must consult [supports] first: a loader can refuse a connection
 * (wrong/wrapped driver) or a schema (e.g. BINARY columns a text protocol
 * cannot carry) with a typed reason, and the transfer falls back to batched
 * INSERT with a warning rather than failing.
 */
interface BulkLoader {

    /** Short engine name for warnings/telemetry (e.g. "postgres-copy"). */
    val name: String

    /** Can this loader serve [connection] + [schema]? Typed no-with-reason. */
    fun supports(connection: Connection, schema: Schema): BulkSupport

    /**
     * Stream [batches] into [table] via the native protocol. [table] and the
     * schema's column names are quoted per the owning dialect's rules by the
     * implementation. Throws [BulkLoadException] on failure (nothing committed).
     */
    fun bulkLoad(connection: Connection, table: String, schema: Schema,
                 batches: Sequence<RowBatch>, options: BulkLoadOptions = BulkLoadOptions()): OperationReport
}

/** Options for one bulk load. */
data class BulkLoadOptions(
    val cancelToken: CancelToken = CancelToken.none(),
    /** (batchIndex, rowsStreamed) — progress without row values (log-safe). */
    val onProgress: ((Int, Long) -> Unit)? = null,
)

/** Typed answer to "can this loader run here?". */
sealed interface BulkSupport {
    data object Yes : BulkSupport
    data class No(val reason: String) : BulkSupport
}

/** A bulk load failed; nothing was committed. [report] is the honest partial state. */
class BulkLoadException(
    message: String,
    val report: OperationReport,
    cause: Throwable,
) : RuntimeException(message, cause)
