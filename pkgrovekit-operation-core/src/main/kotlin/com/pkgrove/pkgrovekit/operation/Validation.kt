package com.pkgrove.pkgrovekit.operation

import kotlin.reflect.KProperty1

/**
 * The validation DSL (HEL-602).
 *
 * Rules are declared against the TYPED input, once, and run in the pipeline
 * BEFORE authorization and before the handler. There is one validator per
 * operation and both adapters run it, so a REST client and an MCP agent get the
 * same [ValidationError] list for the same bad input — the property the issue
 * asks to be proven, obtained by construction rather than by duplicating rules
 * per transport.
 *
 * Property references (`SetActual::state`) supply the field NAME, so the error a
 * client sees always matches the field they sent. No reflection library is
 * involved: `KProperty1.name`/`get` are stdlib.
 */
public class ValidationScope<I> internal constructor() {

    private val rules = mutableListOf<(I) -> List<ValidationError>>()
    private val summary = mutableListOf<String>()
    private val fields = linkedSetOf<String>()

    /** Field names this validator has rules for — cross-checked against the schema. */
    internal fun referencedFields(): Set<String> = fields

    internal fun summaryLines(): List<String> = summary.toList()

    internal fun build(): (I) -> List<ValidationError> {
        val snapshot = rules.toList()
        return { input -> snapshot.flatMap { it(input) } }
    }

    /** Declares rules for the field behind [property]. */
    public fun <V> field(property: KProperty1<I, V>, rules: FieldRules<V>.() -> Unit) {
        field(property.name, { property.get(it) }, rules)
    }

    /** Declares rules for a named field extracted by [extract] (nested paths, computed fields). */
    public fun <V> field(name: String, extract: (I) -> V, rules: FieldRules<V>.() -> Unit) {
        require(name.isNotBlank()) { "a validated field needs a name" }
        fields += name
        val scope = FieldRules<V>(name).apply(rules)
        summary += scope.summaryLines()
        val checks = scope.build()
        this.rules += { input -> checks(extract(input)) }
    }

    /**
     * A whole-payload rule — cross-field invariants ("end must not precede
     * start") that no single field owns.
     */
    public fun rule(
        code: String,
        message: String,
        field: String = "",
        predicate: (I) -> Boolean,
    ) {
        require(code.isNotBlank()) { "a validation rule needs a code" }
        summary += if (field.isEmpty()) "payload: $code — $message" else "$field: $code — $message"
        rules += { input -> if (predicate(input)) emptyList() else listOf(ValidationError(field, code, message)) }
    }

    /** An escape hatch returning errors directly, for rules that must explain themselves. */
    public fun custom(describe: String = "custom", check: (I) -> List<ValidationError>) {
        summary += "payload: $describe"
        rules += check
    }
}

/**
 * Rules for one field. Each built-in adds a [ValidationError] with a STABLE
 * [ValidationError.code], because clients branch on codes, not on prose.
 *
 * Every rule except [required] SKIPS a null value: "absent" is [required]'s
 * business alone, so an optional field with a `scale(2)` rule does not produce
 * two errors when it is simply not sent.
 */
public class FieldRules<V> internal constructor(private val field: String) {

    private val checks = mutableListOf<(V) -> ValidationError?>()
    private val summary = mutableListOf<String>()

    internal fun summaryLines(): List<String> = summary.map { "$field: $it" }

    internal fun build(): (V) -> List<ValidationError> {
        val snapshot = checks.toList()
        return { value -> snapshot.mapNotNull { it(value) } }
    }

    private fun add(describe: String, code: String, message: String, ok: (V & Any) -> Boolean) {
        summary += describe
        checks += { value ->
            when {
                value == null -> null // only `required` speaks about absence
                ok(value) -> null
                else -> ValidationError(field, code, message)
            }
        }
    }

    /** The field must be present, and a String field must not be blank. */
    public fun required() {
        summary += "required"
        checks += { value ->
            val absent = value == null || (value is String && value.isBlank())
            if (absent) ValidationError(field, "required", "field '$field' is required") else null
        }
    }

    /** A numeric field (or numeric string) must be > 0. */
    public fun positive() {
        add("positive", "positive", "field '$field' must be greater than zero") { value ->
            numeric(value)?.let { it > java.math.BigDecimal.ZERO } ?: false
        }
    }

    /** A numeric field must be >= 0. */
    public fun nonNegative() {
        add("non-negative", "non_negative", "field '$field' must not be negative") { value ->
            numeric(value)?.let { it >= java.math.BigDecimal.ZERO } ?: false
        }
    }

    /**
     * A numeric field must have at most [digits] fractional digits. The money
     * rule: `scale(2)` rejects `1.005` rather than silently rounding it.
     */
    public fun scale(digits: Int) {
        require(digits >= 0) { "scale(digits) needs a non-negative digit count" }
        add(
            "scale <= $digits",
            "scale",
            "field '$field' must have at most $digits decimal place(s)",
        ) { value -> numeric(value)?.let { it.stripTrailingZeros().scale() <= digits } ?: false }
    }

    /** The field must be a 3-letter ISO-4217-shaped uppercase currency code. */
    public fun isoCurrency() {
        add("ISO-4217 currency", "iso_currency", "field '$field' must be a 3-letter ISO-4217 currency code") { value ->
            val text = value.toString()
            text.length == 3 && text.all { it in 'A'..'Z' }
        }
    }

    /** String length bounds (inclusive). */
    public fun length(min: Int = 0, max: Int = Int.MAX_VALUE) {
        require(min >= 0 && max >= min) { "length(min=$min, max=$max) is not a valid range" }
        val described = if (max == Int.MAX_VALUE) "length >= $min" else "length $min..$max"
        add(described, "length", "field '$field' must have $described") { value ->
            value.toString().length in min..max
        }
    }

    /** The field must match [regex]. */
    public fun pattern(regex: Regex, describe: String = regex.pattern) {
        add("matches $describe", "pattern", "field '$field' must match $describe") { value ->
            regex.matches(value.toString())
        }
    }

    /** The field must be one of [allowed] (compared by `toString`). */
    public fun oneOf(vararg allowed: String) {
        val set = allowed.toList()
        require(set.isNotEmpty()) { "oneOf(...) needs at least one allowed value" }
        add(
            "one of ${set.joinToString("|")}",
            "one_of",
            "field '$field' must be one of ${set.joinToString(", ")}",
        ) { value -> value.toString() in set }
    }

    /** A caller-supplied predicate with its own stable code and message. */
    public fun custom(code: String, message: String, ok: (V & Any) -> Boolean) {
        require(code.isNotBlank()) { "custom(code, ...) needs a non-blank code" }
        add("$code — $message", code, message, ok)
    }

    private fun numeric(value: Any): java.math.BigDecimal? = when (value) {
        is java.math.BigDecimal -> value
        is Int -> java.math.BigDecimal(value)
        is Long -> java.math.BigDecimal(value)
        is Short -> java.math.BigDecimal(value.toInt())
        is Byte -> java.math.BigDecimal(value.toInt())
        is Double -> java.math.BigDecimal(value.toString())
        is Float -> java.math.BigDecimal(value.toString())
        is String -> value.trim().toBigDecimalOrNull()
        else -> null
    }
}
