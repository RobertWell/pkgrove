package com.pkgrove.pkgrovekit.storage

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant

/**
 * Row datasets as manifest-committed object parts (HEL-236 scenarios 1–3):
 * bounded encoded parts under a run-scoped prefix, published atomically via
 * [StagingArea] — the manifest is the ONLY thing a reader trusts, so a
 * mid-export failure never yields a readable half-dataset. Reading streams
 * parts back into [RowBatch]es with per-part checksum + row-count
 * verification; corruption fails visibly and typed.
 *
 * Pairs with pkgrovekit-jdbc/transfer naturally: a `JdbcReader`'s batches feed
 * [export]; [DatasetHandle.batches] feeds a `JdbcBatchWriter` — that is the
 * database → object storage → database path proven end-to-end in
 * `integration-tests` (`StorageDatasetRoundTripIT`).
 */
object ObjectDataset {

    data class ExportOptions(
        val format: RowPartFormat = JsonLinesRowFormat,
        /** Bound of ONE encoded part buffer — the export's peak memory. */
        val maxPartBytes: Int = 16 * 1024 * 1024,
        val manifestName: String = "manifest.json",
        val cancelToken: CancelToken = CancelToken.none(),
        /** (partsWritten, rowsWritten) — log-safe progress, no keys/values. */
        val onProgress: ((Int, Long) -> Unit)? = null,
        val clock: Clock = Clock.systemUTC(),
    ) {
        init {
            require(maxPartBytes > 0) { "maxPartBytes must be positive" }
        }
    }

    data class ExportResult(val manifestKey: ObjectKey, val manifest: DatasetManifest)

    /**
     * Stream [batches] into `<prefix>/<runId>/part-*.…` + a committed manifest.
     * Bounded: at most ONE encoded part ([ExportOptions.maxPartBytes]) is held.
     * On any failure the staging area is discarded and nothing was published.
     */
    @JvmStatic
    fun export(
        store: ObjectStore,
        prefix: String,
        runId: String,
        schema: Schema,
        batches: Sequence<RowBatch>,
        options: ExportOptions = ExportOptions(),
    ): ExportResult {
        val staging = StagingArea(store, prefix, runId) // capability gate runs HERE
        val declareChecksum = StorageCapability.CHECKSUM_SHA256 in store.capabilities
        val parts = mutableListOf<DatasetPart>()
        try {
            val buffer = ByteArrayOutputStream(minOf(options.maxPartBytes, 1 shl 20))
            var bufferedRows = 0L
            var totalRows = 0L

            fun flushPart() {
                if (bufferedRows == 0L) return
                options.cancelToken.throwIfCancelled()
                val bytes = buffer.toByteArray()
                buffer.reset()
                val name = "part-%05d.%s".format(parts.size + 1, options.format.fileExtension)
                val checksum = Checksum.sha256(bytes)
                staging.stage(
                    name, ContentSource.of(bytes),
                    PutOptions(checksum = checksum.takeIf { declareChecksum }),
                )
                parts += DatasetPart(
                    key = ObjectKey("$prefix/$runId/$name"), // FINAL key, recorded in the manifest
                    stagedName = name,
                    sizeBytes = bytes.size.toLong(),
                    rowCount = bufferedRows,
                    sha256Base64 = checksum.valueBase64,
                )
                options.onProgress?.invoke(parts.size, totalRows)
                bufferedRows = 0L
            }

            for (batch in batches) {
                options.cancelToken.throwIfCancelled()
                require(batch.schema == schema) { "batch schema differs from dataset schema" }
                bufferedRows += options.format.write(schema, batch.rows, buffer)
                totalRows += batch.size
                if (buffer.size() >= options.maxPartBytes) flushPart()
            }
            flushPart()

            val manifest = DatasetManifest(
                formatId = options.format.id,
                runId = runId,
                createdAt = options.clock.instant(),
                schema = schema,
                parts = parts.toList(),
                totalRows = totalRows,
            )
            val manifestKey = ObjectKey("$prefix/$runId/${options.manifestName}")
            val plan = parts.associate { staging.stageKey(it.stagedName) to it.key }
            staging.publish(plan, manifestKey, ContentSource.of(manifest.toJson()))
            return ExportResult(manifestKey, manifest)
        } catch (e: Exception) {
            // deterministic cleanup: an aborted export leaves NOTHING staged
            runCatching { staging.discard() }
            throw e
        }
    }

    data class ReadOptions(
        val batchRows: Int = 1_000,
        /** Verify each part's manifest sha256 while streaming (strongly advised). */
        val verifyChecksums: Boolean = true,
        val cancelToken: CancelToken = CancelToken.none(),
    )

    /** Open a published dataset by its manifest key. */
    @JvmStatic
    fun open(store: ObjectStore, manifestKey: ObjectKey, options: ReadOptions = ReadOptions()): DatasetHandle {
        val manifestText = store.get(manifestKey).use { it.stream().readBytes().toString(Charsets.UTF_8) }
        val manifest = DatasetManifest.parse(manifestText)
        return DatasetHandle(store, manifest, options)
    }
}

/** One published dataset part as the manifest records it. */
data class DatasetPart(
    val key: ObjectKey,
    val stagedName: String,
    val sizeBytes: Long,
    val rowCount: Long,
    val sha256Base64: String,
)

/**
 * The dataset commit record. Existence of a (conditionally-created) manifest
 * is the dataset's existence; its part list is the dataset's exact extent.
 */
data class DatasetManifest(
    val formatId: String,
    val runId: String,
    val createdAt: Instant,
    val schema: Schema,
    val parts: List<DatasetPart>,
    val totalRows: Long,
) {
    fun toJson(): String = buildString {
        append("{\"version\":1,")
        append("\"format\":").append(Json.quote(formatId)).append(',')
        append("\"runId\":").append(Json.quote(runId)).append(',')
        append("\"createdAt\":").append(Json.quote(createdAt.toString())).append(',')
        append("\"totalRows\":").append(totalRows).append(',')
        append("\"schema\":[")
        schema.columns.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"name\":").append(Json.quote(c.name))
            append(",\"kind\":").append(Json.quote(c.kind.name))
            append(",\"typeName\":").append(Json.quote(c.typeName))
            c.nullable?.let { append(",\"nullable\":").append(it) }
            c.precision?.let { append(",\"precision\":").append(it) }
            c.scale?.let { append(",\"scale\":").append(it) }
            c.timeZoned?.let { append(",\"timeZoned\":").append(it) }
            append('}')
        }
        append("],\"parts\":[")
        parts.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append("{\"key\":").append(Json.quote(p.key.value))
            append(",\"stagedName\":").append(Json.quote(p.stagedName))
            append(",\"sizeBytes\":").append(p.sizeBytes)
            append(",\"rowCount\":").append(p.rowCount)
            append(",\"sha256\":").append(Json.quote(p.sha256Base64))
            append('}')
        }
        append("]}")
    }

    companion object {
        @JvmStatic
        fun parse(json: String): DatasetManifest {
            val root = Json.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("dataset manifest is not a JSON object")

            fun str(m: Map<*, *>, k: String): String =
                m[k] as? String ?: throw IllegalArgumentException("manifest field '$k' missing/not a string")

            fun long(m: Map<*, *>, k: String): Long =
                (m[k] as? Json.RawNumber)?.text?.toLong()
                    ?: throw IllegalArgumentException("manifest field '$k' missing/not a number")

            val schema = Schema(
                (root["schema"] as? List<*> ?: throw IllegalArgumentException("manifest has no schema"))
                    .map { c ->
                        c as Map<*, *>
                        Column(
                            name = str(c, "name"),
                            kind = ValueKind.valueOf(str(c, "kind")),
                            typeName = str(c, "typeName"),
                            nullable = c["nullable"] as? Boolean,
                            precision = (c["precision"] as? Json.RawNumber)?.text?.toInt(),
                            scale = (c["scale"] as? Json.RawNumber)?.text?.toInt(),
                            timeZoned = c["timeZoned"] as? Boolean,
                        )
                    },
            )
            val parts = (root["parts"] as? List<*> ?: throw IllegalArgumentException("manifest has no parts"))
                .map { p ->
                    p as Map<*, *>
                    DatasetPart(
                        key = ObjectKey(str(p, "key")),
                        stagedName = str(p, "stagedName"),
                        sizeBytes = long(p, "sizeBytes"),
                        rowCount = long(p, "rowCount"),
                        sha256Base64 = str(p, "sha256"),
                    )
                }
            return DatasetManifest(
                formatId = str(root, "format"),
                runId = str(root, "runId"),
                createdAt = Instant.parse(str(root, "createdAt")),
                schema = schema,
                parts = parts,
                totalRows = long(root, "totalRows"),
            )
        }
    }
}

/**
 * A readable, verified view of a published dataset. [batches] streams — one
 * part connection and at most [ObjectDataset.ReadOptions.batchRows] decoded
 * rows live at a time. Verification: per-part sha256 at part EOF, per-part
 * and total row counts after decode; any mismatch throws typed.
 */
class DatasetHandle internal constructor(
    private val store: ObjectStore,
    val manifest: DatasetManifest,
    private val options: ObjectDataset.ReadOptions,
) {
    private val format: RowPartFormat = when (manifest.formatId) {
        JsonLinesRowFormat.id -> JsonLinesRowFormat
        else -> throw IllegalArgumentException(
            "dataset format '${manifest.formatId}' has no registered reader",
        )
    }

    val schema: Schema get() = manifest.schema

    fun batches(): Sequence<RowBatch> = sequence {
        var totalRows = 0L
        for (part in manifest.parts) {
            options.cancelToken.throwIfCancelled()
            val expected = Checksum(ChecksumAlgorithm.SHA256, part.sha256Base64)
                .takeIf { options.verifyChecksums }
            var partRows = 0L
            store.get(
                part.key,
                GetOptions(verifyChecksum = false, expectedChecksum = expected),
            ).use { content ->
                for (batch in format.read(manifest.schema, content.stream(), options.batchRows)) {
                    options.cancelToken.throwIfCancelled()
                    partRows += batch.size
                    totalRows += batch.size
                    yield(batch)
                }
                // format.read consumed to EOF => checksum verified by the stream
            }
            if (partRows != part.rowCount) {
                throw StorageIoException(
                    "dataset part '${part.key}' decoded $partRows rows but the manifest " +
                        "records ${part.rowCount} — refusing silently-partial data",
                    retrySafe = false,
                )
            }
        }
        if (totalRows != manifest.totalRows) {
            throw StorageIoException(
                "dataset '${manifest.runId}' decoded $totalRows rows but the manifest " +
                    "records ${manifest.totalRows}",
                retrySafe = false,
            )
        }
    }
}
