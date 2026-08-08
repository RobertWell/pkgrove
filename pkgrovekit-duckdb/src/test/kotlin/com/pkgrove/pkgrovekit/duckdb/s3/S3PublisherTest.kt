package com.pkgrove.pkgrovekit.duckdb.s3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HEL-236: atomic-replace semantics proven against stubbed seams — an
 * in-memory "bucket" plays the object store, and failures are injected at
 * every stage. The invariant under test: NO failure mode leaves a corrupt or
 * partial FINAL object; staging orphans are allowed but always reported.
 */
class S3PublisherTest {

    private val session = S3Session(
        endpoint = "minio.local:9000",
        accessKeyId = "test-access",
        secretAccessKey = "test-secret",
    )
    private val target = ObjectKey("model-results", "training/records.parquet")

    /** One bucket: key -> object content (a string stands in for the bytes). */
    private class Bucket {
        val objects = LinkedHashMap<String, String>()
        fun stagingKeys() = objects.keys.filter { ".staging-" in it }
    }

    /** DuckDB seam stub: "writes" the query text as the object content. */
    private class StubPort(
        val bucket: Bucket,
        var rows: Long = 3,
        var failMidWrite: Boolean = false,
        var readBackSkew: Long = 0,
    ) : S3Publisher.CopyPort {
        private fun keyOf(uri: String) = uri.removePrefix("s3://model-results/")

        override fun copyTo(sourceSql: String, stagingUri: String): Long {
            if (failMidWrite) {
                // a mid-write failure leaves a PARTIAL staging object behind
                bucket.objects[keyOf(stagingUri)] = "PARTIAL"
                throw RuntimeException("connection reset mid-write")
            }
            bucket.objects[keyOf(stagingUri)] = "rows=$rows sql=$sourceSql"
            return rows
        }

        override fun countRows(uri: String): Long {
            val content = bucket.objects[keyOf(uri)]
                ?: throw RuntimeException("no such object: $uri")
            check(content != "PARTIAL") { "read of a partial object" }
            return content.substringAfter("rows=").substringBefore(" ").toLong() + readBackSkew
        }
    }

    private class StubOps(
        val store: Bucket,
        var failCopy: Boolean = false,
        var failDelete: Boolean = false,
    ) : S3Publisher.ObjectStoreOps {
        override fun copyObject(bucket: String, sourceKey: String, destinationKey: String) {
            if (failCopy) throw RuntimeException("copy refused")
            store.objects[destinationKey] = store.objects[sourceKey]
                ?: throw RuntimeException("staging object missing: $sourceKey")
        }

        override fun deleteObject(bucket: String, key: String) {
            if (failDelete) throw RuntimeException("delete refused")
            store.objects.remove(key)
        }
    }

    private fun publisher(ops: StubOps) = S3Publisher(session, ops)

    // ── success path ────────────────────────────────────────────────────────

    @Test
    fun `success replaces the final object atomically and cleans staging`() {
        val bucket = Bucket()
        bucket.objects[target.key] = "OLD"
        val port = StubPort(bucket, rows = 3)

        val outcome = publisher(StubOps(bucket))
            .publish(port, "SELECT * FROM t", target, S3Publisher.Options(runId = "r1"))

        val published = assertInstanceOf(S3Publisher.PublishOutcome.Published::class.java, outcome)
        assertEquals(3, published.rows)
        assertEquals("r1", published.runId)
        assertNull(published.stagingOrphan)
        assertTrue(published.warnings.isEmpty())
        assertEquals("rows=3 sql=SELECT * FROM t", bucket.objects[target.key])
        assertEquals(emptyList<String>(), bucket.stagingKeys(), "staging must be cleaned up")
    }

    @Test
    fun `re-run is idempotent - same final key, no duplicates, no residue`() {
        val bucket = Bucket()
        val port = StubPort(bucket, rows = 3)
        val publisher = publisher(StubOps(bucket))

        publisher.publish(port, "SELECT 1", target, S3Publisher.Options(runId = "r1"))
        port.rows = 5
        val second = publisher.publish(port, "SELECT 2", target, S3Publisher.Options(runId = "r2"))

        assertInstanceOf(S3Publisher.PublishOutcome.Published::class.java, second)
        assertEquals(setOf(target.key), bucket.objects.keys, "exactly ONE object — the final key")
        assertEquals("rows=5 sql=SELECT 2", bucket.objects[target.key], "second run's content wins")
    }

    // ── failure stages: prior final object must be untouched in ALL of them ─

    @Test
    fun `mid-write failure leaves prior final untouched and reports the staging orphan`() {
        val bucket = Bucket()
        bucket.objects[target.key] = "OLD"
        val port = StubPort(bucket, failMidWrite = true)

        val outcome = publisher(StubOps(bucket))
            .publish(port, "SELECT * FROM t", target, S3Publisher.Options(runId = "r1"))

        val failed = assertInstanceOf(S3Publisher.PublishOutcome.Failed::class.java, outcome)
        assertEquals(S3Publisher.Stage.WRITE_STAGING, failed.stage)
        assertEquals("OLD", bucket.objects[target.key], "final object must be untouched")
        assertEquals(target.staging("r1"), failed.stagingOrphan, "orphan must be reported")
        assertEquals(listOf(target.staging("r1").key), bucket.stagingKeys())
    }

    @Test
    fun `verification mismatch fails before replace - final untouched`() {
        val bucket = Bucket()
        bucket.objects[target.key] = "OLD"
        val port = StubPort(bucket, rows = 3, readBackSkew = -1)

        val outcome = publisher(StubOps(bucket))
            .publish(port, "SELECT * FROM t", target, S3Publisher.Options(runId = "r1"))

        val failed = assertInstanceOf(S3Publisher.PublishOutcome.Failed::class.java, outcome)
        assertEquals(S3Publisher.Stage.VERIFY, failed.stage)
        assertInstanceOf(S3Publisher.VerificationException::class.java, failed.cause)
        assertEquals("OLD", bucket.objects[target.key], "final object must be untouched")
        assertEquals(target.staging("r1"), failed.stagingOrphan)
    }

    @Test
    fun `replace failure leaves prior final untouched and reports the orphan`() {
        val bucket = Bucket()
        bucket.objects[target.key] = "OLD"
        val port = StubPort(bucket, rows = 3)

        val outcome = publisher(StubOps(bucket, failCopy = true))
            .publish(port, "SELECT * FROM t", target, S3Publisher.Options(runId = "r1"))

        val failed = assertInstanceOf(S3Publisher.PublishOutcome.Failed::class.java, outcome)
        assertEquals(S3Publisher.Stage.REPLACE, failed.stage)
        assertEquals("OLD", bucket.objects[target.key], "final object must be untouched")
        assertEquals(target.staging("r1"), failed.stagingOrphan)
        assertEquals(listOf(target.staging("r1").key), bucket.stagingKeys())
    }

    @Test
    fun `cleanup failure still publishes - orphan reported with a warning`() {
        val bucket = Bucket()
        bucket.objects[target.key] = "OLD"
        val port = StubPort(bucket, rows = 3)

        val outcome = publisher(StubOps(bucket, failDelete = true))
            .publish(port, "SELECT * FROM t", target, S3Publisher.Options(runId = "r1"))

        val published = assertInstanceOf(S3Publisher.PublishOutcome.Published::class.java, outcome)
        assertEquals("rows=3 sql=SELECT * FROM t", bucket.objects[target.key], "replace happened")
        assertEquals(target.staging("r1"), published.stagingOrphan)
        assertEquals(listOf("STAGING_ORPHAN"), published.warnings.map { it.code })
    }

    // ── run identity ────────────────────────────────────────────────────────

    @Test
    fun `each run stages under a fresh runId by default`() {
        val bucket = Bucket()
        val port = StubPort(bucket, failMidWrite = true)
        val publisher = publisher(StubOps(bucket))

        val a = publisher.publish(port, "SELECT 1", target)
        val b = publisher.publish(port, "SELECT 1", target)
        assertNotNull((a as S3Publisher.PublishOutcome.Failed).stagingOrphan)
        assertTrue(a.runId != b.runId, "default runIds must not collide")
    }

    // ── target validation ───────────────────────────────────────────────────

    @Test
    fun `object keys that could escape a SQL literal are refused`() {
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("b", "k'ey") }
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("b'", "key") }
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("b", "k ey") }
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("b", "/key") }
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("", "key") }
        assertThrows(IllegalArgumentException::class.java) { ObjectKey("b", "") }
    }

    @Test
    fun `uri and staging naming follow the documented scheme`() {
        val key = ObjectKey("model-results", "a/b/c.parquet")
        assertEquals("s3://model-results/a/b/c.parquet", key.uri)
        assertEquals(
            "s3://model-results/a/b/c.parquet.staging-r9",
            key.staging("r9").uri,
        )
    }
}
