package com.pkgrove.pkgrovekit.quarkus

import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.TransactionPolicy
import com.pkgrove.pkgrovekit.oracle.OracleDialect
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigValue
import org.eclipse.microprofile.config.spi.ConfigSource
import org.eclipse.microprofile.config.spi.Converter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

/**
 * Pure-logic proof of [PkgroveKitQuarkusConfig] parsing/validation against an
 * in-memory MP Config (HEL-172). This intentionally covers the
 * startup-invalid-config surface (all problems listed, typed failure) that
 * integration-tests-quarkus does not re-prove with QuarkusUnitTest — adding
 * quarkus-junit5-internal for one deployment-failure assertion was judged not
 * worth the extra framework dependency; the producer throws exactly this
 * exception at deployment time.
 */
class PkgroveKitQuarkusConfigTest {

    /** Minimal deterministic MP Config over a map. Only the surface the parser
     *  uses (String getOptionalValue + propertyNames) is implemented. */
    private class MapConfig(private val map: Map<String, String>) : Config {
        override fun <T> getValue(propertyName: String, propertyType: Class<T>): T =
            getOptionalValue(propertyName, propertyType).orElseThrow {
                NoSuchElementException(propertyName)
            }

        override fun getConfigValue(propertyName: String): ConfigValue =
            throw UnsupportedOperationException("not used by the parser")

        @Suppress("UNCHECKED_CAST")
        override fun <T> getOptionalValue(propertyName: String, propertyType: Class<T>): Optional<T> {
            require(propertyType == String::class.java) {
                "parser must read raw strings only, asked for $propertyType"
            }
            // MP Config semantics: empty value == absent
            @Suppress("UNCHECKED_CAST")
            return Optional.ofNullable(map[propertyName]?.takeIf { it.isNotEmpty() }) as Optional<T>
        }

        override fun getPropertyNames(): Iterable<String> = map.keys
        override fun getConfigSources(): Iterable<ConfigSource> = emptyList()
        override fun <T> getConverter(forType: Class<T>): Optional<Converter<T>> = Optional.empty()
        override fun <T> unwrap(type: Class<T>): T = throw UnsupportedOperationException()
    }

    @Test
    fun `parses a full multi-database configuration deterministically`() {
        val cfg = PkgroveKitQuarkusConfig.from(MapConfig(mapOf(
            "pkgrovekit.databases.warehouse.datasource" to "warehouse",
            "pkgrovekit.databases.warehouse.dialect" to "duckdb",
            "pkgrovekit.databases.warehouse.max-connections" to "8",
            "pkgrovekit.databases.warehouse.default-policy" to "auto-commit",
            "pkgrovekit.databases.main.dialect" to "postgres",
            "pkgrovekit.databases.main.datasource" to "<default>",
            // unrelated properties must be ignored
            "quarkus.datasource.db-kind" to "h2",
            "pkgrovekit.enabled" to "true",
        )))

        assertTrue(cfg.enabled)
        assertEquals(listOf("main", "warehouse"), cfg.databases.map { it.key },
            "keys must come out sorted (deterministic registration order)")

        val main = cfg.databases[0]
        assertNull(main.datasourceName, "<default> selects the default datasource")
        assertSame(PostgresDialect, main.dialect)
        assertEquals("postgres", main.dialectName)
        assertNull(main.maxConnections)
        assertNull(main.defaultPolicy)

        val wh = cfg.databases[1]
        assertEquals("warehouse", wh.datasourceName)
        assertSame(DuckDbDialect, wh.dialect)
        assertEquals(8, wh.maxConnections)
        assertSame(TransactionPolicy.AutoCommit, wh.defaultPolicy)
    }

    @Test
    fun `omitted datasource means default and enabled defaults to true`() {
        val cfg = PkgroveKitQuarkusConfig.from(MapConfig(mapOf(
            "pkgrovekit.databases.solo.dialect" to "oracle",
        )))
        assertTrue(cfg.enabled)
        assertEquals(1, cfg.databases.size)
        assertNull(cfg.databases[0].datasourceName)
        assertSame(OracleDialect, cfg.databases[0].dialect)
    }

    @Test
    fun `all four dialect names resolve to the real dialect objects`() {
        val cfg = PkgroveKitQuarkusConfig.from(MapConfig(mapOf(
            "pkgrovekit.databases.a.dialect" to "postgres",
            "pkgrovekit.databases.b.dialect" to "oracle",
            "pkgrovekit.databases.c.dialect" to "duckdb",
            "pkgrovekit.databases.d.dialect" to "ansi",
        )))
        assertEquals(
            listOf(PostgresDialect, OracleDialect, DuckDbDialect, AnsiDialect),
            cfg.databases.map { it.dialect },
        )
    }

    @Test
    fun `enabled=false parses`() {
        val cfg = PkgroveKitQuarkusConfig.from(MapConfig(mapOf(
            "pkgrovekit.enabled" to "false",
            "pkgrovekit.databases.main.dialect" to "ansi",
        )))
        assertFalse(cfg.enabled)
    }

    @Test
    fun `every problem is listed together in one typed failure`() {
        val e = assertThrows<PkgroveKitQuarkusConfig.InvalidConfigException> {
            PkgroveKitQuarkusConfig.from(MapConfig(mapOf(
                "pkgrovekit.enabled" to "yep",                                  // bad boolean
                "pkgrovekit.databases.one.dialect" to "mysql",                  // unknown dialect
                "pkgrovekit.databases.two.datasource" to "x",                   // dialect missing
                "pkgrovekit.databases.three.dialect" to "ansi",
                "pkgrovekit.databases.three.max-connections" to "many",         // bad int
                "pkgrovekit.databases.three.default-policy" to "chunked",       // not nameable
                "pkgrovekit.databases.four.dialecto" to "ansi",                 // unknown attribute
                "pkgrovekit.databases.nodot" to "x",                            // malformed
            )))
        }
        val all = e.problems.joinToString("\n")
        assertEquals(7, e.problems.size, "expected 7 problems, got:\n$all")
        assertTrue("'yep' is not a boolean" in all, all)
        assertTrue("unknown dialect 'mysql'" in all, all)
        assertTrue("pkgrovekit.databases.two.dialect is required" in all, all)
        assertTrue("'many' is not a positive integer" in all, all)
        assertTrue("unknown policy 'chunked'" in all, all)
        assertTrue("unknown attribute 'dialecto'" in all, all)
        assertTrue("malformed" in all, all)
        // and the message itself carries the full list for deployment logs
        assertTrue("7 problem(s)" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `zero and negative max-connections are rejected`() {
        val e = assertThrows<PkgroveKitQuarkusConfig.InvalidConfigException> {
            PkgroveKitQuarkusConfig.from(MapConfig(mapOf(
                "pkgrovekit.databases.a.dialect" to "ansi",
                "pkgrovekit.databases.a.max-connections" to "0",
            )))
        }
        assertTrue(e.problems.single().contains("positive integer"))
    }

    @Test
    fun `config-declared keys are value-equal by name and class`() {
        assertEquals(PkgroveKitDatabaseKey("main"), PkgroveKitDatabaseKey("main"))
        assertEquals(PkgroveKitDatabaseKey("main").hashCode(),
                     PkgroveKitDatabaseKey("main").hashCode())
        assertFalse(PkgroveKitDatabaseKey("main") == PkgroveKitDatabaseKey("other"))
        // a config key never equals a hand-written application key of the same name
        val appKey = object : com.pkgrove.pkgrovekit.jdbc.DatabaseKey("main") {}
        assertFalse(PkgroveKitDatabaseKey("main").equals(appKey))
    }
}
