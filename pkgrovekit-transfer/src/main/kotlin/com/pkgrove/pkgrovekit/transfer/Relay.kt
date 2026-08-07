package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.Identifiers
import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.jdbc.DatabaseKey
import com.pkgrove.pkgrovekit.jdbc.Databases
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.NamedSql
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.TransactionalWriter
import javax.sql.DataSource

/**
 * The PkgroveKit golden path (HEL-125): configure infrastructure ONCE, define
 * transfers as immutable plans, execute through the managed runtime, handle a
 * typed [TransferOutcome].
 *
 * ```
 * configure once -> immutable plan -> pure transforms -> execute -> typed outcome
 * ```
 *
 * Application code never touches `Connection`/`PreparedStatement`/commit —
 * the runtime leases connections from the [Databases] registry (HEL-128),
 * applies the declared policies, and releases on every path.
 */
class Relay private constructor(
    private val databases: Databases,
    private val dialects: Map<DatabaseKey, SqlDialect>,
) : AutoCloseable {

    class Builder internal constructor() {
        private val db = mutableListOf<Pair<DatabaseKey, DataSource>>()
        private val caps = mutableMapOf<DatabaseKey, Int?>()
        private val dialects = mutableMapOf<DatabaseKey, SqlDialect>()

        /** Register a database ONCE: identity + pool + dialect (+ lease budget).
         *  PkgroveKit borrows connections from your pool and never closes it. */
        fun database(key: DatabaseKey, dataSource: DataSource, dialect: SqlDialect,
                     maxConnections: Int? = null) {
            db += key to dataSource
            caps[key] = maxConnections
            dialects[key] = dialect
        }

        internal fun build(): Relay {
            val registry = Databases.build {
                for ((key, ds) in db) {
                    val cap = caps[key]
                    if (cap != null) applicationOwned(key, ds, maxConnections = cap)
                    else applicationOwned(key, ds)
                }
            }
            return Relay(registry, dialects.toMap())
        }
    }

    companion object {
        /** Startup entry point — the only place infrastructure is described. */
        @JvmStatic
        fun build(block: Builder.() -> Unit): Relay = Builder().apply(block).build()
    }

    override fun close() = databases.close()

    // ── Plan definition ─────────────────────────────────────────────────────

    /** Source half of a transfer plan under definition. */
    class SourceSpec internal constructor(private val key: DatabaseKey) {
        private var sql: String? = null
        private val params = mutableMapOf<String, Any?>()

        /** Multiline, `:named`-parameter SQL — the recommended form. */
        fun query(sql: String) { this.sql = sql }

        /** Bind a named parameter. Missing names are rejected before execution. */
        fun bind(name: String, value: Any?) { params[name] = value }

        internal fun toFlow(): Workflows.SourceFlow {
            val s = sql ?: throw PlanDefinitionException("from(...) needs a query { }")
            return Workflows.from(key, s, params.toMap())
        }
    }

    /** Sink half: table, name-based mapping, write identity, transaction shape. */
    class SinkSpec internal constructor() {
        internal var renames = mutableListOf<Pair<String, String>>()
        internal var omitted = mutableListOf<String>()
        internal var upsertKeys: List<String>? = null
        internal var commitPolicy: JdbcBatchWriter.CommitPolicy = JdbcBatchWriter.CommitPolicy.AllOrNothing
        internal var mode: SqlDialect.TargetMode? = null
        internal var useBulkLoad: Boolean = false
        internal var processor: (() -> BatchProcessor)? = null

        /** Rename a source column into the target by NAME (never by position). */
        fun rename(source: String, target: String) { renames += source to target }

        /** Drop a source column; the target's default applies. */
        fun omit(source: String) { omitted += source }

        /** Row identity for idempotent sync — Oracle MERGE / ON CONFLICT.
         *  Defaults the target mode to APPEND (an upsert targets an existing
         *  table); override with [mode] if you really want otherwise. */
        fun upsertBy(vararg keys: String) { upsertKeys = keys.toList() }

        /** All-or-nothing: any failure rolls the whole transfer back (default). */
        fun atomic() { commitPolicy = JdbcBatchWriter.CommitPolicy.AllOrNothing }

        /** Commit every [batches] read-batches (batch size = the plan's
         *  readBatchSize); a failure yields [TransferOutcome.Partial] with a
         *  checkpoint instead of losing completed work. */
        fun chunked(batches: Int = 1) {
            commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(batches.coerceAtLeast(1))
        }

        /** How the target table is established (CREATE by default; APPEND when
         *  [upsertBy] is used). */
        fun mode(mode: SqlDialect.TargetMode) { this.mode = mode }

        /**
         * HEL-228: process CONSECUTIVE rows sharing [keyColumns] as one group —
         * the streaming-safe form of grouped aggregation. Memory is bounded by
         * [maxGroupRows], which is required (not defaulted) so the budget is a
         * decision, not an accident; a larger group fails loudly.
         *
         * REQUIRES the source query to be ordered by [keyColumns]. HEL-255: the
         * ordering guard remembers the last [recentKeyMemory] closed keys
         * (default [ConsecutiveGrouper.DEFAULT_RECENT_KEY_MEMORY] = 10 000), so
         * a key reappearing within that window fails the transfer instead of
         * emitting two partial aggregates for one key — and a key reappearing
         * further back than the window is NOT detected. Widening the window
         * widens the guard at ~113 bytes per key; see [ConsecutiveGrouper] for
         * the guarantee in full.
         */
        fun groupConsecutiveBy(vararg keyColumns: String, maxGroupRows: Int,
                               outputSchema: com.pkgrove.pkgrovekit.core.Schema,
                               recentKeyMemory: Int =
                                   ConsecutiveGrouper.DEFAULT_RECENT_KEY_MEMORY,
                               summarize: (key: List<Any?>,
                                           rows: List<com.pkgrove.pkgrovekit.core.Row>)
                                   -> List<com.pkgrove.pkgrovekit.core.Row>) {
            val keys = keyColumns.toList()
            processor = {
                ConsecutiveGrouper(keys, maxGroupRows, outputSchema, recentKeyMemory, summarize)
            }
        }

        /** HEL-228: apply an explicit bounded [BatchProcessor] to the batch stream. */
        fun processBatches(factory: () -> BatchProcessor) { processor = factory }

        /** HEL-161: use the sink dialect's native bulk-ingest path (Postgres
         *  COPY / DuckDB Appender) when available — falls back to batched
         *  INSERT with a warning otherwise. All-or-nothing; incompatible with
         *  [upsertBy] (the request falls back, it never fails). */
        fun bulkLoad() { useBulkLoad = true }
    }

    /** Raised when a plan DEFINITION is structurally incomplete — at definition
     *  time, never at execution time. */
    class PlanDefinitionException(message: String) : IllegalArgumentException(message)

    /** A complete, immutable, inspectable transfer plan: identities + SQL +
     *  policies. No connections, credentials, or cursors inside — safe to hold,
     *  log (params are not stringified), pass around, and execute repeatedly. */
    class TransferPlan internal constructor(
        val name: String,
        internal val flow: Workflows.ExecutableFlow,
    ) {
        override fun toString() = "TransferPlan($name: ${flow.sourceKey} -> ${flow.sinkKey}/${flow.sinkTable})"
    }

    class PlanBuilder internal constructor(private val relay: Relay) {
        private var source: SourceSpec? = null
        private var transform: ((Row) -> Row?)? = null
        private var sinkKey: DatabaseKey? = null
        private var sinkTable: String? = null
        private var sinkSpec: SinkSpec? = null

        fun from(key: DatabaseKey, block: SourceSpec.() -> Unit) {
            source = SourceSpec(key).apply(block)
        }

        /** Pure, per-row transform (return null to drop). Compose freely;
         *  keep them deterministic and effect-free — they are unit-testable
         *  without any database. */
        fun transform(fn: (Row) -> Row?) {
            val prev = transform
            transform = if (prev == null) fn else { r -> prev(r)?.let(fn) }
        }

        /** Keep only rows matching [predicate]. */
        fun filter(predicate: (Row) -> Boolean) = transform { r -> if (predicate(r)) r else null }

        fun to(key: DatabaseKey, table: String, block: SinkSpec.() -> Unit = {}) {
            sinkKey = key
            sinkTable = table
            sinkSpec = SinkSpec().apply(block)
        }

        internal fun build(name: String): TransferPlan {
            val src = source ?: throw PlanDefinitionException("transfer(\"$name\") needs from(...)")
            val key = sinkKey ?: throw PlanDefinitionException("transfer(\"$name\") needs to(...)")
            val table = sinkTable!!
            val spec = sinkSpec!!
            val dialect = relay.dialects[key] ?: throw PlanDefinitionException(
                "no dialect registered for $key — register it in Relay.build { database(...) }")
            val mapping =
                if (spec.renames.isEmpty() && spec.omitted.isEmpty()) Mapping.IDENTITY
                else Mapping.build {
                    for ((s, t) in spec.renames) s mapsTo t
                    for (o in spec.omitted) omit(o)
                }
            val options = Transfer.Options(
                mode = spec.mode ?: if (spec.upsertKeys != null) SqlDialect.TargetMode.APPEND
                                    else SqlDialect.TargetMode.CREATE,
                commitPolicy = spec.commitPolicy,
                mapping = mapping,
                upsertKeys = spec.upsertKeys,
                useBulkLoad = spec.useBulkLoad,
                processor = spec.processor,
            )
            var flow = src.toFlow()
            transform?.let { flow = flow.transform(it) }
            return TransferPlan(name, flow.to(key, dialect, table, options))
        }
    }

    /** Define an immutable transfer plan. Performs NO I/O; an incomplete plan
     *  fails HERE (definition time), so only complete plans reach [execute]. */
    fun transfer(name: String, block: PlanBuilder.() -> Unit): TransferPlan =
        PlanBuilder(this).apply(block).build(name)

    // ── Execution ───────────────────────────────────────────────────────────

    /** Execute one plan through the managed runtime and return a typed
     *  [TransferOutcome]. Cancellation and fatal JVM errors propagate — they
     *  are never normalized into a business failure. */
    fun execute(plan: TransferPlan, cancel: CancelToken = CancelToken.none()): TransferOutcome {
        val flow = plan.flow
        return try {
            val opts = flow.options.copy(rowTransform = flow.transform, cancelToken = cancel)
            val report =
                if (flow.sourceKey == flow.sinkKey)
                    databases.withConnection(flow.sourceKey, opts.cancelToken) { c ->
                        Transfer.run(c, flow.sourceSql, flow.namedParams, c,
                                     flow.sinkDialect, flow.sinkTable, opts)
                    }
                else
                    databases.withConnections(listOf(flow.sourceKey, flow.sinkKey),
                                              opts.cancelToken) { held ->
                        Transfer.run(held.getValue(flow.sourceKey), flow.sourceSql,
                                     flow.namedParams, held.getValue(flow.sinkKey),
                                     flow.sinkDialect, flow.sinkTable, opts)
                    }
            if (report.completed) TransferOutcome.Completed(plan, report)
            else TransferOutcome.Partial(plan, report,
                TransferOutcome.Checkpoint(report.rowsAffected))
        } catch (e: OperationCancelledException) {
            TransferOutcome.Cancelled(plan,
                e.report?.let { TransferOutcome.Checkpoint(it.rowsAffected) })
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw e         // cooperative cancel, never swallowed
        } catch (e: Exception) {
            when (rejectionReason(e)) {
                null -> {
                    // a mid-transfer batch failure carries an honest partial report
                    val bwe = (e as? JdbcBatchWriter.BatchWriteException)
                        ?: (e.cause as? JdbcBatchWriter.BatchWriteException)
                    if (bwe != null && bwe.report.rowsAffected > 0)
                        TransferOutcome.Partial(plan, bwe.report,
                            TransferOutcome.Checkpoint(bwe.report.rowsAffected), e)
                    else TransferOutcome.Failed(plan, e)
                }
                else -> TransferOutcome.Rejected(plan, rejectionReason(e)!!, e)
            }
        }
        // Errors (OutOfMemoryError, ...) intentionally not caught.
    }

    /** A plan-admission problem — nothing was executed, nothing was written. */
    private fun rejectionReason(e: Exception): String? = when (e) {
        is Mapping.MappingException -> "invalid mapping: ${e.message}"
        is NamedSql.MissingParametersException -> "missing parameters: ${e.missing}"
        is Identifiers.UnsafeIdentifierException -> "unsafe identifier"
        is TransactionalWriter.UnsupportedPolicyException -> "unsupported policy: ${e.message}"
        else -> null
    }
}

/**
 * The typed result of executing a [Relay.TransferPlan] — impossible states are
 * unrepresentable: there is no nullable report + nullable error pair, partial
 * completion can never read as success, and rejection (nothing ran) is distinct
 * from failure (something broke).
 */
sealed interface TransferOutcome {
    val plan: Relay.TransferPlan

    /** Resume point for a partial transfer: rows already durably committed.
     *  Re-run the plan with a `WHERE`-clause offset or an [Relay.SinkSpec.upsertBy]
     *  identity to make the resume idempotent. */
    data class Checkpoint(val committedRows: Long)

    /** Everything ran, everything committed. */
    data class Completed(override val plan: Relay.TransferPlan,
                         val report: OperationReport) : TransferOutcome

    /** Some rows are durably committed, the rest are not — never mistakable
     *  for success; carries the exact resume point. */
    data class Partial(override val plan: Relay.TransferPlan,
                       val report: OperationReport,
                       val checkpoint: Checkpoint,
                       val cause: Throwable? = null) : TransferOutcome

    /** The plan could not be admitted (bad mapping, missing parameters,
     *  unsafe identifier, unsupported policy) — nothing was executed. */
    data class Rejected(override val plan: Relay.TransferPlan,
                        val reason: String,
                        val cause: Throwable) : TransferOutcome

    /** Execution failed with nothing (durably) committed. */
    data class Failed(override val plan: Relay.TransferPlan,
                      val cause: Throwable) : TransferOutcome

    /** Cooperatively cancelled via the plan's CancelToken. [checkpoint] carries
     *  the durably-committed rows when cancellation aborted a write (null for
     *  read-side cancellation) — resume from it like [Partial]. */
    data class Cancelled(override val plan: Relay.TransferPlan,
                         val checkpoint: Checkpoint? = null) : TransferOutcome
}
