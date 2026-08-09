// Scenario (HEL-236): S3-compatible object storage explicitly selected — e.g.
// streaming a transfer's dataset to MinIO/Amazon S3 and back. Intended user: a
// data workflow that ADDS storage next to the transfer engine. Pulls the
// storage API + the AWS-SDK-v2 adapter (s3 + its transport only — never the
// full SDK surface), and nothing from coordination or the frameworks.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.5.0"))
    implementation("com.pkgrove:pkgrovekit-transfer")
    implementation("com.pkgrove:pkgrovekit-storage-s3")
}
extra["requiredModules"] =
    "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer," +
        "pkgrovekit-storage-api,pkgrovekit-storage-s3"
extra["forbiddenModules"] =
    "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-postgres,pkgrovekit-jdbi," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana," +
        "pkgrovekit-saga,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
// selecting storage must NOT drag in the async transport (excluded) or MinIO SDK
extra["forbiddenGroups"] = "io.minio"

// prove the surface stays minimal: the SDK arrives WITHOUT the netty async
// transport (the adapter excludes it; sync Apache is the transport)
tasks.named("verifyFixture") {
    doLast {
        val names = configurations.getByName("runtimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "software.amazon.awssdk" }
            .map { it.moduleVersion.id.name }
        require(names.contains("s3")) { "[storage-s3] expected software.amazon.awssdk:s3 on the classpath" }
        require(!names.contains("netty-nio-client")) {
            "[storage-s3] netty-nio-client leaked — the adapter must exclude the async transport"
        }
    }
}
