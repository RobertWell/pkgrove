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
    // HEL-236: database → object storage → database end-to-end proof
    testImplementation(project(":pkgrovekit-storage-api"))
    testImplementation(project(":pkgrovekit-storage-s3"))
    testImplementation(libs.testcontainers.minio)
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

// HEL-234 §4: the Postgres/DuckDB container suite is a BLOCKING PR gate in CI
// (ci.yml `integration-postgres`); only the resource-heavy Oracle-Free image
// stays informational on hosted runners. Same test sources — this task is a
// filtered view of `test`, so the gate cannot drift from the real suite.
tasks.register<Test>("postgresIntegrationTest") {
    description = "Container-backed integration suite minus Oracle-Free (blocking in CI — HEL-234)"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { excludeTestsMatching("*Oracle*") }
}

tasks.withType<Test>().configureEach {
    // HEL-256: PostgresStreamingIT asserts on RETAINED heap, so the heap ceiling
    // has to be a property of the test rather than of whoever's machine runs it.
    // Left unpinned, a large default heap lets dropped batches pile up as
    // uncollected garbage and a genuinely streaming read measures as if it were
    // buffering. 512m is Gradle's own default — stated here so it stays true.
    maxHeapSize = "512m"

    // HEL-234: soak duration is caller-controlled but ALWAYS bounded — local
    // proof runs default to 2 minutes inside the test; the scheduled CI tier
    // passes -Ppkgrovekit.soak.minutes=12 (see .gitlab-ci.yml `stress-soak`).
    (findProperty("pkgrovekit.soak.minutes") as String?)?.let {
        systemProperty("pkgrovekit.soak.minutes", it)
    }

    // The docker-java shaded inside testcontainers defaults to Docker API v1.32,
    // which modern daemons (min API 1.40) reject outright — every container
    // start then fails with "Could not find a valid Docker environment". Pin a
    // version every Docker >= 20.10 daemon accepts (same fix as HEL-175).
    systemProperty("api.version", "1.41")
}
