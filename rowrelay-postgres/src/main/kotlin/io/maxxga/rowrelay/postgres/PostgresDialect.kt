package io.maxxga.rowrelay.postgres

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.Schema
import io.maxxga.rowrelay.core.ValueKind
import io.maxxga.rowrelay.jdbc.SqlDialect

/**
 * PostgreSQL as a first-class relational adapter (HEL-127): source (the
 * standard [io.maxxga.rowrelay.jdbc.ValueReader] handles the pgjdbc driver's
 * JDK-typed values) and target (this dialect). Same contract as Oracle/DuckDB
 * — no direction-specific artifacts, driver stays consumer-controlled.
 */
object PostgresDialect : SqlDialect {

    override val name: String = "postgres"

    /** Postgres savepoints are first-class. */
    override val supportsSavepoints: Boolean = true

    /** Postgres folds unquoted identifiers to LOWERCASE — the mirror image of
     *  Oracle's rule, same deterministic policy: fold-then-quote so generated
     *  SQL matches objects created without quotes. */
    override fun identifierCase(name: String): String = name.lowercase()

    override fun typeFor(column: Column): String? = when (column.kind) {
        ValueKind.TEXT -> {
            val p = column.precision
            if (p == null || p > 10_485_760) "TEXT" else "VARCHAR($p)"
        }
        ValueKind.BOOLEAN -> "BOOLEAN"
        ValueKind.BINARY -> "BYTEA"
        ValueKind.NUMERIC -> {
            val p = column.precision
            val s = column.scale ?: 0
            when {
                p == null -> "DOUBLE PRECISION"
                s > 0 -> "NUMERIC(${p.coerceAtMost(1000)},${s.coerceAtMost(1000)})"
                p <= 4 -> "SMALLINT"
                p <= 9 -> "INTEGER"
                p <= 18 -> "BIGINT"
                else -> "NUMERIC(${p.coerceAtMost(1000)})"
            }
        }
        ValueKind.TEMPORAL -> {
            val t = column.typeName.uppercase()
            when {
                column.timeZoned == true -> "TIMESTAMPTZ"
                t == "DATE" -> "DATE"
                t.startsWith("TIME") && !t.startsWith("TIMESTAMP") -> "TIME"
                else -> "TIMESTAMP"
            }
        }
        // HEL-127: uuid / json / jsonb / arrays arrive as JDBC OTHER or ARRAY
        // (ValueKind.OTHER). Recreate them faithfully on a Postgres target from
        // the driver's own type name rather than rejecting the column.
        ValueKind.OTHER -> postgresTypeName(column)
    }

    /** DDL for a Postgres OTHER/ARRAY column, from its pgjdbc type name; null if
     *  genuinely unmappable (the transfer layer then applies ConversionPolicy). */
    private fun postgresTypeName(column: Column): String? {
        val t = column.typeName.lowercase()
        return when {
            t == "uuid" -> "UUID"
            t == "json" -> "JSON"
            t == "jsonb" -> "JSONB"
            // pgjdbc names an array by its element type prefixed with '_'
            // ("_int4", "_text", ...). Rebuild the canonical "<elem>[]" form,
            // which Postgres accepts as a column type.
            t.startsWith("_") && t.length > 1 -> t.substring(1) + "[]"
            // already an explicit array spelling ("int4[]", "text[]")
            t.endsWith("[]") -> t
            else -> null
        }
    }

    /** True when [column] is a Postgres uuid/json/jsonb/array — its value is
     *  carried as text by [PostgresValueReader] and needs a typed bind. */
    private fun arrayCast(column: Column): String? {
        val t = column.typeName.lowercase()
        return when {
            t.startsWith("_") && t.length > 1 -> t.substring(1) + "[]"
            t.endsWith("[]") -> t
            else -> null
        }
    }

    /** ON CONFLICT upsert — requires a unique/PK constraint on [keyColumns]. */
    override fun upsertSql(table: String, schema: Schema, keyColumns: List<String>): String {
        val cols = schema.columns.joinToString(", ") { quoteIdent(it.name, "column") }
        val marks = schema.columns.joinToString(", ") { "?" }
        val keys = keyColumns.joinToString(", ") { quoteIdent(schema[it].name, "key column") }
        val keyNorm = keyColumns.map { it.lowercase() }.toSet()
        val nonKey = schema.columns.filter { it.name.lowercase() !in keyNorm }
        // A table whose every column is a conflict key has nothing to update on
        // conflict — `DO UPDATE SET <empty>` is invalid SQL, so degrade to the
        // correct idempotent form: DO NOTHING (the row already exists as-is).
        val conflict = if (nonKey.isEmpty()) "DO NOTHING"
            else "DO UPDATE SET " + nonKey.joinToString(", ") {
                val q = quoteIdent(it.name, "column")
                "$q = EXCLUDED.$q"
            }
        return "INSERT INTO ${quoteIdent(table, "table")} ($cols) VALUES ($marks) " +
               "ON CONFLICT ($keys) $conflict"
    }

    override fun bindValue(value: Any?, column: Column): Any? {
        // HEL-127: reconstruct uuid/json/jsonb/array from the text the
        // PostgresValueReader carried, so a String round-trips into the exact
        // Postgres type. A genuine UUID/PGobject/Array value passes through.
        if (value is String) {
            val t = column.typeName.lowercase()
            when {
                t == "uuid" -> return java.util.UUID.fromString(value)
                t == "json" || t == "jsonb" -> return pgObject(t, value)
                else -> arrayCast(column)?.let { return pgObject(it, value) }
            }
        }
        return when (value) {
            is java.time.LocalDateTime -> java.sql.Timestamp.valueOf(value)
            is java.time.LocalDate -> java.sql.Date.valueOf(value)
            is java.time.LocalTime -> java.sql.Time.valueOf(value)
            is java.time.OffsetDateTime -> java.sql.Timestamp.from(value.toInstant())
            else -> value
        }
    }

    /** A typed pgjdbc value: the driver sends [text] with an explicit `::[type]`
     *  cast, so json/jsonb/array literals land in the right column type. */
    private fun pgObject(type: String, text: String): Any =
        org.postgresql.util.PGobject().apply { this.type = type; this.value = text }
}
