// pkgrovekit-bom: dependency-constraint platform (HEL-235). Lets a consumer
// import `platform("com.pkgrove:pkgrovekit-bom:<v>")` once and then declare each
// pkgrovekit module WITHOUT a version — the BOM pins them all to one release.
//
// A BOM adds NO runtime dependencies of its own: `java-platform` publishes only
// <dependencyManagement> constraints, never <dependencies>. There is
// deliberately NO `pkgrovekit-all` aggregate — the point of the hierarchy is
// that a consumer selects exactly the capabilities they need; the BOM only
// aligns versions, it never pulls modules in.
//
// NOTE: the `java-platform` plugin is applied from the ROOT build so the
// `javaPlatform` software component exists when the root publishing convention
// creates this module's publication.

// Constraints only — every publishable pkgrovekit module at THIS release. The
// list is asserted complete by the root `assertModuleHierarchy` task (a new
// published module missing from the BOM fails the build).
dependencies {
    constraints {
        api(project(":pkgrovekit-core"))
        api(project(":pkgrovekit-jdbc"))
        api(project(":pkgrovekit-transfer"))
        api(project(":pkgrovekit-oracle"))
        api(project(":pkgrovekit-duckdb"))
        api(project(":pkgrovekit-postgres"))
        api(project(":pkgrovekit-jdbi"))
        api(project(":pkgrovekit-coordination-api"))
        api(project(":pkgrovekit-jta"))
        api(project(":pkgrovekit-narayana"))
        api(project(":pkgrovekit-saga"))
        api(project(":pkgrovekit-quarkus"))
        api(project(":pkgrovekit-spring-boot-starter"))
    }
}
