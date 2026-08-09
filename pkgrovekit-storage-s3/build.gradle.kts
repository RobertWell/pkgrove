// pkgrovekit-storage-s3: S3-compatible implementation of the storage API using
// AWS SDK for Java 2.x (HEL-236). The product contract is S3-COMPATIBLE
// storage: MinIO is the tested local/CI target (via Testcontainers below, with
// the SAME AWS client — that identity is the compatibility proof), Amazon S3 is
// the reference cloud target (opt-in smoke test, see docs/storage.md).
//
// Surface discipline: only software.amazon.awssdk:s3 is selected — never the
// full SDK BOM. netty-nio-client is excluded (async client unused; the sync
// Apache client is the transport). AWS types stay inside this adapter except
// the documented escape hatch (S3ObjectStore.wrap(S3Client)).
dependencies {
    api(project(":pkgrovekit-storage-api"))
    implementation(libs.awssdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awssdk.apache.client)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<Test>().configureEach {
    // Boundedness tests assert streaming behavior under a KNOWN heap budget
    // (same reasoning as HEL-255/HEL-256): a large default heap would let a
    // buffering regression pass unnoticed.
    maxHeapSize = "512m"
    // docker-java default API version is rejected by modern daemons (HEL-175).
    systemProperty("api.version", "1.41")
}
