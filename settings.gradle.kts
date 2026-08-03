// PkgroveKit — reusable Kotlin data library (HEL-120/HEL-123).
plugins {
    // auto-provision the Java 21 toolchain on machines that lack it
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "pkgrovekit"

include(
    "pkgrovekit-core",
    "pkgrovekit-jdbc",
    "pkgrovekit-jdbi",
    "pkgrovekit-oracle",
    "pkgrovekit-duckdb",
    "pkgrovekit-transfer",
    "pkgrovekit-postgres",
    "integration-tests",
)
