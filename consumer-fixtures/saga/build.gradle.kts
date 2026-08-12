// Scenario: compensation-based (saga) coordination for resources that cannot
// join XA (DuckDB, files, HTTP services, long-running workflows). Intended user:
// an app that needs a coordinated multi-step workflow WITHOUT distributed ACID.
// Pulls only the coordination API + saga interpreter — explicitly NO JTA, NO
// Narayana, and none of the data-access spine.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-saga")
}
extra["requiredModules"] = "pkgrovekit-coordination-api,pkgrovekit-saga"
extra["forbiddenModules"] =
    "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer,pkgrovekit-jdbi," +
        "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-postgres,pkgrovekit-jta," +
        "pkgrovekit-narayana,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
