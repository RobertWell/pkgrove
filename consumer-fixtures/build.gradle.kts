// HEL-235 consumer-fixture harness. Each subproject declares ONLY the PkgroveKit
// capability its scenario selects (versionless — pinned by the published
// pkgrovekit-bom platform) and states, via `requiredModules` / `forbiddenModules`
// extras, which pkgrovekit modules MUST and MUST NOT appear on its resolved
// runtime classpath. `verifyFixture` asserts both and records the full runtime
// classpath to build/runtime-classpath.txt. This is how the module hierarchy is
// proven from the CONSUMER side: a real downstream resolving real POMs.
subprojects {
    apply(plugin = "java")

    // the published PkgroveKit modules target Java 21; resolve against a 21 JVM
    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    repositories {
        mavenLocal()   // the just-published PkgroveKit release
        mavenCentral() // its external transitive deps
    }

    tasks.register("verifyFixture") {
        group = "verification"
        description = "Assert required PkgroveKit modules present + forbidden absent on the runtime classpath"
        doLast {
            val artifacts = configurations.getByName("runtimeClasspath")
                .resolvedConfiguration.resolvedArtifacts
            val pkgroveOnClasspath = artifacts
                .filter { it.moduleVersion.id.group == "com.pkgrove" }
                .map { it.moduleVersion.id.name }
                .toSortedSet()

            fun list(key: String): List<String> =
                if (project.extra.has(key)) (project.extra[key] as String).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                else emptyList()

            val required = list("requiredModules")
            val forbidden = list("forbiddenModules")
            require(required.isNotEmpty()) { "[${project.name}] fixture declared no requiredModules — spec not wired" }

            val missing = required.filter { it !in pkgroveOnClasspath }
            val leaked = forbidden.filter { it in pkgroveOnClasspath }

            // record the FULL runtime classpath (every group) as evidence
            val out = layout.buildDirectory.file("runtime-classpath.txt").get().asFile
            out.parentFile.mkdirs()
            out.writeText(buildString {
                appendLine("# fixture: ${project.name}")
                appendLine("# pkgrovekit modules present: ${pkgroveOnClasspath.joinToString(", ")}")
                appendLine("# --- full runtime classpath ---")
                artifacts.map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}:${it.moduleVersion.id.version}" }
                    .sorted().forEach { appendLine(it) }
            })

            val problems = buildList {
                if (missing.isNotEmpty()) add("MISSING required module(s): $missing")
                if (leaked.isNotEmpty()) add("FORBIDDEN module(s) leaked onto runtime classpath: $leaked")
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    "[${project.name}] fixture assertion FAILED:\n" +
                        problems.joinToString("\n") { "  - $it" } +
                        "\n  present pkgrovekit modules: $pkgroveOnClasspath",
                )
            }
            logger.lifecycle(
                "[${project.name}] OK — present: ${required.sorted()}; " +
                    "confirmed absent: ${forbidden.sorted()}",
            )
        }
    }
}

tasks.register("verifyAllFixtures") {
    group = "verification"
    description = "Run every HEL-235 consumer fixture assertion"
    dependsOn(subprojects.map { "${it.path}:verifyFixture" })
}
