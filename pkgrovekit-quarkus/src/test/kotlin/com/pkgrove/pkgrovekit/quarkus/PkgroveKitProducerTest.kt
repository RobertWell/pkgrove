package com.pkgrove.pkgrovekit.quarkus

import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource as QuarkusDataSource
import jakarta.enterprise.inject.Default
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.util.TypeLiteral
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigValue
import org.eclipse.microprofile.config.spi.ConfigSource
import org.eclipse.microprofile.config.spi.Converter
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.util.Optional

/**
 * HEL-234: malformed-configuration / missing-datasource / ambiguity / disposer
 * behavior of [PkgroveKitProducer] — the deployment-failure paths the issue
 * calls out — proven against the REAL CDI Instance and Agroal API types via
 * tiny in-memory fakes (no container, no Arc; the real-framework happy path
 * stays in integration-tests-quarkus).
 */
class PkgroveKitProducerTest {

    // --- fakes --------------------------------------------------------------

    private class MapConfig(private val map: Map<String, String>) : Config {
        override fun <T> getValue(propertyName: String, propertyType: Class<T>): T =
            getOptionalValue(propertyName, propertyType).orElseThrow { NoSuchElementException(propertyName) }
        override fun getConfigValue(propertyName: String): ConfigValue =
            throw UnsupportedOperationException()
        @Suppress("UNCHECKED_CAST")
        override fun <T> getOptionalValue(propertyName: String, propertyType: Class<T>): Optional<T> =
            Optional.ofNullable(map[propertyName]?.takeIf { it.isNotEmpty() }) as Optional<T>
        override fun getPropertyNames(): Iterable<String> = map.keys
        override fun getConfigSources(): Iterable<ConfigSource> = emptyList()
        override fun <T> getConverter(forType: Class<T>): Optional<Converter<T>> = Optional.empty()
        override fun <T> unwrap(type: Class<T>): T = throw UnsupportedOperationException()
    }

    /** Inert AgroalDataSource — the producer must only REGISTER it, never use it. */
    private fun fakeAgroal(): AgroalDataSource =
        Proxy.newProxyInstance(
            AgroalDataSource::class.java.classLoader,
            arrayOf(AgroalDataSource::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "fake-agroal"
                "hashCode" -> 0
                "equals" -> false
                else -> throw UnsupportedOperationException(
                    "producer must not touch the datasource at build time: ${method.name}")
            }
        } as AgroalDataSource

    /**
     * Fake CDI [Instance] resolving like Quarkus does: `@Default` -> the
     * default datasource, `@io.quarkus.agroal.DataSource(name)` -> a named one.
     * [activationFailure] simulates Quarkus's synthetic bean for an
     * UNCONFIGURED named datasource, which throws on instantiation.
     */
    private class FakeInstance(
        private val defaultDs: List<AgroalDataSource> = emptyList(),
        private val named: Map<String, List<AgroalDataSource>> = emptyMap(),
        private val activationFailure: RuntimeException? = null,
        private val selected: List<AgroalDataSource>? = null,
    ) : Instance<AgroalDataSource> {

        override fun select(vararg qualifiers: Annotation): Instance<AgroalDataSource> {
            val q = qualifiers.single()
            val candidates = when {
                q is Default -> defaultDs
                q is QuarkusDataSource -> named[q.value] ?: emptyList()
                else -> throw IllegalArgumentException("unexpected qualifier $q")
            }
            return FakeInstance(defaultDs, named, activationFailure, candidates)
        }

        override fun <U : AgroalDataSource?> select(subtype: Class<U>, vararg qualifiers: Annotation): Instance<U> =
            throw UnsupportedOperationException()
        override fun <U : AgroalDataSource?> select(subtype: TypeLiteral<U>, vararg qualifiers: Annotation): Instance<U> =
            throw UnsupportedOperationException()
        override fun isUnsatisfied(): Boolean = (selected ?: emptyList()).isEmpty()
        override fun isAmbiguous(): Boolean = (selected ?: emptyList()).size > 1
        override fun destroy(instance: AgroalDataSource) {}
        override fun getHandle(): Instance.Handle<AgroalDataSource> = throw UnsupportedOperationException()
        override fun handles(): Iterable<Instance.Handle<AgroalDataSource>> = throw UnsupportedOperationException()
        override fun get(): AgroalDataSource {
            activationFailure?.let { throw it }
            return selected!!.single()
        }
        override fun iterator(): MutableIterator<AgroalDataSource> =
            (selected ?: emptyList()).toMutableList().iterator()
    }

    private fun producer(
        config: Map<String, String>,
        instance: Instance<AgroalDataSource> = FakeInstance(defaultDs = listOf(fakeAgroal())),
    ) = PkgroveKitProducer(MapConfig(config), instance)

    // --- tests --------------------------------------------------------------

    @Test
    fun `disabled kit with a live injection point fails loudly`() {
        val e = assertThrows<IllegalStateException> {
            producer(mapOf(
                "pkgrovekit.enabled" to "false",
                "pkgrovekit.databases.main.dialect" to "ansi",
            )).relay()
        }
        assertTrue("pkgrovekit.enabled=false" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `no configured databases fails with the actionable hint`() {
        val e = assertThrows<PkgroveKitQuarkusConfig.InvalidConfigException> {
            producer(emptyMap()).relay()
        }
        assertTrue("no databases configured" in e.problems.single(), e.problems.single())
    }

    @Test
    fun `malformed configuration propagates the full problem list`() {
        val e = assertThrows<PkgroveKitQuarkusConfig.InvalidConfigException> {
            producer(mapOf("pkgrovekit.databases.main.dialect" to "mysql")).relay()
        }
        assertTrue("unknown dialect 'mysql'" in e.problems.single(), e.problems.single())
    }

    @Test
    fun `missing default datasource is reported with the fix hint`() {
        val e = assertThrows<IllegalStateException> {
            producer(
                mapOf("pkgrovekit.databases.main.dialect" to "ansi"),
                FakeInstance(), // nothing registered at all
            ).relay()
        }
        val msg = e.message ?: ""
        assertTrue("no Agroal datasource '<default>'" in msg, msg)
        assertTrue("quarkus.datasource.db-kind" in msg, msg)
    }

    @Test
    fun `missing named datasource is reported with the named fix hint`() {
        val e = assertThrows<IllegalStateException> {
            producer(
                mapOf(
                    "pkgrovekit.databases.wh.dialect" to "ansi",
                    "pkgrovekit.databases.wh.datasource" to "warehouse",
                ),
                FakeInstance(defaultDs = listOf(fakeAgroal())), // named one absent
            ).relay()
        }
        val msg = e.message ?: ""
        assertTrue("no Agroal datasource 'warehouse'" in msg, msg)
        assertTrue("quarkus.datasource.warehouse" in msg, msg)
    }

    @Test
    fun `ambiguous datasource resolution is rejected`() {
        val e = assertThrows<IllegalStateException> {
            producer(
                mapOf("pkgrovekit.databases.main.dialect" to "ansi"),
                FakeInstance(defaultDs = listOf(fakeAgroal(), fakeAgroal())),
            ).relay()
        }
        assertTrue("resolved ambiguously" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `datasource that exists but cannot activate is wrapped with key and fix`() {
        val boom = IllegalArgumentException("synthetic bean: datasource not configured")
        val e = assertThrows<IllegalStateException> {
            producer(
                mapOf(
                    "pkgrovekit.databases.main.dialect" to "ansi",
                    "pkgrovekit.databases.main.datasource" to "other",
                ),
                FakeInstance(
                    named = mapOf("other" to listOf(fakeAgroal())),
                    activationFailure = boom,
                ),
            ).relay()
        }
        val msg = e.message ?: ""
        assertTrue("pkgrovekit.databases.main" in msg, msg)
        assertTrue("could not be activated" in msg, msg)
        assertSame(boom, e.cause)
    }

    @Test
    fun `valid config produces a relay and the disposer closes it without touching pools`() {
        val p = producer(
            mapOf(
                "pkgrovekit.databases.main.dialect" to "ansi",
                "pkgrovekit.databases.wh.dialect" to "duckdb",
                "pkgrovekit.databases.wh.datasource" to "warehouse",
            ),
            FakeInstance(
                defaultDs = listOf(fakeAgroal()),
                named = mapOf("warehouse" to listOf(fakeAgroal())),
            ),
        )
        val relay = p.relay()
        assertNotNull(relay)
        // disposer: closes ONLY PkgroveKit-managed registrations; the fake
        // datasource proxies throw on ANY use, so this also proves the pools
        // are never touched
        p.close(relay)
    }
}
