// Scenario: XA/2PC coordination across two XA-capable databases using the
// Narayana transaction manager, standalone (non-app-server). Intended user: an
// app that genuinely needs atomic commit across two XA resources. Pulls the
// coordination API + JTA interpretation + Narayana TM. The data-access spine
// (core/jdbc/transfer/dialects) is a SEPARATE, orthogonal concern and is NOT
// dragged in by the coordination layer.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-narayana")
}
extra["requiredModules"] = "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana"
extra["forbiddenModules"] =
    "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer,pkgrovekit-jdbi," +
        "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-postgres,pkgrovekit-saga," +
        "pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
