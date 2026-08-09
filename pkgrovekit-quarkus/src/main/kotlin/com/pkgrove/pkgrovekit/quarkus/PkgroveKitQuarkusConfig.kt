package com.pkgrove.pkgrovekit.quarkus

import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider
import com.pkgrove.pkgrovekit.jdbc.TransactionPolicy
import org.eclipse.microprofile.config.Config

/**
 * Typed PkgroveKit configuration parsed from MicroProfile Config (HEL-172).
 *
 * Recognized keys:
 *  - `pkgrovekit.enabled` — optional boolean, default `true`.
 *  - `pkgrovekit.databases.<key>.dialect` — REQUIRED per database; one of
 *    `postgres` | `oracle` | `duckdb` | `ansi`, resolved to the real dialect
 *    object ([PostgresDialect], [OracleDialect], [DuckDbDialect],
 *    [AnsiDialect]). An unknown name is a startup failure, never a fallback.
 *  - `pkgrovekit.databases.<key>.datasource` — optional name of the Quarkus
 *    datasource to use; the literal `<default>` or omission selects the
 *    DEFAULT Quarkus datasource ([DatabaseConfig.datasourceName] = null).
 *  - `pkgrovekit.databases.<key>.max-connections` — optional positive int
 *    lease budget forwarded to `Relay.build { database(..., maxConnections) }`.
 *  - `pkgrovekit.databases.<key>.default-policy` — optional, validated
 *    [TransactionPolicy] name: `atomic` | `auto-commit` | `savepoint-per-batch`
 *    | `join-existing` (only the parameterless policies are nameable).
 *
 * Parsing is DETERMINISTIC and EXPLICIT: database keys are discovered by
 * iterating [Config.getPropertyNames] for the dotted
 * `pkgrovekit.databases.` prefix — no classpath scanning, no reflection.
 * (Environment-variable-mangled forms such as `PKGROVEKIT_DATABASES_MAIN_...`
 * are matched only if the config implementation republishes them under the
 * dotted name, as SmallRye Config in Quarkus does.) Keys are processed in
 * sorted order, so the resulting [databases] list — and therefore Relay
 * registration order — is stable across restarts.
 *
 * Every problem is collected and reported TOGETHER in one
 * [InvalidConfigException] (a typo in database 3 does not hide the missing
 * dialect on database 1); the producer throws it at deployment/first-injection
 * time, failing the application loudly.
 */
data class PkgroveKitQuarkusConfig(
    val enabled: Boolean,
    /** Sorted by [DatabaseConfig.key] — deterministic registration order. */
    val databases: List<DatabaseConfig>,
) {

    /** One validated `pkgrovekit.databases.<key>.*` block. */
    data class DatabaseConfig(
        /** The PkgroveKit database identity ([PkgroveKitDatabaseKey] name). */
        val key: String,
        /** Quarkus datasource name; null = the DEFAULT datasource. */
        val datasourceName: String?,
        /** Validated dialect name as configured (lowercased). */
        val dialectName: String,
        /** The resolved dialect object. */
        val dialect: SqlDialect,
        /** Optional lease budget for the Relay registration. */
        val maxConnections: Int?,
        /** Optional validated default transaction policy. */
        val defaultPolicy: TransactionPolicy?,
    )

    /** All configuration problems, together — thrown at producer time so an
     *  invalid deployment fails on startup/first injection, never lazily. */
    class InvalidConfigException(val problems: List<String>) : RuntimeException(
        "invalid PkgroveKit configuration — ${problems.size} problem(s):\n" +
            problems.joinToString("\n") { "  - $it" },
    )

    companion object {
        /** Literal `datasource` value selecting the default Quarkus datasource. */
        const val DEFAULT_DATASOURCE = "<default>"

        private const val ENABLED_KEY = "pkgrovekit.enabled"
        private const val DATABASES_PREFIX = "pkgrovekit.databases."

        private val KNOWN_ATTRIBUTES =
            setOf("datasource", "dialect", "max-connections", "default-policy")

        /** Dialects available to configuration = every dialect module ACTUALLY
         *  on the classpath (discovered via [SqlDialectProvider], HEL-235) plus
         *  this module's built-in generic [AnsiDialect]. The adapter no longer
         *  compile-depends on any concrete dialect module: a `quarkus + oracle`
         *  consumer sees `oracle` and `ansi`, never `postgres`/`duckdb`.
         *  `ansi` always wins its key (built-in), otherwise providers are used. */
        internal fun availableDialects(): Map<String, SqlDialect> =
            SqlDialectProvider.loadAll() + ("ansi" to AnsiDialect)

        /** Only parameterless policies can be named in flat configuration —
         *  `Chunked` needs `rowsPerCommit` and belongs in code. */
        private val POLICIES: Map<String, TransactionPolicy> = mapOf(
            "atomic" to TransactionPolicy.Atomic,
            "auto-commit" to TransactionPolicy.AutoCommit,
            "savepoint-per-batch" to TransactionPolicy.SavepointPerBatch,
            "join-existing" to TransactionPolicy.JoinExisting,
        )

        /** Parse + validate. Throws [InvalidConfigException] listing EVERY
         *  problem when anything is invalid. */
        fun from(config: Config): PkgroveKitQuarkusConfig {
            val problems = mutableListOf<String>()

            val enabled = when (val raw = str(config, ENABLED_KEY)?.lowercase()) {
                null -> true
                "true" -> true
                "false" -> false
                else -> {
                    problems += "$ENABLED_KEY: '$raw' is not a boolean (true|false)"
                    true
                }
            }

            // discover database keys from the dotted property names (sorted →
            // deterministic order and deterministic error listing)
            val keys = sortedSetOf<String>()
            for (name in config.propertyNames) {
                if (!name.startsWith(DATABASES_PREFIX)) continue
                val rest = name.substring(DATABASES_PREFIX.length)
                val dot = rest.indexOf('.')
                if (dot <= 0 || dot == rest.length - 1) {
                    problems += "$name: malformed — expected " +
                        "pkgrovekit.databases.<key>.<attribute>"
                    continue
                }
                val key = rest.substring(0, dot)
                val attribute = rest.substring(dot + 1)
                if (attribute !in KNOWN_ATTRIBUTES) {
                    problems += "$name: unknown attribute '$attribute' " +
                        "(known: ${KNOWN_ATTRIBUTES.sorted().joinToString(", ")})"
                    continue
                }
                keys += key
            }

            val dialects = availableDialects()
            val databases = keys.mapNotNull { key ->
                parseDatabase(config, key, dialects, problems)
            }

            if (problems.isNotEmpty()) throw InvalidConfigException(problems)
            return PkgroveKitQuarkusConfig(enabled, databases)
        }

        private fun parseDatabase(
            config: Config,
            key: String,
            dialects: Map<String, SqlDialect>,
            problems: MutableList<String>,
        ): DatabaseConfig? {
            val base = "$DATABASES_PREFIX$key"
            var ok = true

            val dialectRaw = str(config, "$base.dialect")
            var dialect: SqlDialect? = null
            var dialectName = ""
            if (dialectRaw == null) {
                problems += "$base.dialect is required " +
                    "(one of: ${dialects.keys.sorted().joinToString(", ")})"
                ok = false
            } else {
                dialectName = dialectRaw.trim().lowercase()
                dialect = dialects[dialectName]
                if (dialect == null) {
                    problems += "$base.dialect: unknown dialect '$dialectRaw' " +
                        "(available on this classpath: ${dialects.keys.sorted().joinToString(", ")})"
                    ok = false
                }
            }

            val dsRaw = str(config, "$base.datasource")?.trim()
            val datasourceName =
                if (dsRaw == null || dsRaw == DEFAULT_DATASOURCE) null else dsRaw

            val maxRaw = str(config, "$base.max-connections")
            var maxConnections: Int? = null
            if (maxRaw != null) {
                val parsed = maxRaw.trim().toIntOrNull()
                if (parsed == null || parsed <= 0) {
                    problems += "$base.max-connections: '$maxRaw' is not a positive integer"
                    ok = false
                } else {
                    maxConnections = parsed
                }
            }

            val policyRaw = str(config, "$base.default-policy")
            var policy: TransactionPolicy? = null
            if (policyRaw != null) {
                policy = POLICIES[policyRaw.trim().lowercase()]
                if (policy == null) {
                    problems += "$base.default-policy: unknown policy '$policyRaw' " +
                        "(one of: ${POLICIES.keys.sorted().joinToString(", ")})"
                    ok = false
                }
            }

            return if (ok) DatabaseConfig(
                key = key,
                datasourceName = datasourceName,
                dialectName = dialectName,
                dialect = dialect!!,
                maxConnections = maxConnections,
                defaultPolicy = policy,
            ) else null
        }

        /** Read one property as String. MP Config treats an empty value as
         *  absent (empty Optional), which matches "omitted" semantics here. */
        private fun str(config: Config, name: String): String? =
            config.getOptionalValue(name, String::class.java).orElse(null)
    }
}
