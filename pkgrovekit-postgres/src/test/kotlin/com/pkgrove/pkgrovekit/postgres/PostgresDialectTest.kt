package com.pkgrove.pkgrovekit.postgres

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HEL-127: Postgres type table, LOWERCASE identifier policy, upsert DDL. */
class PostgresDialectTest {

    private fun col(kind: ValueKind, type: String = "T", p: Int? = null, s: Int? = null,
                    tz: Boolean? = null) =
        Column("c", kind, type, precision = p, scale = s, timeZoned = tz)

    @Test
    fun `type mapping`() {
        assertEquals("VARCHAR(80)", PostgresDialect.typeFor(col(ValueKind.TEXT, p = 80)))
        assertEquals("TEXT", PostgresDialect.typeFor(col(ValueKind.TEXT)))
        assertEquals("BOOLEAN", PostgresDialect.typeFor(col(ValueKind.BOOLEAN)))
        assertEquals("BYTEA", PostgresDialect.typeFor(col(ValueKind.BINARY, p = 16)))
        assertEquals("NUMERIC(10,2)", PostgresDialect.typeFor(col(ValueKind.NUMERIC, p = 10, s = 2)))
        assertEquals("BIGINT", PostgresDialect.typeFor(col(ValueKind.NUMERIC, p = 18)))
        assertEquals("TIMESTAMPTZ", PostgresDialect.typeFor(col(ValueKind.TEMPORAL, tz = true)))
        assertEquals("DATE", PostgresDialect.typeFor(col(ValueKind.TEMPORAL, type = "DATE", tz = false)))
        assertNull(PostgresDialect.typeFor(col(ValueKind.OTHER)))
    }

    @Test
    fun `HEL-127 uuid, json, jsonb and array target types`() {
        assertEquals("UUID", PostgresDialect.typeFor(col(ValueKind.OTHER, type = "uuid")))
        assertEquals("JSON", PostgresDialect.typeFor(col(ValueKind.OTHER, type = "json")))
        assertEquals("JSONB", PostgresDialect.typeFor(col(ValueKind.OTHER, type = "jsonb")))
        // pgjdbc names arrays by element type prefixed with '_'
        assertEquals("int4[]", PostgresDialect.typeFor(col(ValueKind.OTHER, type = "_int4")))
        assertEquals("text[]", PostgresDialect.typeFor(col(ValueKind.OTHER, type = "_text")))
        assertEquals("varchar[]", PostgresDialect.typeFor(col(ValueKind.OTHER, type = "varchar[]")))
        // still null for genuinely unmapped OTHER types
        assertNull(PostgresDialect.typeFor(col(ValueKind.OTHER, type = "geometry")))
    }

    @Test
    fun `HEL-127 uuid text binds to a real UUID`() {
        val u = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
        val bound = PostgresDialect.bindValue(u, col(ValueKind.OTHER, type = "uuid"))
        assertEquals(java.util.UUID.fromString(u), bound)
        // a non-uuid OTHER string passes through untouched (no pgjdbc needed)
        assertEquals("plain", PostgresDialect.bindValue("plain", col(ValueKind.TEXT)))
    }

    @Test
    fun `postgres folds identifiers DOWN before quoting`() {
        val schema = Schema(listOf(
            Column("USER_NAME", ValueKind.TEXT, "VARCHAR", precision = 50),
            Column("Score", ValueKind.NUMERIC, "NUMERIC", precision = 10, scale = 2)))
        val ddl = PostgresDialect.createTableDdl("Dest_Table", schema, SqlDialect.TargetMode.CREATE)
        assertEquals("CREATE TABLE \"dest_table\" (\"user_name\" VARCHAR(50), \"score\" NUMERIC(10,2))", ddl)
        assertTrue(PostgresDialect.insertSql("Dest_Table", schema).startsWith("INSERT INTO \"dest_table\""))
    }

    @Test
    fun `on conflict upsert keyed by name`() {
        val schema = Schema(listOf(
            Column("id", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("name", ValueKind.TEXT, "VARCHAR", precision = 50)))
        val sql = PostgresDialect.upsertSql("t", schema, listOf("id"))
        assertEquals("INSERT INTO \"t\" (\"id\", \"name\") VALUES (?, ?) " +
                     "ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\"", sql)
    }

    @Test
    fun `key-only table degrades to ON CONFLICT DO NOTHING (not empty DO UPDATE SET)`() {
        val schema = Schema(listOf(
            Column("a", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("b", ValueKind.NUMERIC, "BIGINT", precision = 18)))
        val sql = PostgresDialect.upsertSql("t", schema, listOf("a", "b"))
        assertEquals("INSERT INTO \"t\" (\"a\", \"b\") VALUES (?, ?) " +
                     "ON CONFLICT (\"a\", \"b\") DO NOTHING", sql)
        assertTrue("DO UPDATE SET " !in sql)  // never an empty update list
    }

    @Test
    fun `capabilities`() {
        assertTrue(PostgresDialect.supportsSavepoints)
        assertEquals("postgres", PostgresDialect.name)
    }
}
