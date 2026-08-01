package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.DataWarning
import io.maxxga.rowrelay.core.OperationReport
import io.maxxga.rowrelay.core.RowBatch
import java.sql.Connection

/**
 * Batch DML execution with explicit commit policy (HEL-120 capability 5).
 * Deterministic and honest about partial completion:
 *
 *  - [CommitPolicy.AllOrNothing]: one transaction; ANY failure rolls back
 *    everything and the report says rowsAffected=0, completed=false.
 *  - [CommitPolicy.PerChunk]: commit after every [chunkBatches] batches; a
 *    failure rolls back only the OPEN chunk, and the report identifies the
 *    failed batch index and row range so the caller can resume.
 *
 * The writer takes over the connection's autoCommit for the duration and
 * restores it after. The caller owns the connection.
 */
object JdbcBatchWriter {

    sealed class CommitPolicy {
        object AllOrNothing : CommitPolicy()
        data class PerChunk(val chunkBatches: Int = 1) : CommitPolicy() {
            init { require(chunkBatches > 0) { "chunkBatches must be positive" } }
        }
    }

    data class WriteOptions(
        val commitPolicy: CommitPolicy = CommitPolicy.AllOrNothing,
        val cancelToken: CancelToken = CancelToken.none(),
        /** Called after each executed batch: (batchIndex, rowsSoFar). */
        val onProgress: ((Int, Long) -> Unit)? = null,
    )

    class BatchWriteException(
        message: String,
        val report: OperationReport,
        cause: Throwable,
    ) : RuntimeException(message, cause)

    /**
     * Execute [dml] (with ? placeholders, one per row value, in schema order)
     * once per row, batched per [RowBatch]. Returns an honest [OperationReport];
     * on failure throws [BatchWriteException] carrying the partial report.
     */
    @JvmStatic
    @JvmOverloads
    fun write(connection: Connection, dml: String, batches: Sequence<RowBatch>,
              options: WriteOptions = WriteOptions()): OperationReport {
        val start = System.nanoTime()
        val warnings = mutableListOf<DataWarning>()
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        var rowsCommitted = 0L
        var rowsInOpenChunk = 0L
        var batchIndex = -1
        var batchesDone = 0
        var chunkStartRow = 0L
        var thrown: Throwable? = null

        fun elapsed() = (System.nanoTime() - start) / 1_000_000

        try {
            connection.prepareStatement(dml).use { st ->
                var batchesSinceCommit = 0
                for (batch in batches) {
                    batchIndex++
                    options.cancelToken.throwIfCancelled()
                    val batchStartRow = rowsCommitted + rowsInOpenChunk
                    try {
                        for (row in batch.rows) {
                            row.values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                            st.addBatch()
                        }
                        st.executeBatch()
                    } catch (e: Exception) {
                        connection.rollback()
                        val report = OperationReport(
                            rowsAffected = rowsCommitted, batches = batchesDone,
                            elapsedMillis = elapsed(), completed = false,
                            warnings = warnings.toList(),
                            failedBatchIndex = batchIndex,
                            failedRowRange = batchStartRow until (batchStartRow + batch.size),
                        )
                        throw BatchWriteException(
                            "batch $batchIndex failed (rows $batchStartRow..${batchStartRow + batch.size - 1}); " +
                            "$rowsCommitted rows previously committed", report, e)
                    }
                    rowsInOpenChunk += batch.size
                    batchesDone++
                    batchesSinceCommit++
                    options.onProgress?.invoke(batchIndex, rowsCommitted + rowsInOpenChunk)

                    val policy = options.commitPolicy
                    if (policy is CommitPolicy.PerChunk && batchesSinceCommit >= policy.chunkBatches) {
                        connection.commit()
                        rowsCommitted += rowsInOpenChunk
                        rowsInOpenChunk = 0
                        batchesSinceCommit = 0
                        chunkStartRow = rowsCommitted
                    }
                }
            }
            connection.commit()
            rowsCommitted += rowsInOpenChunk
            return OperationReport(
                rowsAffected = rowsCommitted, batches = batchesDone,
                elapsedMillis = elapsed(), completed = true, warnings = warnings.toList())
        } catch (e: BatchWriteException) {
            thrown = e
            throw e
        } catch (e: Exception) {
            // cancellation or infrastructure failure: roll back the open chunk.
            // A rollback that itself fails is surfaced (attached), never swallowed.
            // (Whether cancellation should propagate AS cancellation rather than
            // wrapped here — with its partial report carried — is a deliberate
            // HEL-125 outcome-type decision, handled in that effort.)
            val rollbackFailure = runCatching { connection.rollback() }.exceptionOrNull()
            val report = OperationReport(
                rowsAffected = rowsCommitted, batches = batchesDone,
                elapsedMillis = elapsed(), completed = false, warnings = warnings.toList(),
                failedBatchIndex = if (batchIndex >= 0) batchIndex else null,
                failedRowRange = if (rowsInOpenChunk > 0) chunkStartRow until (chunkStartRow + rowsInOpenChunk) else null,
            )
            val ex = BatchWriteException("write aborted after $rowsCommitted committed rows", report, e)
            rollbackFailure?.let { ex.addSuppressed(it) }
            thrown = ex
            throw ex
        } finally {
            // Restoring autoCommit is cleanup: attach a failure to the in-flight
            // exception, or surface it on the normal-return path — never swallow.
            val restoreFailure = runCatching { connection.autoCommit = previousAutoCommit }.exceptionOrNull()
            if (restoreFailure != null) {
                if (thrown != null) thrown.addSuppressed(restoreFailure) else throw restoreFailure
            }
        }
    }
}
