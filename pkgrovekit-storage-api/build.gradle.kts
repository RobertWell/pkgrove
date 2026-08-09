// pkgrovekit-storage-api: vendor-neutral object-storage concepts (HEL-236).
// Depends ONLY on pkgrovekit-core (Row/Schema/CancelToken/DataWarning reuse) —
// NO AWS SDK, no MinIO SDK, no database adapter, no framework, no coordination.
// Ships an InMemoryObjectStore reference implementation so consumers (and this
// module's own tests) can exercise storage workflows without any provider.
dependencies {
    api(project(":pkgrovekit-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
