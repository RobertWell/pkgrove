package com.pkgrove.pkgrovekit.quarkus.it

import jakarta.enterprise.inject.Instance
import jakarta.enterprise.util.TypeLiteral

/**
 * A hand-built `Instance<T>` for the boot-gate test (HEL-602).
 *
 * The startup bean takes `Instance<OperationModule>` — the real one, which this
 * container resolves. To prove the gate REJECTS a bad catalogue, the test needs
 * to hand the same bean a module set the container does not have, which means
 * one small `Instance` of its own. It implements the real CDI interface, so the
 * bean is exercised through its actual signature.
 */
internal class SyntheticInstance<T>(private val items: List<T>) : Instance<T> {

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
        SyntheticInstance(items.filter { subtype.isInstance(it) } as List<U>)

    override fun <U : T> select(subtype: TypeLiteral<U>, vararg qualifiers: Annotation): Instance<U> =
        throw UnsupportedOperationException()

    override fun isUnsatisfied(): Boolean = items.isEmpty()

    override fun isAmbiguous(): Boolean = items.size > 1

    override fun destroy(instance: T) = Unit

    override fun getHandle(): Instance.Handle<T> = throw UnsupportedOperationException()

    override fun handles(): Iterable<Instance.Handle<T>> = throw UnsupportedOperationException()
}
