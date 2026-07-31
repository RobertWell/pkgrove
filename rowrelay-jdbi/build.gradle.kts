// rowrelay-jdbi: first-class JDBI entry point over the same model/machinery
// as the JDBC path (equivalence by construction).
dependencies {
    api(project(":rowrelay-jdbc"))
    api(libs.jdbi3.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
