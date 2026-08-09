// pkgrovekit-quarkus: Quarkus/CDI adapter (HEL-172). Framework surfaces
// (CDI API, MP Config API, Quarkus Agroal) are compileOnly — PROVIDED by the
// consuming Quarkus application; nothing from io.quarkus/io.agroal/jakarta.*
// leaks transitively onto a consumer classpath. Tests here are plain JUnit for
// PURE logic only (config parsing / dialect resolution / blocking-boundary
// heuristics); the real-framework proof lives in integration-tests-quarkus.
dependencies {
    // HEL-235: the adapter compile-depends ONLY on transfer (it produces a
    // Relay). Concrete dialects are discovered at runtime via
    // SqlDialectProvider (ServiceLoader) from whatever dialect modules the
    // consumer actually added — so a `quarkus + oracle` consumer never carries
    // postgres/duckdb. The dialect modules are test-only here (the config tests
    // assert the real dialect singletons resolve by id via ServiceLoader).
    api(project(":pkgrovekit-transfer"))
    compileOnly(libs.cdi.api)
    compileOnly(libs.mp.config.api)
    compileOnly(libs.quarkus.agroal)
    testImplementation(project(":pkgrovekit-postgres"))
    testImplementation(project(":pkgrovekit-oracle"))
    testImplementation(project(":pkgrovekit-duckdb"))
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
