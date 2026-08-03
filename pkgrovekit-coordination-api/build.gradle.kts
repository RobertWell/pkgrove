// pkgrovekit-coordination-api: inert coordination plans, participant
// capabilities, typed validation and global outcomes (HEL-170). Deliberately
// dependency-light: stdlib + JDK only — importing this module pulls NO
// transaction manager, no JTA API, no drivers. The effectful interpreters live
// in pkgrovekit-jta / pkgrovekit-saga.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
