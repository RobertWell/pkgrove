package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.OperationReport
import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.jdbc.DatabaseKey
import io.maxxga.rowrelay.jdbc.Databases
import io.maxxga.rowrelay.jdbc.SqlDialect
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
 * Distributed backends: the executor is pluggable ([WorkflowExecutor]).
 * Apache River was evaluated for the distributed slot and is NOT
 * implemented: River is retired in the Apache Attic (unmaintained — it would
 * also fail this repo's own supply-chain gate). The seam stays open for a
 * maintained backend; see docs/WORKFLOWS.md.
 */
object Workflows {

    /** Immutable flow definition. Build via [from]. */
    data class RowFlow internal constructor(
        val sourceKey: DatabaseKey,
        val sourceSql: String,
        val namedParams: Map<String, Any?>,
        internal val transform: ((Row) -> Row?)?,
        val sinkKey: DatabaseKey?,
        val sinkDialect: SqlDialect?,
        val sinkTable: String?,
        val options: Transfer.Options,
    ) {
        /** Per-row transform (schema-preserving); return null to drop the row.
         *  Chained transforms compose in order. */
        fun transform(fn: (Row) -> Row?): RowFlow {
            val prev = transform
            val composed: (Row) -> Row? =
                if (prev == null) fn else { r -> prev(r)?.let(fn) }
            return copy(transform = composed)
        }

        /** Keep only rows matching [predicate]. */
        fun filter(predicate: (Row) -> Boolean): RowFlow =
            transform { r -> if (predicate(r)) r else null }

        /** Terminal: write into [table] on [key] via [dialect]. */
        fun to(key: DatabaseKey, dialect: SqlDialect, table: String,
               options: Transfer.Options = this.options): RowFlow =
            copy(sinkKey = key, sinkDialect = dialect, sinkTable = table,
                 options = options)
    }

    /** Start a flow from a named-parameter query on [key]. */
    @JvmStatic
    @JvmOverloads
    fun from(key: DatabaseKey, sql: String, namedParams: Map<String, Any?> = emptyMap(),
             options: Transfer.Options = Transfer.Options()): RowFlow =
        RowFlow(key, sql, namedParams, null, null, null, null, options)

    /** One executed flow's result. */
    data class FlowResult(val flow: RowFlow, val report: OperationReport?,
                          val error: Throwable?) {
        val succeeded: Boolean get() = error == null
    }

    /** Pluggable execution backend — sequential, parallel, or (future,
     *  maintained) distributed. Definitions in, results out; resource
     *  acquisition always flows through the [Databases] registry. */
    interface WorkflowExecutor {
        fun execute(flows: List<RowFlow>, databases: Databases): List<FlowResult>
    }

    private fun runOne(flow: RowFlow, databases: Databases): FlowResult {
        val sinkKey = flow.sinkKey
            ?: return FlowResult(flow, null,
                IllegalStateException("flow has no sink — call .to(...)"))
        val dialect = flow.sinkDialect!!
        val table = flow.sinkTable!!
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
        } catch (t: Throwable) {
            FlowResult(flow, null, t)
        }
    }

    /** Runs flows one after another; first-class for correctness-critical
     *  pipelines and the default choice. */
    object SequentialExecutor : WorkflowExecutor {
        override fun execute(flows: List<RowFlow>, databases: Databases): List<FlowResult> =
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
        override fun execute(flows: List<RowFlow>, databases: Databases): List<FlowResult> {
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
}
