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
        /** (batchIndex, rowsWritten) — progress without row values (log-safe). */
        val onProgress: ((Int, Long) -> Unit)? = null,
    )

    /**
     * Run one transfer. The caller owns both connections. Returns an honest
     * [OperationReport]; failures throw with the partial report attached
     * (see [JdbcBatchWriter.BatchWriteException]).
     */
    @JvmStatic
    @JvmOverloads
    fun run(source: Connection, sourceSql: String, sourceParams: List<Any?> = emptyList(),
            target: Connection, targetDialect: SqlDialect, targetTable: String,
            options: Options = Options()): OperationReport {
        val warnings = mutableListOf<DataWarning>()
        JdbcReader.open(
            source, sourceSql, sourceParams,
            JdbcReader.ReadOptions(fetchSize = options.fetchSize,
                                   cancelToken = options.cancelToken,
                                   valueReader = options.sourceValueReader)).use { stream ->
            // 1. schema: inferred from the source, adapted per policy
            val effective = targetDialect.adaptSchema(
                stream.schema, options.conversionPolicy) { warnings += it }
            val keptIndexes = effective.columns.map { stream.schema.indexOf(it.name) }

            // 2. establish the target table per mode
            establishTarget(target, targetDialect, targetTable, effective, options.mode)

            // 3. stream: project + bind-adapt each batch, hand to the writer
            val insert = targetDialect.insertSql(targetTable, effective)
            val outBatches = stream.batches(options.readBatchSize).map { batch ->
                RowBatch(effective, batch.rows.map { row ->
                    Row(effective, keptIndexes.mapIndexed { out, src ->
                        targetDialect.bindValue(row[src], effective[out])
                    })
                })
            }
            val report = JdbcBatchWriter.write(
                target, insert, outBatches,
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
