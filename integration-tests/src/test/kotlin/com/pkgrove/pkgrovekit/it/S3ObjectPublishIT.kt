package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.duckdb.s3.ObjectKey
import com.pkgrove.pkgrovekit.duckdb.s3.S3Publisher
import com.pkgrove.pkgrovekit.duckdb.s3.S3Session
import com.pkgrove.pkgrovekit.duckdb.s3.SigV4ObjectStoreOps
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-236: one real MinIO round-trip + one injected failure — small by design.
 * Skipped without Docker (like every other IT), and additionally skipped when
 * the DuckDB `httpfs` extension cannot be installed/loaded (it downloads on
 * first use; an air-gapped runner must not fail this suite).
 *
 * Uses GenericContainer (already on the classpath via testcontainers core)
 * rather than a new testcontainers module, keeping the locked dependency
 * graph unchanged.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ObjectPublishIT {

    private lateinit var minio: GenericContainer<*>
    private lateinit var duck: Connection
    private lateinit var session: S3Session
    private lateinit var ops: SigV4ObjectStoreOps

    private val bucket = "model-results"

    @BeforeAll
    fun start() {
        minio = GenericContainer("minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
        minio.start()

        session = S3Session(
            endpoint = "${minio.host}:${minio.getMappedPort(9000)}",
            accessKeyId = "minioadmin",
            secretAccessKey = "minioadmin",
            useSsl = false,
        )
        ops = SigV4ObjectStoreOps(session)
        ops.createBucket(bucket)

        duck = DriverManager.getConnection("jdbc:duckdb:")
        duck.createStatement().use { st ->
            st.execute("CREATE TABLE training_record AS SELECT range AS id, 'run-' || range AS label FROM range(3)")
        }
        // httpfs downloads on first INSTALL — skip (not fail) where that is impossible
        val httpfs = runCatching { session.configure(duck) }
        Assumptions.assumeTrue(
            httpfs.isSuccess,
            "DuckDB httpfs extension unavailable: ${httpfs.exceptionOrNull()?.message}",
        )
    }

    @AfterAll
    fun stop() {
        if (this::duck.isInitialized) runCatching { duck.close() }
        if (this::minio.isInitialized) runCatching { minio.stop() }
    }

    private fun count(uri: String): Long =
        duck.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM read_parquet('$uri')")
                .use { rs -> rs.next(); rs.getLong(1) }
        }

    private fun objectsUnder(prefix: String): List<String> =
        duck.createStatement().use { st ->
            st.executeQuery("SELECT file FROM glob('s3://$bucket/$prefix/*')").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    @Test
    fun `parquet round-trip with atomic replace and no staging residue`() {
        val target = ObjectKey(bucket, "records/train.parquet")
        val publisher = S3Publisher(session, ops)

        val first = publisher.publish(duck, "SELECT * FROM training_record", target)
        val published = assertInstanceOf(S3Publisher.PublishOutcome.Published::class.java, first)
        assertEquals(3, published.rows)
        assertEquals(3, count(target.uri), "final object must be readable back through S3")

        // re-run with different data: same final key, replaced content, no duplicates
        val second = publisher.publish(
            duck, "SELECT * FROM training_record LIMIT 2", target,
        )
        assertInstanceOf(S3Publisher.PublishOutcome.Published::class.java, second)
        assertEquals(2, count(target.uri), "replaced object carries the new run's rows")
        assertEquals(
            listOf("s3://$bucket/records/train.parquet"),
            objectsUnder("records"),
            "exactly one object — no staging residue, no duplicates",
        )
    }

    @Test
    fun `injected replace failure leaves the prior final object intact`() {
        val target = ObjectKey(bucket, "guarded/train.parquet")
        val good = S3Publisher(session, ops)
        assertInstanceOf(
            S3Publisher.PublishOutcome.Published::class.java,
            good.publish(duck, "SELECT * FROM training_record", target),
        )

        val failingOps = object : S3Publisher.ObjectStoreOps {
            override fun copyObject(bucket: String, sourceKey: String, destinationKey: String) =
                throw RuntimeException("injected: replace refused")
            override fun deleteObject(bucket: String, key: String) =
                throw RuntimeException("injected: delete refused")
        }
        val outcome = S3Publisher(session, failingOps)
            .publish(duck, "SELECT * FROM training_record LIMIT 1", target)

        val failed = assertInstanceOf(S3Publisher.PublishOutcome.Failed::class.java, outcome)
        assertEquals(S3Publisher.Stage.REPLACE, failed.stage)
        assertEquals(3, count(target.uri), "prior final object must be untouched")
        assertEquals(
            1, count(failed.stagingOrphan.uri),
            "the staging orphan holds the failed run's rows and is reported",
        )
        // clean the orphan with the real ops — proves DeleteObject too
        ops.deleteObject(failed.stagingOrphan.bucket, failed.stagingOrphan.key)
        assertEquals(
            listOf("s3://$bucket/guarded/train.parquet"),
            objectsUnder("guarded"),
        )
    }
}
