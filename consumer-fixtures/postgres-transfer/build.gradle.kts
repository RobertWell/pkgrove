// Scenario: batch DB->DB transfer with a PostgreSQL endpoint. Intended user: a
// plain-JVM (no framework) app running the transfer engine against Postgres.
// Pulls the transfer engine (+ coroutines) and the postgres dialect, but never
// oracle/duckdb, jdbi, coordination, or any framework.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.5.0"))
    implementation("com.pkgrove:pkgrovekit-postgres")
    implementation("com.pkgrove:pkgrovekit-transfer")
}
extra["requiredModules"] = "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-postgres,pkgrovekit-transfer"
extra["forbiddenModules"] =
    "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-jdbi," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana," +
        "pkgrovekit-saga,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
