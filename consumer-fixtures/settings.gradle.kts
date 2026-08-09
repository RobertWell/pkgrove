// HEL-235 consumer fixtures — a SEPARATE Gradle build (deliberately NOT included
// in the root settings) so each fixture resolves PUBLISHED PkgroveKit artifacts
// from mavenLocal exactly as a real downstream consumer would, never as project
// dependencies. Run with: ./gradlew -p consumer-fixtures verifyAllFixtures
// (after `./gradlew publishToMavenLocal`). See run-fixtures.sh.
plugins {
    // provision/locate the Java 21 toolchain the published modules require
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "pkgrovekit-consumer-fixtures"

include(
    "jdbc-only",
    "oracle-duckdb",
    "postgres-transfer",
    "spring-postgres",
    "quarkus-oracle",
    "jdbi",
    "xa-narayana",
    "saga",
)
