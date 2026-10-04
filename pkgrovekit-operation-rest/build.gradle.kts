// pkgrovekit-operation-rest: binds an OperationRegistry to an HTTP surface
// (HEL-602).
//
// There is deliberately NO jakarta.ws.rs dependency, not even compileOnly: the
// dispatcher takes (method, path, headers, body) and returns (status, body), so
// the host app owns its own JAX-RS/Vert.x/Ktor resource classes and this module
// cannot pin a REST API version onto it. A host resource is a ~10-line delegate
// (see docs/operations.md), which is less code than the annotations the
// alternative would need.
dependencies {
    api(project(":pkgrovekit-operation-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
