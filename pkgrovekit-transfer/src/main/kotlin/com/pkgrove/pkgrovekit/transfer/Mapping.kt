package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind

/**
 * Named source-to-target column mapping (HEL-119). Target values are selected
 * by SOURCE COLUMN NAME, never by ordinal position — a source query may
 * reorder its SELECT list freely without changing where values land.
 *
 * Name matching is case-insensitive (SQL identifier semantics; the same
 * normalization [Schema] itself uses). Every rename and omission is validated
 * against the actual source schema BEFORE anything is written, and problems
 * are reported by name.
 */
class Mapping private constructor(
    private val renames: List<Pair<String, String>>,   // source -> target, declaration order
    private val constants: List<Triple<String, Any?, ValueKind>>,  // target, value, kind
    private val omitted: Set<String>,                  // normalized source names
) {

    class MappingException(message: String) : IllegalArgumentException(message)

    /** Builder for the mapping DSL: `Mapping.build { "src" mapsTo "dst"; ... }`. */
    class Builder internal constructor() {
        internal val renames = mutableListOf<Pair<String, String>>()
        internal val constants = mutableListOf<Triple<String, Any?, ValueKind>>()
        internal val omitted = mutableSetOf<String>()

        /** Route source column [this] into target column [target]. */
        infix fun String.mapsTo(target: String) { renames += this to target }

        /** Emit target column [target] with the same [value] on every row.
         *  [kind] is required when [value] is null (nothing to infer from). */
        fun constant(target: String, value: Any?, kind: ValueKind = inferKind(value)) {
            constants += Triple(target, value, kind)
        }

        /** Drop source column [source] from the transfer (target default applies). */
        fun omit(source: String) { omitted += source.lowercase() }

        private fun inferKind(value: Any?): ValueKind = when (value) {
            null -> throw MappingException("constant with null value requires an explicit ValueKind")
            is String -> ValueKind.TEXT
            is Boolean -> ValueKind.BOOLEAN
            is ByteArray -> ValueKind.BINARY
            is Number -> ValueKind.NUMERIC
            is java.time.temporal.Temporal -> ValueKind.TEMPORAL
            else -> throw MappingException("cannot infer a value kind for constant target; pass one explicitly")
        }
    }

    /** Where a target column's value comes from — exposed via [MappingPlan]. */
    sealed class Source {
        /** Value of the source column at [index] (name kept for diagnostics). */
        data class FromColumn(val index: Int, val name: String) : Source()
        /** The same constant [value] on every row. */
        data class Constant(val value: Any?) : Source()
    }

    /**
     * The RESOLVED plan: effective target schema plus, per target column,
     * exactly where its value comes from. Inspectable and testable before any
     * write happens (HEL-119: "expose the resolved mapping plan").
     */
    data class MappingPlan(
        val sourceSchema: Schema,
        val targetSchema: Schema,
        val sources: List<Source>,
    ) {
        init {
            require(sources.size == targetSchema.size)
        }

        override fun toString(): String =
            targetSchema.columns.mapIndexed { i, c ->
                val s = when (val src = sources[i]) {
                    is Source.FromColumn -> src.name
                    is Source.Constant -> "constant"
                }
                "$s -> ${c.name}"
            }.joinToString(", ", prefix = "MappingPlan(", postfix = ")")
    }

    /**
     * Resolve this mapping against a concrete [source] schema. Rejects — by
     * name, before any write — renames of columns that do not exist, colliding
     * target names, and omissions of unknown columns.
     */
    fun resolve(source: Schema): MappingPlan {
        val sourceNames = source.columns.map { it.name.lowercase() }.toSet()

        val unknownRenames = renames.map { it.first }.filter { it.lowercase() !in sourceNames }
        if (unknownRenames.isNotEmpty())
            throw MappingException("mapping references source columns that do not exist: " +
                                   unknownRenames.joinToString(", "))
        val unknownOmits = omitted.filter { it !in sourceNames }
        if (unknownOmits.isNotEmpty())
            throw MappingException("mapping omits source columns that do not exist: " +
                                   unknownOmits.joinToString(", "))
        val renamedSources = renames.map { it.first.lowercase() }
        renamedSources.groupBy { it }.filterValues { it.size > 1 }.keys.let {
            if (it.isNotEmpty())
                throw MappingException("source columns mapped more than once: ${it.joinToString(", ")}")
        }

        val renameBySource = renames.associate { it.first.lowercase() to it.second }
        val targetCols = mutableListOf<Column>()
        val sources = mutableListOf<Source>()

        source.columns.forEachIndexed { i, c ->
            val norm = c.name.lowercase()
            if (norm in omitted) return@forEachIndexed
            val targetName = renameBySource[norm] ?: c.name
            targetCols += c.copy(name = targetName)
            sources += Source.FromColumn(i, c.name)
        }
        constants.forEach { (target, value, kind) ->
            targetCols += Column(target, kind, kind.name)
            sources += Source.Constant(value)
        }

        val duplicateTargets = targetCols.map { it.name.lowercase() }
            .groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicateTargets.isNotEmpty())
            throw MappingException("mapping produces duplicate target columns: " +
                                   duplicateTargets.joinToString(", "))
        if (targetCols.isEmpty())
            throw MappingException("mapping leaves no target columns")

        return MappingPlan(source, Schema(targetCols), sources)
    }

    companion object {
        /** Identity mapping: every source column passes through by name. */
        @JvmField val IDENTITY = Mapping(emptyList(), emptyList(), emptySet())

        /** Kotlin DSL entry: `Mapping.build { "source_user" mapsTo "user_name" }`. */
        fun build(block: Builder.() -> Unit): Mapping {
            val b = Builder().apply(block)
            return Mapping(b.renames.toList(), b.constants.toList(), b.omitted.toSet())
        }

        /** Java-friendly form: `Mapping.of(Map.of("source_user", "user_name"))`. */
        @JvmStatic
        fun of(renames: Map<String, String>): Mapping =
            Mapping(renames.entries.map { it.key to it.value }, emptyList(), emptySet())
    }
}
