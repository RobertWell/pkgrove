// pkgrovekit-spring-boot-starter: Spring Boot adapter (HEL-172). Spring stays
// compileOnly — the consumer's Boot app supplies the framework, and framework
// classes must never ride a PkgroveKit runtime classpath (the HEL-170
// isolation groups cover org.springframework). The dialect modules are
// driver-free, so api-exposing all three adds zero drivers. Tests run the
// REAL framework (Boot autoconfigure + Spring tx) over a REAL pool (Hikari)
// and a REAL database (DuckDB) — no mocks, no containers.
dependencies {
    api(project(":pkgrovekit-transfer"))
    api(project(":pkgrovekit-postgres"))
    api(project(":pkgrovekit-oracle"))
    api(project(":pkgrovekit-duckdb"))

    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.jdbc)
    compileOnly(libs.spring.context)

    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.test)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.hikaricp)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
}
