// Scenario: two dialect adapters side by side (Oracle + DuckDB), no transfer
// engine. Proves the adapters are mutually independent and pull neither each
// other's driver nor postgres. Drivers stay consumer-owned (ojdbc / duckdb_jdbc
// are compileOnly in the adapters, so absent here).
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-oracle")
    implementation("com.pkgrove:pkgrovekit-duckdb")
}
extra["requiredModules"] = "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-oracle,pkgrovekit-duckdb"
extra["forbiddenModules"] =
    "pkgrovekit-transfer,pkgrovekit-jdbi,pkgrovekit-postgres," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana," +
        "pkgrovekit-saga,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
