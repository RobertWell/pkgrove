// pkgrovekit-operation-core: the functional operation DSL (HEL-602). ZERO
// runtime dependencies beyond the Kotlin stdlib — like pkgrovekit-core, and for
// the same reason: this module describes a consuming application's whole API
// surface, so it must never impose a JSON, REST, MCP or DI stack on it. The
// dependency-free Json tree in this module exists precisely so that neither
// Jackson nor kotlinx.serialization appears on a consumer's classpath because
// of us.
//
// Deliberately NOT depending on pkgrovekit-core either: the operation layer
// shares no types with the data-access spine, and an edge would make every
// REST/MCP consumer resolve the JDBC data model they do not use.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
