// pkgrovekit-transfer: the bidirectional SQL-in/data-out transfer engine.
// Dialect-agnostic: works against the SqlDialect contract from pkgrovekit-jdbc.
dependencies {
    api(project(":pkgrovekit-jdbc"))
    api(libs.coroutines.core)
    testImplementation(project(":pkgrovekit-duckdb"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
