// pkgrovekit-oracle: Oracle dialect + oracle.sql.* value normalization.
// The driver is compileOnly — consumers bring their own Oracle JDBC driver.
dependencies {
    api(project(":pkgrovekit-jdbc"))
    compileOnly(libs.ojdbc11)
    testImplementation(libs.junit.jupiter)
    // HEL-234: OracleValueReader normalizes REAL oracle.sql.* driver values —
    // the tests construct them, so the driver is needed at test compile time.
    testImplementation(libs.ojdbc11)
    testRuntimeOnly(libs.junit.launcher)
}
