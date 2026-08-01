package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.RowBatch
import java.sql.Connection

/**
 * Executes batch DML under a selected [TransactionPolicy] and returns a
 * machine-readable [TransactionOutcome] (HEL-126). Built on the same
 * row-binding as [JdbcBatchWriter]; the policies differ ONLY in commit/
 * rollback choreography, and every outcome states exactly what is committed
 * and whether a retry can duplicate effects.
 */
object TransactionalWriter {

    data class WriteOptions(
        val cancelToken: CancelToken = CancelToken.none(),
        /** (rowsCommittedOrStaged) — progress without row values. */
        val onProgress: ((Long) -> Unit)? = null,
    )

    class UnsupportedPolicyException(message: String) : IllegalArgumentException(message)

    /**
     * Write [batches] through [dml] under [policy]. The caller owns the
     * connection. Unsupported/conflicting policy states fail BEFORE any row
     * is processed (e.g. [TransactionPolicy.JoinExisting] on an auto-commit
     * connection, [TransactionPolicy.SavepointPerBatch] on a dialect without
     * savepoints).
     */
    @JvmStatic
    @JvmOverloads
    fun write(connection: Connection, dml: String, batches: Sequence<RowBatch>,
              policy: TransactionPolicy, dialect: SqlDialect? = null,
              options: WriteOptions = WriteOptions()): TransactionOutcome = when (policy) {
        is TransactionPolicy.Atomic -> atomic(connection, dml, batches, policy, options)
        is TransactionPolicy.Chunked -> chunked(connection, dml, batches, policy, options)
        is TransactionPolicy.JoinExisting -> join(connection, dml, batches, policy, options)
        is TransactionPolicy.SavepointPerBatch -> savepoint(connection, dml, batches, policy, dialect, options)
        is TransactionPolicy.AutoCommit -> autoCommit(connection, dml, batches, policy, options)
    }

    // ── Atomic: delegate to the proven all-or-nothing writer ────────────────
    private fun atomic(c: Connection, dml: String, batches: Sequence<RowBatch>,
                       policy: TransactionPolicy, o: WriteOptions): TransactionOutcome =
        try {
            val r = JdbcBatchWriter.write(c, dml, batches,
                JdbcBatchWriter.WriteOptions(
                    commitPolicy = JdbcBatchWriter.CommitPolicy.AllOrNothing,
                    cancelToken = o.cancelToken,
                    onProgress = { _, rows -> o.onProgress?.invoke(rows) }))
            TransactionOutcome(TransactionState.COMMITTED, policy,
                committedRows = r.rowsAffected, rolledBackRows = 0,
                committedChunks = listOf(ChunkRange(0, r.rowsAffected)),
                failedChunk = null, checkpoint = null, retrySafety = RetrySafety.COMPLETE)
        } catch (e: JdbcBatchWriter.BatchWriteException) {
            throw TransactionWriteException(
                TransactionOutcome(TransactionState.ROLLED_BACK, policy,
                    committedRows = 0,
                    rolledBackRows = e.report.failedRowRange?.last?.plus(1) ?: 0,
                    committedChunks = emptyList(),
                    failedChunk = e.report.failedRowRange?.let { ChunkRange(it.first, it.last + 1) },
                    checkpoint = TransferCheckpoint(0),
                    retrySafety = RetrySafety.SAFE_NOTHING_COMMITTED), e)
        }

    // ── Chunked: re-chunk to rowsPerCommit, commit per chunk ────────────────
    private fun chunked(c: Connection, dml: String, batches: Sequence<RowBatch>,
                        policy: TransactionPolicy.Chunked, o: WriteOptions): TransactionOutcome {
        val rechunked = rechunk(batches, policy.rowsPerCommit)
        return try {
            val r = JdbcBatchWriter.write(c, dml, rechunked,
                JdbcBatchWriter.WriteOptions(
                    commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1),
                    cancelToken = o.cancelToken,
                    onProgress = { _, rows -> o.onProgress?.invoke(rows) }))
            TransactionOutcome(TransactionState.COMMITTED, policy,
                committedRows = r.rowsAffected, rolledBackRows = 0,
                committedChunks = ranges(r.rowsAffected, policy.rowsPerCommit),
                failedChunk = null, checkpoint = TransferCheckpoint(r.rowsAffected),
                retrySafety = RetrySafety.COMPLETE)
        } catch (e: JdbcBatchWriter.BatchWriteException) {
            val committed = e.report.rowsAffected
            throw TransactionWriteException(
                TransactionOutcome(TransactionState.PARTIALLY_COMMITTED, policy,
                    committedRows = committed,
                    rolledBackRows = (e.report.failedRowRange?.size() ?: 0),
                    committedChunks = ranges(committed, policy.rowsPerCommit),
                    failedChunk = e.report.failedRowRange?.let { ChunkRange(it.first, it.last + 1) },
                    checkpoint = TransferCheckpoint(committed),
                    retrySafety = if (committed > 0) RetrySafety.UNSAFE_PARTIAL_COMMITTED
                                  else RetrySafety.SAFE_NOTHING_COMMITTED), e)
        }
    }

    // ── JoinExisting: append only; the caller commits ───────────────────────
    private fun join(c: Connection, dml: String, batches: Sequence<RowBatch>,
                     policy: TransactionPolicy, o: WriteOptions): TransactionOutcome {
        if (c.autoCommit) throw UnsupportedPolicyException(
            "JoinExisting requires a caller-owned transaction (connection is in auto-commit)")
        var rows = 0L
        c.prepareStatement(dml).use { st ->
            for (batch in batches) {
                o.cancelToken.throwIfCancelled()
                for (row in batch.rows) {
                    row.values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                    st.addBatch()
                }
                st.executeBatch()
                rows += batch.size
                o.onProgress?.invoke(rows)
            }
        }
        // no commit, no rollback, no close — the transaction is not ours
        return TransactionOutcome(TransactionState.PENDING_IN_CALLER_TRANSACTION, policy,
            committedRows = 0, rolledBackRows = 0, committedChunks = emptyList(),
            failedChunk = null, checkpoint = null, retrySafety = RetrySafety.CALLER_OWNED)
    }

    // ── SavepointPerBatch: one transaction, savepoint-scoped batches ────────
    private fun savepoint(c: Connection, dml: String, batches: Sequence<RowBatch>,
                          policy: TransactionPolicy, dialect: SqlDialect?,
                          o: WriteOptions): TransactionOutcome {
        // Fail closed: SavepointPerBatch needs a dialect to VERIFY savepoint
        // support. A null dialect can't be verified, so it is rejected rather
        // than assumed-capable (the precheck must not be bypassable).
        if (dialect == null) throw UnsupportedPolicyException(
            "SavepointPerBatch requires a dialect to verify savepoint support")
        if (!dialect.supportsSavepoints) throw UnsupportedPolicyException(
            "${dialect.name} does not support savepoints")
        val prevAuto = c.autoCommit
        c.autoCommit = false
        var staged = 0L
        var failed: ChunkRange? = null
        var thrown: Throwable? = null
        try {
            c.prepareStatement(dml).use { st ->
                for (batch in batches) {
                    o.cancelToken.throwIfCancelled()
                    val sp = c.setSavepoint()
                    try {
                        for (row in batch.rows) {
                            row.values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                            st.addBatch()
                        }
                        st.executeBatch()
                        staged += batch.size
                        o.onProgress?.invoke(staged)
                    } catch (e: Exception) {
                        c.rollback(sp)                    // discard ONLY this batch
                        failed = ChunkRange(staged, staged + batch.size)
                        break                             // stop; survivors get committed
                    }
                }
            }
            c.commit()
            val state = if (failed == null) TransactionState.COMMITTED
                        else TransactionState.PARTIALLY_COMMITTED
            return TransactionOutcome(state, policy,
                committedRows = staged, rolledBackRows = failed?.size ?: 0,
                committedChunks = if (staged > 0) listOf(ChunkRange(0, staged)) else emptyList(),
                failedChunk = failed, checkpoint = TransferCheckpoint(staged),
                retrySafety = when {
                    failed == null -> RetrySafety.COMPLETE
                    staged > 0 -> RetrySafety.UNSAFE_PARTIAL_COMMITTED
                    else -> RetrySafety.SAFE_NOTHING_COMMITTED
                })
        } catch (e: Exception) {
            // A rollback that itself fails leaves the transaction state
            // UNCERTAIN — surface it (attached as suppressed), never swallow.
            val rollbackFailure = runCatching { c.rollback() }.exceptionOrNull()
            val clean = rollbackFailure == null
            val ex = TransactionWriteException(
                TransactionOutcome(
                    if (clean) TransactionState.ROLLED_BACK else TransactionState.UNCERTAIN,
                    policy,
                    committedRows = 0, rolledBackRows = staged, committedChunks = emptyList(),
                    failedChunk = failed, checkpoint = TransferCheckpoint(0),
                    // clean rollback → safe to retry; failed rollback → NOT safe.
                    retrySafety = if (clean) RetrySafety.SAFE_NOTHING_COMMITTED
                                  else RetrySafety.UNSAFE_PARTIAL_COMMITTED), e)
            rollbackFailure?.let { ex.addSuppressed(it) }
            thrown = ex
            throw ex
        } finally {
            // Restoring autoCommit is cleanup: attach a failure to the in-flight
            // exception, or surface it on the normal-return path — do not hide it.
            val restoreFailure = runCatching { c.autoCommit = prevAuto }.exceptionOrNull()
            if (restoreFailure != null) {
                if (thrown != null) thrown.addSuppressed(restoreFailure) else throw restoreFailure
            }
        }
    }

    // ── AutoCommit: explicit opt-in, exact partial accounting ───────────────
    private fun autoCommit(c: Connection, dml: String, batches: Sequence<RowBatch>,
                           policy: TransactionPolicy, o: WriteOptions): TransactionOutcome {
        val prevAuto = c.autoCommit
        c.autoCommit = true
        var committed = 0L
        var thrown: Throwable? = null
        try {
            c.prepareStatement(dml).use { st ->
                for (batch in batches) {
                    o.cancelToken.throwIfCancelled()
                    for (row in batch.rows) {
                        row.values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
                        try {
                            st.executeUpdate()            // each statement commits
                            committed++
                        } catch (e: Exception) {
                            throw TransactionWriteException(
                                TransactionOutcome(
                                    if (committed > 0) TransactionState.PARTIALLY_COMMITTED
                                    else TransactionState.ROLLED_BACK,
                                    policy, committedRows = committed, rolledBackRows = 0,
                                    committedChunks = if (committed > 0)
                                        listOf(ChunkRange(0, committed)) else emptyList(),
                                    failedChunk = ChunkRange(committed, committed + 1),
                                    checkpoint = TransferCheckpoint(committed),
                                    retrySafety = if (committed > 0)
                                        RetrySafety.UNSAFE_PARTIAL_COMMITTED
                                    else RetrySafety.SAFE_NOTHING_COMMITTED), e)
                        }
                    }
                    o.onProgress?.invoke(committed)
                }
            }
            return TransactionOutcome(TransactionState.COMMITTED, policy,
                committedRows = committed, rolledBackRows = 0,
                committedChunks = listOf(ChunkRange(0, committed)),
                failedChunk = null, checkpoint = TransferCheckpoint(committed),
                retrySafety = RetrySafety.COMPLETE)
        } catch (e: Throwable) {
            thrown = e
            throw e
        } finally {
            val restoreFailure = runCatching { c.autoCommit = prevAuto }.exceptionOrNull()
            if (restoreFailure != null) {
                if (thrown != null) thrown.addSuppressed(restoreFailure) else throw restoreFailure
            }
        }
    }

    private fun rechunk(batches: Sequence<RowBatch>, rows: Int): Sequence<RowBatch> = sequence {
        var buf = mutableListOf<io.maxxga.rowrelay.core.Row>()
        var schema: io.maxxga.rowrelay.core.Schema? = null
        for (b in batches) {
            schema = b.schema
            for (r in b.rows) {
                buf += r
                if (buf.size == rows) { yield(RowBatch(b.schema, buf)); buf = mutableListOf() }
            }
        }
        if (buf.isNotEmpty() && schema != null) yield(RowBatch(schema, buf))
    }

    private fun ranges(committed: Long, per: Int): List<ChunkRange> {
        val out = mutableListOf<ChunkRange>()
        var i = 0L
        while (i < committed) {
            val end = minOf(i + per, committed)
            out += ChunkRange(i, end)
            i = end
        }
        return out
    }

    private fun kotlin.ranges.LongRange.size(): Long = last - first + 1
}

/** Carries the honest partial [outcome] when a transactional write fails. */
class TransactionWriteException(val outcome: TransactionOutcome, cause: Throwable) :
    RuntimeException("transactional write failed: state=${outcome.state} " +
                     "committed=${outcome.committedRows}", cause)
