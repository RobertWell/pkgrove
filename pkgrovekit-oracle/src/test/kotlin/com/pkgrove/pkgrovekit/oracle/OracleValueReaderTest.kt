package com.pkgrove.pkgrovekit.oracle

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.DataWarning
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.time.LocalDateTime

/**
 * HEL-234: [OracleValueReader] normalization of REAL oracle.sql driver values —
 * the "no driver classes leak" rule at the Oracle boundary, without a live
 * database (the classes are constructed directly; the live path is
 * integration-tests' Oracle suite).
 */
class OracleValueReaderTest {

    private val reader = OracleValueReader()
    private val column = Column("c", ValueKind.TEMPORAL, "TIMESTAMP")
    private val ldt = LocalDateTime.of(2026, 8, 9, 12, 30, 45)

    private fun rsOf(value: Any?): ResultSet =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, m, _ ->
            when (m.name) {
                "getObject" -> value
                else -> throw UnsupportedOperationException(m.name)
            }
        } as ResultSet

    private fun read(value: Any?, warn: (DataWarning) -> Unit = {}): Any? =
        reader.read(rsOf(value), 1, column, warn)

    @Test
    fun `oracle TIMESTAMP normalizes to LocalDateTime`() {
        val v = oracle.sql.TIMESTAMP(java.sql.Timestamp.valueOf(ldt))
        assertEquals(ldt, read(v))
    }

    @Test
    fun `oracle DATE normalizes to LocalDateTime — Oracle DATE carries time`() {
        val v = oracle.sql.DATE(java.sql.Timestamp.valueOf(ldt))
        assertEquals(ldt, read(v))
    }

    @Test
    fun `oracle TIMESTAMPLTZ is carried as string WITH a warning — never a guessed zone`() {
        val warnings = mutableListOf<DataWarning>()
        val v = oracle.sql.TIMESTAMPLTZ()
        val out = read(v) { warnings += it }
        assertEquals(v.toString(), out)
        val w = warnings.single()
        assertEquals("timestampltz-stringified", w.code)
        assertEquals("c", w.column)
    }

    @Test
    fun `non-oracle values fall through to the standard normalization`() {
        assertEquals("plain", read("plain"))
        assertEquals(ldt, read(java.sql.Timestamp.valueOf(ldt)))
        assertNull(read(null))
    }
}
