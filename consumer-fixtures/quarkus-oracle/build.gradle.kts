// Scenario: Quarkus app + Oracle. Intended user: a CDI service that injects a
// Relay over Quarkus-managed Agroal datasources and transfers against Oracle.
// The adapter contributes only transfer; the consumer adds exactly the oracle
// dialect. Postgres/DuckDB never arrive (ServiceLoader dialect discovery),
// Spring never arrives (quarkus !-> spring), no coordination module arrives.
// Quarkus/CDI/Agroal are compileOnly in the adapter — the Quarkus app supplies
// them (and the Oracle JDBC driver).
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-quarkus")
    implementation("com.pkgrove:pkgrovekit-oracle")
}
extra["requiredModules"] =
    "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer,pkgrovekit-oracle,pkgrovekit-quarkus"
extra["forbiddenModules"] =
    "pkgrovekit-postgres,pkgrovekit-duckdb,pkgrovekit-jdbi,pkgrovekit-spring-boot-starter," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana,pkgrovekit-saga"
