package com.pkgrove.pkgrovekit.it

import org.testcontainers.DockerClientFactory
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName

/**
 * The MinIO version under test, built locally from MinIO's GitHub release
 * binary (HEL-614). Mirror of `pkgrovekit-storage-s3`'s test helper of the same
 * name — the two test source sets cannot share code without a build change,
 * and the two must pin the SAME version so every S3 claim is made against one
 * server. See that file for why no registry can be pinned any more.
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

    fun name(): DockerImageName {
        val client = DockerClientFactory.instance().client()
        val present = client.listImagesCmd().withImageNameFilter(NAME).exec().isNotEmpty()
        if (!present) {
            ImageFromDockerfile(NAME, false).withFileFromString("Dockerfile", dockerfile).get()
        }
        return DockerImageName.parse(NAME).asCompatibleSubstituteFor("minio/minio")
    }
}
