package com.pkgrove.pkgrovekit.oracle

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.StreamingContract
import com.pkgrove.pkgrovekit.jdbc.ValueReader
import java.sql.ResultSet

/**
 * Oracle as source and target (HEL-120 capability 6). The type table follows
 * the AuditPatchX production experience: NUMBER carries precision/scale (or
 * neither, meaning unconstrained), strings are VARCHAR2 with CLOB overflow,
 * temporals map by time-zone-ness, binary is RAW/BLOB by size.
 */
object OracleDialect : SqlDialect {

    override val name: String = "oracle"

    /** Oracle savepoints are first-class (HEL-126 SavepointPerBatch). */
    override val supportsSavepoints: Boolean = true

    /** HEL-256: ojdbc applies `Statement.fetchSize` directly (it overrides the
     *  driver's default row-prefetch of 10) and needs no particular transaction
     *  state — stated explicitly rather than inherited, because "this dialect
     *  was audited and needs nothing" is the useful fact here. */
    override val streaming: StreamingContract = StreamingContract.HONOURS_FETCH_SIZE

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

    /** Oracle folds unquoted identifiers to UPPERCASE; matching that before
     *  quoting is the deterministic policy that makes generated DDL/DML hit
     *  objects created without quotes (the overwhelmingly common case). */
    override fun identifierCase(name: String): String = name.uppercase()

    /** HEL-224: Oracle executes INSERT … SELECT server-side; a same-database
     *  transfer copies CLOB / TIMESTAMP WITH LOCAL TIME ZONE columns entirely
     *  inside the server, so no value is stringified on the client round-trip. */
    override val supportsServerSideCopy: Boolean = true

    /** MERGE-based upsert. Binds one `?` per schema column via the dual
     *  subquery, in schema order — identical bind shape to insertSql. */
    override fun upsertSql(table: String, schema: com.pkgrove.pkgrovekit.core.Schema,
                           keyColumns: List<String>): String {
        fun q(name: String) = quoteIdent(name, "column")
        val srcSelect = schema.columns.joinToString(", ") { "? AS ${q(it.name)}" }
        val keyNorm = keyColumns.map { it.lowercase() }.toSet()
        val on = keyColumns.joinToString(" AND ") {
            val k = q(schema[it].name); "t.$k = s.$k"
        }
        val nonKeys = schema.columns.filter { it.name.lowercase() !in keyNorm }
        val insertCols = schema.columns.joinToString(", ") { q(it.name) }
        val insertVals = schema.columns.joinToString(", ") { "s.${q(it.name)}" }
        val merge = "MERGE INTO ${quoteIdent(table, "table")} t USING (SELECT $srcSelect FROM dual) s ON ($on) "
        // Key-only table: nothing to UPDATE on match, so emit an insert-only
        // MERGE (WHEN NOT MATCHED only) = insert-if-absent, mirroring Postgres'
        // ON CONFLICT DO NOTHING. WHEN MATCHED with an empty SET is invalid.
        if (nonKeys.isEmpty())
            return merge + "WHEN NOT MATCHED THEN INSERT ($insertCols) VALUES ($insertVals)"
        val updates = nonKeys.joinToString(", ") { "t.${q(it.name)} = s.${q(it.name)}" }
        return merge + "WHEN MATCHED THEN UPDATE SET $updates " +
               "WHEN NOT MATCHED THEN INSERT ($insertCols) VALUES ($insertVals)"
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
