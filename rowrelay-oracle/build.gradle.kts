// rowrelay-oracle: Oracle dialect + oracle.sql.* value normalization.
// The driver is compileOnly — consumers bring their own Oracle JDBC driver.
dependencies {
    api(project(":rowrelay-jdbc"))
    compileOnly(libs.ojdbc11)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
