package com.pkgrove.pkgrovekit.storage

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** HEL-236 scenarios 1–3: bounded parts, manifest-committed datasets, typed corruption. */
class DatasetAndFormatTest {

    private val store = InMemoryObjectStore()

    private val schema = Schema(
        listOf(
            Column("id", ValueKind.NUMERIC, "BIGINT"),
            Column("name", ValueKind.TEXT, "VARCHAR", nullable = true),
            Column("active", ValueKind.BOOLEAN, "BOOLEAN"),
            Column("born", ValueKind.TEMPORAL, "DATE"),
            Column("updated", ValueKind.TEMPORAL, "TIMESTAMP"),
            Column("payload", ValueKind.BINARY, "BLOB", nullable = true),
            Column("score", ValueKind.NUMERIC, "DECIMAL", precision = 10, scale = 2),
        ),
    )

    private fun row(i: Int): Row = Row(
        schema,
        listOf(
            i.toLong(),
            if (i % 7 == 0) null else "name-$i \"quoted\" \n multi",
            i % 2 == 0,
            LocalDate.of(2026, 1, 1).plusDays(i.toLong()),
            LocalDateTime.of(2026, 8, 9, 12, 0).plusSeconds(i.toLong()),
            if (i % 5 == 0) null else byteArrayOf(i.toByte(), (i + 1).toByte()),
            BigDecimal("12345.67").add(BigDecimal(i)),
        ),
    )

    private fun batches(rows: Int, per: Int): Sequence<RowBatch> =
        (0 until rows).map { row(it) }.chunked(per).map { RowBatch(schema, it) }.asSequence()

    @Test
    fun `jsonl round trip preserves typed values`() {
        val rows = (0 until 25).map { row(it) }
        val out = ByteArrayOutputStream()
        val written = JsonLinesRowFormat.write(schema, rows, out)
        assertEquals(25L, written)

        val back = JsonLinesRowFormat
            .read(schema, ByteArrayInputStream(out.toByteArray()), batchRows = 10)
            .toList()
        assertEquals(listOf(10, 10, 5), back.map { it.size })
        val decoded = back.flatMap { it.rows }
        decoded.forEachIndexed { i, r ->
            assertEquals(BigDecimal(i), (r["id"] as BigDecimal))
            if (i % 7 == 0) assertNull(r["name"]) else assertEquals("name-$i \"quoted\" \n multi", r["name"])
            assertEquals(i % 2 == 0, r["active"])
            assertEquals(LocalDate.of(2026, 1, 1).plusDays(i.toLong()), r["born"])
            assertEquals(LocalDateTime.of(2026, 8, 9, 12, 0).plusSeconds(i.toLong()), r["updated"])
            if (i % 5 == 0) assertNull(r["payload"])
            else assertTrue((r["payload"] as ByteArray).contentEquals(byteArrayOf(i.toByte(), (i + 1).toByte())))
            assertEquals(BigDecimal("12345.67").add(BigDecimal(i)), r["score"])
        }
    }

    @Test
    fun `jsonl decodes instants and rejects malformed lines`() {
        val tsSchema = Schema(listOf(Column("at", ValueKind.TEMPORAL, "TIMESTAMPTZ", timeZoned = true)))
        val instant = Instant.parse("2026-08-09T03:00:00Z")
        val out = ByteArrayOutputStream()
        JsonLinesRowFormat.write(tsSchema, listOf(Row(tsSchema, listOf(instant))), out)
        val back = JsonLinesRowFormat.read(tsSchema, ByteArrayInputStream(out.toByteArray()), 10)
            .single().rows.single()
        assertEquals(instant, back["at"])

        assertThrows(IllegalArgumentException::class.java) {
            JsonLinesRowFormat.read(tsSchema, "not-json\n".byteInputStream(), 10).toList()
        }
        assertThrows(IllegalArgumentException::class.java) {
            JsonLinesRowFormat.read(tsSchema, "{\"an\":\"object\"}\n".byteInputStream(), 10).toList()
        }
        assertThrows(IllegalArgumentException::class.java) {
            // wrong arity
            JsonLinesRowFormat.read(tsSchema, "[1,2]\n".byteInputStream(), 10).toList()
        }
    }

    @Test
    fun `export publishes bounded parts with a committed manifest`() {
        var progressCalls = 0
        val result = ObjectDataset.export(
            store, "datasets/people", "run-1", schema, batches(rows = 200, per = 37),
            ObjectDataset.ExportOptions(
                maxPartBytes = 2_000, // force several parts
                onProgress = { _, _ -> progressCalls++ },
            ),
        )
        val manifest = result.manifest
        assertTrue(manifest.parts.size > 1, "expected multiple bounded parts")
        assertEquals(200L, manifest.totalRows)
        assertEquals(200L, manifest.parts.sumOf { it.rowCount })
        assertTrue(progressCalls >= manifest.parts.size)
        // no staging residue after commit
        assertEquals(0, store.list("datasets/people/${StagingArea.STAGING_SEGMENT}/").count())
        // manifest round-trips through its JSON form
        val reparsed = DatasetManifest.parse(manifest.toJson())
        assertEquals(manifest, reparsed)

        // read back: schema + all rows verified
        val handle = ObjectDataset.open(store, result.manifestKey)
        assertEquals(schema, handle.schema)
        val rows = handle.batches().flatMap { it.rows }.toList()
        assertEquals(200, rows.size)
        assertEquals(BigDecimal(199), rows.last()["id"])
    }

    @Test
    fun `a failing export discards staging and publishes nothing`() {
        val boom = sequence<RowBatch> {
            yield(RowBatch(schema, (0 until 10).map { row(it) }))
            throw IllegalStateException("source died")
        }
        assertThrows(IllegalStateException::class.java) {
            ObjectDataset.export(store, "datasets/broken", "run-x", schema, boom)
        }
        assertEquals(0, store.list("datasets/broken/").count(), "no residue — staging discarded, nothing published")
    }

    @Test
    fun `corrupted part fails visibly on read`() {
        val result = ObjectDataset.export(
            store, "datasets/corrupt", "run-1", schema, batches(rows = 30, per = 10),
        )
        // corrupt a published part BEHIND the manifest's back — same length,
        // still valid JSONL, same row count: ONLY the checksum can catch it
        val part = result.manifest.parts.first()
        val body = store.get(part.key, GetOptions(verifyChecksum = false)).use {
            it.stream().readBytes().toString(Charsets.UTF_8)
        }
        val tampered = body.replaceFirst("name-", "nameX")
        assertTrue(tampered != body, "test needs a name value to tamper with")
        store.put(part.key, ContentSource.of(tampered))

        val e = assertThrows(ChecksumMismatchException::class.java) {
            ObjectDataset.open(store, result.manifestKey).batches().count()
        }
        assertEquals(part.key, e.key)

        // with verification off the same read succeeds only if row counts still
        // match — proving verifyChecksums is what caught the corruption
        val rows = ObjectDataset.open(
            store, result.manifestKey,
            ObjectDataset.ReadOptions(verifyChecksums = false),
        ).batches().sumOf { it.size }
        assertEquals(30, rows)
    }

    @Test
    fun `manifest row-count drift is refused`() {
        val result = ObjectDataset.export(
            store, "datasets/drift", "run-1", schema, batches(rows = 20, per = 10),
        )
        // truncate a part (drop its last line) — checksum off to isolate counts
        val part = result.manifest.parts.first()
        val bytes = store.get(part.key, GetOptions(verifyChecksum = false)).use { it.stream().readBytes() }
        val truncated = bytes.toString(Charsets.UTF_8).trim().lines().dropLast(1).joinToString("\n", postfix = "\n")
        store.put(part.key, ContentSource.of(truncated))

        val e = assertThrows(StorageIoException::class.java) {
            ObjectDataset.open(store, result.manifestKey, ObjectDataset.ReadOptions(verifyChecksums = false))
                .batches().count()
        }
        assertFalse(e.retrySafe)
        assertTrue(e.message!!.contains("refusing silently-partial data"))
    }

    @Test
    fun `unknown manifest format is rejected before any part read`() {
        val result = ObjectDataset.export(
            store, "datasets/fmt", "run-1", schema, batches(rows = 5, per = 5),
        )
        val hacked = result.manifest.toJson().replace("jsonl-v1", "parquet-v9")
        store.put(ObjectKey("datasets/fmt/run-1/manifest.json"), ContentSource.of(hacked))
        assertThrows(IllegalArgumentException::class.java) {
            ObjectDataset.open(store, result.manifestKey)
        }
    }

    @Test
    fun `export without conditional create is rejected before data movement`() {
        val limited = object : ObjectStore by store {
            override val capabilities = StorageCapabilities(provider = "no-cond", supported = emptySet())
        }
        assertThrows(CapabilityRejectedException::class.java) {
            ObjectDataset.export(limited, "datasets/nocond", "run-1", schema, batches(5, 5))
        }
        assertEquals(0, store.list("datasets/nocond/").count())
    }

    @Test
    fun `quarantine redacts declared columns and keeps keys value-free`() {
        val qSchema = Schema(
            listOf(
                Column("id", ValueKind.NUMERIC, "BIGINT"),
                Column("email", ValueKind.TEXT, "VARCHAR"),
                Column("note", ValueKind.TEXT, "VARCHAR"),
            ),
        )
        val writer = QuarantineWriter(store, "quarantine/orders", "run-9", setOf("EMAIL"))
        val receipt = writer.write(
            qSchema,
            listOf(
                Row(qSchema, listOf(1L, "alice@example.com", "bad row")),
                Row(qSchema, listOf(2L, "alice@example.com", "also bad")),
                Row(qSchema, listOf(3L, null, "null stays null")),
            ),
            reason = "conversion failed",
        )
        assertEquals(3L, receipt.rowCount)
        assertTrue(receipt.key.value.startsWith("quarantine/orders/run-9/rejected-"))

        val body = store.get(receipt.key).use { it.stream().readBytes().toString(Charsets.UTF_8) }
        assertFalse(body.contains("alice@example.com"), "sensitive value leaked into quarantine")
        // equal values share a fingerprint (traceable), and it is sha256-shaped
        val fingerprints = Regex("\"sha256:[0-9a-f]{16}\"").findAll(body).map { it.value }.toList()
        assertEquals(2, fingerprints.size)
        assertEquals(1, fingerprints.distinct().size)
        assertTrue(body.contains("null stays null"))

        val summaryKey = writer.writeSummary("conversion failed")
        val summary = store.get(summaryKey).use { it.stream().readBytes().toString(Charsets.UTF_8) }
        assertTrue(summary.contains("\"totalRows\":3"))
        assertFalse(summary.contains("alice"))
    }
}
