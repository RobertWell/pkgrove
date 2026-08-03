// pkgrovekit-jdbi: first-class JDBI entry point over the same model/machinery
// as the JDBC path (equivalence by construction).
dependencies {
    api(project(":pkgrovekit-jdbc"))
    // HEL-160: first-class JDBI transfer facade reuses the transfer pipeline.
    api(project(":pkgrovekit-transfer"))
    api(libs.jdbi3.core)
    testImplementation(libs.junit.jupiter)
    // HEL-160: the JDBI transfer facade test needs a concrete target dialect.
    testImplementation(project(":pkgrovekit-duckdb"))
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
