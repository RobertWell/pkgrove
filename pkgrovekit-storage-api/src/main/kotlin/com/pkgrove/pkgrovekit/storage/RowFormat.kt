package com.pkgrove.pkgrovekit.storage

import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Base64

/**
 * How rows become object-part bytes and back (HEL-236 scenarios 1–2). The
 * format is a SEAM: PkgroveKit ships the dependency-free [JsonLinesRowFormat];
 * columnar formats (Parquet) are deliberately delegated to engines that already
 * own them — DuckDB reads/writes Parquet on S3 natively (`docs/storage.md`
 * shows that path) — because a faithful Parquet writer would drag the Hadoop
 * dependency tree into a module whose contract is "lightweight".
 */
interface RowPartFormat {
    /** Stable identifier recorded in manifests (a reader must match it). */
    val id: String

    /** Suggested key suffix, e.g. `jsonl`. */
    val fileExtension: String

    /** Encode [rows] to [out]; returns the row count written. Streaming: one row at a time. */
    fun write(schema: Schema, rows: Iterable<Row>, out: OutputStream): Long

    /** Decode a part back into batches of at most [batchRows] rows. Streaming. */
    fun read(schema: Schema, input: InputStream, batchRows: Int): Sequence<RowBatch>
}

/**
 * JSON Lines: one JSON array per row, positionally aligned with the schema
 * (arrays, not objects — column names live once in the manifest, not per row).
 * Values are the normalized JDK types of the core model: strings/booleans as
 * JSON natives, numbers via [BigDecimal] plain strings (exact — never float
 * round-tripping), temporal types as ISO-8601 strings, binary as base64.
 * Decoding is schema-directed via [ValueKind], so a round trip re-binds
 * faithfully to JDBC.
 */
object JsonLinesRowFormat : RowPartFormat {
    override val id: String = "jsonl-v1"
    override val fileExtension: String = "jsonl"

    override fun write(schema: Schema, rows: Iterable<Row>, out: OutputStream): Long {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        var count = 0L
        for (row in rows) {
            require(row.schema == schema) { "row schema differs from part schema" }
            val sb = StringBuilder(64)
            sb.append('[')
            row.values.forEachIndexed { i, v ->
                if (i > 0) sb.append(',')
                sb.append(encodeValue(v))
            }
            sb.append(']').append('\n')
            writer.write(sb.toString())
            count++
        }
        writer.flush()
        return count
    }

    override fun read(schema: Schema, input: InputStream, batchRows: Int): Sequence<RowBatch> {
        require(batchRows > 0) { "batchRows must be positive" }
        return sequence {
            val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
            val rows = ArrayList<Row>(batchRows)
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val values = (Json.parse(line) as? List<*>)
                    ?: throw IllegalArgumentException("jsonl row is not a JSON array")
                require(values.size == schema.size) {
                    "jsonl row has ${values.size} values for ${schema.size} columns"
                }
                rows += Row(schema, values.mapIndexed { i, v -> decodeValue(v, schema[i].kind) })
                if (rows.size == batchRows) {
                    yield(RowBatch(schema, rows.toList()))
                    rows.clear()
                }
            }
            if (rows.isNotEmpty()) yield(RowBatch(schema, rows.toList()))
        }
    }

    private fun encodeValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is BigDecimal -> v.toPlainString()
        is Byte, is Short, is Int, is Long -> v.toString()
        is Float, is Double -> BigDecimal(v.toString()).toPlainString()
        is ByteArray -> Json.quote(Base64.getEncoder().encodeToString(v))
        is String -> Json.quote(v)
        // java.time types all have ISO-8601 toString()
        else -> Json.quote(v.toString())
    }

    private fun decodeValue(v: Any?, kind: ValueKind): Any? = when (v) {
        null -> null
        is Boolean -> v
        is Json.RawNumber -> BigDecimal(v.text)
        is String -> when (kind) {
            ValueKind.BINARY -> Base64.getDecoder().decode(v)
            ValueKind.TEMPORAL -> parseTemporal(v)
            ValueKind.NUMERIC -> BigDecimal(v)
            ValueKind.BOOLEAN -> v.toBooleanStrictOrNull() ?: v
            else -> v
        }
        else -> throw IllegalArgumentException("unsupported jsonl value shape: ${v::class.simpleName}")
    }

    /** Best-known ISO-8601 shapes, most specific first; unknown stays String. */
    private fun parseTemporal(s: String): Any = runCatching<Any> {
        when {
            s.endsWith("Z") && 'T' in s -> Instant.parse(s)
            ('+' in s.substringAfter('T', "")) || s.substringAfter('T', "").contains('-') ->
                OffsetDateTime.parse(s)
            'T' in s -> LocalDateTime.parse(s)
            ':' in s -> LocalTime.parse(s)
            else -> LocalDate.parse(s)
        }
    }.getOrDefault(s)
}
