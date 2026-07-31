package io.maxxga.rowrelay.core

/**
 * Safe SQL identifier handling (HEL-120 capability 7). Identifiers cannot be
 * bound as parameters, so anywhere a runtime-discovered name reaches SQL text
 * it MUST pass through here first. Extracted from the two proven production
 * gates (AuditPatchX table access, QuerySkiff registrar): a strict allowlist
 * plus defensive quoting — validate-then-quote, never escape-and-hope.
 */
object Identifiers {

    /** Plain SQL identifier: letter/underscore start, word chars after, ≤128. */
    private val SAFE = Regex("^[A-Za-z_][A-Za-z0-9_$#]{0,127}$")

    class UnsafeIdentifierException(message: String) : IllegalArgumentException(message)

    /**
     * Validate a runtime-discovered identifier (column/table/schema name).
     * Throws [UnsafeIdentifierException] WITHOUT echoing the raw value —
     * rejected names are attacker-influenced by definition and the message
     * may reach user-facing surfaces.
     */
    fun validate(name: String, what: String = "identifier"): String {
        if (!SAFE.matches(name)) throw UnsafeIdentifierException("$what cannot be used safely")
        return name
    }

    /**
     * Validate then double-quote for ANSI dialects (DuckDB, Oracle, Postgres).
     * Quoting after validation is belt-and-braces, and preserves case where
     * the dialect is case-sensitive about quoted names.
     */
    fun quote(name: String, what: String = "identifier"): String =
        "\"${validate(name, what)}\""

    /** schema.table with both parts validated and quoted. */
    fun qualified(schema: String?, table: String): String =
        if (schema == null) quote(table, "table")
        else "${quote(schema, "schema")}.${quote(table, "table")}"
}
