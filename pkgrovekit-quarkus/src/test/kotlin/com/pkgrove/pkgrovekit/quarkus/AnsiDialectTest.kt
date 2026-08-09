package com.pkgrove.pkgrovekit.quarkus

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * HEL-234: direct unit coverage of the generic ANSI target dialect — every
 * type-mapping branch, the conservative null (OTHER) contract, and the
 * java.time -> java.sql bind bridges. Previously exercised only indirectly
 * through the Quarkus integration module.
 */
class AnsiDialectTest {

    private fun col(
        kind: ValueKind,
        typeName: String = kind.name,
        precision: Int? = null,
        scale: Int? = null,
        timeZoned: Boolean? = null,
    ) = Column("c", kind, typeName, precision = precision, scale = scale, timeZoned = timeZoned)

    // --- TEXT ---------------------------------------------------------------

    @Test
    fun `text with usable precision maps to bounded varchar`() {
        assertEquals("VARCHAR(255)", AnsiDialect.typeFor(col(ValueKind.TEXT, precision = 255)))
        assertEquals("VARCHAR(1)", AnsiDialect.typeFor(col(ValueKind.TEXT, precision = 1)))
        assertEquals("VARCHAR(1000000)", AnsiDialect.typeFor(col(ValueKind.TEXT, precision = 1_000_000)))
    }

    @Test
    fun `text without usable precision falls back to wide varchar`() {
        assertEquals("VARCHAR(1000000)", AnsiDialect.typeFor(col(ValueKind.TEXT)))
        assertEquals("VARCHAR(1000000)", AnsiDialect.typeFor(col(ValueKind.TEXT, precision = 0)))
        assertEquals("VARCHAR(1000000)", AnsiDialect.typeFor(col(ValueKind.TEXT, precision = -5)))
        assertEquals("VARCHAR(1000000)", AnsiDialect.typeFor(col(ValueKind.TEXT, precision = 1_000_001)))
    }

    // --- BOOLEAN / BINARY / OTHER -------------------------------------------

    @Test
    fun `boolean and binary map to their ansi spellings`() {
        assertEquals("BOOLEAN", AnsiDialect.typeFor(col(ValueKind.BOOLEAN)))
        assertEquals("BLOB", AnsiDialect.typeFor(col(ValueKind.BINARY)))
    }

    @Test
    fun `other returns null so the conversion policy decides — never a silent guess`() {
        assertNull(AnsiDialect.typeFor(col(ValueKind.OTHER, typeName = "GEOMETRY")))
    }

    // --- NUMERIC ------------------------------------------------------------

    @Test
    fun `numeric without precision maps to double precision`() {
        assertEquals("DOUBLE PRECISION", AnsiDialect.typeFor(col(ValueKind.NUMERIC)))
    }

    @Test
    fun `numeric with scale maps to numeric with capped precision and scale`() {
        assertEquals("NUMERIC(10,2)", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 10, scale = 2)))
        // caps: precision at 38, scale at 37
        assertEquals("NUMERIC(38,37)", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 99, scale = 99)))
    }

    @Test
    fun `integral numeric picks the narrowest integer type by precision`() {
        assertEquals("SMALLINT", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 4)))
        assertEquals("INTEGER", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 5)))
        assertEquals("INTEGER", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 9)))
        assertEquals("BIGINT", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 10)))
        assertEquals("BIGINT", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 18)))
        assertEquals("NUMERIC(19)", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 19)))
        assertEquals("NUMERIC(38)", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 99)))
    }

    @Test
    fun `zero scale is treated as integral`() {
        assertEquals("SMALLINT", AnsiDialect.typeFor(col(ValueKind.NUMERIC, precision = 2, scale = 0)))
    }

    // --- TEMPORAL -----------------------------------------------------------

    @Test
    fun `time-zoned temporal wins regardless of type name`() {
        assertEquals(
            "TIMESTAMP WITH TIME ZONE",
            AnsiDialect.typeFor(col(ValueKind.TEMPORAL, typeName = "DATE", timeZoned = true)),
        )
    }

    @Test
    fun `date maps to date`() {
        assertEquals("DATE", AnsiDialect.typeFor(col(ValueKind.TEMPORAL, typeName = "date")))
    }

    @Test
    fun `time maps to time but timestamp does not`() {
        assertEquals("TIME", AnsiDialect.typeFor(col(ValueKind.TEMPORAL, typeName = "TIME")))
        assertEquals("TIME", AnsiDialect.typeFor(col(ValueKind.TEMPORAL, typeName = "TIME WITHOUT TIME ZONE")))
        assertEquals("TIMESTAMP", AnsiDialect.typeFor(col(ValueKind.TEMPORAL, typeName = "TIMESTAMP")))
    }

    @Test
    fun `unknown temporal falls back to timestamp`() {
        assertEquals("TIMESTAMP", AnsiDialect.typeFor(col(ValueKind.TEMPORAL, typeName = "DATETIME2")))
    }

    // --- bindValue ----------------------------------------------------------

    @Test
    fun `java time values are bridged to their java sql types`() {
        val c = col(ValueKind.TEMPORAL, typeName = "TIMESTAMP")
        val ldt = LocalDateTime.of(2026, 8, 9, 12, 30, 15)
        assertEquals(java.sql.Timestamp.valueOf(ldt), AnsiDialect.bindValue(ldt, c))
        val ld = LocalDate.of(2026, 8, 9)
        assertEquals(java.sql.Date.valueOf(ld), AnsiDialect.bindValue(ld, c))
        val lt = LocalTime.of(12, 30, 15)
        assertEquals(java.sql.Time.valueOf(lt), AnsiDialect.bindValue(lt, c))
        val odt = OffsetDateTime.of(2026, 8, 9, 12, 30, 15, 0, ZoneOffset.UTC)
        assertEquals(java.sql.Timestamp.from(odt.toInstant()), AnsiDialect.bindValue(odt, c))
    }

    @Test
    fun `non-temporal values pass through unchanged including null`() {
        val c = col(ValueKind.TEXT)
        val s = "unchanged"
        assertSame(s, AnsiDialect.bindValue(s, c))
        assertNull(AnsiDialect.bindValue(null, c))
        assertEquals(42, AnsiDialect.bindValue(42, c))
    }

    @Test
    fun `dialect reports its ansi name`() {
        assertEquals("ansi", AnsiDialect.name)
    }
}
