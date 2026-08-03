// pkgrovekit-postgres: PostgreSQL dialect (HEL-127). Driver is consumer-
// controlled (compileOnly); live tests run in integration-tests via
// testcontainers.
dependencies {
    api(project(":pkgrovekit-jdbc"))
    compileOnly(libs.postgres.jdbc)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
