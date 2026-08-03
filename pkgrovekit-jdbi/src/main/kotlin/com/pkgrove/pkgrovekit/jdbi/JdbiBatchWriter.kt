package com.pkgrove.pkgrovekit.jdbi

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import org.jdbi.v3.core.Handle

/**
 * Batch writes through a JDBI [Handle] with the SAME commit-policy semantics
 * as the JDBC writer (HEL-120 capability 3: equivalent behavior, normal JDBI
 * transaction rules).
 *
 * Transaction interaction is explicit, never silent:
 *  - handle NOT in a transaction: the library manages transactions exactly
 *    like the JDBC path (all-or-nothing, or chunk commits).
 *  - handle ALREADY in a caller-owned transaction: AllOrNothing simply adds
 *    the writes to that transaction (the CALLER commits); PerChunk is
 *    REJECTED with a clear error — chunk-committing inside someone else's
 *    transaction would silently break their atomicity.
 */
object JdbiBatchWriter {

    @JvmStatic
    @JvmOverloads
    fun write(handle: Handle, dml: String, batches: Sequence<RowBatch>,
              options: JdbcBatchWriter.WriteOptions = JdbcBatchWriter.WriteOptions()): OperationReport {
        if (!handle.isInTransaction) {
            // library-managed transactions — delegate to the proven JDBC writer
            // on the handle's connection (same code path = same semantics).
            return JdbcBatchWriter.write(handle.connection, dml, batches, options)
        }
        require(options.commitPolicy !is JdbcBatchWriter.CommitPolicy.PerChunk) {
            "PerChunk commit policy inside a caller-owned JDBI transaction would " +
            "break the caller's atomicity — commit management belongs to whoever " +
            "opened the transaction. Use AllOrNothing here, or write outside the " +
            "transaction."
        }
        // caller-owned transaction: append batches, never commit/rollback here.
        val start = System.nanoTime()
        var rows = 0L
        var batchCount = 0
        val prepared = handle.prepareBatch(dml)
        for (batch in batches) {
            options.cancelToken.throwIfCancelled()
            for (row in batch.rows) {
                row.values.forEachIndexed { i, v -> prepared.bind(i, v) }
                prepared.add()
            }
            prepared.execute()
            rows += batch.size
            batchCount++
            options.onProgress?.invoke(batchCount - 1, rows)
        }
        return OperationReport(
            rowsAffected = rows, batches = batchCount,
            elapsedMillis = (System.nanoTime() - start) / 1_000_000,
            completed = true)
    }
}
