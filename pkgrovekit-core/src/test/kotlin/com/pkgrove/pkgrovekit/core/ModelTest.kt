package com.pkgrove.pkgrovekit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelTest {

    private fun schema() = Schema(listOf(
        Column("id", ValueKind.NUMERIC, "BIGINT", precision = 19, scale = 0),
        Column("name", ValueKind.TEXT, "VARCHAR"),
    ))

    @Test
    fun `schema lookup is case-insensitive and rejects duplicates`() {
        val s = schema()
        assertEquals(0, s.indexOf("ID"))
        assertEquals("name", s["NAME"].name)
        assertTrue(s.contains("Id"))
        assertFalse(s.contains("missing"))
        assertThrows(NoSuchElementException::class.java) { s.indexOf("missing") }
        assertThrows(IllegalArgumentException::class.java) {
            Schema(listOf(Column("a", ValueKind.TEXT, "T"), Column("A", ValueKind.TEXT, "T")))
        }
    }

    @Test
    fun `row aligns with schema and maps by name`() {
        val r = Row(schema(), listOf(7L, "seven"))
        assertEquals(7L, r["ID"])
        assertEquals("seven", r[1])
        assertEquals(mapOf("id" to 7L, "name" to "seven"), r.asMap())
        assertThrows(IllegalArgumentException::class.java) { Row(schema(), listOf(1L)) }
    }

    @Test
    fun `batch enforces one schema`() {
        val s = schema()
        val other = Schema(listOf(Column("x", ValueKind.TEXT, "T")))
        assertThrows(IllegalArgumentException::class.java) {
            RowBatch(s, listOf(Row(other, listOf("v"))))
        }
        assertEquals(0, RowBatch(s, emptyList()).size)
    }

    @Test
    fun `identifier gate validates and quotes without echoing bad names`() {
        assertEquals("\"trade_date\"", Identifiers.quote("trade_date"))
        assertEquals("\"S\".\"T\"", Identifiers.qualified("S", "T"))
        for (evil in listOf("x\" ) --", "a b", "1col", "", "col;drop", "x".repeat(129))) {
            val ex = assertThrows(Identifiers.UnsafeIdentifierException::class.java) {
                Identifiers.validate(evil, "column")
            }
            if (evil.isNotEmpty()) {
                assertFalse(ex.message!!.contains(evil), "raw identifier must not be echoed")
            }
        }
    }

    @Test
    fun `cancel token cancels explicitly and by deadline`() {
        val t = CancelToken.none()
        assertFalse(t.isCancelled)
        t.cancel()
        assertTrue(t.isCancelled)
        assertThrows(OperationCancelledException::class.java) { t.throwIfCancelled() }

        val timed = CancelToken.withTimeout(0)
        Thread.sleep(1)
        assertTrue(timed.isCancelled)
    }

    @Test
    fun `operation report surfaces partial completion`() {
        val r = OperationReport(rowsAffected = 10, batches = 2, elapsedMillis = 5,
                                completed = false, failedBatchIndex = 2,
                                failedRowRange = 10L until 15L)
        assertFalse(r.completed)
        assertEquals(2, r.failedBatchIndex)
        assertEquals(10L..14L, r.failedRowRange!!.first..r.failedRowRange!!.last)
        assertNull(OperationReport(1, 1, 1, true).failedBatchIndex)
    }
}
