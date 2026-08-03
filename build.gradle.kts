// PkgroveKit root build: shared configuration for every module. Publishable
// modules add the `pkgrovekit.publish` convention below; integration-tests
// deliberately does not (it is never published — HEL-123 §2).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply false
    // HEL-124 §7: SBOM of the actual resolved dependency graph (CycloneDX)
    alias(libs.plugins.cyclonedx)
}

// Aggregate SBOM over all publishable modules; runtime vs test scopes are
// distinguished by CycloneDX component scopes in the output.
tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setProjectType("library")
    setDestination(project.file("build/sbom"))
    setOutputFormat("all")   // JSON + XML
}

// Release-version policy (HEL-123 owner directive): published coordinates and
// documentation use IMMUTABLE releases — never a mutable `-SNAPSHOT`. MAJOR for
// breaking API / major workflow redesign, MINOR for backward-compatible
// downstream enhancements, PATCH for backward-compatible fixes; 0.x is NOT a
// blanket exception for breaking downstream changes. Development builds carry
// commit identity instead (`-Pdev` → `<release>-dev.<shortSha>`), and such
// coordinates are never published (the publish workflow refuses any version
// containing `-SNAPSHOT`/`-dev`).
// 0.3.0 = first release under the Maven-Central-verified namespace com.pkgrove
// (HEL-189). 0.2.0 artifacts remain immutable at com.pkgrove.pkgrovekit in the
// GitLab/GitHub registries; consumers migrate coordinates on upgrade.
val pkgrovekitRelease = "0.4.0"

/** Short commit sha for `-Pdev` local builds; safe fallback if git is absent so
 *  a dev build never fails on version resolution. Only invoked when `-Pdev` is
 *  set, so normal `check`/publish never runs git. */
fun devBuildVersion(): String = try {
    val sha = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
        .redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText().trim()
    if (sha.isEmpty()) "$pkgrovekitRelease-dev" else "$pkgrovekitRelease-dev.$sha"
} catch (_: Exception) {
    "$pkgrovekitRelease-dev"
}

allprojects {
    // HEL-189: Maven-Central-verified namespace (owner-verified 2026-08-02).
    // Java package names stay com.pkgrove.pkgrovekit — groupId and code packages
    // are independent, and a source-wide rename would churn every consumer.
    group = "com.pkgrove"
    version = if (project.hasProperty("dev")) devBuildVersion() else pkgrovekitRelease

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // HEL-124 §6: reproducibility — every configuration is locked; dynamic
    // versions (1.+, ranges, changing modules) cannot resolve. Lockfiles are
    // committed and are also the input the CVE scanner reads.
    dependencyLocking {
        lockAllConfigurations()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed", "skipped") }
    }
}

// HEL-170 proof #4: the coordination layer is OPT-IN — standard PkgroveKit
// modules must never leak JTA/Narayana onto a consumer's classpath. This task
// resolves each standard module's runtime classpath and fails the build if a
// coordination-only artifact appears; wired into `check` so CI enforces it.
val standardModules = listOf(
    "pkgrovekit-core", "pkgrovekit-jdbc", "pkgrovekit-jdbi",
    "pkgrovekit-oracle", "pkgrovekit-duckdb", "pkgrovekit-transfer", "pkgrovekit-postgres",
)
val forbiddenCoordinationGroups = listOf(
    "jakarta.transaction", "org.jboss.narayana",
    // HEL-172: framework adapters are opt-in too
    "org.springframework", "io.quarkus", "io.agroal", "com.zaxxer",
)

val assertCoordinationIsolation = tasks.register("assertCoordinationIsolation") {
    description = "Fails if a standard module's runtimeClasspath contains JTA/Narayana (HEL-170)"
    doLast {
        val leaks = standardModules.flatMap { name ->
            val cfg = project(name).configurations.getByName("runtimeClasspath")
            cfg.resolvedConfiguration.resolvedArtifacts
                .filter { a -> forbiddenCoordinationGroups.any { g -> a.moduleVersion.id.group.startsWith(g) } }
                .map { "$name -> ${it.moduleVersion.id}" }
        }
        if (leaks.isNotEmpty()) {
            throw GradleException(
                "Coordination dependencies leaked into standard modules (HEL-170 rule 3):\n" +
                    leaks.joinToString("\n"),
            )
        }
        logger.lifecycle("coordination isolation OK: no JTA/Narayana on ${standardModules.size} standard module classpaths")
    }
}

// every `check` run (and therefore CI) enforces the isolation rule
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(assertCoordinationIsolation) }
}

/** Publishable-module convention: sources + Dokka-javadoc jars, maven-publish
 *  to GitHub Packages (credentials from the Actions environment only). */
configure(subprojects.filter { it.name.startsWith("pkgrovekit-") }) {
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka")
    // HEL-189: PGP signing for Maven Central. Gradle-core plugin (no new
    // dependency → passes the supply-chain gate). GATED: only signs when an
    // in-memory key is provided via env, so GitHub-Packages / GitLab / local
    // publishes keep working unsigned. Central requires signed artifacts.
    apply(plugin = "signing")

    // HEL-124: Dokka 1.9.x transitively pins jackson-databind 2.12.x, which
    // carries HIGH/CRITICAL advisories. It is BUILD TOOLING (never on a
    // consumer classpath), but the policy prefers an upgrade over an
    // exception — force the fixed line on the dokka configurations only.
    configurations.matching { it.name.startsWith("dokka") }.configureEach {
        resolutionStrategy {
            force("com.fasterxml.jackson.core:jackson-databind:2.18.8")
        }
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }

    val dokkaJavadocJar = tasks.register<Jar>("dokkaJavadocJar") {
        dependsOn(tasks.named("dokkaJavadoc"))
        from(tasks.named("dokkaJavadoc"))
        archiveClassifier.set("javadoc")
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifact(dokkaJavadocJar)
                pom {
                    name.set(project.name)
                    description.set("PkgroveKit — reusable Kotlin data library: " +
                                    "dynamic JDBC/JDBI data access and bidirectional batch transfer")
                    url.set("https://github.com/RobertWell/pkgrove")
                    // HEL-189 Maven Central metadata. developers/scm/issueManagement
                    // complete. License decided by the owner: MIT (2026-08).
                    // Default emits MIT on every published artifact; overridable
                    // via -Ppkgrovekit.license.name/.url. Central REQUIRES it — see
                    // docs/RELEASING.md.
                    val licName = (findProperty("pkgrovekit.license.name") as String?) ?: "MIT License"
                    val licUrl = (findProperty("pkgrovekit.license.url") as String?) ?: "https://opensource.org/license/mit"
                    licenses {
                        license {
                            name.set(licName)
                            url.set(licUrl)
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("RobertWell")
                            name.set("RobertWell")
                            url.set("https://github.com/RobertWell")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/RobertWell/pkgrove.git")
                        developerConnection.set("scm:git:ssh://git@github.com/RobertWell/pkgrove.git")
                        url.set("https://github.com/RobertWell/pkgrove")
                    }
                    issueManagement {
                        system.set("Linear")
                        url.set("https://linear.app/hellostock")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/RobertWell/pkgrove")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
            // HEL-123: LAN GitLab Maven registry (PAT-free registry consumption
            // for LAN CI consumers). Only configured inside GitLab CI — the env
            // supplies the in-cluster URL + ephemeral job token. http is the
            // accepted in-cluster hop (pod->service on a single node; the
            // job-scoped token expires with the job — same tradeoff as the
            // datakit deploy, documented there).
            val gitlabMavenUrl = System.getenv("PKGROVEKIT_GITLAB_MAVEN_URL")
            if (!gitlabMavenUrl.isNullOrBlank() && System.getenv("CI_JOB_TOKEN") != null) {
                maven {
                    name = "GitLabLan"
                    url = uri(gitlabMavenUrl)
                    isAllowInsecureProtocol = true
                    credentials(HttpHeaderCredentials::class) {
                        name = "Job-Token"
                        value = System.getenv("CI_JOB_TOKEN")
                    }
                    authentication { create<HttpHeaderAuthentication>("header") }
                }
            }
            // HEL-189: Maven Central (public, tokenless CONSUMPTION). GATED on
            // env like the others — only configured when the Central credentials
            // are present (in CI, for a tagged release). MAVEN_CENTRAL_URL points
            // at the OSSRH-compatible staging endpoint the owner's Central Portal
            // namespace exposes; final release is promoted via the Portal. See
            // docs/RELEASING.md. Requires PGP-signed artifacts (below).
            val centralUrl = System.getenv("MAVEN_CENTRAL_URL")
            val centralUser = System.getenv("MAVEN_CENTRAL_USERNAME")
            if (!centralUrl.isNullOrBlank() && !centralUser.isNullOrBlank()) {
                maven {
                    name = "MavenCentral"
                    url = uri(centralUrl)
                    credentials {
                        username = centralUser
                        password = System.getenv("MAVEN_CENTRAL_PASSWORD")
                    }
                }
            }
            // HEL-189: the Central PORTAL is not a Maven PUT endpoint — a release
            // is staged into a local maven-layout tree, zipped, and POSTed to
            // /api/v1/publisher/upload. Gated on CENTRAL_BUNDLE_DIR (release
            // tooling only; inert otherwise).
            val bundleDir = System.getenv("CENTRAL_BUNDLE_DIR")
            if (!bundleDir.isNullOrBlank()) {
                maven {
                    name = "CentralBundle"
                    url = uri("file://$bundleDir")
                }
            }
        }
    }

    // HEL-189: sign publications for Maven Central. GATED — only required/active
    // when SIGNING_KEY is provided (in-memory ASCII-armored PGP key + password
    // via env, no keyring file in the repo). Without it, unsigned publishes to
    // GitHub Packages / GitLab / mavenLocal keep working unchanged.
    extensions.configure<SigningExtension> {
        val signingKey = System.getenv("SIGNING_KEY")
        val signingPassword = System.getenv("SIGNING_PASSWORD")
        isRequired = !signingKey.isNullOrBlank()
        if (isRequired) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(extensions.getByType<PublishingExtension>().publications["maven"])
        }
    }
}
