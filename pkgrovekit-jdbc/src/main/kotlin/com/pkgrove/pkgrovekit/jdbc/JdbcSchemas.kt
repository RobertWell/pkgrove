package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
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
            val kind = kindOf(jdbcType)
            Column(
                name = meta.getColumnLabel(i),
                kind = kind,
                // TEMPORAL columns take the JDBC-STANDARD name derived from the
                // type CODE, not the vendor string: Oracle names its
                // datetime-valued DATE columns "DATE" while reporting code 93
                // (TIMESTAMP) — trusting the vendor name made targets create
                // date-only columns and silently drop the time component
                // (live-Oracle-proven). Other kinds keep the vendor name as
                // informational context.
                typeName = if (kind == ValueKind.TEMPORAL) temporalName(jdbcType)
                           else meta.getColumnTypeName(i) ?: "UNKNOWN",
                nullable = when (meta.isNullable(i)) {
                    ResultSetMetaData.columnNoNulls -> false
                    ResultSetMetaData.columnNullable -> true
                    else -> null
                },
                precision = meta.getPrecision(i).takeIf { it > 0 },
                scale = meta.getScale(i).takeIf { jdbcType.isNumericType() },
                timeZoned = when (jdbcType) {
                    Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE,
                    ORACLE_TIMESTAMPTZ, ORACLE_TIMESTAMPLTZ -> true
                    Types.TIMESTAMP, Types.TIME, Types.DATE -> false
                    else -> null
                },
            )
        }
        return Schema(cols)
    }

    // Oracle's driver reports these VENDOR codes instead of the JDBC-standard
    // constants (proven by the live-Oracle integration suite) — without them a
    // TIMESTAMP WITH TIME ZONE column classifies as OTHER and gets rejected.
    private const val ORACLE_TIMESTAMPTZ = -101   // oracle.jdbc.OracleTypes.TIMESTAMPTZ
    private const val ORACLE_TIMESTAMPLTZ = -102  // oracle.jdbc.OracleTypes.TIMESTAMPLTZ
    private const val ORACLE_BINARY_FLOAT = 100   // oracle.jdbc.OracleTypes.BINARY_FLOAT
    private const val ORACLE_BINARY_DOUBLE = 101  // oracle.jdbc.OracleTypes.BINARY_DOUBLE

    fun kindOf(jdbcType: Int): ValueKind = when (jdbcType) {
        ORACLE_TIMESTAMPTZ, ORACLE_TIMESTAMPLTZ -> ValueKind.TEMPORAL
        ORACLE_BINARY_FLOAT, ORACLE_BINARY_DOUBLE -> ValueKind.NUMERIC
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

    private fun temporalName(jdbcType: Int): String = when (jdbcType) {
        Types.DATE -> "DATE"
        Types.TIME -> "TIME"
        Types.TIME_WITH_TIMEZONE -> "TIME WITH TIME ZONE"
        Types.TIMESTAMP_WITH_TIMEZONE, ORACLE_TIMESTAMPTZ, ORACLE_TIMESTAMPLTZ ->
            "TIMESTAMP WITH TIME ZONE"
        else -> "TIMESTAMP"
    }
}
