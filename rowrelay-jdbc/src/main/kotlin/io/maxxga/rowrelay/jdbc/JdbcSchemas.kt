package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.Schema
import io.maxxga.rowrelay.core.ValueKind
import java.sql.ResultSetMetaData
import java.sql.Types

/**
 * ResultSet metadata → the common [Schema] (no DTOs — HEL-120 capability 2).
 * The JDBC type code decides [ValueKind]; the vendor type NAME rides along
 * informationally. Precision/scale are surfaced only where meaningful.
 */
object JdbcSchemas {

    fun fromMetaData(meta: ResultSetMetaData): Schema {
        val cols = (1..meta.columnCount).map { i ->
            val jdbcType = meta.getColumnType(i)
            Column(
                name = meta.getColumnLabel(i),
                kind = kindOf(jdbcType),
                typeName = meta.getColumnTypeName(i) ?: "UNKNOWN",
                nullable = when (meta.isNullable(i)) {
                    ResultSetMetaData.columnNoNulls -> false
                    ResultSetMetaData.columnNullable -> true
                    else -> null
                },
                precision = meta.getPrecision(i).takeIf { it > 0 },
                scale = meta.getScale(i).takeIf { jdbcType.isNumericType() },
                timeZoned = when (jdbcType) {
                    Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> true
                    Types.TIMESTAMP, Types.TIME, Types.DATE -> false
                    else -> null
                },
            )
        }
        return Schema(cols)
    }

    fun kindOf(jdbcType: Int): ValueKind = when (jdbcType) {
        Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
        Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
        Types.CLOB, Types.NCLOB -> ValueKind.TEXT

        Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
        Types.FLOAT, Types.REAL, Types.DOUBLE,
        Types.NUMERIC, Types.DECIMAL -> ValueKind.NUMERIC

        Types.BIT, Types.BOOLEAN -> ValueKind.BOOLEAN

        Types.DATE, Types.TIME, Types.TIMESTAMP,
        Types.TIME_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE -> ValueKind.TEMPORAL

        Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ValueKind.BINARY

        else -> ValueKind.OTHER
    }

    private fun Int.isNumericType(): Boolean =
        this == Types.NUMERIC || this == Types.DECIMAL
}
