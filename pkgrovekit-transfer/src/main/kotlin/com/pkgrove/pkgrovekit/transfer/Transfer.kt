package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.ConversionPolicy
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.jdbc.ConnectionOwnership
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.ValueReader
import java.sql.Connection

/**
 * SQL-in/data-out bidirectional transfer (HEL-120 capability 4; HEL-119).
 * Source: arbitrary parameterized read SQL. Target: a table established per
 * [SqlDialect.TargetMode], written with the batch writer's commit policies.
 * Bounded memory by construction — one read batch in flight at a time, and
 * (HEL-256) the source driver is actively made to stream rather than assumed
 * to: `fetchSize` alone does not stop pgjdbc buffering a whole result set, so
 * the read enforces the source's [com.pkgrove.pkgrovekit.jdbc.StreamingContract]
 * (see [Options.sourceConnectionOwnership]). The one case that cannot be made
 * to stream — source and target sharing one physical connection, where the
 * writer's commit closes the cursor — is reported as a [DataWarning] on the
 * [OperationReport] rather than claimed as bounded.
 *
 * Direction-agnostic: "Oracle → DuckDB" vs "DuckDB → Oracle" is just which
 * connection is source and which dialect is target.
 */
object Transfer {

    data class Options(
        val mode: SqlDialect.TargetMode = SqlDialect.TargetMode.CREATE,
        val conversionPolicy: ConversionPolicy = ConversionPolicy.REJECT,
        val readBatchSize: Int = 1_000,
        val fetchSize: Int = 1_000,
        val commitPolicy: JdbcBatchWriter.CommitPolicy = JdbcBatchWriter.CommitPolicy.AllOrNothing,
        val cancelToken: CancelToken = CancelToken.none(),
        val sourceValueReader: ValueReader = ValueReader.DEFAULT,
        /** Named source-to-target column mapping; identity by default. */
        val mapping: Mapping = Mapping.IDENTITY,
        /** HEL-125: per-row transform applied AFTER reading, BEFORE mapping —
         *  return null to filter the row out. Must preserve the row's schema
         *  (renames/constants belong to [mapping]). */
        val rowTransform: ((com.pkgrove.pkgrovekit.core.Row) -> com.pkgrove.pkgrovekit.core.Row?)? = null,
        /** Explicit named key columns switch the write to UPSERT (HEL-119);
         *  null (default) = plain batch insert. Requires target uniqueness on
         *  the keys where the dialect needs it, and usually APPEND mode. */
        val upsertKeys: List<String>? = null,
        /** (batchIndex, rowsWritten) — progress without row values (log-safe). */
        val onProgress: ((Int, Long) -> Unit)? = null,
        /**
         * HEL-161: use the target dialect's native bulk-ingest path (Postgres
         * COPY / DuckDB Appender) when available. Falls back to batched INSERT
         * with a [DataWarning] when the loader refuses the connection/schema,
         * when [upsertKeys] are set (bulk paths cannot upsert), or when the
         * write goes through a caller-supplied [TargetWriter] (e.g. the JDBI
         * facade — its transaction contract must not be bypassed). The bulk
         * path is all-or-nothing regardless of [commitPolicy].
         */
        val useBulkLoad: Boolean = false,
        /**
         * HEL-228: an explicit stateful step applied to the ALREADY-mapped,
         * bind-adapted batch stream just before the write. Deliberately a
         * separate option from [rowTransform] — a pure row map and a stateful
         * aggregation have different memory contracts, and hiding the second
         * inside the first is exactly what this option exists to prevent.
         * The processor declares its own bound; streaming and cancellation are
         * preserved (nothing is materialized beyond what it holds).
         */
        val processor: (() -> BatchProcessor)? = null,
        /**
         * HEL-256: may the read reconfigure the SOURCE connection so its driver
         * actually streams (pgjdbc ignores fetchSize in autocommit and buffers
         * everything)? Default [ConnectionOwnership.LEASED] — taken over and
         * restored, matching what [JdbcBatchWriter] already does to the target
         * connection. Set [ConnectionOwnership.CALLER_OWNED] when the source is
         * inside a transaction you own; the read then refuses rather than
         * silently buffer if the driver cannot stream as handed over.
         *
         * Ignored when source and target are the same physical connection —
         * see [runPositional].
         */
        val sourceConnectionOwnership: ConnectionOwnership = ConnectionOwnership.LEASED,
        /**
         * HEL-224: when source and target are the SAME physical connection
         * (same database), push the copy down to a native server-side
         * INSERT … SELECT instead of round-tripping every row through the
         * client. Opt-in — the row-streaming path is still the default and the
         * only path across different databases.
         *
         * Falls back to streaming with a visible [DataWarning] whenever the
         * push-down cannot faithfully express the transfer: source and target
         * are different connections, the dialect declares no server-side copy
         * ([SqlDialect.supportsServerSideCopy]), a [rowTransform] or [processor]
         * must run client-side, [upsertKeys] are set (scope is INSERT … SELECT
         * only, per the HEL-224 audit), [useBulkLoad] is also requested, or the
         * mapping injects constant columns that have no source expression.
         */
        val useServerSideCopy: Boolean = false,
    )

    /**
     * The target-write seam (HEL-160). Transfer prepares the plan, establishes
     * the table, and streams mapped/bind-adapted batches; HOW those batches are
     * written to the target is pluggable so a JDBI [org.jdbi.v3.core.Handle]
     * transfer can honor caller-owned transactions without unwrapping to the raw
     * connection. The default writer is the JDBC batch writer; the JDBI facade
     * substitutes one that routes through JdbiBatchWriter.
     */
    fun interface TargetWriter {
        fun write(dml: String, batches: Sequence<RowBatch>,
                  options: JdbcBatchWriter.WriteOptions): OperationReport
    }

    /**
     * Run one transfer with NAMED source parameters (`:user_name`) — the
     * recommended form. See [NamedSql] for parsing/missing-name semantics.
     */
    @JvmStatic
    @JvmOverloads
    fun run(source: Connection, sourceSql: String, namedParams: Map<String, Any?>,
            target: Connection, targetDialect: SqlDialect, targetTable: String,
            options: Options = Options()): OperationReport =
        runNamed(source, sourceSql, namedParams, target, targetDialect, targetTable,
                 options, TargetWriter { dml, b, o -> JdbcBatchWriter.write(target, dml, b, o) },
                 bulkTarget = target)

    /**
     * HEL-160: run a transfer whose target write is performed by [writer], with
     * DDL (table establishment) executed on [ddlConnection]. This is the seam the
     * JDBI facade uses to route writes through a caller's [org.jdbi.v3.core.Handle]
     * while reusing the entire read/map/adapt/establish pipeline. Named-parameter
     * form; [ddlConnection] and the connection [writer] targets must be the same
     * physical connection.
     */
    @JvmStatic
    @JvmOverloads
    fun runToWriter(source: Connection, sourceSql: String, namedParams: Map<String, Any?>,
                    ddlConnection: Connection, targetDialect: SqlDialect, targetTable: String,
                    options: Options = Options(), writer: TargetWriter): OperationReport =
        runNamed(source, sourceSql, namedParams, ddlConnection, targetDialect, targetTable,
                 options, writer, bulkTarget = null)

    private fun runNamed(source: Connection, sourceSql: String, namedParams: Map<String, Any?>,
                         ddlConnection: Connection, targetDialect: SqlDialect, targetTable: String,
                         options: Options, writer: TargetWriter,
                         bulkTarget: Connection?): OperationReport {
        val named = com.pkgrove.pkgrovekit.jdbc.NamedSql.parse(sourceSql)
        val preWarnings = mutableListOf<DataWarning>()
        val values = named.bind(namedParams) { preWarnings += it }
        return runPositional(source, named.sql, values, ddlConnection, targetDialect,
                             targetTable, options, preWarnings, writer, bulkTarget)
    }

    /**
     * Positional-parameter form — low-level compatibility; prefer the named
     * overload. The caller owns both connections. Returns an honest
     * [OperationReport]; failures throw with the partial report attached
     * (see [JdbcBatchWriter.BatchWriteException]).
     */
    @JvmStatic
    @JvmOverloads
    fun run(source: Connection, sourceSql: String, sourceParams: List<Any?> = emptyList(),
            target: Connection, targetDialect: SqlDialect, targetTable: String,
            options: Options = Options()): OperationReport =
        runPositional(source, sourceSql, sourceParams, target, targetDialect,
                      targetTable, options, emptyList(),
                      TargetWriter { dml, b, o -> JdbcBatchWriter.write(target, dml, b, o) },
                      bulkTarget = target)

    private fun runPositional(source: Connection, sourceSql: String, sourceParams: List<Any?>,
                              target: Connection, targetDialect: SqlDialect, targetTable: String,
                              options: Options, preWarnings: List<DataWarning>,
                              writer: TargetWriter, bulkTarget: Connection? = null): OperationReport {
        val warnings = preWarnings.toMutableList()
        // HEL-256: a server-side cursor lives INSIDE the read's transaction, so
        // when the target writer holds this very same connection its commit
        // would close the cursor mid-stream. That case cannot stream at any
        // price — the read declares it and warns instead of either breaking at
        // the first commit or overstating the memory bound. Note the source
        // dialect is deliberately NOT passed: `targetDialect` describes the
        // TARGET, and using it to decide the SOURCE's driver behavior would be
        // wrong on every cross-vendor transfer. The read detects the source
        // contract from the source connection's own driver.
        // HEL-224: same-database server-side copy fast path. Eligible only when
        // every gate says the transfer is a pure column-select copy over one
        // connection; otherwise it falls through to the streaming path below,
        // leaving a visible warning so the choice is never silent.
        if (options.useServerSideCopy) {
            when (val refusal = serverSideCopyRefusal(source, target, targetDialect, options)) {
                null -> runServerSideCopy(source, sourceSql, sourceParams, target,
                                          targetDialect, targetTable, options, warnings)
                    ?.let { return it }   // null = constant mapping, warned + falls through
                else -> warnings += DataWarning(
                    code = "SERVER_SIDE_COPY_UNAVAILABLE",
                    message = "server-side copy requested but unavailable ($refusal) — " +
                              "using client-side streaming")
            }
        }
        val readOwnership =
            if (source === target) ConnectionOwnership.SHARED_WITH_WRITER
            else options.sourceConnectionOwnership
        JdbcReader.open(
            source, sourceSql, sourceParams,
            JdbcReader.ReadOptions(fetchSize = options.fetchSize,
                                   cancelToken = options.cancelToken,
                                   valueReader = options.sourceValueReader,
                                   ownership = readOwnership)).use { stream ->
            // 1. resolve the NAMED mapping plan against the discovered source
            //    schema (rejects unknown/duplicate/ambiguous names BEFORE any
            //    write), then adapt the target schema per conversion policy.
            val plan = options.mapping.resolve(stream.schema)
            val effective = targetDialect.adaptSchema(
                plan.targetSchema, options.conversionPolicy) { warnings += it }
            val keptSources = effective.columns.map { c ->
                plan.sources[plan.targetSchema.indexOf(c.name)]
            }

            // HEL-228: a stateful step may RESHAPE rows (grouping/aggregation), so
            // the table we establish and the DML we generate must describe what
            // will actually be WRITTEN — the processor's output schema — not the
            // source's. Built here, before DDL, for exactly that reason.
            val processorInstance = options.processor?.invoke()
            val writeSchema = processorInstance?.outputSchema ?: effective

            // 2. establish the target table per mode
            establishTarget(target, targetDialect, targetTable, writeSchema, options.mode)

            // 3. insert or explicit named-key upsert
            val dml = options.upsertKeys?.let { keys ->
                val missing = keys.filter { !writeSchema.contains(it) }
                require(missing.isEmpty()) {
                    "upsert key columns not present in the target schema: ${missing.joinToString(", ")}"
                }
                targetDialect.upsertSql(targetTable, writeSchema, keys)
                    ?: throw UnsupportedOperationException(
                        "${targetDialect.name} has no upsert support")
            } ?: targetDialect.insertSql(targetTable, writeSchema)

            // 4. stream: map by NAME + bind-adapt each batch, hand to the writer
            val outBatches = stream.batches(options.readBatchSize).map { batch ->
                val sourceRows = options.rowTransform?.let { t ->
                    batch.rows.mapNotNull { r ->
                        t(r)?.also { require(it.schema == batch.schema) {
                            "rowTransform must preserve the source schema" } }
                    }
                } ?: batch.rows
                RowBatch(effective, sourceRows.map { row ->
                    Row(effective, keptSources.mapIndexed { out, src ->
                        val raw = when (src) {
                            is Mapping.Source.FromColumn -> row[src.index]
                            is Mapping.Source.Constant -> src.value
                        }
                        targetDialect.bindValue(raw, effective[out])
                    })
                })
            }
            // HEL-228: stateful step sits between mapping and the writer, so it
            // sees the same bind-adapted rows the writer would have written.
            val processed = processorInstance?.let {
                runProcessor(it, outBatches, options.cancelToken)
            } ?: outBatches
            // HEL-161: opt-in native bulk path — used only when every gate says
            // yes; otherwise fall back to the batched writer with a visible warning.
            if (options.useBulkLoad) {
                val loader = targetDialect.bulkLoader()
                val refusal: String? = when {
                    bulkTarget == null ->
                        "a caller-supplied TargetWriter owns the write path"
                    options.upsertKeys != null ->
                        "upsert keys are set (bulk paths cannot upsert)"
                    loader == null ->
                        "${targetDialect.name} has no bulk loader"
                    else -> when (val s = loader.supports(bulkTarget, targetTable, writeSchema)) {
                        is com.pkgrove.pkgrovekit.jdbc.BulkSupport.Yes -> null
                        is com.pkgrove.pkgrovekit.jdbc.BulkSupport.No -> s.reason
                    }
                }
                if (refusal == null) {
                    val report = loader!!.bulkLoad(
                        bulkTarget!!, targetTable, writeSchema, processed,
                        com.pkgrove.pkgrovekit.jdbc.BulkLoadOptions(
                            cancelToken = options.cancelToken,
                            onProgress = options.onProgress))
                    return report.copy(warnings = warnings + stream.warnings + report.warnings)
                }
                warnings += DataWarning(
                    code = "BULK_LOAD_UNAVAILABLE",
                    message = "bulk load requested but unavailable ($refusal) — using batched INSERT")
            }
            val report = writer.write(
                dml, processed,
                JdbcBatchWriter.WriteOptions(commitPolicy = options.commitPolicy,
                                             cancelToken = options.cancelToken,
                                             onProgress = options.onProgress))
            return report.copy(warnings = warnings + stream.warnings + report.warnings)
        }
    }

    /**
     * HEL-224: the cheap, connection-independent gates for the server-side copy
     * path. Returns a human-readable reason to fall back to streaming, or null
     * when the push-down is worth attempting (the mapping's constant-column gate
     * is resolved later, in [runServerSideCopy], once the schema is known).
     */
    private fun serverSideCopyRefusal(source: Connection, target: Connection,
                                      dialect: SqlDialect, options: Options): String? = when {
        source !== target -> "source and target are different connections/databases"
        !dialect.supportsServerSideCopy -> "${dialect.name} has no server-side copy"
        options.rowTransform != null -> "a row transform must run client-side"
        options.processor != null -> "a stateful processor must run client-side"
        options.upsertKeys != null -> "upsert keys are set (scope is INSERT … SELECT only)"
        options.useBulkLoad -> "bulk load is a client-side ingest path"
        else -> null
    }

    /**
     * HEL-224: run the transfer as one native INSERT … SELECT on the shared
     * connection. Returns the typed [OperationReport], or null when the mapping
     * injects a constant column (no source expression to push down) — in which
     * case a warning is appended and the caller falls back to streaming.
     *
     * The source query executes only for METADATA here: the reader is opened to
     * read its schema and is never iterated, so no rows cross the client. It is
     * closed before the copy runs so its cursor cannot collide with the
     * INSERT … SELECT on the same connection. The copy re-runs the query
     * entirely server-side.
     */
    private fun runServerSideCopy(source: Connection, sourceSql: String,
                                  sourceParams: List<Any?>, target: Connection,
                                  dialect: SqlDialect, targetTable: String,
                                  options: Options,
                                  warnings: MutableList<DataWarning>): OperationReport? {
        val start = System.nanoTime()
        lateinit var effective: Schema
        var sourceCols: List<String>? = null
        var readWarnings: List<DataWarning> = emptyList()
        JdbcReader.open(
            source, sourceSql, sourceParams,
            JdbcReader.ReadOptions(fetchSize = 1, cancelToken = options.cancelToken,
                                   valueReader = options.sourceValueReader,
                                   ownership = ConnectionOwnership.SHARED_WITH_WRITER)).use { stream ->
            val plan = options.mapping.resolve(stream.schema)
            effective = dialect.adaptSchema(plan.targetSchema, options.conversionPolicy) { warnings += it }
            val kept = effective.columns.map { c ->
                plan.sources[plan.targetSchema.indexOf(c.name)]
            }
            // A constant target column has no column to SELECT from the source —
            // it cannot be pushed into INSERT … SELECT. Fall back rather than
            // silently drop it.
            if (kept.none { it is Mapping.Source.Constant }) {
                sourceCols = kept.map { stream.schema[(it as Mapping.Source.FromColumn).index].name }
            }
            readWarnings = stream.warnings
        }
        val srcCols = sourceCols ?: run {
            warnings += DataWarning(
                code = "SERVER_SIDE_COPY_UNAVAILABLE",
                message = "server-side copy requested but the mapping adds constant columns " +
                          "with no source expression — using client-side streaming")
            return null
        }
        val copySql = dialect.serverSideCopySql(
            targetTable, effective.columns.map { it.name }, srcCols, sourceSql) ?: run {
            warnings += DataWarning(
                code = "SERVER_SIDE_COPY_UNAVAILABLE",
                message = "${dialect.name} produced no server-side copy SQL — using client-side streaming")
            return null
        }

        // Establish the target from the discovered write schema, then run the
        // copy as a single atomic server-side statement.
        establishTarget(target, dialect, targetTable, effective, options.mode)
        val previousAutoCommit = target.autoCommit
        val rows: Long = try {
            target.autoCommit = false
            val n = target.prepareStatement(copySql).use { st ->
                sourceParams.forEachIndexed { i, p -> st.setObject(i + 1, p) }
                options.cancelToken.throwIfCancelled()
                st.executeUpdate().toLong()
            }
            target.commit()
            n
        } catch (e: Exception) {
            runCatching { target.rollback() }
            throw e
        } finally {
            runCatching { target.autoCommit = previousAutoCommit }
        }
        return OperationReport(
            rowsAffected = rows, batches = 1,
            elapsedMillis = (System.nanoTime() - start) / 1_000_000,
            completed = true, warnings = warnings + readWarnings)
    }

    private fun establishTarget(target: Connection, dialect: SqlDialect,
                                table: String, schema: Schema, mode: SqlDialect.TargetMode) {
        when (mode) {
            SqlDialect.TargetMode.APPEND -> return  // table must already exist
            SqlDialect.TargetMode.FAIL_IF_EXISTS, SqlDialect.TargetMode.CREATE,
            SqlDialect.TargetMode.CREATE_OR_REPLACE, SqlDialect.TargetMode.TEMPORARY -> {
                target.createStatement().use { st ->
                    st.execute(dialect.createTableDdl(table, schema, mode))
                }
            }
        }
    }
}
