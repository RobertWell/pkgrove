package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.ConversionPolicy
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.ValueReader
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
        val rowTransform: ((com.pkgrove.pkgrovekit.core.Row) -> com.pkgrove.pkgrovekit.core.Row?)? = null,
        /** Explicit named key columns switch the write to UPSERT (HEL-119);
         *  null (default) = plain batch insert. Requires target uniqueness on
         *  the keys where the dialect needs it, and usually APPEND mode. */
        val upsertKeys: List<String>? = null,
        /** (batchIndex, rowsWritten) — progress without row values (log-safe). */
        val onProgress: ((Int, Long) -> Unit)? = null,
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
                 options, TargetWriter { dml, b, o -> JdbcBatchWriter.write(target, dml, b, o) })

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
                 options, writer)

    private fun runNamed(source: Connection, sourceSql: String, namedParams: Map<String, Any?>,
                         ddlConnection: Connection, targetDialect: SqlDialect, targetTable: String,
                         options: Options, writer: TargetWriter): OperationReport {
        val named = com.pkgrove.pkgrovekit.jdbc.NamedSql.parse(sourceSql)
        val preWarnings = mutableListOf<DataWarning>()
        val values = named.bind(namedParams) { preWarnings += it }
        return runPositional(source, named.sql, values, ddlConnection, targetDialect,
                             targetTable, options, preWarnings, writer)
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
                      TargetWriter { dml, b, o -> JdbcBatchWriter.write(target, dml, b, o) })

    private fun runPositional(source: Connection, sourceSql: String, sourceParams: List<Any?>,
                              target: Connection, targetDialect: SqlDialect, targetTable: String,
                              options: Options, preWarnings: List<DataWarning>,
                              writer: TargetWriter): OperationReport {
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
            val report = writer.write(
                dml, outBatches,
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
