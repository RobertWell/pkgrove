package io.maxxga.rowrelay.duckdb

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.ValueKind
import io.maxxga.rowrelay.jdbc.SqlDialect

/**
 * DuckDB as a transfer TARGET (and, with the default [io.maxxga.rowrelay.jdbc.ValueReader],
 * a source). Types come from the common model's kind/precision/scale — vendor
 * names on the source side are never trusted directly.
 */
object DuckDbDialect : SqlDialect {

    override val name: String = "duckdb"

    override fun typeFor(column: Column): String? = when (column.kind) {
        ValueKind.TEXT -> "VARCHAR"
        ValueKind.BOOLEAN -> "BOOLEAN"
        ValueKind.BINARY -> "BLOB"
        ValueKind.NUMERIC -> numericType(column)
        ValueKind.TEMPORAL -> temporalType(column)
        ValueKind.OTHER -> null   // policy decides (never a silent guess)
    }

    private fun numericType(c: Column): String {
        val p = c.precision
        val s = c.scale ?: 0
        return when {
            // no declared precision: the widest safe general numeric
            p == null -> "DOUBLE"
            s > 0 -> "DECIMAL(${p.coerceAtMost(38)},${s.coerceAtMost(37)})"
            p <= 4 -> "SMALLINT"
            p <= 9 -> "INTEGER"
            p <= 18 -> "BIGINT"
            else -> "DECIMAL(${p.coerceAtMost(38)},0)"
        }
    }

    private fun temporalType(c: Column): String {
        val t = c.typeName.uppercase()
        return when {
            c.timeZoned == true -> "TIMESTAMP WITH TIME ZONE"
            t == "DATE" -> "DATE"
            t.startsWith("TIME") && !t.startsWith("TIMESTAMP") -> "TIME"
            else -> "TIMESTAMP"
        }
    }

    /** ON CONFLICT upsert — requires a PK/unique constraint on [keyColumns]. */
    override fun upsertSql(table: String, schema: io.maxxga.rowrelay.core.Schema, keyColumns: List<String>): String {
        val cols = schema.columns.joinToString(", ") { quoteIdent(it.name, "column") }
        val marks = schema.columns.joinToString(", ") { "?" }
        val keys = keyColumns.joinToString(", ") { quoteIdent(schema[it].name, "key column") }
        val keyNorm = keyColumns.map { it.lowercase() }.toSet()
        val updates = schema.columns.filter { it.name.lowercase() !in keyNorm }
            .joinToString(", ") {
                val q = quoteIdent(it.name, "column")
                "$q = EXCLUDED.$q"
            }
        return "INSERT INTO ${quoteIdent(table, "table")} ($cols) VALUES ($marks) " +
               "ON CONFLICT ($keys) DO UPDATE SET $updates"
    }

    /** DuckDB's JDBC driver refuses some java.time binds; java.sql works. */
    override fun bindValue(value: Any?, column: Column): Any? = when (value) {
        is java.time.LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is java.time.LocalDate -> java.sql.Date.valueOf(value)
        is java.time.LocalTime -> java.sql.Time.valueOf(value)
        is java.time.OffsetDateTime -> java.sql.Timestamp.from(value.toInstant())
        else -> value
    }
}
