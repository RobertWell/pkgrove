package com.pkgrove.pkgrovekit.it

/** Tiny helper for the Java example: Kotlin Sequence -> java Iterable.
 *  (Also a design note for the API review: Java callers consuming batch
 *  sequences need this bridge — candidate for a first-class Java-friendly
 *  batches API before 1.0.) */
object SequenceBridge {
    @JvmStatic
    fun <T> toIterable(seq: Sequence<T>): Iterable<T> = Iterable { seq.iterator() }
}
