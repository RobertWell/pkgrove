// Scenario: direct JDBC access only. Intended user: an app doing dynamic
// row reads/writes over one connection with no transfer engine, no JDBI, no
// specific dialect module (brings its own driver + a hand-rolled dialect, or
// uses an adapter separately).
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:${project.extra["pkgrovekitVersion"]}"))
    implementation("com.pkgrove:pkgrovekit-jdbc")
}
extra["requiredModules"] = "pkgrovekit-core,pkgrovekit-jdbc"
extra["forbiddenModules"] =
    "pkgrovekit-transfer,pkgrovekit-jdbi,pkgrovekit-oracle,pkgrovekit-duckdb," +
        "pkgrovekit-postgres,pkgrovekit-coordination-api,pkgrovekit-jta," +
        "pkgrovekit-narayana,pkgrovekit-saga,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter," +
        "pkgrovekit-storage-api,pkgrovekit-storage-s3," +
        // HEL-602: the operation layer is a disjoint tree — a database-only
        // consumer must never resolve an API-surface model it did not select
        "pkgrovekit-operation-core,pkgrovekit-operation-rest," +
        "pkgrovekit-operation-mcp,pkgrovekit-operation-quarkus"
// HEL-236: a database-only consumer resolves ZERO AWS SDK / MinIO artifacts
extra["forbiddenGroups"] = "software.amazon.awssdk,io.minio"
