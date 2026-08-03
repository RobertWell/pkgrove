// integration-tests: cross-module scenarios + the COMPILED documentation
// examples (README quick starts live here so CI proves they build — HEL-123
// documentation gate). This module is NEVER published.
plugins {
    java
}
dependencies {
    testImplementation(project(":pkgrovekit-core"))
    testImplementation(project(":pkgrovekit-jdbc"))
    testImplementation(project(":pkgrovekit-jdbi"))
    testImplementation(project(":pkgrovekit-duckdb"))
    testImplementation(project(":pkgrovekit-transfer"))
    testImplementation(project(":pkgrovekit-oracle"))
    testImplementation(project(":pkgrovekit-postgres"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.oracle)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
    testRuntimeOnly(libs.ojdbc11)
    testRuntimeOnly(libs.postgres.jdbc)
    testImplementation(libs.testcontainers.postgres)
    // HEL-129: a REAL pool for the lifecycle matrix — proxy DataSources cannot
    // prove pool return/eviction behavior.
    testImplementation(libs.hikaricp)
}
