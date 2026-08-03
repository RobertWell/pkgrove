package com.pkgrove.pkgrovekit.quarkus

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.SqlDialect

/**
 * Generic ANSI-SQL target dialect (HEL-172) for engines that have no dedicated
 * PkgroveKit adapter module — e.g. H2 in the Quarkus integration tests, or any
 * reasonably standards-compliant JDBC target. Selected in configuration via
 * `pkgrovekit.databases.<key>.dialect=ansi`.
 *
 * Deliberately conservative:
 *  - only kinds with a faithful ANSI spelling map ([ValueKind.OTHER] returns
 *    null so the transfer layer applies the caller's ConversionPolicy — never
 *    a silent guess);
 *  - no native upsert is claimed (`upsertSql` stays null → an `upsertBy`
 *    request is rejected loudly rather than emulated);
 *  - savepoint support is NOT claimed (conservative default), even though many
 *    ANSI engines have it — a dedicated dialect module is the place to opt in;
 *  - identifier case is preserved (no vendor folding rule can be assumed).
 */
object AnsiDialect : SqlDialect {

    override val name: String = "ansi"

    override fun typeFor(column: Column): String? = when (column.kind) {
        ValueKind.TEXT -> {
            val p = column.precision
            if (p != null && p > 0 && p <= 1_000_000) "VARCHAR($p)" else "VARCHAR(1000000)"
        }
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
            p == null -> "DOUBLE PRECISION"
            s > 0 -> "NUMERIC(${p.coerceAtMost(38)},${s.coerceAtMost(37)})"
            p <= 4 -> "SMALLINT"
            p <= 9 -> "INTEGER"
            p <= 18 -> "BIGINT"
            else -> "NUMERIC(${p.coerceAtMost(38)})"
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

    /** Bind java.time values through their java.sql bridges — maximally
     *  portable across ANSI-ish drivers whose setObject support varies. */
    override fun bindValue(value: Any?, column: Column): Any? = when (value) {
        is java.time.LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is java.time.LocalDate -> java.sql.Date.valueOf(value)
        is java.time.LocalTime -> java.sql.Time.valueOf(value)
        is java.time.OffsetDateTime -> java.sql.Timestamp.from(value.toInstant())
        else -> value
    }
}
