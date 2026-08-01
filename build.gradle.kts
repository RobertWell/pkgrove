// RowRelay root build: shared configuration for every module. Publishable
// modules add the `rowrelay.publish` convention below; integration-tests
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
val rowrelayRelease = "0.2.0"

/** Short commit sha for `-Pdev` local builds; safe fallback if git is absent so
 *  a dev build never fails on version resolution. Only invoked when `-Pdev` is
 *  set, so normal `check`/publish never runs git. */
fun devBuildVersion(): String = try {
    val sha = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
        .redirectErrorStream(true).start()
        .inputStream.bufferedReader().readText().trim()
    if (sha.isEmpty()) "$rowrelayRelease-dev" else "$rowrelayRelease-dev.$sha"
} catch (_: Exception) {
    "$rowrelayRelease-dev"
}

allprojects {
    group = "io.maxxga.rowrelay"
    version = if (project.hasProperty("dev")) devBuildVersion() else rowrelayRelease

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

/** Publishable-module convention: sources + Dokka-javadoc jars, maven-publish
 *  to GitHub Packages (credentials from the Actions environment only). */
configure(subprojects.filter { it.name.startsWith("rowrelay-") }) {
    apply(plugin = "maven-publish")
    apply(plugin = "org.jetbrains.dokka")

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
                    description.set("RowRelay — reusable Kotlin data library: " +
                                    "dynamic JDBC/JDBI data access and bidirectional batch transfer")
                    url.set("https://github.com/RobertWell/rowrelay")
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/RobertWell/rowrelay")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
}
