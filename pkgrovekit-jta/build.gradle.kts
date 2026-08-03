// pkgrovekit-jta: Jakarta Transactions interpretation of XA coordination plans
// (HEL-170). Delegates begin/commit/rollback to an EXTERNAL TransactionManager —
// no custom 2PC lives here. jakarta.transaction-api is `api` because callers
// hand us a TransactionManager; no TM IMPLEMENTATION is pulled (Narayana lives
// in the separate pkgrovekit-narayana convenience module).
dependencies {
    api(project(":pkgrovekit-coordination-api"))
    api(libs.jakarta.transaction.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
