package com.pkgrove.pkgrovekit.storage.s3

import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.MinIOContainer
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * One MinIO container for the whole suite (started lazily, reaped by Ryuk).
 * MinIO is the officially tested S3-compatible target (HEL-236) — the SAME
 * AWS SDK client production uses talks to it, which is the compatibility
 * proof. Image pinned: capability claims (conditional writes, checksums) are
 * version-dependent facts, so the version under test must be explicit.
 */
object MinioSupport {
    const val IMAGE = "minio/minio:RELEASE.2025-09-07T16-13-09Z"

    /**
     * Whether a Docker daemon is reachable. These ITs need a real MinIO
     * container, and the container is started from a `lazy` initialiser — so
     * without Docker every class died at init with IllegalStateException rather
     * than skipping. That is what failed the nightly `verification-metadata`
     * job (2026-08-12, pipeline 1557): it deliberately excludes
     * `:integration-tests:test`, but these live in `:pkgrovekit-storage-s3:test`
     * and that exclusion never covered them. Tests that REQUIRE a daemon must
     * skip loudly where there is none, and still run for real in the
     * dind-backed jobs.
     */
    val dockerAvailable: Boolean by lazy {
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    }

    val container: MinIOContainer by lazy {
        MinIOContainer(IMAGE).also { it.start() }
    }

    private val bucketSeq = AtomicInteger(0)

    fun config(): S3StorageConfig = S3StorageConfig(
        region = "us-east-1",
        endpoint = URI.create(container.s3URL),
        credentials = S3Credentials.Static(container.userName, container.password),
    )

    /** A store on a FRESH bucket (test isolation without cross-test cleanup). */
    fun newStore(prefix: String = ""): S3ObjectStore {
        val bucket = "pkgrovekit-it-${bucketSeq.incrementAndGet()}"
        return S3ObjectStore.open(config(), bucket, prefix).also { it.createBucketIfMissing() }
    }
}
