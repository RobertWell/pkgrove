// pkgrovekit-operation-mcp: binds an OperationRegistry to an MCP tool surface
// (HEL-602).
//
// Transport-neutral on purpose: it does NOT depend on the quarkiverse MCP
// server extension. The first consumer (Travel Planner) runs its own JSON-RPC
// 2.0 Streamable-HTTP server at /mcp (protocol 2025-06-18), and a dependency on
// someone else's MCP runtime would make this module unusable there. A
// quarkiverse-mcp-server adapter can be added later as another thin module over
// the same McpDispatcher.
dependencies {
    api(project(":pkgrovekit-operation-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
