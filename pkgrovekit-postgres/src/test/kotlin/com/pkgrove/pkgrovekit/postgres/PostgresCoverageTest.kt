package com.pkgrove.pkgrovekit.postgres

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.OperationCancelledException
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.BulkLoadException
import com.pkgrove.pkgrovekit.jdbc.BulkLoadOptions
import com.pkgrove.pkgrovekit.jdbc.BulkSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.PGConnection
import org.postgresql.copy.CopyIn
import org.postgresql.util.PGobject
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException

/**
 * HEL-234: Postgres boundary paths a DuckDB-only unit suite never reached —
 * [PostgresValueReader] vendor-value normalization, the [PostgresDialect]
 * uuid/json/array reconstruction branches, and [PostgresCopyLoader]'s COPY
 * protocol choreography (success, failure rollback, cancellation) against
 * fake pgjdbc seams. The live end-to-end COPY proof stays in
 * integration-tests' BulkLoadIT.
 */
class PostgresCoverageTest {

    private fun col(name: String, kind: ValueKind = ValueKind.NUMERIC, typeName: String = kind.name) =
        Column(name, kind, typeName)

    // --- PostgresValueReader -------------------------------------------------

    @Test
    fun `pgobject json arrives as its text value without warnings`() {
        val reader = PostgresValueReader()
        val rs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, m, _ ->
            when (m.name) {
                "getObject" -> PGobject().apply { type = "jsonb"; value = """{"a":1}""" }
                else -> throw UnsupportedOperationException(m.name)
            }
        } as ResultSet
        val warnings = mutableListOf<DataWarning>()
        assertEquals("""{"a":1}""",
            reader.read(rs, 1, col("j", ValueKind.OTHER, "jsonb")) { warnings += it })
        assertTrue(warnings.isEmpty(), "vendor value must normalize WITHOUT a warning")
    }

    @Test
    fun `sql arrays are carried as the canonical pg literal text`() {
        val reader = PostgresValueReader()
        val array = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Array::class.java)) { _, m, _ ->
            when (m.name) {
                "toString" -> "{1,2,3}"
                "hashCode" -> 0
                else -> throw UnsupportedOperationException(m.name)
            }
        } as java.sql.Array
        val rs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, m, _ ->
            when (m.name) { "getObject" -> array; else -> throw UnsupportedOperationException(m.name) }
        } as ResultSet
        assertEquals("{1,2,3}", reader.read(rs, 1, col("a", ValueKind.OTHER, "_int4")) {})
    }

    @Test
    fun `everything else falls through to standard normalization`() {
        val reader = PostgresValueReader()
        val rs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, m, _ ->
            when (m.name) { "getObject" -> "plain"; else -> throw UnsupportedOperationException(m.name) }
        } as ResultSet
        assertEquals("plain", reader.read(rs, 1, col("s", ValueKind.TEXT)) {})
    }

    // --- PostgresDialect uuid/json/array reconstruction ----------------------

    @Test
    fun `other-kind postgres types are recreated from the driver type name`() {
        assertEquals("UUID", PostgresDialect.typeFor(col("u", ValueKind.OTHER, "uuid")))
        assertEquals("JSON", PostgresDialect.typeFor(col("j", ValueKind.OTHER, "json")))
        assertEquals("JSONB", PostgresDialect.typeFor(col("j", ValueKind.OTHER, "jsonb")))
        assertEquals("int4[]", PostgresDialect.typeFor(col("a", ValueKind.OTHER, "_int4")))
        assertEquals("text[]", PostgresDialect.typeFor(col("a", ValueKind.OTHER, "text[]")))
        assertNull(PostgresDialect.typeFor(col("g", ValueKind.OTHER, "geometry")),
                   "genuinely unmappable OTHER stays null for the policy layer")
    }

    @Test
    fun `string values are re-typed for uuid json and array columns on bind`() {
        val u = PostgresDialect.bindValue("123e4567-e89b-12d3-a456-426614174000",
                                          col("u", ValueKind.OTHER, "uuid"))
        assertTrue(u is java.util.UUID, "$u")

        val j = PostgresDialect.bindValue("""{"k":true}""", col("j", ValueKind.OTHER, "jsonb"))
        assertTrue(j is PGobject && j.type == "jsonb" && j.value == """{"k":true}""", "$j")

        val a = PostgresDialect.bindValue("{1,2}", col("a", ValueKind.OTHER, "_int4"))
        assertTrue(a is PGobject && a.type == "int4[]", "$a")

        val explicit = PostgresDialect.bindValue("{x}", col("a", ValueKind.OTHER, "text[]"))
        assertTrue(explicit is PGobject && explicit.type == "text[]", "$explicit")

        // an ordinary string column passes through untouched
        assertEquals("plain", PostgresDialect.bindValue("plain", col("s", ValueKind.TEXT, "varchar")))
    }

    @Test
    fun `temporal and text edges map deterministically`() {
        assertEquals("TIMESTAMPTZ", PostgresDialect.typeFor(
            col("t", ValueKind.TEMPORAL, "timestamptz").copy(timeZoned = true)))
        assertEquals("TIME", PostgresDialect.typeFor(col("t", ValueKind.TEMPORAL, "TIME")))
        assertEquals("DATE", PostgresDialect.typeFor(col("t", ValueKind.TEMPORAL, "DATE")))
        assertEquals("TIMESTAMP", PostgresDialect.typeFor(col("t", ValueKind.TEMPORAL, "TIMESTAMP")))
        assertEquals("TEXT", PostgresDialect.typeFor(col("s", ValueKind.TEXT)))
        assertEquals("VARCHAR(10)", PostgresDialect.typeFor(col("s", ValueKind.TEXT).copy(precision = 10)))
        assertEquals("TEXT", PostgresDialect.typeFor(col("s", ValueKind.TEXT).copy(precision = 10_485_761)))
        assertEquals("BYTEA", PostgresDialect.typeFor(col("b", ValueKind.BINARY)))
        assertEquals("NUMERIC(1000,1000)", PostgresDialect.typeFor(
            col("n", ValueKind.NUMERIC).copy(precision = 2000, scale = 2000)))
    }

    // --- PostgresCopyLoader --------------------------------------------------

    private val schema = Schema(listOf(col("id"), col("name", ValueKind.TEXT)))

    private class FakePg {
        var autoCommit = true
        val calls = mutableListOf<String>()
        val written = StringBuilder()
        var copyActive = false
        var failOnWrite = false
        var endCopyResult = -1L

        val copyIn: CopyIn = Proxy.newProxyInstance(
            CopyIn::class.java.classLoader, arrayOf(CopyIn::class.java),
        ) { _, m, args ->
            when (m.name) {
                "writeToCopy" -> {
                    if (failOnWrite) throw SQLException("server rejected COPY data")
                    val bytes = args!![0] as ByteArray
                    written.append(String(bytes, (args[1] as Int), (args[2] as Int), Charsets.UTF_8))
                    null
                }
                "endCopy" -> { copyActive = false; calls += "endCopy"; endCopyResult }
                "isActive" -> copyActive
                "cancelCopy" -> { copyActive = false; calls += "cancelCopy"; null }
                else -> throw UnsupportedOperationException(m.name)
            }
        } as CopyIn

        val pgConnection: PGConnection = Proxy.newProxyInstance(
            PGConnection::class.java.classLoader, arrayOf(PGConnection::class.java),
        ) { _, m, _ ->
            when (m.name) {
                "getCopyAPI" -> throw UnsupportedOperationException("copyAPI is stubbed via CopyManager")
                else -> throw UnsupportedOperationException(m.name)
            }
        } as PGConnection

        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java),
        ) { _, m, args ->
            when (m.name) {
                "isWrapperFor" -> true
                "unwrap" -> pgConnection
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> { autoCommit = args!![0] as Boolean; calls += "setAutoCommit=${args[0]}"; null }
                "commit" -> { calls += "commit"; null }
                "rollback" -> { calls += "rollback"; null }
                else -> throw UnsupportedOperationException(m.name)
            }
        } as Connection
    }

    @Test
    fun `supports refuses binary columns and non-pg connections with typed reasons`() {
        val fake = FakePg()
        val binary = Schema(listOf(col("payload", ValueKind.BINARY)))
        val no = PostgresCopyLoader.supports(fake.connection, "t", binary) as BulkSupport.No
        assertTrue("bytea" in no.reason, no.reason)

        val alien = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, m, _ ->
            when (m.name) { "isWrapperFor" -> false; else -> throw UnsupportedOperationException(m.name) }
        } as Connection
        val no2 = PostgresCopyLoader.supports(alien, "t", schema) as BulkSupport.No
        assertTrue("not a pgjdbc connection" in no2.reason, no2.reason)

        assertEquals(BulkSupport.Yes, PostgresCopyLoader.supports(fake.connection, "t", schema))
    }

    @Test
    fun `a copy failure rolls back restores autoCommit and reports zero committed`() {
        val fake = FakePg()
        val ex = assertThrows<BulkLoadException> {
            PostgresCopyLoader.bulkLoad(fake.connection, "t", schema, sequenceOf(
                RowBatch(schema, listOf(Row(schema, listOf(1L, "a")))),
            ))
        }
        assertEquals(0L, ex.report.rowsAffected)
        assertFalse(ex.report.completed)
        assertTrue("nothing committed" in (ex.message ?: ""), ex.message ?: "")
        assertTrue("rollback" in fake.calls, fake.calls.toString())
        assertTrue(fake.autoCommit, "autoCommit must be restored")
        assertFalse("commit" in fake.calls)
    }

    @Test
    fun `cancellation before the copy starts commits nothing`() {
        val fake = FakePg()
        val token = CancelToken.none().also { it.cancel() }
        val ex = assertThrows<BulkLoadException> {
            PostgresCopyLoader.bulkLoad(fake.connection, "t", schema,
                sequenceOf(RowBatch(schema, listOf(Row(schema, listOf(1L, "a"))))),
                BulkLoadOptions(cancelToken = token))
        }
        assertTrue(ex.cause is UnsupportedOperationException || ex.cause is OperationCancelledException,
                   "${ex.cause}")
        assertTrue(fake.autoCommit)
    }

    // --- CSV serialization edges (public contract of the loader) -------------

    @Test
    fun `csv serialization pins null empty-string quoting and escaping`() {
        val sb = StringBuilder()
        PostgresCopyLoader.appendCsvRow(sb, listOf(
            null,                                  // NULL = unquoted empty
            "",                                    // empty string MUST be quoted
            "plain",
            "has,comma",
            "has\"quote",
            java.math.BigDecimal("0.00000001"),    // never scientific notation
            true,
            PGobject().apply { type = "jsonb"; value = """{"a":"b"}""" },
        ))
        assertEquals(
            ",\"\",plain,\"has,comma\",\"has\"\"quote\",0.00000001,true,\"{\"\"a\"\":\"\"b\"\"}\"\n",
            sb.toString(),
        )
    }

    @Test
    fun `null pgobject value serializes as quoted empty string`() {
        val sb = StringBuilder()
        PostgresCopyLoader.appendCsvRow(sb, listOf(PGobject().apply { type = "json" }))
        assertEquals("\"\"\n", sb.toString())
    }
}
