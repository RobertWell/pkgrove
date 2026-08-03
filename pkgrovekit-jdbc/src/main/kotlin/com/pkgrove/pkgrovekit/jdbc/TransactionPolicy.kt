package com.pkgrove.pkgrovekit.jdbc

/**
 * Typed, selectable transaction behavior (HEL-126). The core rule: PkgroveKit
 * never hides commit, rollback, partial completion, or retry semantics —
 * every policy states exactly who commits, what a failure destroys, and
 * whether a retry can duplicate effects.
 *
 * These policies govern ONE database's local transaction. A cross-database
 * transfer involves two independent resources; see `docs/TRANSACTIONS.md` —
 * PkgroveKit never claims global atomicity across databases.
 */
sealed class TransactionPolicy {

    /**
     * One transaction for the complete target-side operation: commit only
     * after every write succeeds, roll back everything on failure. Suited to
     * smaller transfers or hard all-or-nothing requirements — transaction
     * duration, lock footprint, and undo growth scale with the transfer, so
     * bound the row count operationally.
     */
    data object Atomic : TransactionPolicy()

    /**
     * Commit after every [rowsPerCommit] rows (bounded transaction size for
     * production ETL). A failure destroys only the OPEN chunk; committed
     * chunks stay committed and are reported as such — the outcome is never
     * presented as atomic. Restarting from `checkpoint` is the caller's
     * decision; a blind retry of the whole input WILL duplicate committed
     * rows unless the write is idempotent (see [RetrySafety]).
     */
    data class Chunked(
        val rowsPerCommit: Int,
        val failure: ChunkFailure = ChunkFailure.RollBackCurrentChunk,
    ) : TransactionPolicy() {
        init { require(rowsPerCommit > 0) { "rowsPerCommit must be positive" } }
    }

    /**
     * Participate in a transaction the CALLER owns (application-managed JDBC
     * or a JDBI handle transaction). PkgroveKit appends work and NEVER commits,
     * rolls back, or closes the connection — rollback responsibility is
     * explicitly the caller's. Chunked commits are rejected under a joined
     * transaction (they would break the owner's atomicity).
     */
    data object JoinExisting : TransactionPolicy()

    /**
     * Savepoint-scoped batches inside one transaction (adapters reporting
     * [SqlDialect.supportsSavepoints]): a failed batch rolls back TO ITS
     * SAVEPOINT, earlier batches in the same transaction survive, and the
     * final commit publishes everything that succeeded. Differs from
     * [Chunked]: nothing is visible to other sessions until the single final
     * commit, and a failure discards only the failed batch, not the open
     * chunk's siblings.
     */
    data object SavepointPerBatch : TransactionPolicy()

    /**
     * Driver auto-commit, one statement at a time — explicitly selected only;
     * never the default for multi-batch ETL. Every executed row is committed
     * immediately, so a mid-stream failure leaves everything before it
     * committed; the outcome reports exactly how far it got.
     */
    data object AutoCommit : TransactionPolicy()

    /** What happens to the open chunk when a batch fails under [Chunked]. */
    enum class ChunkFailure { RollBackCurrentChunk }
}

/** Can a naive retry of the same input duplicate effects? Machine-readable so
 *  schedulers/retry layers (HEL-125) can act without guessing. */
enum class RetrySafety {
    /** Nothing committed — a retry re-runs from scratch safely. */
    SAFE_NOTHING_COMMITTED,
    /** Some rows are committed — retry only from the checkpoint, or ensure
     *  idempotent writes (e.g. upsert on named keys); a blind full retry
     *  duplicates rows. */
    UNSAFE_PARTIAL_COMMITTED,
    /** The operation completed — a retry would duplicate everything unless
     *  the write is idempotent. */
    COMPLETE,
    /** The caller owns the transaction — retry semantics belong to them. */
    CALLER_OWNED,
}

/** A committed or failed contiguous row range (0-based, end-exclusive). */
data class ChunkRange(val fromRow: Long, val toRowExclusive: Long) {
    val size: Long get() = toRowExclusive - fromRow
}

/** Resume position for chunked/auto-commit operations: the next source row
 *  index that has NOT been committed. */
data class TransferCheckpoint(val nextRow: Long)

/**
 * Machine-readable transaction outcome (HEL-126 result model). Partial
 * completion is impossible to mistake for success: [state] is COMMITTED only
 * when everything the policy promised is committed.
 */
data class TransactionOutcome(
    val state: TransactionState,
    val policy: TransactionPolicy,
    val committedRows: Long,
    val rolledBackRows: Long,
    val committedChunks: List<ChunkRange>,
    val failedChunk: ChunkRange?,
    val checkpoint: TransferCheckpoint?,
    val retrySafety: RetrySafety,
)

enum class TransactionState {
    /** Everything the policy promised is committed. */
    COMMITTED,
    /** Some chunks/statements committed, then a failure — see committedChunks/
     *  failedChunk/checkpoint. */
    PARTIALLY_COMMITTED,
    /** Nothing is committed. */
    ROLLED_BACK,
    /** Work was appended into a caller-owned transaction; its fate is the
     *  caller's commit/rollback. */
    PENDING_IN_CALLER_TRANSACTION,
    /** A cleanup step (rollback) after a failure ITSELF failed, so the
     *  transaction's final state could not be confirmed. The connection must be
     *  treated as tainted (invalidated, not returned to a pool as healthy) and
     *  a retry is unsafe. The triggering + cleanup failures are attached to the
     *  thrown [TransactionWriteException] (cause + suppressed). */
    UNCERTAIN,
}
