// PkgroveKit — reusable Kotlin data library (HEL-120/HEL-123).
plugins {
    // auto-provision the Java 21 toolchain on machines that lack it
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pkgrovekit"

include(
    // HEL-235: dependency-constraint platform (adds no runtime deps; NOT an
    // aggregate). Lets consumers omit per-module versions.
    "pkgrovekit-bom",
    "pkgrovekit-core",
    "pkgrovekit-jdbc",
    "pkgrovekit-jdbi",
    "pkgrovekit-oracle",
    "pkgrovekit-duckdb",
    "pkgrovekit-transfer",
    "pkgrovekit-postgres",
    // HEL-170: optional coordination layer — NEVER a dependency of the modules
    // above (enforced by the assertCoordinationIsolation task).
    "pkgrovekit-coordination-api",
    "pkgrovekit-jta",
    "pkgrovekit-narayana",
    "pkgrovekit-saga",
    // HEL-172: optional framework adapters — frameworks never leak into the
    // standard modules (assertCoordinationIsolation covers these groups too).
    "pkgrovekit-quarkus",
    "pkgrovekit-spring-boot-starter",
    // HEL-236: optional S3-compatible object storage. storage-api is
    // vendor-neutral (core + JDK only); storage-s3 is the AWS-SDK-v2 adapter.
    // NEVER a dependency of the data-access spine — database-only consumers
    // must not resolve AWS SDK modules (enforced by assertModuleHierarchy +
    // the consumer fixtures).
    "pkgrovekit-storage-api",
    "pkgrovekit-storage-s3",
    "integration-tests",
    "integration-tests-quarkus",
)
