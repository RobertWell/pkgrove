package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.storage.CheckpointStore
import com.pkgrove.pkgrovekit.storage.ChecksumMismatchException
import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.GetOptions
import com.pkgrove.pkgrovekit.storage.ObjectDataset
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.PreconditionFailedException
import com.pkgrove.pkgrovekit.storage.QuarantineWriter
import com.pkgrove.pkgrovekit.storage.StagingArea
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.Duration

/**
 * HEL-236 scenarios 3/4/6 on real MinIO: staged atomic publish, resumable
 * conditional checkpoints, dataset export/read with corruption detection, and
 * redacted quarantine — the storage-api workflows running against a provider
 * instead of the in-memory reference.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioWorkflowsIT {

    @BeforeAll
    fun dockerOrSkip() = Assumptions.assumeTrue(
        MinioSupport.dockerAvailable,
        "Docker unavailable — MinIO ITs skipped here; they run for real in the dind-backed CI jobs",
    )

    // LAZY on purpose: an eager field initialiser starts the container during
    // class CONSTRUCTION, i.e. before @BeforeAll can skip — which is why the
    // Docker assumption alone did not stop the no-daemon failure.
    private val store by lazy { MinioSupport.newStore() }

    @AfterAll
    fun tearDown() {
        // Only close what was actually opened — touching `store` here would
        // instantiate it and re-create the very failure this guards against.
        if (MinioSupport.dockerAvailable) store.close()
    }

    private val schema = Schema(
        listOf(
            Column("id", ValueKind.NUMERIC, "BIGINT"),
            Column("label", ValueKind.TEXT, "VARCHAR"),
        ),
    )

    private fun batches(rows: Int, per: Int = 100): Sequence<RowBatch> =
        (0 until rows).map { Row(schema, listOf(it.toLong(), "label-$it")) }
            .chunked(per).map { RowBatch(schema, it) }.asSequence()

    @Test
    fun `staged publish commits atomically via the conditional manifest`() {
        val staging = StagingArea(store, "staged/out", "run-1")
        staging.stage("part-1", ContentSource.of("part-one"))
        // invisible before commit
        assertEquals(0, store.list("staged/out/run-1/").count())
        staging.publish(
            plan = mapOf(staging.stageKey("part-1") to ObjectKey("staged/out/run-1/part-1")),
            manifestKey = ObjectKey("staged/out/run-1/manifest.json"),
            manifestBody = ContentSource.of("{\"parts\":1}"),
        )
        assertTrue(store.exists(ObjectKey("staged/out/run-1/manifest.json")))
        assertEquals(0, store.list("staged/out/${StagingArea.STAGING_SEGMENT}/").count())

        // a second publisher racing the SAME manifest loses typed and rolls back
        val loser = StagingArea(store, "staged/out", "run-2")
        loser.stage("part-1", ContentSource.of("loser-part"))
        assertThrows(PreconditionFailedException::class.java) {
            loser.publish(
                plan = mapOf(loser.stageKey("part-1") to ObjectKey("staged/out/run-2/part-1")),
                manifestKey = ObjectKey("staged/out/run-1/manifest.json"),
                manifestBody = ContentSource.of("{\"parts\":1}"),
            )
        }
        assertFalse(store.exists(ObjectKey("staged/out/run-2/part-1")))
        loser.discard()
    }

    @Test
    fun `abandoned staging is reaped by the deterministic cleanup rule`() {
        val abandoned = StagingArea(store, "staged/reap", "run-dead")
        abandoned.stage("part-1", ContentSource.of("orphan"))
        Thread.sleep(1100) // MinIO lastModified has second precision
        val reaped = StagingArea.cleanupAbandoned(store, "staged/reap", Duration.ZERO)
        assertEquals(1L, reaped)
        assertEquals(0, abandoned.stagedObjects().size)
    }

    @Test
    fun `checkpoints conflict typed under concurrent workers`() {
        val checkpoints = CheckpointStore(store, "ckpt/transfer-1")
        assertNull(checkpoints.latest())
        val c1 = checkpoints.save("""{"offset":500}""", null)
        val c2 = checkpoints.save("""{"offset":900}""", c1.sequence)
        assertEquals(2L, c2.sequence)
        assertEquals("""{"offset":900}""", checkpoints.latest()!!.data)
        // stale worker cannot clobber committed progress
        assertThrows(PreconditionFailedException::class.java) {
            CheckpointStore(store, "ckpt/transfer-1").save("""{"offset":700}""", c1.sequence)
        }
        assertEquals("""{"offset":900}""", checkpoints.latest()!!.data)
    }

    @Test
    fun `dataset export-read round trip with provider-verified parts`() {
        val result = ObjectDataset.export(
            store, "datasets/roundtrip", "run-1", schema, batches(rows = 5_000),
            ObjectDataset.ExportOptions(maxPartBytes = 64 * 1024),
        )
        assertTrue(result.manifest.parts.size > 1)
        assertEquals(5_000L, result.manifest.totalRows)
        // no staging residue on the provider
        assertEquals(0, store.list("datasets/roundtrip/${StagingArea.STAGING_SEGMENT}/").count())

        val handle = ObjectDataset.open(store, result.manifestKey)
        assertEquals(schema, handle.schema)
        val rows = handle.batches().flatMap { it.rows }.toList()
        assertEquals(5_000, rows.size)
        assertEquals(BigDecimal(4_999), rows.last()["id"])
        assertEquals("label-4999", rows.last()["label"])
    }

    @Test
    fun `tampered dataset part fails the read visibly`() {
        val result = ObjectDataset.export(
            store, "datasets/tamper", "run-1", schema, batches(rows = 500),
        )
        val part = result.manifest.parts.first()
        val body = store.get(part.key, GetOptions(verifyChecksum = false)).use {
            it.stream().readBytes().toString(Charsets.UTF_8)
        }
        store.put(part.key, ContentSource.of(body.replaceFirst("label-", "labelX")))
        val e = assertThrows(ChecksumMismatchException::class.java) {
            ObjectDataset.open(store, result.manifestKey).batches().count()
        }
        assertEquals(part.key, e.key)
    }

    @Test
    fun `interrupted export leaves no published dataset`() {
        val dying = sequence<RowBatch> {
            yieldAll(batches(rows = 300))
            throw IllegalStateException("source connection lost")
        }
        assertThrows(IllegalStateException::class.java) {
            ObjectDataset.export(
                store, "datasets/interrupted", "run-1", schema, dying,
                ObjectDataset.ExportOptions(maxPartBytes = 4 * 1024),
            )
        }
        assertEquals(
            0, store.list("datasets/interrupted/").count(),
            "an interrupted export must leave neither manifest nor residue",
        )
    }

    @Test
    fun `quarantine on the provider redacts and stays traceable`() {
        val writer = QuarantineWriter(store, "quarantine/import", "run-7", setOf("secret_col"))
        val qSchema = Schema(
            listOf(
                Column("id", ValueKind.NUMERIC, "BIGINT"),
                Column("secret_col", ValueKind.TEXT, "VARCHAR"),
            ),
        )
        val receipt = writer.write(
            qSchema,
            listOf(Row(qSchema, listOf(1L, "api-key-hunter2"))),
            reason = "schema drift",
        )
        val body = store.get(receipt.key).use { it.stream().readBytes().toString(Charsets.UTF_8) }
        assertFalse(body.contains("hunter2"), "sensitive value leaked to provider storage")
        assertTrue(body.contains("sha256:"))
        val summary = writer.writeSummary("schema drift")
        assertTrue(store.exists(summary))
    }
}
