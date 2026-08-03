package com.pkgrove.pkgrovekit.duckdb

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.SqlDialect

/**
 * DuckDB as a transfer TARGET (and, with the default [com.pkgrove.pkgrovekit.jdbc.ValueReader],
 * a source). Types come from the common model's kind/precision/scale — vendor
 * names on the source side are never trusted directly.
 */
object DuckDbDialect : SqlDialect {

    override val name: String = "duckdb"

    /** DuckDB's JDBC driver does not implement java.sql savepoints — the
     *  capability report keeps SavepointPerBatch failing EARLY here. */
    override val supportsSavepoints: Boolean = false

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
            // HEL-168: no declared precision. Defaulting to DOUBLE silently ROUNDS
            // large integers — e.g. DuckDB reports BIGINT with no precision, and a
            // Long.MAX value stored in a DOUBLE loses its low digits. When the
            // source type name identifies an integer type, preserve integer-ness;
            // only a genuine float type (DOUBLE/REAL/FLOAT) falls back to DOUBLE.
            p == null -> integerTypeFromName(c.typeName) ?: "DOUBLE"
            s > 0 -> "DECIMAL(${p.coerceAtMost(38)},${s.coerceAtMost(37)})"
            p <= 4 -> "SMALLINT"
            p <= 9 -> "INTEGER"
            p <= 18 -> "BIGINT"
            else -> "DECIMAL(${p.coerceAtMost(38)},0)"
        }
    }

    /** Integer target type inferred from a source type NAME when precision is
     *  unavailable (the only signal left). Null = not a known integer type, so
     *  the caller keeps the DOUBLE fallback. Order matters: BIGINT/SMALLINT are
     *  checked before the bare "INT" substring. */
    private fun integerTypeFromName(typeName: String): String? {
        val t = typeName.uppercase()
        return when {
            "HUGEINT" in t -> "HUGEINT"
            "BIGINT" in t || "INT8" in t || "INT64" in t -> "BIGINT"
            "SMALLINT" in t || "INT2" in t -> "SMALLINT"
            "TINYINT" in t -> "TINYINT"
            "INT" in t -> "INTEGER"
            else -> null   // DOUBLE / REAL / FLOAT / unqualified NUMBER -> DOUBLE
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
    override fun upsertSql(table: String, schema: com.pkgrove.pkgrovekit.core.Schema, keyColumns: List<String>): String {
        val cols = schema.columns.joinToString(", ") { quoteIdent(it.name, "column") }
        val marks = schema.columns.joinToString(", ") { "?" }
        val keys = keyColumns.joinToString(", ") { quoteIdent(schema[it].name, "key column") }
        val keyNorm = keyColumns.map { it.lowercase() }.toSet()
        val nonKey = schema.columns.filter { it.name.lowercase() !in keyNorm }
        // A table whose every column is a conflict key has nothing to update on
        // conflict — `DO UPDATE SET <empty>` is invalid SQL, so degrade to the
        // correct idempotent form: DO NOTHING (parity with PostgresDialect).
        val conflict = if (nonKey.isEmpty()) "DO NOTHING"
            else "DO UPDATE SET " + nonKey.joinToString(", ") {
                val q = quoteIdent(it.name, "column")
                "$q = EXCLUDED.$q"
            }
        return "INSERT INTO ${quoteIdent(table, "table")} ($cols) VALUES ($marks) " +
               "ON CONFLICT ($keys) $conflict"
    }

    /** DuckDB's JDBC driver refuses several java.time binds; java.sql works for
     *  most. TIME is the exception: java.sql.Time is SECOND-precision and gets
     *  local-timezone-shifted, so a LocalTime bound through it loses its fraction
     *  and jumps hours (HEL-168). DuckDB parses an ISO time STRING losslessly, so
     *  LocalTime is bound as its ISO text instead. */
    override fun bindValue(value: Any?, column: Column): Any? = when (value) {
        is java.time.LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is java.time.LocalDate -> java.sql.Date.valueOf(value)
        is java.time.LocalTime -> value.toString()   // 'HH:mm:ss[.SSSSSSSSS]', lossless
        is java.time.OffsetDateTime -> java.sql.Timestamp.from(value.toInstant())
        else -> value
    }
}
