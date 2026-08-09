package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.sql.rowset.serial.SerialBlob
import javax.sql.rowset.serial.SerialClob

/**
 * HEL-234: driver-value normalization ([ValueReader.Default]) and JDBC
 * metadata mapping ([JdbcSchemas]) boundary cases — LOB materialization,
 * unknown-driver-type stringification (warned, never leaked), vendor type
 * codes, and nullability/precision/scale edges — via minimal JDBC fakes, so
 * the branches no real single engine can produce are still proven.
 */
class ValueReaderAndSchemasTest {

    private val column = Column("c", ValueKind.TEXT, "VARCHAR")

    /** ResultSet fake: getObject(i) returns [value]; everything else is unused. */
    private fun rsOf(value: Any?): ResultSet =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, _ ->
            when (method.name) {
                "getObject" -> value
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ResultSet

    private fun read(value: Any?, warn: (DataWarning) -> Unit = {}): Any? =
        ValueReader.DEFAULT.read(rsOf(value), 1, column, warn)

    // --- ValueReader.Default -------------------------------------------------

    @Test
    fun `sql null maps to null`() {
        assertNull(read(null))
    }

    @Test
    fun `clob materializes to string including the empty clob`() {
        assertEquals("lob-content", read(SerialClob("lob-content".toCharArray())))
        // empty CLOB: getSubString(1, 0) is driver-hostile — the reader must
        // short-circuit to ""
        val empty = object : SerialClob("x".toCharArray()) {
            override fun length(): Long = 0
        }
        assertEquals("", read(empty))
    }

    @Test
    fun `blob materializes to byte array`() {
        val bytes = byteArrayOf(1, 2, 3)
        assertArrayEquals(bytes, read(SerialBlob(bytes)) as ByteArray)
    }

    @Test
    fun `java sql temporals convert to java time`() {
        val ldt = LocalDateTime.of(2026, 8, 9, 10, 20, 30)
        assertEquals(ldt, read(java.sql.Timestamp.valueOf(ldt)))
        assertEquals(LocalDate.of(2026, 8, 9), read(java.sql.Date.valueOf(LocalDate.of(2026, 8, 9))))
        assertEquals(LocalTime.of(10, 20, 30), read(java.sql.Time.valueOf(LocalTime.of(10, 20, 30))))
    }

    @Test
    fun `allowed jdk types pass through untouched`() {
        for (v in listOf<Any>("s", true, 1, 2L, 3.5, 4.5f, java.math.BigDecimal("9.99"),
                              LocalDate.of(2026, 1, 1))) {
            assertEquals(v, read(v))
        }
        val raw = byteArrayOf(9)
        assertArrayEquals(raw, read(raw) as ByteArray)
    }

    @Test
    fun `uuid is carried as its string form`() {
        val u = UUID.randomUUID()
        assertEquals(u.toString(), read(u))
    }

    @Test
    fun `unknown driver type is stringified WITH a warning — never leaked`() {
        class VendorStruct { override fun toString() = "vendor-repr" }
        val warnings = mutableListOf<DataWarning>()
        assertEquals("vendor-repr", read(VendorStruct()) { warnings += it })
        val w = warnings.single()
        assertEquals("unrepresentable-type", w.code)
        assertEquals("c", w.column)
        assertTrue("VendorStruct" in w.message, w.message)
    }

    // --- JdbcSchemas ---------------------------------------------------------

    private data class FakeCol(
        val label: String, val type: Int, val typeName: String?,
        val nullable: Int = ResultSetMetaData.columnNullableUnknown,
        val precision: Int = 0, val scale: Int = 0,
    )

    private fun metaOf(vararg cols: FakeCol): ResultSetMetaData =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSetMetaData::class.java)) { _, method, args ->
            fun col() = cols[(args!![0] as Int) - 1]
            when (method.name) {
                "getColumnCount" -> cols.size
                "getColumnType" -> col().type
                "getColumnLabel" -> col().label
                "getColumnTypeName" -> col().typeName
                "isNullable" -> col().nullable
                "getPrecision" -> col().precision
                "getScale" -> col().scale
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ResultSetMetaData

    @Test
    fun `kind mapping covers every jdbc family and the oracle vendor codes`() {
        assertEquals(ValueKind.TEXT, JdbcSchemas.kindOf(Types.NCLOB))
        assertEquals(ValueKind.NUMERIC, JdbcSchemas.kindOf(Types.DECIMAL))
        assertEquals(ValueKind.BOOLEAN, JdbcSchemas.kindOf(Types.BIT))
        assertEquals(ValueKind.TEMPORAL, JdbcSchemas.kindOf(Types.TIME_WITH_TIMEZONE))
        assertEquals(ValueKind.BINARY, JdbcSchemas.kindOf(Types.LONGVARBINARY))
        assertEquals(ValueKind.OTHER, JdbcSchemas.kindOf(Types.SQLXML))
        // Oracle vendor codes (live-Oracle-proven quirk)
        assertEquals(ValueKind.TEMPORAL, JdbcSchemas.kindOf(-101))
        assertEquals(ValueKind.TEMPORAL, JdbcSchemas.kindOf(-102))
        assertEquals(ValueKind.NUMERIC, JdbcSchemas.kindOf(100))
        assertEquals(ValueKind.NUMERIC, JdbcSchemas.kindOf(101))
    }

    @Test
    fun `temporal columns take the jdbc-standard name derived from the type code`() {
        val schema = JdbcSchemas.fromMetaData(metaOf(
            FakeCol("d", Types.DATE, "vendor-date"),
            FakeCol("t", Types.TIME, "vendor-time"),
            FakeCol("ttz", Types.TIME_WITH_TIMEZONE, "vendor-ttz"),
            FakeCol("ts", Types.TIMESTAMP, "vendor-ts"),
            FakeCol("tstz", Types.TIMESTAMP_WITH_TIMEZONE, "vendor-tstz"),
            FakeCol("otz", -101, "TIMESTAMP(6) WITH TIME ZONE"),
        ))
        assertEquals(
            listOf("DATE", "TIME", "TIME WITH TIME ZONE", "TIMESTAMP",
                   "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE"),
            schema.columns.map { it.typeName },
        )
        assertEquals(listOf(false, false, true, false, true, true),
                     schema.columns.map { it.timeZoned })
    }

    @Test
    fun `nullability precision and scale edges are surfaced only where meaningful`() {
        val schema = JdbcSchemas.fromMetaData(metaOf(
            FakeCol("req", Types.VARCHAR, "VARCHAR", ResultSetMetaData.columnNoNulls, precision = 40),
            FakeCol("opt", Types.NUMERIC, "NUMERIC", ResultSetMetaData.columnNullable,
                    precision = 10, scale = 2),
            FakeCol("unk", Types.INTEGER, "INT", ResultSetMetaData.columnNullableUnknown,
                    precision = 0, scale = 5),
            FakeCol("noname", Types.OTHER, null),
        ))
        val (req, opt, unk, noname) = schema.columns
        assertEquals(false, req.nullable)
        assertEquals(40, req.precision)
        assertNull(req.scale, "scale only surfaces for NUMERIC/DECIMAL")
        assertNull(req.timeZoned)

        assertEquals(true, opt.nullable)
        assertEquals(2, opt.scale)

        assertNull(unk.nullable)
        assertNull(unk.precision, "precision 0 means not meaningful")
        assertNull(unk.scale, "INTEGER carries no scale")

        assertEquals("UNKNOWN", noname.typeName)
        assertEquals(ValueKind.OTHER, noname.kind)
    }
}
