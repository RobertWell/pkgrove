// pkgrovekit-jdbc: direct JDBC access path. Depends ONLY on pkgrovekit-core and
// java.sql — JDBC-only consumers never receive JDBI transitively (HEL-123 §2).
// Drivers are consumer-controlled: tests use DuckDB as a real zero-config DB.
dependencies {
    api(project(":pkgrovekit-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
