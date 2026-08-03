// pkgrovekit-narayana: convenience wiring of the Narayana transaction manager
// for STANDALONE (non-app-server) usage — HEL-170. Narayana is the established
// external TM; PkgroveKit ships zero 2PC logic of its own. Only consumers who
// import THIS module receive Narayana.
dependencies {
    api(project(":pkgrovekit-jta"))
    implementation(libs.narayana.jta)
    // narayana declares jboss-logging as provided; standalone use needs it present
    runtimeOnly(libs.jboss.logging)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
