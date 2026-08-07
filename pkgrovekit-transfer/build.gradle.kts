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

// HEL-255: the 1M-group retention test reads the JVM's used heap, so the test
// JVM must have a KNOWN, fixed budget rather than whatever Gradle's default
// happens to be on the machine — otherwise the measurement means something
// different per host. 512m is ample for the bounded implementation and would
// still have held the old unbounded key set, so the test fails on retention,
// not on OutOfMemoryError (a leak proven by an OOM is a flaky test).
tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
}
