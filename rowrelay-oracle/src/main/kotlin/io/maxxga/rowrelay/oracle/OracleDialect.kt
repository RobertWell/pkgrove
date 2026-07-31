package io.maxxga.rowrelay.oracle

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.DataWarning
import io.maxxga.rowrelay.core.ValueKind
import io.maxxga.rowrelay.jdbc.SqlDialect
import io.maxxga.rowrelay.jdbc.ValueReader
import java.sql.ResultSet

/**
 * Oracle as source and target (HEL-120 capability 6). The type table follows
 * the AuditPatchX production experience: NUMBER carries precision/scale (or
 * neither, meaning unconstrained), strings are VARCHAR2 with CLOB overflow,
 * temporals map by time-zone-ness, binary is RAW/BLOB by size.
 */
object OracleDialect : SqlDialect {

    override val name: String = "oracle"

    /** VARCHAR2 byte budget before we fall to CLOB. */
    private const val MAX_VARCHAR2 = 4000

    override fun typeFor(column: Column): String? = when (column.kind) {
        ValueKind.TEXT -> {
            val p = column.precision
            if (p == null || p > MAX_VARCHAR2) "CLOB" else "VARCHAR2($p CHAR)"
        }
        // Oracle has no BOOLEAN column type before 23ai; NUMBER(1) is the
        // long-established convention and round-trips 0/1 faithfully.
        ValueKind.BOOLEAN -> "NUMBER(1)"
        ValueKind.BINARY -> {
            val p = column.precision
            if (p != null && p <= 2000) "RAW($p)" else "BLOB"
        }
        ValueKind.NUMERIC -> {
            val p = column.precision
            val s = column.scale ?: 0
            when {
                p == null -> "NUMBER"
                s > 0 -> "NUMBER(${p.coerceAtMost(38)},${s.coerceAtMost(127)})"
                else -> "NUMBER(${p.coerceAtMost(38)})"
            }
        }
        ValueKind.TEMPORAL -> {
            val t = column.typeName.uppercase()
            when {
                column.timeZoned == true -> "TIMESTAMP WITH TIME ZONE"
                t == "DATE" -> "DATE"
                else -> "TIMESTAMP"
            }
        }
        ValueKind.OTHER -> null
    }

    override fun bindValue(value: Any?, column: Column): Any? = when (value) {
        // ojdbc handles java.time directly since 21c drivers, but Timestamp is
        // the universally safe form (matches the AuditPatchX binding fix).
        is java.time.LocalDateTime -> java.sql.Timestamp.valueOf(value)
        is java.time.LocalDate -> java.sql.Timestamp.valueOf(value.atStartOfDay())
        is Boolean -> if (value) 1 else 0    // NUMBER(1) convention
        else -> value
    }
}

/**
 * Source-side normalization for Oracle driver classes — extends the standard
 * reader so oracle.sql.* never escapes into the common model (extracted from
 * AuditPatchX's normalizeValueForJson).
 */
class OracleValueReader : ValueReader.Default() {
    override fun normalize(v: Any, column: Column, warn: (DataWarning) -> Unit): Any? =
        when (v) {
            is oracle.sql.TIMESTAMP -> v.timestampValue().toLocalDateTime()
            is oracle.sql.TIMESTAMPTZ -> v.toOffsetDateTime()
            is oracle.sql.DATE -> v.timestampValue().toLocalDateTime()
            is oracle.sql.TIMESTAMPLTZ -> {
                // no session-free faithful conversion exists; carry as string
                // WITH a warning rather than guess a zone (never silent).
                warn(DataWarning("timestampltz-stringified",
                                 "TIMESTAMP WITH LOCAL TIME ZONE carried as string", column.name))
                v.toString()
            }
            else -> super.normalize(v, column, warn)
        }

    override fun read(rs: ResultSet, index: Int, column: Column,
                      warn: (DataWarning) -> Unit): Any? = super.read(rs, index, column, warn)
}
