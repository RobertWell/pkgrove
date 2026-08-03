package com.pkgrove.pkgrovekit.quarkus

import com.pkgrove.pkgrovekit.transfer.Relay
import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Any as AnyQualifier
import jakarta.enterprise.inject.Default
import jakarta.enterprise.inject.Disposes
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.Config
import io.quarkus.agroal.DataSource as QuarkusDataSource

/**
 * CDI producer wiring Quarkus-managed Agroal datasources into a PkgroveKit
 * [Relay] (HEL-172). Configuration contract: [PkgroveKitQuarkusConfig].
 *
 * Ownership: every datasource is registered APPLICATION_OWNED — PkgroveKit
 * borrows connections and NEVER closes the Agroal pool; pool lifecycle stays
 * with Quarkus (see the [close] disposer).
 *
 * Threading: [Relay.execute] is blocking JDBC — never call it on a Vert.x
 * event-loop thread. Guard call sites with [BlockingBoundary.assertBlockingAllowed]
 * and run transfer work on worker threads (`@Blocking` endpoints, schedulers).
 *
 * JTA: this module deliberately does NOT re-implement transaction plumbing.
 * Joining a Quarkus-managed/JTA transaction is pkgrovekit-jta's job (HEL-170):
 * a consumer builds `JtaCoordinator(injectedTransactionManager, participants)`
 * with the Quarkus-provided `jakarta.transaction.TransactionManager` and its
 * registered XA participants; the external TM runs the 2PC protocol.
 */
@ApplicationScoped
open class PkgroveKitProducer @Inject constructor(
    private val config: Config,
    @AnyQualifier private val dataSources: Instance<AgroalDataSource>,
) {

    /**
     * Produces the application-wide [Relay], built from
     * `pkgrovekit.databases.<key>.*` configuration. Any configuration problem
     * ([PkgroveKitQuarkusConfig.InvalidConfigException] — all problems listed)
     * or unresolvable datasource throws here, failing deployment/injection
     * loudly instead of yielding a half-wired Relay.
     *
     * `@Singleton` (not a normal scope) on purpose: [Relay] is a final class,
     * and a pseudo-scoped bean needs no client proxy.
     *
     * Transfer plans reference the configured databases by
     * `PkgroveKitDatabaseKey("<key>")` (value-equal to the registered keys).
     */
    @Produces
    @Singleton
    open fun relay(): Relay {
        val cfg = PkgroveKitQuarkusConfig.from(config)
        check(cfg.enabled) {
            "PkgroveKit is disabled (pkgrovekit.enabled=false) but a Relay was " +
                "requested for injection. Re-enable it, or remove the injection point."
        }
        if (cfg.databases.isEmpty()) {
            throw PkgroveKitQuarkusConfig.InvalidConfigException(listOf(
                "no databases configured — a Relay injection point exists but no " +
                    "pkgrovekit.databases.<key>.dialect entries were found. Declare at " +
                    "least one database (e.g. pkgrovekit.databases.main.dialect=postgres).",
            ))
        }
        return Relay.build {
            for (db in cfg.databases) {
                val ds = resolveDataSource(db.key, db.datasourceName)
                database(PkgroveKitDatabaseKey(db.key), ds, db.dialect,
                         maxConnections = db.maxConnections)
            }
        }
    }

    /**
     * Disposes the produced [Relay] when the container shuts down.
     *
     * This intentionally calls ONLY [Relay.close] and never touches the
     * Agroal datasources: `Relay.close()` delegates to `Databases.close()`,
     * which closes exclusively PKGROVEKIT_MANAGED registrations — and every
     * registration made by [relay] is APPLICATION_OWNED (`Relay.Builder
     * .database(...)` registers via `Databases.Builder.applicationOwned`), so
     * the disposer provably leaves the pools alone. Closing an Agroal pool
     * here would double-manage a Quarkus-owned resource: Quarkus shuts its
     * datasources down itself, and doing it first would break any other bean
     * still using them during shutdown ordering.
     */
    open fun close(@Disposes relay: Relay) {
        relay.close()
    }

    /** Resolve one configured database's Agroal datasource via portable CDI —
     *  no Arc API. Default datasource = the `@Default`-qualified bean; a named
     *  one = `@io.quarkus.agroal.DataSource(name)`. */
    private fun resolveDataSource(key: String, datasourceName: String?): AgroalDataSource {
        val label = datasourceName ?: PkgroveKitQuarkusConfig.DEFAULT_DATASOURCE
        val selected =
            if (datasourceName == null) dataSources.select(Default.Literal.INSTANCE)
            else dataSources.select(QuarkusDataSource.DataSourceLiteral(datasourceName))
        check(!selected.isUnsatisfied) {
            "pkgrovekit.databases.$key: no Agroal datasource '$label' is defined. " +
                fixHint(datasourceName)
        }
        check(!selected.isAmbiguous) {
            "pkgrovekit.databases.$key: datasource '$label' resolved ambiguously " +
                "(multiple AgroalDataSource beans match). Disambiguate the " +
                "application's datasource beans."
        }
        try {
            return selected.get()
        } catch (e: RuntimeException) {
            // e.g. Quarkus's synthetic bean for an unconfigured named datasource
            // throws on instantiation — re-raise with the key and the fix.
            throw IllegalStateException(
                "pkgrovekit.databases.$key: Agroal datasource '$label' exists but " +
                    "could not be activated. " + fixHint(datasourceName), e)
        }
    }

    private fun fixHint(datasourceName: String?): String =
        if (datasourceName == null) {
            "Add default datasource config (quarkus.datasource.db-kind + " +
                "quarkus.datasource.jdbc.url) and a quarkus-jdbc-* driver extension."
        } else {
            "Add quarkus.datasource.$datasourceName.* config (db-kind + jdbc.url) " +
                "and a quarkus-jdbc-* driver extension."
        }
}
