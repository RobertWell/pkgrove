package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/**
 * HEL-234: constant-column kind inference — every inference branch and both
 * typed refusals (null without an explicit kind, uninferable values).
 */
class MappingInferKindTest {

    @Test
    fun `each constant value shape infers its kind at declaration time`() {
        // inference happens eagerly in the default argument — success = no throw
        Mapping.build {
            constant("s", "text")            // TEXT
            constant("b", true)              // BOOLEAN
            constant("bin", byteArrayOf(1))  // BINARY
            constant("n", 42)                // NUMERIC
            constant("d", 4.2)               // NUMERIC (any Number)
            constant("t", LocalDate.of(2026, 8, 9))  // TEMPORAL
        }
    }

    @Test
    fun `null constants require an explicit kind`() {
        val e = assertThrows<Mapping.MappingException> {
            Mapping.build { constant("c", null) }
        }
        assertTrue("explicit ValueKind" in (e.message ?: ""), e.message ?: "")
        // and WITH the explicit kind it is accepted
        Mapping.build { constant("c", null, ValueKind.TEXT) }
    }

    @Test
    fun `uninferable constants are refused with the actionable message`() {
        class Opaque
        val e = assertThrows<Mapping.MappingException> {
            Mapping.build { constant("c", Opaque()) }
        }
        assertTrue("pass one explicitly" in (e.message ?: ""), e.message ?: "")
    }
}
