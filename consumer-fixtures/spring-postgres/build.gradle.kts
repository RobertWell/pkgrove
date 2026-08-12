// Scenario: Spring Boot app + PostgreSQL. Intended user: a Boot service that
// auto-configures a Relay over its own DataSource beans and transfers against
// Postgres. The starter contributes only transfer (it produces a Relay); the
// consumer adds exactly the postgres dialect it wants. Oracle/DuckDB never
// arrive (HEL-235 ServiceLoader dialect discovery), Quarkus never arrives
// (spring !-> quarkus), and no coordination module arrives. Spring itself is
// compileOnly in the starter — the consumer's Boot app supplies it.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-spring-boot-starter")
    implementation("com.pkgrove:pkgrovekit-postgres")
}
extra["requiredModules"] =
    "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer,pkgrovekit-postgres,pkgrovekit-spring-boot-starter"
extra["forbiddenModules"] =
    "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-jdbi,pkgrovekit-quarkus," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana,pkgrovekit-saga"
