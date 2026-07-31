// rowrelay-transfer: the bidirectional SQL-in/data-out transfer engine.
// Dialect-agnostic: works against the SqlDialect contract from rowrelay-jdbc.
dependencies {
    api(project(":rowrelay-jdbc"))
    testImplementation(project(":rowrelay-duckdb"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
