package io.maxxga.rowrelay.jdbi

import io.maxxga.rowrelay.core.OperationReport
import io.maxxga.rowrelay.jdbc.SqlDialect
import io.maxxga.rowrelay.transfer.Transfer
import org.jdbi.v3.core.Handle

/**
 * First-class JDBI transfer facade (HEL-160). A JDBI consumer runs a transfer
 * whose TARGET is a JDBI [Handle] — with the SAME transaction guarantees as
 * [JdbiBatchWriter] — without unwrapping to `handle.connection`.
 *
 * Transaction interaction is identical to [JdbiBatchWriter] because the target
 * write is routed through it:
 *  - handle NOT in a transaction: library-managed transactions, exactly like the
 *    JDBC transfer path (all-or-nothing, or chunk commits).
 *  - handle ALREADY in a caller-owned transaction: batches are appended to it and
 *    the CALLER commits; a PerChunk commit policy is REJECTED (chunk-committing
 *    inside someone else's transaction would break their atomicity).
 *
 * The read side is still driven by a source [java.sql.Connection] and SQL, so the
 * facade composes with any source. Only the write side becomes JDBI-idiomatic.
 */
object JdbiTransfer {

    /**
     * Transfer from [source]/[sourceSql] into [targetTable] on [targetHandle],
     * using the same [Transfer.Options] as the JDBC path. DDL (table
     * establishment) and the batch writes both run on the handle's connection, so
     * a caller-owned transaction wraps the whole transfer atomically.
     */
    @JvmStatic
    @JvmOverloads
    fun run(source: java.sql.Connection, sourceSql: String, namedParams: Map<String, Any?>,
            targetHandle: Handle, targetDialect: SqlDialect, targetTable: String,
            options: Transfer.Options = Transfer.Options()): OperationReport {
        // Fail early with the SAME message JdbiBatchWriter uses, BEFORE reading
        // or establishing anything, when the policy can't be honored in the
        // caller's transaction — a transfer must not create a table then refuse
        // to write into it.
        if (targetHandle.isInTransaction) {
            require(options.commitPolicy !is io.maxxga.rowrelay.jdbc.JdbcBatchWriter.CommitPolicy.PerChunk) {
                "PerChunk commit policy inside a caller-owned JDBI transaction would " +
                "break the caller's atomicity — commit management belongs to whoever " +
                "opened the transaction. Use AllOrNothing here, or transfer outside the " +
                "transaction."
            }
        }
        return Transfer.runToWriter(
            source, sourceSql, namedParams,
            targetHandle.connection, targetDialect, targetTable, options
        ) { dml, batches, writeOptions ->
            JdbiBatchWriter.write(targetHandle, dml, batches, writeOptions)
        }
    }
}
