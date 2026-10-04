// pkgrovekit-operation-quarkus: CDI wiring for the operation registry (HEL-602).
// Framework surfaces are compileOnly, exactly as in pkgrovekit-quarkus — the
// consuming Quarkus application PROVIDES the CDI and MP Config implementations,
// and nothing from jakarta.*/io.quarkus leaks transitively onto a consumer
// classpath.
//
// The startup check uses the CDI-standard @Observes @Initialized(ApplicationScoped)
// event rather than io.quarkus.runtime.StartupEvent, so no quarkus-core
// dependency is required and the same bean works in any CDI container.
dependencies {
    api(project(":pkgrovekit-operation-core"))
    compileOnly(libs.cdi.api)
    compileOnly(libs.mp.config.api)
    testImplementation(libs.junit.jupiter)
    // compileOnly does not reach the test classpath; the producer/startup tests
    // run against the REAL CDI Instance and MP Config types via tiny fakes
    // (no container, no Arc) — the pkgrovekit-quarkus precedent.
    testImplementation(libs.cdi.api)
    testImplementation(libs.mp.config.api)
    testRuntimeOnly(libs.junit.launcher)
}
