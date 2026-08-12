// Scenario: JDBI-first data access + the JdbiTransfer facade. Intended user: an
// app that already uses JDBI Handles and wants PkgroveKit's readers/writers and
// the first-class JDBI transfer facade. JdbiTransfer takes a transfer public
// type, so transfer is present by design. A read-only JDBI consumer that never
// touches JdbiTransfer (e.g. AuditPatchX) may exclude pkgrovekit-transfer — see
// docs/scenarios.md. No dialect adapters, no coordination, no framework.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-jdbi")
}
extra["requiredModules"] = "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer,pkgrovekit-jdbi"
extra["forbiddenModules"] =
    "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-postgres," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana," +
        "pkgrovekit-saga,pkgrovekit-quarkus,pkgrovekit-spring-boot-starter"
