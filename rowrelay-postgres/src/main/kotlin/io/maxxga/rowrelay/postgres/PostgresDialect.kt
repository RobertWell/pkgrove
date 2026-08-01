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
        ValueKind.OTHER -> null
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

    override fun bindValue(value: Any?, column: Column): Any? = when (value) {
        is java.time.LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is java.time.LocalDate -> java.sql.Date.valueOf(value)
        is java.time.LocalTime -> java.sql.Time.valueOf(value)
        is java.time.OffsetDateTime -> java.sql.Timestamp.from(value.toInstant())
        else -> value
    }
}
