package com.pkgrove.pkgrovekit.storage

import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.Schema
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock

/**
 * Failed-row quarantine with redaction (HEL-236 scenario 6). Rejected rows are
 * written under `<prefix>/<runId>/` so audit evidence stays traceable to the
 * run that produced it, and the columns the caller declares sensitive are
 * REDACTED before any byte leaves the process: the stored value is
 * `sha256:<16 hex>` — a stable fingerprint (equal values match across rows,
 * supporting investigation) that cannot be reversed to the value.
 *
 * Security invariants: keys carry only runId + counters (never row data), the
 * summary object carries counts + the caller's reason string (never values),
 * and redaction is applied per WRITE — there is no unredacted code path.
 */
class QuarantineWriter(
    private val store: ObjectStore,
    private val prefix: String,
    val runId: String,
    redactColumns: Set<String>,
    private val format: RowPartFormat = JsonLinesRowFormat,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(prefix.isNotEmpty() && !prefix.endsWith("/")) {
            "prefix must be non-empty without a trailing '/'"
        }
        require(runId.isNotEmpty() && runId.none { it == '/' }) { "runId must be a single path segment" }
    }

    private val redactLower = redactColumns.map { it.lowercase() }.toSet()
    private var partCounter = 0
    private var totalRows = 0L

    data class Receipt(val key: ObjectKey, val rowCount: Long)

    /** Write one batch of rejected rows (redacted). Returns where they landed. */
    fun write(schema: Schema, rows: List<Row>, reason: String): Receipt {
        require(rows.isNotEmpty()) { "refusing to write an empty quarantine part" }
        val redactIdx = schema.columns.withIndex()
            .filter { it.value.name.lowercase() in redactLower }
            .map { it.index }
            .toSet()
        val redacted = if (redactIdx.isEmpty()) {
            rows
        } else {
            rows.map { row ->
                Row(
                    schema,
                    row.values.mapIndexed { i, v ->
                        if (i in redactIdx && v != null) fingerprint(v) else v
                    },
                )
            }
        }
        partCounter += 1
        val key = ObjectKey("$prefix/$runId/rejected-%05d.%s".format(partCounter, format.fileExtension))
        val buffer = ByteArrayOutputStream()
        val written = format.write(schema, redacted, buffer)
        val bytes = buffer.toByteArray()
        store.put(
            key, ContentSource.of(bytes),
            PutOptions(
                checksum = Checksum.sha256(bytes)
                    .takeIf { StorageCapability.CHECKSUM_SHA256 in store.capabilities },
                userMetadata = mapOf("pkgrovekit-quarantine-reason" to sanitize(reason)),
            ),
        )
        totalRows += written
        return Receipt(key, written)
    }

    /** Write the run summary (counts + reason — no row data). Call once, last. */
    fun writeSummary(reason: String): ObjectKey {
        val key = ObjectKey("$prefix/$runId/summary.json")
        val body = buildString {
            append("{\"runId\":").append(Json.quote(runId))
            append(",\"createdAt\":").append(Json.quote(clock.instant().toString()))
            append(",\"reason\":").append(Json.quote(sanitize(reason)))
            append(",\"parts\":").append(partCounter)
            append(",\"totalRows\":").append(totalRows)
            append('}')
        }
        store.put(key, ContentSource.of(body), PutOptions(contentType = "application/json"))
        return key
    }

    private fun fingerprint(value: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toString().toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Reasons are labels: strip control chars so log/metadata layers stay clean. */
    private fun sanitize(reason: String): String =
        reason.filter { it.code in 0x20..0x7e || it.code > 0x9f }.take(512)
}
