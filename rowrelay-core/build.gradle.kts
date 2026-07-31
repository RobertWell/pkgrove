// rowrelay-core: the common dynamic data model. ZERO runtime dependencies
// beyond the Kotlin stdlib — no JDBC, no JDBI, no drivers.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
