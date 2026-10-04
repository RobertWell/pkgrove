package com.pkgrove.pkgrovekit.storage.s3

import org.testcontainers.DockerClientFactory
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName

/**
 * The MinIO version under test, built locally from MinIO's GitHub release
 * binary (HEL-614).
 *
 * MinIO stopped publishing container images in 2025: Docker Hub `minio/minio`
 * no longer exists, `quay.io/minio/minio` answers 401 and `dl.min.io` answers
 * 410 for every archive — so there is no registry left to pin, and the nightly
 * enforced build was red from 2026-09-30 for that reason alone. The GitHub
 * release assets remain, so the image is built here from the checksum-verified
 * `linux-amd64` binary on alpine: reproducible, registry-free, credential-free,
 * and the version under test stays an explicit fact (capability claims such as
 * conditional writes and checksums are version-dependent).
 *
 * Built once per Docker daemon and kept (`deleteOnExit = false`); CI runners are
 * ephemeral, so each job builds it once. The result is registered as a
 * compatible substitute for `minio/minio`, which is what Testcontainers'
 * `MinIOContainer` requires.
 *
 * Prod and the LAN cluster run `quay.io/minio/minio:RELEASE.2023-09-30T07-02-29Z`
 * from node-local image storage; this pin is the library's documented
 * capability baseline, not the deployed version.
 */
object MinioTestImage {
    const val VERSION = "RELEASE.2025-09-07T16-13-09Z"
    const val SHA256 = "7c5bd8512c6e966455b1d198209358b2d191c77a83ab377c4073281065fb855f"
    const val NAME = "pkgrovekit/minio-test:$VERSION"

    private const val BINARY_URL =
        "https://github.com/minio/minio/releases/download/$VERSION/minio.linux-amd64.$VERSION"

    val dockerfile: String = """
        FROM alpine:3.20
        ADD $BINARY_URL /usr/bin/minio
        RUN echo "$SHA256  /usr/bin/minio" | sha256sum -c - && chmod 0755 /usr/bin/minio && mkdir -p /data
        EXPOSE 9000 9001
        ENTRYPOINT ["/usr/bin/minio"]
        CMD ["server", "/data"]
    """.trimIndent()

    /** Builds the image if this daemon does not have it yet, then names it as a `minio/minio` substitute. */
    fun name(): DockerImageName {
        val client = DockerClientFactory.instance().client()
        val present = client.listImagesCmd().withImageNameFilter(NAME).exec().isNotEmpty()
        if (!present) {
            ImageFromDockerfile(NAME, false).withFileFromString("Dockerfile", dockerfile).get()
        }
        return DockerImageName.parse(NAME).asCompatibleSubstituteFor("minio/minio")
    }
}
