package com.pkgrove.pkgrovekit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * HEL-234: pins the framework-neutral control/reporting surface — conversion
 * policy, warnings, cancellation linking, schema/row edges — that transfer
 * suites only exercise indirectly.
 */
class ControlAndModelCoverageTest {

    // --- ConversionPolicy / ConversionException / DataWarning ---------------

    @Test
    fun `conversion policy names are the documented contract`() {
        assertEquals(
            listOf("REJECT", "STRINGIFY", "BINARY_COPY", "SKIP"),
            ConversionPolicy.entries.map { it.name },
        )
        assertEquals(ConversionPolicy.REJECT, ConversionPolicy.valueOf("REJECT"))
    }

    @Test
    fun `conversion exception names the column when known`() {
        val e = ConversionException("cannot convert INTERVAL", column = "duration")
        assertEquals("duration", e.column)
        assertEquals("cannot convert INTERVAL", e.message)
        assertNull(ConversionException("no column context").column)
    }

    @Test
    fun `data warning renders code column and message`() {
        val w = DataWarning("LOSSY", "precision truncated", column = "amount")
        assertEquals("[LOSSY] amount: precision truncated", w.toString())
        assertEquals("amount", w.column)
    }

    @Test
    fun `data warning without a column renders without the column segment`() {
        val w = DataWarning("SKIPPED", "column dropped by policy")
        assertEquals("[SKIPPED] column dropped by policy", w.toString())
        assertNull(w.column)
    }

    // --- CancelToken (linking + deadline) ------------------------------------

    @Test
    fun `linked token observes any parent cancellation`() {
        val parent = CancelToken.none()
        val linked = CancelToken.linked(parent)
        assertFalse(linked.isCancelled)
        parent.cancel()
        assertTrue(linked.isCancelled)
        assertThrows<OperationCancelledException> { linked.throwIfCancelled() }
    }

    @Test
    fun `expired timeout token reports cancelled`() {
        val t = CancelToken.withTimeout(-1)
        assertTrue(t.isCancelled)
    }

    @Test
    fun `cancellation exception carries the partial report for resumability`() {
        val report = OperationReport(rowsAffected = 500, batches = 5, elapsedMillis = 12,
                                     completed = false, failedBatchIndex = 5,
                                     failedRowRange = 500L..599L)
        val e = OperationCancelledException(report)
        assertEquals(500, e.report?.rowsAffected)
        assertEquals(5, e.report?.failedBatchIndex)
        assertEquals(500L..599L, e.report?.failedRowRange)
        assertNull(OperationCancelledException().report, "read-side cancellation has no report")
    }

    // --- Schema / Row / RowBatch edges ---------------------------------------

    private val schema = Schema(listOf(
        Column("ID", ValueKind.NUMERIC, "BIGINT"),
        Column("Name", ValueKind.TEXT, "VARCHAR"),
    ))

    @Test
    fun `schema toString lists columns with type names`() {
        assertEquals("Schema(ID BIGINT, Name VARCHAR)", schema.toString())
    }

    @Test
    fun `schema equality is by columns and lookup is case-insensitive`() {
        val same = Schema(schema.columns)
        assertEquals(schema, same)
        assertEquals(schema.hashCode(), same.hashCode())
        assertFalse(schema.equals("not a schema"))
        assertTrue(schema.contains("name"))
        assertFalse(schema.contains("missing"))
        assertEquals(schema.columns[1], schema["NAME"])
        assertEquals(schema.columns[0], schema[0])
        assertThrows<NoSuchElementException> { schema.indexOf("missing") }
    }

    @Test
    fun `row exposes values by index name and map view`() {
        val row = Row(schema, listOf(1L, "alpha"))
        assertEquals(1L, row[0])
        assertEquals("alpha", row["name"])
        assertEquals(mapOf("ID" to 1L, "Name" to "alpha"), row.asMap())
        assertEquals("Row({ID=1, Name=alpha})", row.toString())
    }

    @Test
    fun `row batch enforces one schema and reports emptiness`() {
        val batch = RowBatch(schema, listOf(Row(schema, listOf(1L, "a"))))
        assertEquals(1, batch.size)
        assertFalse(batch.isEmpty())
        assertTrue(RowBatch(schema, emptyList()).isEmpty())
        val other = Schema(listOf(Column("x", ValueKind.TEXT, "VARCHAR")))
        assertThrows<IllegalArgumentException> {
            RowBatch(schema, listOf(Row(other, listOf("v"))))
        }
    }

    @Test
    fun `column with kind-relevant metadata round-trips`() {
        val c = Column("ts", ValueKind.TEMPORAL, "TIMESTAMPTZ", nullable = true,
                       precision = 6, scale = null, timeZoned = true)
        assertEquals(true, c.timeZoned)
        assertEquals(6, c.precision)
        assertThrows<IllegalArgumentException> { Column("", ValueKind.TEXT, "VARCHAR") }
    }
}
