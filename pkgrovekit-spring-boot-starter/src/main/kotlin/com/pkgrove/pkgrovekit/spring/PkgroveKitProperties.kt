package com.pkgrove.pkgrovekit.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `pkgrovekit.*` configuration tree (HEL-172). Declares WHICH existing
 * Spring `DataSource` beans PkgroveKit may borrow and under WHAT dialect —
 * the starter never creates, replaces, or closes a pool.
 *
 * ```yaml
 * pkgrovekit:
 *   databases:
 *     ledger:
 *       dialect: postgres          # required: duckdb | oracle | postgres
 *       datasource-bean: ledgerDs  # optional when exactly one DataSource bean exists
 *       max-connections: 4         # optional PkgroveKit lease budget for this key
 *       default-policy: Atomic     # optional, metadata only (validated at startup)
 * ```
 *
 * An invalid entry fails context refresh with the offending
 * `pkgrovekit.databases.<key>` property named — never a silent fallback.
 */
@ConfigurationProperties(prefix = "pkgrovekit")
class PkgroveKitProperties {

    /** Master switch: `false` removes the whole auto-configuration. */
    var enabled: Boolean = true

    /** Database registrations, keyed by the name consumers address via
     *  [SpringDatabaseKey]`("<key>")` when defining Relay plans. */
    var databases: Map<String, DatabaseSpec> = emptyMap()

    /** One configured database: bean reference + dialect + optional budget. */
    class DatabaseSpec {

        /** Name of an EXISTING `DataSource` bean. May be omitted ONLY when the
         *  context has exactly one `DataSource` bean; otherwise startup fails
         *  listing the candidates. */
        var datasourceBean: String? = null

        /** Required dialect id: `duckdb` | `oracle` | `postgres` (matched
         *  case-insensitively). PkgroveKit publishes no generic/ANSI dialect —
         *  an unknown id fails startup rather than guessing SQL. */
        var dialect: String? = null

        /** PkgroveKit's lease budget for this key (NOT the pool's size);
         *  omitted → the [com.pkgrove.pkgrovekit.jdbc.Databases] default. */
        var maxConnections: Int? = null

        /** Documented default [com.pkgrove.pkgrovekit.jdbc.TransactionPolicy]
         *  NAME (`Atomic`, `AutoCommit`, `Chunked`, `JoinExisting`,
         *  `SavepointPerBatch`). Validated at startup, stored as metadata —
         *  the policy each operation selects always wins. */
        var defaultPolicy: String? = null
    }
}
