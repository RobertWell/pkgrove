package io.maxxga.rowrelay.core

/**
 * The common dynamic data model (HEL-120 capability 1): tabular data described
 * at runtime — no DTOs, no entities, no generated classes, no per-table code.
 *
 * Driver-specific objects must never appear in these contracts: adapters
 * normalize vendor values (oracle.sql.TIMESTAMP, java.sql.Clob, ...) into the
 * JDK types enumerated by [ValueKind] BEFORE they reach a [Row], attaching a
 * [DataWarning] whenever a value cannot be represented safely.
 */

/** Broad classification of a column's values, dialect-independent. */
enum class ValueKind { TEXT, NUMERIC, BOOLEAN, TEMPORAL, BINARY, OTHER }

/**
 * A runtime-discovered column. [typeName] is the source database's own name
 * for the type (e.g. "NUMBER", "VARCHAR", "TIMESTAMP WITH TIME ZONE") and is
 * informational; portable logic must branch on [kind]/[precision]/[scale]/
 * [timeZoned], not on vendor type names.
 */
data class Column(
    val name: String,
    val kind: ValueKind,
    val typeName: String,
    val nullable: Boolean? = null,
    val precision: Int? = null,
    val scale: Int? = null,
    /** true when the type carries an explicit time zone / offset. */
    val timeZoned: Boolean? = null,
) {
    init {
        require(name.isNotEmpty()) { "column name must not be empty" }
    }
}

/** An ordered set of columns; lookup is case-insensitive (SQL semantics). */
class Schema(val columns: List<Column>) {
    private val byLowerName: Map<String, Int> = buildMap {
        columns.forEachIndexed { i, c ->
            val key = c.name.lowercase()
            require(put(key, i) == null) { "duplicate column name: ${c.name}" }
        }
    }

    val size: Int get() = columns.size

    fun indexOf(name: String): Int =
        byLowerName[name.lowercase()]
            ?: throw NoSuchElementException("no such column: $name")

    fun contains(name: String): Boolean = name.lowercase() in byLowerName

    operator fun get(name: String): Column = columns[indexOf(name)]
    operator fun get(index: Int): Column = columns[index]

    override fun toString(): String =
        "Schema(${columns.joinToString(", ") { "${it.name} ${it.typeName}" }})"

    override fun equals(other: Any?): Boolean = other is Schema && other.columns == columns
    override fun hashCode(): Int = columns.hashCode()
}

/**
 * One row: values positionally aligned with a [Schema]. Values are already
 * normalized JDK types (String, BigDecimal, Long, Boolean, java.time.*,
 * ByteArray, ...) — never driver classes.
 */
class Row(val schema: Schema, val values: List<Any?>) {
    init {
        require(values.size == schema.size) {
            "row has ${values.size} values for ${schema.size} columns"
        }
    }

    operator fun get(index: Int): Any? = values[index]
    operator fun get(name: String): Any? = values[schema.indexOf(name)]

    /** Map view (ordered, case-preserving column names) for map-oriented callers. */
    fun asMap(): Map<String, Any?> =
        schema.columns.indices.associate { schema.columns[it].name to values[it] }

    override fun toString(): String = "Row(${asMap()})"
}

/** An ordered chunk of rows sharing one schema — the transfer/batch unit. */
class RowBatch(val schema: Schema, val rows: List<Row>) {
    init {
        rows.forEach { require(it.schema == schema) { "row schema differs from batch schema" } }
    }

    val size: Int get() = rows.size
    fun isEmpty(): Boolean = rows.isEmpty()
}

/**
 * A non-fatal representation/conversion problem the caller MUST be able to
 * see (never silent): lossy conversion, unrepresentable value, skipped column.
 */
data class DataWarning(
    val code: String,
    val message: String,
    val column: String? = null,
) {
    override fun toString(): String =
        "[$code]${column?.let { " $it:" } ?: ""} $message"
}
