package com.pkgrove.pkgrovekit.duckdb

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HEL-159: branch-matrix unit coverage for the DuckDB TARGET dialect (the
 * transfer target in every docs example), at parity with Oracle/Postgres.
 * The live-DuckDB ITs exercise transfer end-to-end; these pin the type/upsert/
 * bind BRANCHES the ITs don't isolate.
 */
class DuckDbDialectTest {

    private fun col(kind: ValueKind, type: String = "T", p: Int? = null, s: Int? = null,
                    tz: Boolean? = null) =
        Column("c", kind, type, precision = p, scale = s, timeZoned = tz)

    @Test
    fun `numeric precision and scale branches`() {
        // no precision + no integer type-name hint -> DOUBLE (genuine float)
        assertEquals("DOUBLE", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, type = "DOUBLE")))
        // HEL-168: no precision but an integer source type NAME -> preserve integer-ness
        // (defaulting to DOUBLE silently rounds large integers, e.g. BIGINT Long.MAX)
        assertEquals("BIGINT", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, type = "BIGINT")))
        assertEquals("INTEGER", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, type = "INTEGER")))
        assertEquals("SMALLINT", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, type = "SMALLINT")))
        assertEquals("HUGEINT", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, type = "HUGEINT")))
        // scale > 0 -> DECIMAL, precision/scale coerced to DuckDB max (38 / 37)
        assertEquals("DECIMAL(10,2)", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 10, s = 2)))
        assertEquals("DECIMAL(38,37)", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 50, s = 40)))
        // integer widths by precision
        assertEquals("SMALLINT", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 4)))
        assertEquals("INTEGER", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 9)))
        assertEquals("BIGINT", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 18)))
        // beyond BIGINT with zero scale -> DECIMAL(p,0); p kept as-is up to 38, then coerced
        assertEquals("DECIMAL(25,0)", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 25)))
        assertEquals("DECIMAL(38,0)", DuckDbDialect.typeFor(col(ValueKind.NUMERIC, p = 60)))
    }

    @Test
    fun `scalar kind mapping`() {
        assertEquals("VARCHAR", DuckDbDialect.typeFor(col(ValueKind.TEXT, p = 80)))
        assertEquals("BOOLEAN", DuckDbDialect.typeFor(col(ValueKind.BOOLEAN)))
        assertEquals("BLOB", DuckDbDialect.typeFor(col(ValueKind.BINARY, p = 16)))
        // OTHER is never a silent guess — policy decides
        assertNull(DuckDbDialect.typeFor(col(ValueKind.OTHER, type = "geometry")))
    }

    @Test
    fun `temporal branches`() {
        assertEquals("TIMESTAMP WITH TIME ZONE",
            DuckDbDialect.typeFor(col(ValueKind.TEMPORAL, type = "TIMESTAMPTZ", tz = true)))
        assertEquals("DATE", DuckDbDialect.typeFor(col(ValueKind.TEMPORAL, type = "DATE")))
        assertEquals("TIME", DuckDbDialect.typeFor(col(ValueKind.TEMPORAL, type = "TIME")))
        // TIMESTAMP must NOT be misread as TIME by the startsWith guard
        assertEquals("TIMESTAMP", DuckDbDialect.typeFor(col(ValueKind.TEMPORAL, type = "TIMESTAMP")))
        // tz flag wins even when the vendor name says DATE
        assertEquals("TIMESTAMP WITH TIME ZONE",
            DuckDbDialect.typeFor(col(ValueKind.TEMPORAL, type = "DATE", tz = true)))
    }

    @Test
    fun `upsert keyed by name updates only non-key columns`() {
        val schema = Schema(listOf(
            Column("id", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("name", ValueKind.TEXT, "VARCHAR", precision = 50)))
        val sql = DuckDbDialect.upsertSql("t", schema, listOf("id"))
        assertEquals("INSERT INTO \"t\" (\"id\", \"name\") VALUES (?, ?) " +
                     "ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"", sql)
    }

    @Test
    fun `key-only table degrades to DO NOTHING not an empty DO UPDATE SET`() {
        // HEL-159: this branch produced invalid SQL ("DO UPDATE SET ") before the fix.
        val schema = Schema(listOf(
            Column("a", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("b", ValueKind.NUMERIC, "BIGINT", precision = 18)))
        val sql = DuckDbDialect.upsertSql("t", schema, listOf("a", "b"))!!
        assertEquals("INSERT INTO \"t\" (\"a\", \"b\") VALUES (?, ?) " +
                     "ON CONFLICT (\"a\", \"b\") DO NOTHING", sql)
        assertFalse(sql.contains("DO UPDATE SET "))  // never an empty update list
    }

    @Test
    fun `bindValue coerces java_time to java_sql`() {
        val ldt = java.time.LocalDateTime.of(2026, 8, 2, 10, 30, 0)
        assertEquals(java.sql.Timestamp.valueOf(ldt),
            DuckDbDialect.bindValue(ldt, col(ValueKind.TEMPORAL)))

        val ld = java.time.LocalDate.of(2026, 8, 2)
        assertEquals(java.sql.Date.valueOf(ld),
            DuckDbDialect.bindValue(ld, col(ValueKind.TEMPORAL, type = "DATE")))

        // HEL-168: LocalTime binds as a lossless ISO string, NOT java.sql.Time
        // (which is second-precision + timezone-shifted).
        val lt = java.time.LocalTime.parse("10:30:00.123456")
        assertEquals("10:30:00.123456",
            DuckDbDialect.bindValue(lt, col(ValueKind.TEMPORAL, type = "TIME")))

        val odt = java.time.OffsetDateTime.parse("2026-08-02T10:30:00+02:00")
        assertEquals(java.sql.Timestamp.from(odt.toInstant()),
            DuckDbDialect.bindValue(odt, col(ValueKind.TEMPORAL, tz = true)))

        // anything else passes through untouched
        assertEquals("plain", DuckDbDialect.bindValue("plain", col(ValueKind.TEXT)))
        assertNull(DuckDbDialect.bindValue(null, col(ValueKind.TEXT)))
    }

    @Test
    fun `capabilities — duckdb jdbc has no savepoints`() {
        assertEquals("duckdb", DuckDbDialect.name)
        assertFalse(DuckDbDialect.supportsSavepoints)
    }

    @Test
    fun `createTableDdl uses the mapped types and preserves identifier case`() {
        val schema = Schema(listOf(
            Column("Id", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("Label", ValueKind.TEXT, "VARCHAR", precision = 50)))
        val ddl = DuckDbDialect.createTableDdl("Dest", schema, com.pkgrove.pkgrovekit.jdbc.SqlDialect.TargetMode.CREATE)
        // DuckDB default identifier policy preserves case (no Oracle-style fold)
        assertEquals("CREATE TABLE \"Dest\" (\"Id\" BIGINT, \"Label\" VARCHAR)", ddl)
    }
}
