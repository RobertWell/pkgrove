// RowRelay root build: shared configuration for every module. Publishable
// modules add the `rowrelay.publish` convention below; integration-tests
// deliberately does not (it is never published — HEL-123 §2).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    group = "io.maxxga.rowrelay"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

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
