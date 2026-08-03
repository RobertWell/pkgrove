// pkgrovekit-saga: compensation-based coordination for resources that cannot
// join XA (DuckDB, files, HTTP services, long-running workflows) — HEL-170.
// Depends only on the coordination API; explicitly NOT an ACID mechanism.
dependencies {
    api(project(":pkgrovekit-coordination-api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
