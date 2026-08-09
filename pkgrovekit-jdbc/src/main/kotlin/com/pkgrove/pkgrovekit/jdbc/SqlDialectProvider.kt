package com.pkgrove.pkgrovekit.jdbc

import java.util.ServiceLoader

/**
 * Service-loaded extension point that lets a framework adapter discover the
 * [SqlDialect] modules ACTUALLY on the classpath, without compile-time coupling
 * to any concrete dialect (HEL-235).
 *
 * Before HEL-235 the Quarkus / Spring adapters `api`-depended on every dialect
 * module ([com.pkgrove.pkgrovekit.postgres], `oracle`, `duckdb`) purely so their
 * configuration parser could map the string ids `postgres|oracle|duckdb` to the
 * dialect singletons. That forced a "spring + postgres" consumer to also carry
 * Oracle and DuckDB — the opposite of "pay only for the capability you select".
 *
 * Now each dialect module ships one provider registered under
 * `META-INF/services/com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider`, and an
 * adapter resolves ids via [loadById]. A consumer receives exactly the dialects
 * whose modules they added; an unknown id fails loudly at startup — never a
 * silent guess.
 *
 * Implementations MUST be side-effect-free and cheap to construct (ServiceLoader
 * instantiates them via a public no-arg constructor).
 */
interface SqlDialectProvider {

    /** Stable configuration id for this dialect, e.g. `"postgres"`. Lower-case,
     *  matched case-insensitively by [loadById]. */
    val id: String

    /** The dialect singleton this provider contributes. */
    fun dialect(): SqlDialect

    companion object {

        /** Discover every dialect provider visible to [loader] (defaults to the
         *  thread-context/loader that loaded this class). Later duplicates of an
         *  id are ignored so a stable, first-wins map results. */
        @JvmStatic
        @JvmOverloads
        fun loadAll(
            loader: ClassLoader? = Thread.currentThread().contextClassLoader
                ?: SqlDialectProvider::class.java.classLoader,
        ): Map<String, SqlDialect> {
            val out = LinkedHashMap<String, SqlDialect>()
            for (p in ServiceLoader.load(SqlDialectProvider::class.java, loader)) {
                val key = p.id.trim().lowercase()
                if (key.isNotEmpty()) out.putIfAbsent(key, p.dialect())
            }
            return out
        }

        /** Resolve one dialect id against the providers on the classpath, or
         *  null if no module contributes it. Case-insensitive. */
        @JvmStatic
        @JvmOverloads
        fun loadById(
            id: String,
            loader: ClassLoader? = Thread.currentThread().contextClassLoader
                ?: SqlDialectProvider::class.java.classLoader,
        ): SqlDialect? = loadAll(loader)[id.trim().lowercase()]
    }
}
