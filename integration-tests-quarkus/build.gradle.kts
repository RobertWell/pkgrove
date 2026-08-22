// integration-tests-quarkus: the REAL-framework proof for pkgrovekit-quarkus
// (HEL-172) — CDI-injected Relay over live Agroal/H2 datasources. This module
// is NEVER published (name deliberately not pkgrovekit-*, so the root
// publishing convention skips it). Kotlin comes from the root convention.
plugins {
    alias(libs.plugins.quarkus)
}

dependencies {
    // Standard Quarkus-Gradle BOM alignment: the platform BOM, enforced, at the
    // same version the catalog pins for the quarkus plugin/extensions (3.21.1).
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${libs.versions.quarkus.get()}"))
    implementation(project(":pkgrovekit-quarkus"))
    implementation(libs.quarkus.agroal)
    implementation(libs.quarkus.jdbc.h2)
    // Caller-owned JTA transactions for the JoinExisting proof (HEL-172):
    // already on the runtime classpath transitively via quarkus-agroal, declared
    // explicitly because tests compile against io.quarkus.narayana.jta.
    // QuarkusTransaction. Version governed by the enforced BOM above.
    implementation("io.quarkus:quarkus-narayana-jta")
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.junit.jupiter)
}

// CVE-2025-67030 (HEL-259 gate): the Quarkus 3.21.1 BOM resolves transitive
// org.codehaus.plexus:plexus-utils 3.5.1 (directory traversal in extractFile).
// This module is test-only and never published, but the security gate scans
// the LOCKED graph and a fixed line exists — force it rather than register a
// .trivyignore exception. enforcedPlatform outranks ordinary constraints, so
// resolutionStrategy.force is required here. Drop when the BOM catches up.
configurations.all {
    resolutionStrategy {
        force("org.codehaus.plexus:plexus-utils:3.6.1")
    }
}
