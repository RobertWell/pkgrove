// rowrelay-transfer: the bidirectional SQL-in/data-out transfer engine.
// Dialect-agnostic: works against the SqlDialect contract from rowrelay-jdbc.
dependencies {
    api(project(":rowrelay-jdbc"))
    api(libs.coroutines.core)
    testImplementation(project(":rowrelay-duckdb"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
