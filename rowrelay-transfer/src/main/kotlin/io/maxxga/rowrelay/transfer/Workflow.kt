package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.BranchFailure
import io.maxxga.rowrelay.core.OperationReport
import io.maxxga.rowrelay.core.WorkflowOutcome
import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.Databases
import io.maxxga.rowrelay.jdbc.SqlDialect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Functional workflow surface (HEL-125): a flow DEFINITION is immutable data
 * — typed database keys, SQL, named params, transforms, sink options — with
 * no live connections, credentials, or pools inside (safe to hand to any
 * executor, local or distributed). Execution acquires leases from the
 * [Databases] registry per the HEL-128 scoped model and releases them on
 * every path.
 *
 * Executors are pluggable ([WorkflowExecutor] for the blocking backends;
 * [executeStructured] for the coroutine backend). The executor-architecture
 * decision (coroutines default; Arrow/Temporal/Pekko/River evaluated seams —
 * River allowed-but-gated, not banned) lives in
 * docs/adr/0001-workflow-executor-architecture.md.
 */
object Workflows {

    /**
     * A flow with a source but NO sink yet — an immutable value that is **not
     * executable**. Add a sink with [to] to get an [ExecutableFlow]. This
     * staging makes an incomplete flow unrepresentable at the executor: there
     * is no nullable-sink recovered with `!!` at run time (HEL-125 §3).
     */
    data class SourceFlow internal constructor(
        val sourceKey: DatabaseKey,
        val sourceSql: String,
        val namedParams: Map<String, Any?>,
        internal val transform: ((Row) -> Row?)?,
    ) {
        /** Per-row transform (schema-preserving); return null to drop the row.
         *  Chained transforms compose in order. */
        fun transform(fn: (Row) -> Row?): SourceFlow {
            val prev = transform
            val composed: (Row) -> Row? =
                if (prev == null) fn else { r -> prev(r)?.let(fn) }
            return copy(transform = composed)
        }

        /** Keep only rows matching [predicate]. */
        fun filter(predicate: (Row) -> Boolean): SourceFlow =
            transform { r -> if (predicate(r)) r else null }

        /** Terminal: complete the flow with a sink. Returns an [ExecutableFlow]
         *  — the ONLY type an executor accepts. */
        fun to(key: DatabaseKey, dialect: SqlDialect, table: String,
               options: Transfer.Options = Transfer.Options()): ExecutableFlow =
            ExecutableFlow(this, key, dialect, table, options)
    }

    /** A complete, runnable flow: source + sink. Executors accept only this. */
    data class ExecutableFlow internal constructor(
        val source: SourceFlow,
        val sinkKey: DatabaseKey,
        val sinkDialect: SqlDialect,
        val sinkTable: String,
        val options: Transfer.Options,
    ) {
        val sourceKey: DatabaseKey get() = source.sourceKey
        val sourceSql: String get() = source.sourceSql
        val namedParams: Map<String, Any?> get() = source.namedParams
        internal val transform: ((Row) -> Row?)? get() = source.transform
    }

    /** Start a flow from a named-parameter query on [key]. Returns a
     *  [SourceFlow]; call [SourceFlow.to] to make it executable. */
    @JvmStatic
    @JvmOverloads
    fun from(key: DatabaseKey, sql: String, namedParams: Map<String, Any?> = emptyMap()): SourceFlow =
        SourceFlow(key, sql, namedParams, null)

    /** One executed flow's result. */
    data class FlowResult(val flow: ExecutableFlow, val report: OperationReport?,
                          val error: Throwable?) {
        val succeeded: Boolean get() = error == null
    }

    /** Pluggable execution backend — sequential, parallel, or (future,
     *  maintained) distributed. Definitions in, results out; resource
     *  acquisition always flows through the [Databases] registry. */
    interface WorkflowExecutor {
        fun execute(flows: List<ExecutableFlow>, databases: Databases): List<FlowResult>
    }

    private fun runOne(flow: ExecutableFlow, databases: Databases): FlowResult {
        val sinkKey = flow.sinkKey        // non-null by type — no !! at run time
        val dialect = flow.sinkDialect
        val table = flow.sinkTable
        return try {
            val opts = flow.options.copy(rowTransform = flow.transform)
            val report =
                if (flow.sourceKey == sinkKey) {
                    // same database: ONE lease, one connection for both sides
                    databases.withConnection(flow.sourceKey, opts.cancelToken) { c ->
                        Transfer.run(c, flow.sourceSql, flow.namedParams, c,
                                     dialect, table, opts)
                    }
                } else {
                    // deterministic key-ordered dual acquisition (HEL-128)
                    databases.withConnections(listOf(flow.sourceKey, sinkKey),
                                              opts.cancelToken) { held ->
                        Transfer.run(held.getValue(flow.sourceKey), flow.sourceSql,
                                     flow.namedParams, held.getValue(sinkKey),
                                     dialect, table, opts)
                    }
                }
            FlowResult(flow, report, null)
        } catch (ce: CancellationException) {
            // NEVER swallowed into a FlowResult: structured cancellation must
            // propagate so the scope can cancel siblings (HEL-128 review).
            throw ce
        } catch (t: Throwable) {
            FlowResult(flow, null, t)
        }
    }

    /** Runs flows one after another; first-class for correctness-critical
     *  pipelines and the default choice. */
    object SequentialExecutor : WorkflowExecutor {
        override fun execute(flows: List<ExecutableFlow>, databases: Databases): List<FlowResult> =
            flows.map { runOne(it, databases) }
    }

    /**
     * Bounded parallel execution of INDEPENDENT flows. Parallelism is capped
     * by [maxConcurrentFlows] AND by each database's lease budget (HEL-128) —
     * fan-out can queue on a budget but can never exhaust a pool or share a
     * connection between branches (each flow leases its own).
     */
    class ParallelExecutor(private val maxConcurrentFlows: Int) : WorkflowExecutor {
        init { require(maxConcurrentFlows > 0) }
        override fun execute(flows: List<ExecutableFlow>, databases: Databases): List<FlowResult> {
            val pool = Executors.newFixedThreadPool(maxConcurrentFlows) { r ->
                Thread(r, "rowrelay-flow").apply { isDaemon = true }
            }
            try {
                val futures = flows.map { f -> pool.submit<FlowResult> { runOne(f, databases) } }
                return futures.map { it.get() }
            } finally {
                pool.shutdown()
                pool.awaitTermination(1, TimeUnit.MINUTES)
            }
        }
    }

    /** Sibling-failure policy for [executeStructured]. */
    enum class BranchPolicy { FAIL_FAST, SUPERVISED }

    private class FlowFailed(val result: FlowResult) : Exception(result.error)

    /**
     * HEL-167: structured-concurrency executor for INDEPENDENT flows.
     *  - bounded parallelism ([maxConcurrency]) AND each database's lease budget;
     *  - structured cancellation: a caller cancel, or a FAIL_FAST sibling
     *    failure, cancels all children — no orphan work, every lease released
     *    through [Databases];
     *  - typed [WorkflowOutcome]: Completed (all ok), Partial (SUPERVISED — some
     *    failed; successes AND named failures retained, never hidden), Failed
     *    (FAIL_FAST bubbled), Cancelled (cooperative cancel preserved).
     * Blocking JDBC runs on [Dispatchers.IO]; branches never share a connection
     * (each flow leases its own via the registry).
     */
    suspend fun executeStructured(
        flows: List<ExecutableFlow>,
        databases: Databases,
        maxConcurrency: Int = flows.size.coerceAtLeast(1),
        policy: BranchPolicy = BranchPolicy.FAIL_FAST,
    ): WorkflowOutcome<List<FlowResult>> {
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        if (flows.isEmpty()) return WorkflowOutcome.Completed(emptyList())
        val gate = Semaphore(maxConcurrency)
        // Coroutine-to-JDBC cancellation bridge (HEL-128 review): each flow's
        // token is LINKED to a scope token that fires on structured
        // cancellation (caller cancel or FAIL_FAST sibling failure). Blocking
        // JDBC work observes the cancel at its next cooperative checkpoint
        // (reader row-batch / writer batch boundaries, lease-wait slices) and
        // releases statements, transactions, and leases promptly — instead of
        // running to completion after the scope has already given up on it.
        val scopeToken = io.maxxga.rowrelay.core.CancelToken.none()
        fun bridged(f: ExecutableFlow): ExecutableFlow = f.copy(
            options = f.options.copy(
                cancelToken = io.maxxga.rowrelay.core.CancelToken.linked(
                    f.options.cancelToken, scopeToken)))
        // The token must fire the MOMENT the scope starts cancelling — not after
        // the scope has finished waiting for its children (the blocking child is
        // exactly what the token is needed to stop). A watcher coroutine's
        // `finally` runs at cancellation-start, which is that moment.
        return try {
            val results = when (policy) {
                BranchPolicy.SUPERVISED -> supervisorScope {
                    val watcher = launch {
                        try { awaitCancellation() } finally { scopeToken.cancel() }
                    }
                    // runOne is exception-safe (returns FlowResult), so a failing
                    // branch does not cancel its siblings — both outcomes retained.
                    val rs = flows.map { f -> async(Dispatchers.IO) { gate.withPermit { runOne(bridged(f), databases) } } }
                        .awaitAll()
                    watcher.cancel()   // normal completion: all work already done
                    rs
                }
                BranchPolicy.FAIL_FAST -> coroutineScope {
                    val watcher = launch {
                        try { awaitCancellation() } finally { scopeToken.cancel() }
                    }
                    val rs = flows.map { f -> async(Dispatchers.IO) {
                        val r = gate.withPermit { runOne(bridged(f), databases) }
                        if (r.error != null) {
                            scopeToken.cancel()      // siblings stop at their next checkpoint
                            throw FlowFailed(r)      // cancels the scope
                        }
                        r
                    } }.awaitAll()
                    watcher.cancel()
                    rs
                }
            }
            val failures = results.filter { it.error != null }
            if (failures.isEmpty()) WorkflowOutcome.Completed(results)
            else WorkflowOutcome.Partial(results,
                failures.map { BranchFailure(it.flow.sinkTable ?: "?", it.error!!) })
        } catch (e: CancellationException) {
            scopeToken.cancel()                       // stop in-flight JDBC work
            throw e                                   // preserve cancellation
        } catch (e: FlowFailed) {
            scopeToken.cancel()                       // siblings stop promptly
            WorkflowOutcome.Failed(e.result.error!!)  // FAIL_FAST: first failure wins
        } catch (e: Throwable) {
            scopeToken.cancel()
            WorkflowOutcome.Failed(e)
        }
    }

}
