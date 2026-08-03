package com.pkgrove.pkgrovekit.spring

import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.oracle.OracleDialect
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.Relay
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata
import javax.sql.DataSource

/**
 * Boot auto-configuration for PkgroveKit (HEL-172): builds ONE [Relay] from
 * the application's EXISTING `DataSource` beans as declared under
 * `pkgrovekit.databases.*`. Every registered pool is APPLICATION_OWNED —
 * borrowed, never closed (context shutdown closes only the Relay's registry
 * bookkeeping; pool lifecycle stays with Spring/the app — see
 * [com.pkgrove.pkgrovekit.jdbc.Databases]).
 *
 * Backoff rules: a user-defined [Relay] bean wins ([ConditionalOnMissingBean]);
 * `pkgrovekit.enabled=false` removes the configuration; an absent or empty
 * `pkgrovekit.databases` tree produces no bean ([DatabasesDeclaredCondition]).
 * A PRESENT tree with any invalid entry fails context refresh from
 * [pkgrovekitRelay] with the offending property named — misconfiguration is
 * never degraded to a missing bean.
 */
@AutoConfiguration
@ConditionalOnClass(DataSource::class)
@ConditionalOnProperty(prefix = "pkgrovekit", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PkgroveKitProperties::class)
class PkgroveKitAutoConfiguration {

    /**
     * The [Relay], assembled from beans the application already owns. Fails
     * context refresh (never a partial/silent Relay) for: an empty-but-enabled
     * declaration, an unknown dialect id, an unknown default-policy name, a
     * `datasource-bean` that names no `DataSource` bean, and an omitted
     * `datasource-bean` when zero or several `DataSource` beans exist.
     */
    @Bean
    @ConditionalOnMissingBean(Relay::class)
    @Conditional(DatabasesDeclaredCondition::class)
    fun pkgrovekitRelay(properties: PkgroveKitProperties, beanFactory: ListableBeanFactory): Relay {
        val specs = properties.databases
        if (specs.isEmpty()) {
            // Defensive: DatabasesDeclaredCondition already gates this path.
            throw PkgroveKitConfigurationException(
                "pkgrovekit is enabled but pkgrovekit.databases is empty — declare at least one " +
                    "pkgrovekit.databases.<key>.dialect entry (or set pkgrovekit.enabled=false)")
        }
        val candidates = beanFactory.getBeanNamesForType(DataSource::class.java).sorted()
        return Relay.build {
            for ((key, spec) in specs) {
                val dialect = resolveDialect(key, spec.dialect)
                validateDefaultPolicy(key, spec.defaultPolicy)
                val dataSource = resolveDataSource(key, spec.datasourceBean, candidates, beanFactory)
                database(SpringDatabaseKey(key), dataSource, dialect,
                         maxConnections = spec.maxConnections)
            }
        }
    }

    /**
     * Matches only when `pkgrovekit.databases.*` is DECLARED (binds to a
     * non-empty map). Conditional choice (HEL-172): a wholly-absent tree means
     * "starter on the classpath, feature unused" — back off quietly with no
     * bean and no failure, so merely depending on the starter cannot break an
     * app. Returning null from the bean method is not a legal Spring backoff,
     * and no `@ConditionalOnProperty` marker can distinguish absent from
     * present-but-invalid — binding the subtree here is the smallest condition
     * that can. Anything invalid INSIDE a declared tree still throws from
     * [pkgrovekitRelay].
     */
    class DatabasesDeclaredCondition : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
            Binder.get(context.environment)
                .bind("pkgrovekit.databases", Bindable.mapOf(String::class.java, Any::class.java))
                .map { it.isNotEmpty() }
                .orElse(false)
    }

    companion object {
        /** Dialect ids → the published dialect singletons. PkgroveKit has no
         *  generic/ANSI dialect; the accepted set is exactly the dialect
         *  modules this starter api-exposes. */
        private val DIALECTS: Map<String, SqlDialect> = mapOf(
            "duckdb" to DuckDbDialect,
            "oracle" to OracleDialect,
            "postgres" to PostgresDialect,
        )

        /** Lower-cased [com.pkgrove.pkgrovekit.jdbc.TransactionPolicy] member
         *  names — the only values `default-policy` may carry. */
        private val POLICY_NAMES =
            setOf("atomic", "autocommit", "chunked", "joinexisting", "savepointperbatch")

        private fun resolveDialect(key: String, id: String?): SqlDialect {
            val known = DIALECTS.keys.sorted().joinToString(", ")
            if (id.isNullOrBlank()) {
                throw PkgroveKitConfigurationException(
                    "pkgrovekit.databases.$key.dialect is required (known dialects: $known)")
            }
            return DIALECTS[id.trim().lowercase()] ?: throw PkgroveKitConfigurationException(
                "pkgrovekit.databases.$key.dialect=$id is not a known dialect " +
                    "(known dialects: $known)")
        }

        private fun validateDefaultPolicy(key: String, policy: String?) {
            if (policy.isNullOrBlank()) return
            if (policy.trim().lowercase() !in POLICY_NAMES) {
                throw PkgroveKitConfigurationException(
                    "pkgrovekit.databases.$key.default-policy=$policy is not a TransactionPolicy " +
                        "(known policies: Atomic, AutoCommit, Chunked, JoinExisting, SavepointPerBatch)")
            }
        }

        private fun resolveDataSource(key: String, beanName: String?, candidates: List<String>,
                                      beanFactory: ListableBeanFactory): DataSource = when {
            beanName != null -> {
                if (beanName !in candidates) {
                    throw PkgroveKitConfigurationException(
                        "pkgrovekit.databases.$key.datasource-bean=$beanName does not name a " +
                            "DataSource bean (DataSource beans present: " +
                            "${candidates.ifEmpty { listOf("<none>") }})")
                }
                beanFactory.getBean(beanName, DataSource::class.java)
            }
            candidates.size == 1 -> beanFactory.getBean(candidates[0], DataSource::class.java)
            candidates.isEmpty() -> throw PkgroveKitConfigurationException(
                "pkgrovekit.databases.$key needs a DataSource but the context has no DataSource " +
                    "beans — define one (e.g. spring.datasource.*) or set " +
                    "pkgrovekit.databases.$key.datasource-bean")
            else -> throw PkgroveKitConfigurationException(
                "pkgrovekit.databases.$key is ambiguous: ${candidates.size} DataSource beans " +
                    "exist $candidates — set pkgrovekit.databases.$key.datasource-bean to one of them")
        }
    }
}

/** A declared `pkgrovekit.*` tree that cannot be assembled into a [Relay] —
 *  thrown during context refresh so misconfiguration can never boot. */
class PkgroveKitConfigurationException(message: String) : IllegalStateException(message)
