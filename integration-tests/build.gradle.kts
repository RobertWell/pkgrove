// integration-tests: cross-module scenarios + the COMPILED documentation
// examples (README quick starts live here so CI proves they build — HEL-123
// documentation gate). This module is NEVER published.
plugins {
    java
}
dependencies {
    testImplementation(project(":rowrelay-core"))
    testImplementation(project(":rowrelay-jdbc"))
    testImplementation(project(":rowrelay-jdbi"))
    testImplementation(project(":rowrelay-duckdb"))
    testImplementation(project(":rowrelay-transfer"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
