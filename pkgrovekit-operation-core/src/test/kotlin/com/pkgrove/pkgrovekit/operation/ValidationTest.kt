package com.pkgrove.pkgrovekit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The validation DSL and its built-in rules (HEL-602). */
class ValidationTest {

    private data class Money(
        val amount: String?,
        val currency: String?,
        val label: String?,
        val quantity: Int?,
        val state: String?,
    )

    private fun validator(declare: ValidationScope<Money>.() -> Unit): (Money) -> List<ValidationError> {
        val registry = operations(PermissiveSurfacePolicy) {
            write<Money, Unit>("money.set") {
                internalOnly(reason = "test")
                validate(declare)
                handle { _, _ -> }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (registry.byId("money.set") as Operation<Money, Unit>).validator
    }

    private fun money(
        amount: String? = "10.00",
        currency: String? = "TWD",
        label: String? = "dinner",
        quantity: Int? = 1,
        state: String? = "DONE",
    ) = Money(amount, currency, label, quantity, state)

    @Test
    fun `required rejects null and blank, and names the property`() {
        val check = validator { field(Money::label) { required() } }
        assertEquals(emptyList<ValidationError>(), check(money()))
        assertEquals(
            listOf(ValidationError("label", "required", "field 'label' is required")),
            check(money(label = null)),
        )
        assertEquals("required", check(money(label = "   ")).single().code)
    }

    @Test
    fun `positive and nonNegative compare numerically, not textually`() {
        val positive = validator { field(Money::amount) { positive() } }
        assertTrue(positive(money(amount = "0.01")).isEmpty())
        assertEquals("positive", positive(money(amount = "0")).single().code)
        assertEquals("positive", positive(money(amount = "-5")).single().code)
        assertEquals("positive", positive(money(amount = "not-a-number")).single().code)

        val nonNegative = validator { field(Money::amount) { nonNegative() } }
        assertTrue(nonNegative(money(amount = "0")).isEmpty())
        assertEquals("non_negative", nonNegative(money(amount = "-0.01")).single().code)
    }

    @Test
    fun `scale rejects more fractional digits than the currency allows`() {
        val check = validator { field(Money::amount) { scale(2) } }
        assertTrue(check(money(amount = "10")).isEmpty())
        assertTrue(check(money(amount = "10.5")).isEmpty())
        assertTrue(check(money(amount = "10.50")).isEmpty())
        // 10.500 strips to 10.5 — trailing zeros are presentation, not precision.
        assertTrue(check(money(amount = "10.500")).isEmpty())
        assertEquals("scale", check(money(amount = "10.005")).single().code)
        assertThrows<IllegalArgumentException> { validator { field(Money::amount) { scale(-1) } } }
    }

    @Test
    fun `isoCurrency requires three uppercase letters`() {
        val check = validator { field(Money::currency) { isoCurrency() } }
        assertTrue(check(money(currency = "TWD")).isEmpty())
        listOf("twd", "TW", "TWDD", "TW1").forEach {
            assertEquals("iso_currency", check(money(currency = it)).single().code, it)
        }
    }

    @Test
    fun `length bounds are inclusive and the open-ended form reads sensibly`() {
        val bounded = validator { field(Money::label) { length(min = 2, max = 4) } }
        assertTrue(bounded(money(label = "ab")).isEmpty())
        assertTrue(bounded(money(label = "abcd")).isEmpty())
        assertEquals("length", bounded(money(label = "a")).single().code)
        assertEquals("length", bounded(money(label = "abcde")).single().code)
        assertTrue(bounded(money(label = "a")).single().message.contains("length 2..4"))

        val minOnly = validator { field(Money::label) { length(min = 3) } }
        assertTrue(minOnly(money(label = "abcdefghij")).isEmpty())
        assertTrue(minOnly(money(label = "ab")).single().message.contains("length >= 3"))

        assertThrows<IllegalArgumentException> { validator { field(Money::label) { length(min = 5, max = 1) } } }
        assertThrows<IllegalArgumentException> { validator { field(Money::label) { length(min = -1) } } }
    }

    @Test
    fun `pattern matches the whole value`() {
        val check = validator { field(Money::label) { pattern(Regex("[a-z]+"), "lowercase letters") } }
        assertTrue(check(money(label = "dinner")).isEmpty())
        assertEquals("pattern", check(money(label = "Dinner")).single().code)
        assertTrue(check(money(label = "Dinner")).single().message.contains("lowercase letters"))
    }

    @Test
    fun `oneOf restricts to the declared set`() {
        val check = validator { field(Money::state) { oneOf("PLANNED", "DONE") } }
        assertTrue(check(money(state = "DONE")).isEmpty())
        assertEquals("one_of", check(money(state = "done")).single().code)
        assertThrows<IllegalArgumentException> { validator { field(Money::state) { oneOf() } } }
    }

    @Test
    fun `custom field rules carry their own code`() {
        val check = validator {
            field(Money::quantity) { custom("even", "quantity must be even") { it % 2 == 0 } }
        }
        assertTrue(check(money(quantity = 2)).isEmpty())
        assertEquals("even", check(money(quantity = 3)).single().code)
        assertThrows<IllegalArgumentException> {
            validator { field(Money::quantity) { custom(" ", "msg") { true } } }
        }
    }

    @Test
    fun `numeric rules accept every JVM number shape`() {
        val check = validator { field("q", { it.quantity }) { positive() } }
        assertTrue(check(money(quantity = 3)).isEmpty())
        assertEquals("positive", check(money(quantity = -3)).single().code)

        val longCheck = operations(PermissiveSurfacePolicy) {
            write<Numbers, Unit>("n.set") {
                internalOnly(reason = "test")
                validate {
                    field(Numbers::asLong) { positive() }
                    field(Numbers::asShort) { positive() }
                    field(Numbers::asByte) { positive() }
                    field(Numbers::asDouble) { positive() }
                    field(Numbers::asFloat) { positive() }
                    field(Numbers::asBigDecimal) { positive() }
                    field(Numbers::asList) { positive() }
                }
                handle { _, _ -> }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val run = (longCheck.byId("n.set") as Operation<Numbers, Unit>).validator
        assertTrue(
            run(Numbers(1L, 1.toShort(), 1.toByte(), 1.0, 1.0f, java.math.BigDecimal.ONE, emptyList())).map { it.field } ==
                listOf("asList"),
            "only the non-numeric field should fail",
        )
    }

    @Test
    fun `rules other than required skip an absent value so one mistake is one error`() {
        val check = validator {
            field(Money::amount) {
                positive()
                scale(2)
            }
        }
        assertTrue(check(money(amount = null)).isEmpty())
        // present and wrong on two counts: both rules speak, neither duplicates `required`
        assertEquals(listOf("positive", "scale"), check(money(amount = "-1.005")).map { it.code })
    }

    @Test
    fun `a whole-payload rule covers cross-field invariants`() {
        val check = validator {
            rule("currency_requires_amount", "currency may only be sent with an amount", "currency") {
                it.currency == null || it.amount != null
            }
            rule("sane", "payload must be sane") { true }
        }
        assertTrue(check(money()).isEmpty())
        val failure = check(money(amount = null)).single()
        assertEquals("currency", failure.field)
        assertEquals("currency_requires_amount", failure.code)
        assertThrows<IllegalArgumentException> { validator { rule("", "msg") { true } } }
    }

    @Test
    fun `a custom payload rule may return several errors`() {
        val check = validator {
            custom("two at once") {
                listOf(
                    ValidationError("a", "x", "first"),
                    ValidationError("b", "y", "second"),
                )
            }
        }
        assertEquals(listOf("a", "b"), check(money()).map { it.field })
    }

    @Test
    fun `the validation summary describes the rules for the Gallery`() {
        val registry = sampleRegistry()
        val summary = registry.byId("actual.set")!!.validationSummary
        assertTrue(summary.any { it.startsWith("state: required") }, summary.toString())
        assertTrue(summary.any { it.contains("one of PLANNED|BOOKED|DONE") }, summary.toString())
        assertTrue(summary.any { it.contains("scale <= 2") }, summary.toString())
        assertTrue(summary.any { it.contains("ISO-4217 currency") }, summary.toString())
    }

    @Test
    fun `a field name is required`() {
        assertThrows<IllegalArgumentException> { validator { field(" ", { it.label }) { required() } } }
    }

    @Test
    fun `an operation with no validate block validates nothing`() {
        val op = sampleRegistry().byId("plan.get")!!
        @Suppress("UNCHECKED_CAST")
        assertEquals(emptyList<ValidationError>(), (op as Operation<GetPlan, *>).validator(GetPlan("p-1")))
        assertEquals(emptyList<String>(), op.validationSummary)
    }

    private data class Numbers(
        val asLong: Long?,
        val asShort: Short?,
        val asByte: Byte?,
        val asDouble: Double?,
        val asFloat: Float?,
        val asBigDecimal: java.math.BigDecimal?,
        val asList: List<String>?,
    )
}
