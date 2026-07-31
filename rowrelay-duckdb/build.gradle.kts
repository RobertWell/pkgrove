// rowrelay-duckdb: DuckDB dialect. The driver is consumer-controlled
// (runtime); tests bring it themselves.
dependencies {
    api(project(":rowrelay-jdbc"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
