// pkgrovekit-quarkus: Quarkus/CDI adapter (HEL-172). Framework surfaces
// (CDI API, MP Config API, Quarkus Agroal) are compileOnly — PROVIDED by the
// consuming Quarkus application; nothing from io.quarkus/io.agroal/jakarta.*
// leaks transitively onto a consumer classpath. Tests here are plain JUnit for
// PURE logic only (config parsing / dialect resolution / blocking-boundary
// heuristics); the real-framework proof lives in integration-tests-quarkus.
dependencies {
    api(project(":pkgrovekit-transfer"))
    api(project(":pkgrovekit-postgres"))
    api(project(":pkgrovekit-oracle"))
    api(project(":pkgrovekit-duckdb"))
    compileOnly(libs.cdi.api)
    compileOnly(libs.mp.config.api)
    compileOnly(libs.quarkus.agroal)
    testImplementation(libs.junit.jupiter)
    // compileOnly does NOT reach the test classpath — the config-parsing tests
    // implement a tiny in-memory org.eclipse.microprofile.config.Config fake.
    testImplementation(libs.mp.config.api)
    // HEL-234: producer misconfiguration-path tests run against the REAL CDI
    // Instance/qualifier types and Agroal API (fakes implement the interfaces;
    // still no container, no Arc).
    testImplementation(libs.cdi.api)
    testImplementation(libs.quarkus.agroal)
    testRuntimeOnly(libs.junit.launcher)
}
