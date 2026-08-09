// Scenario: direct JDBC access only. Intended user: an app doing dynamic
// row reads/writes over one connection with no transfer engine, no JDBI, no
// specific dialect module (brings its own driver + a hand-rolled dialect, or
// uses an adapter separately).
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.5.0"))
    implementation("com.pkgrove:pkgrovekit-jdbc")
}
extra["requiredModules"] = "pkgrovekit-core,pkgrovekit-jdbc"
extra["forbiddenModules"] =
    "pkgrovekit-transfer,pkgrovekit-jdbi,pkgrovekit-oracle,pkgrovekit-duckdb," +
        "pkgrovekit-postgres,pkgrovekit-coordination-api,pkgrovekit-jta," +
        "pkgrovekit-narayana,pkgrovekit-saga,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
