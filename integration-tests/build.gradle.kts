// integration-tests: cross-module scenarios + the COMPILED documentation
// examples (README quick starts live here so CI proves they build — HEL-123
// documentation gate). This module is NEVER published.
plugins {
    java
}
dependencies {
    testImplementation(project(":pkgrovekit-core"))
    testImplementation(project(":pkgrovekit-jdbc"))
    testImplementation(project(":pkgrovekit-jdbi"))
    testImplementation(project(":pkgrovekit-duckdb"))
    testImplementation(project(":pkgrovekit-transfer"))
    testImplementation(project(":pkgrovekit-oracle"))
    testImplementation(project(":pkgrovekit-postgres"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.oracle)
    testImplementation(libs.testcontainers.junit)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.duckdb.jdbc)
    testRuntimeOnly(libs.ojdbc11)
    testRuntimeOnly(libs.postgres.jdbc)
    testImplementation(libs.testcontainers.postgres)
    // HEL-129: a REAL pool for the lifecycle matrix — proxy DataSources cannot
    // prove pool return/eviction behavior.
    testImplementation(libs.hikaricp)
}

// HEL-170: coordination-layer proofs (2x XA-capable Postgres via Narayana).
dependencies {
    testImplementation(project(":pkgrovekit-coordination-api"))
    testImplementation(project(":pkgrovekit-jta"))
    testImplementation(project(":pkgrovekit-narayana"))
    testImplementation(project(":pkgrovekit-saga"))
    // PGXADataSource is referenced at compile time by CoordinationXaIT
    testImplementation(libs.postgres.jdbc)
}

tasks.withType<Test>().configureEach {
    // The docker-java shaded inside testcontainers defaults to Docker API v1.32,
    // which modern daemons (min API 1.40) reject outright — every container
    // start then fails with "Could not find a valid Docker environment". Pin a
    // version every Docker >= 20.10 daemon accepts (same fix as HEL-175).
    systemProperty("api.version", "1.41")
}
