// PkgroveKit root build: shared configuration for every module. Publishable
// modules add the `pkgrovekit.publish` convention below; integration-tests
// deliberately does not (it is never published — HEL-123 §2).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply false
    // HEL-124 §7: SBOM of the actual resolved dependency graph (CycloneDX)
    alias(libs.plugins.cyclonedx)
    // HEL-234: root-level jacoco supplies the tool classpath for the
    // aggregated report/verification tasks below
    jacoco
}

jacoco {
    toolVersion = "0.8.11"
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
val pkgrovekitRelease = "0.5.0"

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

// HEL-235: the BOM is a `java-platform` project — it must NOT receive the
// Kotlin/Java/JaCoCo configuration below (java-platform is incompatible with the
// java plugin) and has its own publication (components["javaPlatform"]).
val bomModule = "pkgrovekit-bom"

configure(subprojects.filter { it.name != bomModule }) {
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

// ---------------------------------------------------------------------------
// HEL-235: architecture enforcement. `assertModuleHierarchy` reads the
// machine-readable allowed graph (gradle/allowed-dependencies.txt) and inspects
// BOTH the DECLARED dependency edges and the RESOLVED runtime classpaths of
// every pkgrovekit module, failing the build on any boundary violation:
//   * an undeclared pkgrovekit->pkgrovekit edge, or one at the wrong scope;
//   * a cycle in the allowed graph;
//   * an adapter depending on another adapter;
//   * a framework (Spring/Quarkus/CDI/MicroProfile/Agroal) reaching core/jdbc/transfer;
//   * jdbi leaking onto a jdbc-only runtime classpath;
//   * jta/narayana on a standard module's runtime classpath;
//   * a JDBC driver leaking transitively (drivers must stay consumer-controlled);
//   * a test-only dependency on any published runtime classpath;
//   * a published-POM (api) edge diverging from the allowed boundary;
//   * the BOM omitting a publishable module.
// Wired into `check` below, so `./gradlew check` (and CI) enforces it.
val hierarchyModules = subprojects.map { it.name }.filter { it.startsWith("pkgrovekit-") }.toSet()
val hierarchyBom = bomModule
val hierarchyAdapters = listOf("pkgrovekit-oracle", "pkgrovekit-duckdb", "pkgrovekit-postgres")
val hierarchyStandardModules = listOf(
    "pkgrovekit-core", "pkgrovekit-jdbc", "pkgrovekit-jdbi",
    "pkgrovekit-oracle", "pkgrovekit-duckdb", "pkgrovekit-transfer", "pkgrovekit-postgres",
)
val hierarchyFrameworkGroups = listOf(
    "org.springframework", "io.quarkus", "io.agroal",
    "jakarta.enterprise", "org.eclipse.microprofile",
)
val hierarchyDriverGroups = mapOf(
    "pkgrovekit-oracle" to "com.oracle.database.jdbc",
    "pkgrovekit-duckdb" to "org.duckdb",
    "pkgrovekit-postgres" to "org.postgresql",
)
val hierarchyTestGroups = listOf(
    "org.junit", "junit", "org.testcontainers", "org.mockito", "com.h2database",
)

val assertModuleHierarchy = tasks.register("assertModuleHierarchy") {
    group = "verification"
    description = "Enforces the explicit acyclic module hierarchy + minimal transitive deps (HEL-235)"
    val allowedFile = rootProject.file("gradle/allowed-dependencies.txt")
    inputs.file(allowedFile)
    doLast {
        val violations = mutableListOf<String>()

        // 1) parse the machine-readable allowed graph
        val edgeRe = Regex("""^\s*(\S+)\s*->\s*(\S+)\s*\((\w+)\)\s*$""")
        val allowed = linkedMapOf<String, LinkedHashMap<String, String>>()
        allowedFile.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val m = edgeRe.matchEntire(line)
                ?: throw GradleException("allowed-dependencies.txt: cannot parse line: '$raw'")
            val (from, to, scope) = m.destructured
            require(scope == "api" || scope == "implementation") {
                "allowed-dependencies.txt: scope must be api|implementation, got '$scope' in '$raw'"
            }
            if (from !in hierarchyModules) violations += "allowed graph names unknown module '$from'"
            if (to !in hierarchyModules) violations += "allowed graph names unknown dependency '$to'"
            allowed.getOrPut(from) { linkedMapOf() }[to] = scope
        }

        // 2) cycle detection over the allowed graph (0=unseen,1=on-stack,2=done)
        val color = mutableMapOf<String, Int>()
        fun visit(n: String, stack: List<String>) {
            color[n] = 1
            for (d in allowed[n]?.keys.orEmpty()) {
                when (color[d]) {
                    1 -> violations += "CYCLE in allowed graph: ${(stack + n + d).joinToString(" -> ")}"
                    2 -> {}
                    else -> visit(d, stack + n)
                }
            }
            color[n] = 2
        }
        allowed.keys.forEach { if (color[it] != 2) visit(it, emptyList()) }

        // helpers over the REAL Gradle model
        fun projectDeps(module: String, config: String): List<String> =
            project(module).configurations.findByName(config)
                ?.dependencies
                ?.filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
                ?.map { it.name }
                ?.filter { it.startsWith("pkgrovekit-") }
                ?: emptyList()
        fun runtimeArtifacts(module: String): List<org.gradle.api.artifacts.ModuleVersionIdentifier> =
            project(module).configurations.findByName("runtimeClasspath")
                ?.resolvedConfiguration?.resolvedArtifacts?.map { it.moduleVersion.id }
                ?: emptyList()

        // 3) declared-edge + scope check (this is also the published-POM check:
        //    api edges become POM compile deps, so a POM cannot diverge from
        //    the allowed boundary without failing here)
        for (module in hierarchyModules.filter { it != hierarchyBom }) {
            val apiDeps = projectDeps(module, "api").toSet()
            val implDeps = (projectDeps(module, "implementation") +
                projectDeps(module, "compileOnly") +
                projectDeps(module, "runtimeOnly")).toSet()
            val allow = allowed[module].orEmpty()
            apiDeps.forEach { d ->
                when (allow[d]) {
                    null -> violations += "UNDECLARED EDGE: $module -> $d (api) is not in the allowed graph"
                    "api" -> {}
                    else -> violations += "SCOPE: $module -> $d declared 'api' but allowed graph says '${allow[d]}'"
                }
            }
            implDeps.forEach { d ->
                when (allow[d]) {
                    null -> violations += "UNDECLARED EDGE: $module -> $d (implementation) is not in the allowed graph"
                    "implementation" -> {}
                    else -> violations += "SCOPE: $module -> $d declared non-api but allowed graph says '${allow[d]}'"
                }
            }
            // every allowed edge must actually exist (stale allow-list / POM drift)
            allow.forEach { (d, scope) ->
                val declared = if (scope == "api") d in apiDeps else d in implDeps
                if (!declared) violations +=
                    "MISSING EDGE: allowed '$module -> $d ($scope)' is not declared (stale allow-list?)"
            }
        }

        // 4) framework leak into the driver-free spine
        for (module in listOf("pkgrovekit-core", "pkgrovekit-jdbc", "pkgrovekit-transfer")) {
            runtimeArtifacts(module)
                .filter { id -> hierarchyFrameworkGroups.any { id.group.startsWith(it) } }
                .forEach { violations += "FRAMEWORK LEAK: $module runtime carries ${it.group}:${it.name}" }
        }

        // 5) jdbi leak onto a jdbc-only runtime classpath
        runtimeArtifacts("pkgrovekit-jdbc")
            .filter { it.group.startsWith("org.jdbi") }
            .forEach { violations += "JDBI LEAK: pkgrovekit-jdbc runtime carries ${it.group}:${it.name}" }

        // 6) jta/narayana on a standard module's runtime classpath
        for (module in hierarchyStandardModules) {
            runtimeArtifacts(module)
                .filter { it.group.startsWith("jakarta.transaction") || it.group.startsWith("org.jboss.narayana") }
                .forEach { violations += "COORDINATION LEAK: $module runtime carries ${it.group}:${it.name}" }
        }

        // 7) adapter -> adapter dependency (via resolved project artifacts)
        for (adapter in hierarchyAdapters) {
            val others = hierarchyAdapters.filter { it != adapter }.toSet()
            runtimeArtifacts(adapter)
                .filter { it.group == "com.pkgrove" && it.name in others }
                .forEach { violations += "ADAPTER->ADAPTER: $adapter runtime carries ${it.name}" }
        }

        // 8) JDBC driver leaking transitively (drivers are consumer-controlled)
        hierarchyDriverGroups.forEach { (module, driverGroup) ->
            runtimeArtifacts(module)
                .filter { it.group.startsWith(driverGroup) }
                .forEach { violations += "DRIVER LEAK: $module runtime carries the driver ${it.group}:${it.name} (must be compileOnly/consumer-provided)" }
        }

        // 9) test-only dependency on a PUBLISHED runtime classpath
        for (module in hierarchyModules.filter { it != hierarchyBom }) {
            runtimeArtifacts(module)
                .filter { id -> hierarchyTestGroups.any { id.group == it || id.group.startsWith("$it.") } }
                .forEach { violations += "TEST DEP ON RUNTIME: $module runtime carries test-only ${it.group}:${it.name}" }
        }

        // 10) BOM completeness — every publishable module (except the BOM) is pinned
        val bomEntries = project(hierarchyBom).configurations.findByName("api")
            ?.dependencyConstraints?.map { it.name }?.toSet().orEmpty()
        (hierarchyModules - hierarchyBom).forEach { module ->
            if (module !in bomEntries)
                violations += "BOM GAP: publishable module $module is missing from pkgrovekit-bom constraints"
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module-hierarchy violations (HEL-235) — ${violations.size} problem(s):\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nThe allowed graph is gradle/allowed-dependencies.txt. Fix the boundary, " +
                    "or (with justification) update the allow-list.",
            )
        }
        logger.lifecycle(
            "module hierarchy OK: ${allowed.values.sumOf { it.size }} allowed edges verified across " +
                "${hierarchyModules.size - 1} modules; no undeclared edges, cycles, adapter/framework/" +
                "driver/test leaks, or BOM gaps.",
        )
    }
}

// architecture enforcement rides `check` (and therefore CI)
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(assertModuleHierarchy) }
}

// ---------------------------------------------------------------------------
// HEL-234: coverage is ENFORCED, not just reported. Every production module
// runs under JaCoCo and its `check` FAILS below the thresholds. Gates:
//   - critical modules (jdbc, transfer, jta, coordination-api): 85% line / 75% branch
//   - every other production module: 80% line / 70% branch
// Ratchet policy (HEL-234): when a module's measured baseline exceeds its
// gate, RAISE the gate — never lower one to match a regression (that needs an
// explicit owner-approved exception). Tool version is pinned so local and CI
// runs measure with the same engine (0.8.11 = the Gradle 8.7 default).
// The integration-test modules have no main sources — they are measurement
// PRODUCERS only; their exec data feeds the aggregated report below.
val productionModules = subprojects.filter { it.name.startsWith("pkgrovekit-") && it.name != bomModule }
val criticalCoverageModules = setOf(
    "pkgrovekit-jdbc", "pkgrovekit-transfer", "pkgrovekit-jta", "pkgrovekit-coordination-api",
)

configure(productionModules) {
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.11"
    }
    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)   // machine-readable for CI/badges
            html.required.set(true)  // human-readable artifact
        }
    }
    val minLine = if (name in criticalCoverageModules) "0.85" else "0.80"
    val minBranch = if (name in criticalCoverageModules) "0.75" else "0.70"
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        dependsOn(tasks.named("test"))
        violationRules {
            rule {
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    minimum = minLine.toBigDecimal()
                }
            }
            rule {
                limit {
                    counter = "BRANCH"
                    value = "COVEREDRATIO"
                    minimum = minBranch.toBigDecimal()
                }
            }
        }
    }
    // the gate rides `check`, so plain `./gradlew check` (and therefore CI)
    // cannot pass with under-covered production code
    tasks.named("check") {
        dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
    }
}

// HEL-234: repository-wide merged coverage (report + enforced floor).
// Merges every exec file present under any module — unit runs always, plus
// integration/testcontainer exec data when those suites have run — over the
// production-module class dirs. Repo gates: 80% line / 70% branch.
val jacocoAggregatedReport = tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "Merged line/branch coverage report over all production modules (HEL-234)"
    productionModules.forEach { dependsOn("${it.path}:test") }
    executionData(fileTree(rootDir) { include("*/build/jacoco/*.exec") })
    classDirectories.setFrom(productionModules.map { it.layout.buildDirectory.dir("classes/kotlin/main") })
    sourceDirectories.setFrom(productionModules.map { it.file("src/main/kotlin") })
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

val jacocoAggregatedVerification = tasks.register<JacocoCoverageVerification>("jacocoAggregatedVerification") {
    group = "verification"
    description = "FAILS the build if repository-wide coverage drops below 80% line / 70% branch (HEL-234)"
    productionModules.forEach { dependsOn("${it.path}:test") }
    executionData(fileTree(rootDir) { include("*/build/jacoco/*.exec") })
    classDirectories.setFrom(productionModules.map { it.layout.buildDirectory.dir("classes/kotlin/main") })
    sourceDirectories.setFrom(productionModules.map { it.file("src/main/kotlin") })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

/** Publishable-module convention: sources + Dokka-javadoc jars, maven-publish
 *  to GitHub Packages (credentials from the Actions environment only). */
configure(subprojects.filter { it.name.startsWith("pkgrovekit-") }) {
    apply(plugin = "maven-publish")
    // HEL-189: PGP signing for Maven Central. Gradle-core plugin (no new
    // dependency → passes the supply-chain gate). GATED: only signs when an
    // in-memory key is provided via env, so GitHub-Packages / GitLab / local
    // publishes keep working unsigned. Central requires signed artifacts.
    apply(plugin = "signing")

    // HEL-235: the BOM is a `java-platform` — it has no sources/Dokka/Java
    // component; its publication comes from components["javaPlatform"]. Every
    // other module is a normal Kotlin/Java library. Apply java-platform here (at
    // root-config time) so its software component exists when the publication is
    // created below; the BOM's own build file only adds the constraints.
    val isBom = name == bomModule
    if (isBom) apply(plugin = "java-platform")
    val dokkaJavadocJar = if (!isBom) {
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
        tasks.register<Jar>("dokkaJavadocJar") {
            dependsOn(tasks.named("dokkaJavadoc"))
            from(tasks.named("dokkaJavadoc"))
            archiveClassifier.set("javadoc")
        }
    } else {
        null
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                if (isBom) {
                    from(components["javaPlatform"])
                } else {
                    from(components["java"])
                    artifact(dokkaJavadocJar!!)
                }
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
