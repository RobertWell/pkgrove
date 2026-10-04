// Scenario (HEL-602): one operation catalogue served on REST and MCP. Intended
// user: an application that already has its own JSON stack, its own HTTP
// framework and its own MCP server, and wants ONE declaration per operation
// rather than a resource method plus a tool definition that drift apart.
//
// This fixture is the consumer-side proof of the strongest claim the README
// makes about the operation layer: selecting it pulls the operation modules and
// NOTHING else — no data-access spine, no Jackson, no JAX-RS, no MCP runtime,
// no DI container.
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.6.0"))
    implementation("com.pkgrove:pkgrovekit-operation-rest")
    implementation("com.pkgrove:pkgrovekit-operation-mcp")
}
extra["requiredModules"] =
    "pkgrovekit-operation-core,pkgrovekit-operation-rest,pkgrovekit-operation-mcp"
// The whole data-access spine, the coordination layer, object storage and the
// framework adapters are absent: the operation layer shares no types with them.
extra["forbiddenModules"] =
    "pkgrovekit-core,pkgrovekit-jdbc,pkgrovekit-transfer,pkgrovekit-jdbi," +
        "pkgrovekit-oracle,pkgrovekit-duckdb,pkgrovekit-postgres," +
        "pkgrovekit-coordination-api,pkgrovekit-jta,pkgrovekit-narayana,pkgrovekit-saga," +
        "pkgrovekit-quarkus,pkgrovekit-spring-boot-starter," +
        "pkgrovekit-storage-api,pkgrovekit-storage-s3," +
        // a REST host must not resolve the CDI wiring it did not select
        "pkgrovekit-operation-quarkus"
// No JSON binder, no REST API, no MCP runtime, no DI container, no AWS SDK.
// operation-core is zero-dependency precisely so these lines can be asserted.
extra["forbiddenGroups"] =
    "com.fasterxml.jackson,jakarta.ws.rs,jakarta.enterprise,org.eclipse.microprofile," +
        "io.quarkus,org.springframework,io.quarkiverse,software.amazon.awssdk,io.minio"

// The zero-dependency claim, stated positively: the ONLY artifacts a consumer of
// the operation layer resolves are the three pkgrovekit modules plus the Kotlin
// stdlib (and its annotations). A new transitive dependency in operation-core
// fails here, which is the point — this module describes a consuming
// application's whole API surface and must not impose a stack on it.
tasks.named("verifyFixture") {
    doLast {
        val allowed = setOf("com.pkgrove", "org.jetbrains.kotlin", "org.jetbrains")
        val unexpected = configurations.getByName("runtimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id }
            .filter { it.group !in allowed }
            .map { "${it.group}:${it.name}:${it.version}" }
            .toSortedSet()
        require(unexpected.isEmpty()) {
            "[operation-rest-mcp] the operation layer must stay dependency-free; " +
                "unexpected runtime artifact(s): $unexpected"
        }
    }
}
