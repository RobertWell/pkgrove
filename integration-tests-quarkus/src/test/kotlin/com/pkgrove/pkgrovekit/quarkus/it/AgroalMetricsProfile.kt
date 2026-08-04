package com.pkgrove.pkgrovekit.quarkus.it

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile enabling Agroal's own pool metrics on BOTH datasources so
 * `AgroalDataSource.getMetrics().activeCount()` is a real gauge instead of the
 * interface's default-0 stub (HEL-172: lease-leak and disposer evidence must
 * not be vacuously green).
 *
 * `quarkus.datasource.jdbc.enable-metrics` is the quarkus-agroal 3.21
 * build-time switch (io.quarkus.agroal.runtime.DataSourceJdbcBuildTimeConfig
 * .enableMetrics). When set explicitly it drives Agroal's
 * `metricsEnabled(...)` directly — no metrics extension required. Being a
 * build-time property it must ride a test profile (re-augmentation), not a
 * runtime override.
 *
 * Shared by [QuarkusDisposerShutdownTest] and [QuarkusCancellationLeakTest]
 * so both run against ONE augmented application instance.
 */
class AgroalMetricsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> = mapOf(
        "quarkus.datasource.jdbc.enable-metrics" to "true",
        "quarkus.datasource.\"warehouse\".jdbc.enable-metrics" to "true",
    )
}
