package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.ConversionException
import io.maxxga.rowrelay.core.ConversionPolicy
import io.maxxga.rowrelay.core.DataWarning
import io.maxxga.rowrelay.core.Identifiers
import io.maxxga.rowrelay.core.Schema

/**
 * Target-side dialect contract (HEL-120 capabilities 4/6): how a database
 * spells types, creates tables, and receives inserts. Adapters implement this
 * once and are usable as SOURCE (via their [ValueReader]) and TARGET (via
 * this) — no direction-specific artifacts.
 */
interface SqlDialect {

    /** Dialect name for reports/errors ("duckdb", "oracle", ...). */
    val name: String

    /**
     * The dialect's DDL type for [column], or null when it has no faithful
     * representation (the transfer layer then applies the caller's
     * [ConversionPolicy] — never a silent guess).
     */
    fun typeFor(column: Column): String?

    /** Value adaptation just before binding (e.g. LocalDateTime→Timestamp). */
    fun bindValue(value: Any?, column: Column): Any? = value

    /**
     * Deterministic identifier-case policy applied before quoting (HEL-119).
     * Default: preserve case. Oracle overrides to UPPERCASE so quoted
     * identifiers match objects created without quotes (Oracle folds unquoted
     * names up; proven by the live-Oracle suite — quoted lowercase names miss
     * unquoted-created tables entirely).
     */
    fun identifierCase(name: String): String = name

    /** Validate + case-fold + quote — every DDL/DML identifier goes through here. */
    fun quoteIdent(name: String, what: String = "identifier"): String =
        "\"" + Identifiers.validate(identifierCase(name), what) + "\""

    /** How the target table is established. */
    enum class TargetMode { CREATE, CREATE_OR_REPLACE, APPEND, TEMPORARY, FAIL_IF_EXISTS }

    fun createTableDdl(table: String, schema: Schema, mode: TargetMode): String {
        val cols = schema.columns.joinToString(", ") { c ->
            val t = typeFor(c) ?: throw ConversionException(
                "no ${name} type for column kind=${c.kind} (${c.typeName})", c.name)
            "${quoteIdent(c.name, "column")} $t"
        }
        val target = quoteIdent(table, "table")
        return when (mode) {
            TargetMode.CREATE, TargetMode.FAIL_IF_EXISTS -> "CREATE TABLE $target ($cols)"
            TargetMode.CREATE_OR_REPLACE -> "CREATE OR REPLACE TABLE $target ($cols)"
            TargetMode.TEMPORARY -> "CREATE TEMPORARY TABLE $target ($cols)"
            TargetMode.APPEND -> throw IllegalArgumentException("APPEND does not create a table")
        }
    }

    fun insertSql(table: String, schema: Schema): String {
        val cols = schema.columns.joinToString(", ") { quoteIdent(it.name, "column") }
        val marks = schema.columns.joinToString(", ") { "?" }
        return "INSERT INTO ${quoteIdent(table, "table")} ($cols) VALUES ($marks)"
    }

    /**
     * Batch upsert DML keyed on [keyColumns] (HEL-119: explicit named-key
     * update/upsert). One `?` per schema column, in schema order, exactly like
     * [insertSql] — the writer binds rows identically for both. Returns null
     * when the dialect has no native upsert (the transfer layer then rejects
     * the request loudly). The target must have a uniqueness guarantee on the
     * key columns where the dialect requires one (e.g. DuckDB ON CONFLICT).
     */
    fun upsertSql(table: String, schema: Schema, keyColumns: List<String>): String? = null

    /**
     * Apply [policy] to columns this dialect cannot represent. Returns the
     * effective schema plus warnings; REJECT throws naming the first bad
     * column. STRINGIFY re-types to a text column; SKIP drops the column.
     */
    fun adaptSchema(schema: Schema, policy: ConversionPolicy,
                    warn: (DataWarning) -> Unit): Schema {
        val kept = schema.columns.mapNotNull { c ->
            if (typeFor(c) != null) return@mapNotNull c
            when (policy) {
                ConversionPolicy.REJECT -> throw ConversionException(
                    "column has no faithful $name representation and policy is REJECT", c.name)
                ConversionPolicy.STRINGIFY -> {
                    warn(DataWarning("stringified", "no faithful $name type; carried as text", c.name))
                    c.copy(kind = io.maxxga.rowrelay.core.ValueKind.TEXT, typeName = "VARCHAR",
                           precision = null, scale = null, timeZoned = null)
                }
                ConversionPolicy.SKIP -> {
                    warn(DataWarning("skipped-column", "no faithful $name type; column dropped", c.name))
                    null
                }
                ConversionPolicy.BINARY_COPY -> throw ConversionException(
                    "BINARY_COPY is only valid for binary-kind columns", c.name)
            }
        }
        if (kept.isEmpty()) throw ConversionException("no columns remain after policy application")
        return Schema(kept)
    }
}
