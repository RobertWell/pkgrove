package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ConversionException
import com.pkgrove.pkgrovekit.core.ConversionPolicy
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.Identifiers
import com.pkgrove.pkgrovekit.core.Schema

/**
 * Target-side dialect contract (HEL-120 capabilities 4/6): how a database
 * spells types, creates tables, and receives inserts. Adapters implement this
 * once and are usable as SOURCE (via their [ValueReader]) and TARGET (via
 * this) — no direction-specific artifacts.
 */
interface SqlDialect {

    /** Dialect name for reports/errors ("duckdb", "oracle", ...). */
    val name: String

    /** Capability report (HEL-126): whether JDBC savepoints work here.
     *  Conservative default false; adapters opt in. */
    val supportsSavepoints: Boolean get() = false

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

    /**
     * HEL-168: a clear, actionable error for a column this dialect cannot
     * represent — names the column, its common [ValueKind], the SOURCE database
     * type name, precision/scale/timeZoned context, and a concrete adapter path,
     * so a caller never has to guess why a transfer refused a column.
     */
    fun unsupportedTypeMessage(column: Column): String {
        val ctx = buildList {
            column.precision?.let { add("precision=$it") }
            column.scale?.let { add("scale=$it") }
            if (column.timeZoned == true) add("timeZoned")
        }.let { if (it.isEmpty()) "" else " [${it.joinToString(", ")}]" }
        return "no faithful $name type for column '${column.name}' " +
               "(kind=${column.kind}, source type '${column.typeName}'$ctx). " +
               "Adapter path: set ConversionPolicy.STRINGIFY to carry it as text (with a " +
               "warning), or SKIP to drop it; for a first-class mapping, add the type to " +
               "${name}Dialect.typeFor (and a source ValueReader.normalize case if the JDBC " +
               "value needs coercion)."
    }

    fun createTableDdl(table: String, schema: Schema, mode: TargetMode): String {
        val cols = schema.columns.joinToString(", ") { c ->
            val t = typeFor(c) ?: throw ConversionException(unsupportedTypeMessage(c), c.name)
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
     * HEL-161: the dialect's native bulk-ingest fast path, or null when the
     * engine has none. OPT-IN only — the transfer pipeline uses it solely when
     * the caller asks (Transfer.Options.useBulkLoad / Relay `bulkLoad()`), and
     * falls back to batched INSERT with a warning when [BulkLoader.supports]
     * says no. Plain inserts only (no upsert semantics on this path).
     */
    fun bulkLoader(): BulkLoader? = null

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
                    unsupportedTypeMessage(c) + " (policy is REJECT)", c.name)
                ConversionPolicy.STRINGIFY -> {
                    warn(DataWarning("stringified", "no faithful $name type; carried as text", c.name))
                    c.copy(kind = com.pkgrove.pkgrovekit.core.ValueKind.TEXT, typeName = "VARCHAR",
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
