// RowRelay — reusable Kotlin data library (HEL-120/HEL-123).
plugins {
    // auto-provision the Java 21 toolchain on machines that lack it
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "rowrelay"

include(
    "rowrelay-core",
    "rowrelay-jdbc",
    "rowrelay-jdbi",
    "rowrelay-oracle",
    "rowrelay-duckdb",
    "rowrelay-transfer",
    "rowrelay-postgres",
    "integration-tests",
)
