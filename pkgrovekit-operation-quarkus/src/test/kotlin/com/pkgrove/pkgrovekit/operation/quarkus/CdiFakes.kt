package com.pkgrove.pkgrovekit.operation.quarkus

import jakarta.enterprise.inject.Instance
import jakarta.enterprise.util.TypeLiteral
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigValue
import org.eclipse.microprofile.config.spi.ConfigSource
import org.eclipse.microprofile.config.spi.Converter
import java.util.Optional

/**
 * Tiny in-memory CDI/MP-Config fakes (HEL-602).
 *
 * The same approach `pkgrovekit-quarkus` uses: implement the REAL
 * `jakarta.enterprise.inject.Instance` and `org.eclipse.microprofile.config.Config`
 * interfaces so the producer is exercised against the actual framework contract
 * — no container, no Arc. The live-container proof is in
 * `integration-tests-quarkus`.
 */
internal class FakeInstance<T>(private val items: List<T>) : Instance<T> {

    override fun iterator(): MutableIterator<T> = items.toMutableList().iterator()

    override fun get(): T =
        when (items.size) {
            0 -> throw IllegalStateException("unsatisfied")
            1 -> items.single()
            else -> throw IllegalStateException("ambiguous")
        }

    override fun select(vararg qualifiers: Annotation): Instance<T> = this

    @Suppress("UNCHECKED_CAST")
    override fun <U : T> select(subtype: Class<U>, vararg qualifiers: Annotation): Instance<U> =
        FakeInstance(items.filter { subtype.isInstance(it) } as List<U>)

    override fun <U : T> select(subtype: TypeLiteral<U>, vararg qualifiers: Annotation): Instance<U> =
        throw UnsupportedOperationException()

    override fun isUnsatisfied(): Boolean = items.isEmpty()

    override fun isAmbiguous(): Boolean = items.size > 1

    override fun destroy(instance: T) = Unit

    override fun getHandle(): Instance.Handle<T> = throw UnsupportedOperationException()

    override fun handles(): Iterable<Instance.Handle<T>> = throw UnsupportedOperationException()
}

internal class MapConfig(private val map: Map<String, String>) : Config {
    override fun <T> getValue(propertyName: String, propertyType: Class<T>): T =
        getOptionalValue(propertyName, propertyType).orElseThrow { NoSuchElementException(propertyName) }

    override fun getConfigValue(propertyName: String): ConfigValue = throw UnsupportedOperationException()

    @Suppress("UNCHECKED_CAST")
    override fun <T> getOptionalValue(propertyName: String, propertyType: Class<T>): Optional<T> =
        Optional.ofNullable(map[propertyName]?.takeIf { it.isNotEmpty() }) as Optional<T>

    override fun getPropertyNames(): Iterable<String> = map.keys

    override fun getConfigSources(): Iterable<ConfigSource> = emptyList()

    override fun <T> getConverter(forType: Class<T>): Optional<Converter<T>> = Optional.empty()

    override fun <T> unwrap(type: Class<T>): T = throw UnsupportedOperationException()
}
