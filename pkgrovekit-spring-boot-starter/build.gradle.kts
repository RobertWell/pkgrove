// pkgrovekit-spring-boot-starter: Spring Boot adapter (HEL-172). Spring stays
// compileOnly — the consumer's Boot app supplies the framework, and framework
// classes must never ride a PkgroveKit runtime classpath (the HEL-170
// isolation groups cover org.springframework). The dialect modules are
// driver-free, so api-exposing all three adds zero drivers. Tests run the
// REAL framework (Boot autoconfigure + Spring tx) over a REAL pool (Hikari)
// and a REAL database (DuckDB) — no mocks, no containers.
dependencies {
    // HEL-235: the starter compile-depends ONLY on transfer (it produces a
    // Relay). Concrete dialects are discovered at runtime via
    // SqlDialectProvider (ServiceLoader) from the dialect modules the consumer
    // actually added — a `spring + postgres` app never carries oracle/duckdb.
    // DuckDB is test-only here: the autoconfigure tests run the real framework
    // over a real DuckDB and resolve the `duckdb` id via ServiceLoader.
    api(project(":pkgrovekit-transfer"))

    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.jdbc)
    compileOnly(libs.spring.context)

    testImplementation(project(":pkgrovekit-duckdb"))
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.hikaricp)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
