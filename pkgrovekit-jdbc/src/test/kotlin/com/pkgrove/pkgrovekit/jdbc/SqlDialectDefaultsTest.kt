package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ConversionException
import com.pkgrove.pkgrovekit.core.ConversionPolicy
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HEL-234: the [SqlDialect] DEFAULT method bodies are contract every adapter
 * inherits (conservative capabilities, DDL/DML generation, policy-driven
 * schema adaptation, actionable unsupported-type errors). Adapters override
 * pieces, so their suites never measure the defaults — this bare
 * implementation pins them directly.
 */
class SqlDialectDefaultsTest {

    /** Bare dialect: only the abstract members; every default stays inherited.
     *  OTHER is unrepresentable — the policy paths need a null typeFor. */
    private val dialect = object : SqlDialect {
        override val name = "bare"
        override fun typeFor(column: Column): String? =
            if (column.kind == ValueKind.OTHER) null else "T_${column.kind}"
    }

    private fun col(name: String, kind: ValueKind = ValueKind.TEXT,
                    precision: Int? = null, scale: Int? = null, timeZoned: Boolean? = null) =
        Column(name, kind, kind.name, precision = precision, scale = scale, timeZoned = timeZoned)

    // --- conservative capability defaults ------------------------------------

    @Test
    fun `capability defaults are conservative`() {
        assertFalse(dialect.supportsSavepoints, "savepoints must be opt-in")
        assertSame(StreamingContract.HONOURS_FETCH_SIZE, dialect.streaming)
        assertNull(dialect.upsertSql("t", Schema(listOf(col("a"))), listOf("a")),
                   "no native upsert claimed by default")
        assertNull(dialect.bulkLoader(), "no bulk fast path claimed by default")
    }

    @Test
    fun `bindValue and identifierCase default to pass-through`() {
        val c = col("a")
        assertEquals("x", dialect.bindValue("x", c))
        assertNull(dialect.bindValue(null, c))
        assertEquals("MiXeD", dialect.identifierCase("MiXeD"))
    }

    @Test
    fun `quoteIdent validates then quotes preserving case`() {
        assertEquals("\"MiXeD\"", dialect.quoteIdent("MiXeD"))
        assertThrows<IllegalArgumentException> { dialect.quoteIdent("bad\"name") }
    }

    // --- DDL / DML generation ------------------------------------------------

    private val schema = Schema(listOf(col("id", ValueKind.NUMERIC), col("name", ValueKind.TEXT)))

    @Test
    fun `createTableDdl spells each target mode`() {
        assertEquals("CREATE TABLE \"t\" (\"id\" T_NUMERIC, \"name\" T_TEXT)",
                     dialect.createTableDdl("t", schema, SqlDialect.TargetMode.CREATE))
        assertEquals("CREATE TABLE \"t\" (\"id\" T_NUMERIC, \"name\" T_TEXT)",
                     dialect.createTableDdl("t", schema, SqlDialect.TargetMode.FAIL_IF_EXISTS))
        assertEquals("CREATE OR REPLACE TABLE \"t\" (\"id\" T_NUMERIC, \"name\" T_TEXT)",
                     dialect.createTableDdl("t", schema, SqlDialect.TargetMode.CREATE_OR_REPLACE))
        assertEquals("CREATE TEMPORARY TABLE \"t\" (\"id\" T_NUMERIC, \"name\" T_TEXT)",
                     dialect.createTableDdl("t", schema, SqlDialect.TargetMode.TEMPORARY))
    }

    @Test
    fun `append mode never creates a table`() {
        assertThrows<IllegalArgumentException> {
            dialect.createTableDdl("t", schema, SqlDialect.TargetMode.APPEND)
        }
    }

    @Test
    fun `createTableDdl rejects an unrepresentable column with the actionable message`() {
        val bad = Schema(listOf(col("geo", ValueKind.OTHER)))
        val e = assertThrows<ConversionException> {
            dialect.createTableDdl("t", bad, SqlDialect.TargetMode.CREATE)
        }
        assertEquals("geo", e.column)
        assertTrue("ConversionPolicy.STRINGIFY" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `insertSql binds one mark per column in schema order`() {
        assertEquals("INSERT INTO \"t\" (\"id\", \"name\") VALUES (?, ?)",
                     dialect.insertSql("t", schema))
    }

    // --- HEL-224 server-side copy (INSERT … SELECT) ---------------------------

    /** A dialect that opts into server-side copy; uses the inherited default
     *  [SqlDialect.serverSideCopySql] body. */
    private val copyDialect = object : SqlDialect {
        override val name = "copy"
        override val supportsServerSideCopy = true
        override fun typeFor(column: Column): String? = "T_${column.kind}"
    }

    @Test
    fun `server-side copy is opt-in and off by default`() {
        assertFalse(dialect.supportsServerSideCopy, "server-side copy must be opt-in")
        assertNull(dialect.serverSideCopySql("t", listOf("a"), listOf("a"), "SELECT 1"),
                   "a dialect without server-side copy returns no SQL")
    }

    @Test
    fun `serverSideCopySql wraps the source as a quoted inline view`() {
        assertEquals(
            "INSERT INTO \"dst\" (\"id\", \"name\") " +
            "SELECT \"src_id\", \"src_name\" FROM (SELECT * FROM t WHERE x = ?) \"pkgrove_src\"",
            copyDialect.serverSideCopySql(
                "dst", listOf("id", "name"), listOf("src_id", "src_name"),
                "SELECT * FROM t WHERE x = ?"))
    }

    @Test
    fun `serverSideCopySql appends a predicate when given`() {
        val sql = copyDialect.serverSideCopySql(
            "dst", listOf("a"), listOf("a"), "SELECT a FROM t", "\"a\" > 0")
        assertTrue(sql!!.endsWith("FROM (SELECT a FROM t) \"pkgrove_src\" WHERE \"a\" > 0"), sql)
    }

    @Test
    fun `serverSideCopySql rejects mismatched or empty column lists`() {
        assertThrows<IllegalArgumentException> {
            copyDialect.serverSideCopySql("dst", listOf("a", "b"), listOf("a"), "SELECT a FROM t")
        }
        assertThrows<IllegalArgumentException> {
            copyDialect.serverSideCopySql("dst", emptyList(), emptyList(), "SELECT 1")
        }
    }

    @Test
    fun `unsupportedTypeMessage carries full column context`() {
        val m = dialect.unsupportedTypeMessage(
            col("ts", ValueKind.OTHER, precision = 6, scale = 2, timeZoned = true))
        assertTrue("precision=6" in m, m)
        assertTrue("scale=2" in m, m)
        assertTrue("timeZoned" in m, m)
        assertTrue("'ts'" in m, m)
        // and without metadata the bracketed context is omitted entirely
        val bare = dialect.unsupportedTypeMessage(col("x", ValueKind.OTHER))
        assertFalse("[" in bare.substringBefore("Adapter path"), bare)
    }

    // --- adaptSchema policy application --------------------------------------

    private val mixed = Schema(listOf(col("keep", ValueKind.TEXT), col("odd", ValueKind.OTHER)))

    @Test
    fun `adaptSchema REJECT throws naming the first bad column`() {
        val e = assertThrows<ConversionException> {
            dialect.adaptSchema(mixed, ConversionPolicy.REJECT) {}
        }
        assertEquals("odd", e.column)
        assertTrue("policy is REJECT" in (e.message ?: ""))
    }

    @Test
    fun `adaptSchema STRINGIFY re-types with a warning`() {
        val warnings = mutableListOf<DataWarning>()
        val adapted = dialect.adaptSchema(mixed, ConversionPolicy.STRINGIFY) { warnings += it }
        assertEquals(2, adapted.size)
        assertEquals(ValueKind.TEXT, adapted["odd"].kind)
        assertEquals("VARCHAR", adapted["odd"].typeName)
        assertNull(adapted["odd"].precision)
        assertEquals(listOf("stringified"), warnings.map { it.code })
        assertEquals("odd", warnings.single().column)
    }

    @Test
    fun `adaptSchema SKIP drops the column with a warning`() {
        val warnings = mutableListOf<DataWarning>()
        val adapted = dialect.adaptSchema(mixed, ConversionPolicy.SKIP) { warnings += it }
        assertEquals(1, adapted.size)
        assertFalse(adapted.contains("odd"))
        assertEquals(listOf("skipped-column"), warnings.map { it.code })
    }

    @Test
    fun `adaptSchema BINARY_COPY is invalid for non-binary columns`() {
        assertThrows<ConversionException> {
            dialect.adaptSchema(mixed, ConversionPolicy.BINARY_COPY) {}
        }
    }

    @Test
    fun `adaptSchema refuses to return an empty schema`() {
        val allBad = Schema(listOf(col("only", ValueKind.OTHER)))
        val e = assertThrows<ConversionException> {
            dialect.adaptSchema(allBad, ConversionPolicy.SKIP) {}
        }
        assertTrue("no columns remain" in (e.message ?: ""))
    }

    @Test
    fun `adaptSchema passes representable columns through untouched`() {
        val clean = Schema(listOf(col("a"), col("b", ValueKind.NUMERIC)))
        val adapted = dialect.adaptSchema(clean, ConversionPolicy.REJECT) {
            throw AssertionError("no warning expected")
        }
        assertEquals(clean, adapted)
    }
}
