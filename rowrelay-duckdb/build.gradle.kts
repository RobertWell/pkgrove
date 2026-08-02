// rowrelay-duckdb: DuckDB dialect. The driver is consumer-controlled
// (runtime); tests bring it themselves.

// HEL-173: report unit-test line coverage so `check` surfaces it (the
// DuckDbDialect branch tests live in src/test; the live-DuckDB path is the
// separate :integration-tests module). CSV feeds the coverage audit.
plugins {
    jacoco
}

dependencies {
    api(project(":rowrelay-jdbc"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        csv.required.set(true)
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestReport"))
}
