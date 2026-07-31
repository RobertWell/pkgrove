package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.CancelToken
import io.maxxga.rowrelay.core.ConversionPolicy
import io.maxxga.rowrelay.core.DataWarning
import io.maxxga.rowrelay.core.OperationReport
import io.maxxga.rowrelay.core.Row
import io.maxxga.rowrelay.core.RowBatch
import io.maxxga.rowrelay.core.Schema
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbc.SqlDialect
import io.maxxga.rowrelay.jdbc.ValueReader
import java.sql.Connection

/**
 * SQL-in/data-out bidirectional transfer (HEL-120 capability 4; HEL-119).
 * Source: arbitrary parameterized read SQL. Target: a table established per
 * [SqlDialect.TargetMode], written with the batch writer's commit policies.
 * Bounded memory by construction — one read batch in flight at a time.
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
        val rowTransform: ((io.maxxga.rowrelay.core.Row) -> io.maxxga.rowrelay.core.Row?)? = null,
        /** Explicit named key columns switch the write to UPSERT (HEL-119);
         *  null (default) = plain batch insert. Requires target uniqueness on
         *  the keys where the dialect needs it, and usually APPEND mode. */
        val upsertKeys: List<String>? = null,
        /** (batchIndex, rowsWritten) — progress without row values (log-safe). */
        val onProgress: ((Int, Long) -> Unit)? = null,
    )

    /**
     * Run one transfer with NAMED source parameters (`:user_name`) — the
     * recommended form. See [NamedSql] for parsing/missing-name semantics.
     */
    @JvmStatic
    @JvmOverloads
    fun run(source: Connection, sourceSql: String, namedParams: Map<String, Any?>,
            target: Connection, targetDialect: SqlDialect, targetTable: String,
            options: Options = Options()): OperationReport {
        val named = io.maxxga.rowrelay.jdbc.NamedSql.parse(sourceSql)
        val preWarnings = mutableListOf<DataWarning>()
        val values = named.bind(namedParams) { preWarnings += it }
        return runPositional(source, named.sql, values, target, targetDialect,
                             targetTable, options, preWarnings)
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
                      targetTable, options, emptyList())

    private fun runPositional(source: Connection, sourceSql: String, sourceParams: List<Any?>,
                              target: Connection, targetDialect: SqlDialect, targetTable: String,
                              options: Options, preWarnings: List<DataWarning>): OperationReport {
        val warnings = preWarnings.toMutableList()
        JdbcReader.open(
            source, sourceSql, sourceParams,
            JdbcReader.ReadOptions(fetchSize = options.fetchSize,
                                   cancelToken = options.cancelToken,
                                   valueReader = options.sourceValueReader)).use { stream ->
            // 1. resolve the NAMED mapping plan against the discovered source
            //    schema (rejects unknown/duplicate/ambiguous names BEFORE any
            //    write), then adapt the target schema per conversion policy.
            val plan = options.mapping.resolve(stream.schema)
            val effective = targetDialect.adaptSchema(
                plan.targetSchema, options.conversionPolicy) { warnings += it }
            val keptSources = effective.columns.map { c ->
                plan.sources[plan.targetSchema.indexOf(c.name)]
            }

            // 2. establish the target table per mode
            establishTarget(target, targetDialect, targetTable, effective, options.mode)

            // 3. insert or explicit named-key upsert
            val dml = options.upsertKeys?.let { keys ->
                val missing = keys.filter { !effective.contains(it) }
                require(missing.isEmpty()) {
                    "upsert key columns not present in the target schema: ${missing.joinToString(", ")}"
                }
                targetDialect.upsertSql(targetTable, effective, keys)
                    ?: throw UnsupportedOperationException(
                        "${targetDialect.name} has no upsert support")
            } ?: targetDialect.insertSql(targetTable, effective)

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
            val report = JdbcBatchWriter.write(
                target, dml, outBatches,
                JdbcBatchWriter.WriteOptions(commitPolicy = options.commitPolicy,
                                             cancelToken = options.cancelToken,
                                             onProgress = options.onProgress))
            return report.copy(warnings = warnings + stream.warnings + report.warnings)
        }
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
