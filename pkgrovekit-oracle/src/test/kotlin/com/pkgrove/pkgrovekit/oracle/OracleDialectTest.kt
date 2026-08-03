package com.pkgrove.pkgrovekit.oracle

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ConversionException
import com.pkgrove.pkgrovekit.core.ConversionPolicy
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Oracle type table + DDL/insert generation (no live Oracle; the pilot's
 *  testcontainers suite exercises the live path). */
class OracleDialectTest {

    private fun col(kind: ValueKind, type: String = "T", p: Int? = null, s: Int? = null,
                    tz: Boolean? = null) =
        Column("c", kind, type, precision = p, scale = s, timeZoned = tz)

    @Test
    fun `numeric mapping preserves precision and scale`() {
        assertEquals("NUMBER", OracleDialect.typeFor(col(ValueKind.NUMERIC)))
        assertEquals("NUMBER(10,2)", OracleDialect.typeFor(col(ValueKind.NUMERIC, p = 10, s = 2)))
        assertEquals("NUMBER(19)", OracleDialect.typeFor(col(ValueKind.NUMERIC, p = 19, s = 0)))
        assertEquals("NUMBER(38,37)", OracleDialect.typeFor(col(ValueKind.NUMERIC, p = 99, s = 37)))
    }

    @Test
    fun `text maps to varchar2 with clob overflow`() {
        assertEquals("VARCHAR2(200 CHAR)", OracleDialect.typeFor(col(ValueKind.TEXT, p = 200)))
        assertEquals("CLOB", OracleDialect.typeFor(col(ValueKind.TEXT)))
        assertEquals("CLOB", OracleDialect.typeFor(col(ValueKind.TEXT, p = 40001)))
    }

    @Test
    fun `boolean binary temporal mappings`() {
        assertEquals("NUMBER(1)", OracleDialect.typeFor(col(ValueKind.BOOLEAN)))
        assertEquals("RAW(16)", OracleDialect.typeFor(col(ValueKind.BINARY, p = 16)))
        assertEquals("BLOB", OracleDialect.typeFor(col(ValueKind.BINARY, p = 100_000)))
        assertEquals("DATE", OracleDialect.typeFor(col(ValueKind.TEMPORAL, type = "DATE", tz = false)))
        assertEquals("TIMESTAMP", OracleDialect.typeFor(col(ValueKind.TEMPORAL, type = "TIMESTAMP", tz = false)))
        assertEquals("TIMESTAMP WITH TIME ZONE",
                     OracleDialect.typeFor(col(ValueKind.TEMPORAL, type = "TIMESTAMPTZ", tz = true)))
        assertNull(OracleDialect.typeFor(col(ValueKind.OTHER)))
    }

    @Test
    fun `bind adaptation matches production conventions`() {
        val c = col(ValueKind.TEMPORAL, "TIMESTAMP")
        val ldt = java.time.LocalDateTime.of(2026, 7, 31, 12, 0)
        assertEquals(java.sql.Timestamp.valueOf(ldt), OracleDialect.bindValue(ldt, c))
        assertEquals(1, OracleDialect.bindValue(true, col(ValueKind.BOOLEAN)))
        assertEquals(0, OracleDialect.bindValue(false, col(ValueKind.BOOLEAN)))
    }

    @Test
    fun `ddl and insert are generated from validated identifiers only`() {
        val schema = Schema(listOf(
            Column("id", ValueKind.NUMERIC, "NUMBER", precision = 10, scale = 0),
            Column("name", ValueKind.TEXT, "VARCHAR2", precision = 50)))
        // Oracle policy: identifiers are UPPERCASED then quoted, so generated
        // SQL matches objects created without quotes (live-Oracle-proven).
        val ddl = OracleDialect.createTableDdl("t_dest", schema, SqlDialect.TargetMode.CREATE)
        assertEquals("CREATE TABLE \"T_DEST\" (\"ID\" NUMBER(10), \"NAME\" VARCHAR2(50 CHAR))", ddl)
        assertEquals("INSERT INTO \"T_DEST\" (\"ID\", \"NAME\") VALUES (?, ?)",
                     OracleDialect.insertSql("t_dest", schema))
        assertThrows(com.pkgrove.pkgrovekit.core.Identifiers.UnsafeIdentifierException::class.java) {
            OracleDialect.createTableDdl("t; DROP TABLE x", schema, SqlDialect.TargetMode.CREATE)
        }
    }

    @Test
    fun `policy application is explicit and never silent`() {
        val schema = Schema(listOf(
            Column("good", ValueKind.TEXT, "VARCHAR2", precision = 10),
            Column("weird", ValueKind.OTHER, "SDO_GEOMETRY")))
        assertThrows(ConversionException::class.java) {
            OracleDialect.adaptSchema(schema, ConversionPolicy.REJECT) {}
        }
        val warnings = mutableListOf<com.pkgrove.pkgrovekit.core.DataWarning>()
        val stringified = OracleDialect.adaptSchema(schema, ConversionPolicy.STRINGIFY) { warnings += it }
        assertEquals(ValueKind.TEXT, stringified["weird"].kind)
        assertEquals("stringified", warnings.single().code)

        warnings.clear()
        val skipped = OracleDialect.adaptSchema(schema, ConversionPolicy.SKIP) { warnings += it }
        assertTrue(!skipped.contains("weird"))
        assertEquals("skipped-column", warnings.single().code)
    }

    @Test
    fun `key-only table emits an insert-only MERGE (no empty UPDATE SET)`() {
        val schema = Schema(listOf(
            Column("a", ValueKind.NUMERIC, "NUMBER", precision = 18),
            Column("b", ValueKind.NUMERIC, "NUMBER", precision = 18)))
        val sql = OracleDialect.upsertSql("t", schema, listOf("a", "b"))
        assertTrue("WHEN MATCHED" !in sql)         // nothing to update → no MATCHED clause
        assertTrue("WHEN NOT MATCHED THEN INSERT" in sql)
    }
}
