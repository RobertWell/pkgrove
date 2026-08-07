package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema

/**
 * HEL-228: dataset-level processing that a pure row map cannot express, WITHOUT
 * silently abandoning the bounded-streaming contract.
 *
 * The design rule is that stateful work must never be disguised as an ordinary
 * `transform`. Each category below states, in its own name and signature, how
 * much memory it can hold:
 *
 *  1. [Transfer.Options.rowTransform] — row mapping, fully streaming (existing).
 *  2. [BatchProcessor]  — one bounded batch at a time; memory <= batch limit.
 *  3. [ConsecutiveGrouper] — consecutive rows sharing an ordered key form one
 *     group; memory <= the largest group plus a bounded ordering-guard window,
 *     both of which the caller bounds explicitly.
 *
 * Categories 4 (partitioned keyed aggregation with spill) and 5 (materialized
 * stages) are deliberately NOT implemented here: they change the streaming
 * guarantee and need the spill/checkpoint machinery the issue sequences after
 * these two. Nothing in this file buffers a whole dataset, and every bound is
 * a constructor argument rather than a hidden default.
 */

/** What a stateful step may emit for one unit of input. */
class ProcessOutput internal constructor(val batches: List<RowBatch>) {
    companion object {
        /** Emit nothing now (state retained for a later unit). */
        @JvmStatic fun none(): ProcessOutput = ProcessOutput(emptyList())
        /** Emit exactly one batch. */
        @JvmStatic fun of(batch: RowBatch): ProcessOutput = ProcessOutput(listOf(batch))
        /** Emit rows as one batch under [schema] (empty -> nothing). */
        @JvmStatic fun rows(schema: Schema, rows: List<Row>): ProcessOutput =
            if (rows.isEmpty()) none() else of(RowBatch(schema, rows))
    }
}

/**
 * Category 2 — bounded batch processing. [accept] sees ONE batch of at most
 * [maxRows] rows and returns what to emit; [finish] flushes anything held.
 * Implementations may keep state ACROSS batches only within their declared
 * budget: the pipeline enforces [maxRows] on the input side, and an
 * implementation that hoards rows is the implementation's own bug, so
 * [BoundedRowBuffer] is provided to make the bounded choice the easy one.
 */
interface BatchProcessor {
    val maxRows: Int
    /**
     * The schema this processor EMITS, when it differs from the input schema.
     * A grouping/aggregating step reshapes rows, so the target table and INSERT
     * must be built from THIS, not from the source schema — otherwise the table
     * is created with the source's columns and the write fails on a column the
     * processor never emits. null means "unchanged" (pass-through shape).
     */
    val outputSchema: Schema? get() = null
    fun accept(batch: RowBatch): ProcessOutput
    /** Called once after the last batch (also after cancellation-free EOF). */
    fun finish(): ProcessOutput = ProcessOutput.none()
    /** Always called — success, failure, or cancellation — for cleanup. */
    fun close() {}
}

/**
 * Category 3 — ordered grouping. Rows arriving in key order are gathered per
 * key and handed to [summarize] when the key changes; the open group's rows are
 * the dominant state, and [maxGroupRows] makes that bound explicit and
 * enforceable instead of an unstated assumption.
 *
 * REQUIRES the source to be ordered by [keyColumns] (add ORDER BY to the source
 * SQL). Out-of-order input is a caller error and is reported as one rather than
 * silently merged — but the guard that detects it costs memory, so its reach is
 * bounded and stated here exactly rather than overclaimed.
 *
 * THE ORDERING GUARANTEE, EXACTLY (HEL-255): the last [recentKeyMemory] closed
 * keys are retained. A key that reappears while it is still inside that window
 * ALWAYS throws [OutOfOrderGroupException]. A key that reappears after more
 * than [recentKeyMemory] other groups have closed is NOT detected: its rows are
 * summarized as a second, separate group for the same key, and the caller gets
 * two partial aggregates with no error. The window catches the interleaving a
 * missing ORDER BY actually produces; catching an arbitrarily distant
 * reappearance would require retaining every key ever seen, which is memory
 * proportional to the dataset. (The original implementation did exactly that —
 * ~113 bytes per key, 108 MB at 1M groups — while [largestGroupRows] went on
 * reporting the per-group bound as healthy.)
 *
 * Total retained state is therefore <= [maxGroupRows] rows + [recentKeyMemory]
 * keys, both caller-set, and is INDEPENDENT of the number of groups in the
 * dataset. [bufferedRows] and [retainedKeys] report the live size of each half,
 * so the instrumentation cannot show a bound holding while memory grows.
 */
class ConsecutiveGrouper(
    private val keyColumns: List<String>,
    private val maxGroupRows: Int,
    private val declaredOutput: Schema,
    /** Ordering-guard window: how many recently closed keys stay detectable.
     *  See the guarantee above — this is the guard's reach AND its memory. */
    private val recentKeyMemory: Int = DEFAULT_RECENT_KEY_MEMORY,
    private val summarize: (key: List<Any?>, rows: List<Row>) -> List<Row>,
) : BatchProcessor {

    companion object {
        /**
         * Default ordering-guard window. 10k keys is roughly 1 MB at the ~113
         * bytes/key measured in HEL-255 — small enough to be invisible next to
         * a JDBC fetch buffer, wide enough that any realistic interleaving from
         * a missing/partial ORDER BY still lands inside it.
         */
        const val DEFAULT_RECENT_KEY_MEMORY: Int = 10_000
    }

    init {
        require(keyColumns.isNotEmpty()) { "groupConsecutiveBy needs at least one key column" }
        require(maxGroupRows > 0) { "maxGroupRows must be positive (it is the memory bound)" }
        require(recentKeyMemory > 0) {
            "recentKeyMemory must be positive — it is both the ordering guard's reach and its " +
            "memory bound; a zero window would silently accept unordered input"
        }
    }

    // The INPUT-batch limit and the STATE bound are different things: a grouper
    // streams rows out of whatever batch it is given into per-key state, so it
    // accepts any batch size. Its memory bound is maxGroupRows + recentKeyMemory,
    // enforced below. (Conflating the two rejected perfectly valid transfers.)
    override val maxRows: Int get() = Int.MAX_VALUE

    override val outputSchema: Schema get() = declaredOutput

    private var currentKey: List<Any?>? = null
    private val buffer = ArrayList<Row>()

    // The ordering guard's whole state. LinkedHashMap with removeEldestEntry is
    // the JDK's own bounded-map primitive — a LinkedHashSet cannot evict, and a
    // hand-rolled ring buffer + HashSet would just re-implement this. Insertion
    // order (not access order) is deliberate: on correctly ordered input a
    // retained key is never looked up again, so "oldest closed group" is the
    // only sensible eviction victim, and FIFO keeps eviction O(1) and
    // predictable. Values are unused — this is a set that forgets.
    private val recentKeys = object : LinkedHashMap<List<Any?>, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, Unit>): Boolean =
            size > recentKeyMemory
    }

    private var emitted = 0L
    private var groupsSeen = 0L
    private var largestGroup = 0

    /** Rows emitted so far — for the caller's own metrics/logging. */
    val emittedRows: Long get() = emitted
    /** Groups completed so far. NOT a state size — completed groups are emitted
     *  and released; see [bufferedRows]/[retainedKeys] for what is still held. */
    val groups: Long get() = groupsSeen
    /**
     * High-water mark of the OPEN-group buffer — compare against [maxGroupRows]
     * to see headroom. This is a per-group figure only: it says nothing about
     * total retention, which is [bufferedRows] + [retainedKeys] (HEL-255 —
     * this metric once read "1" while 108 MB of keys was retained).
     */
    val largestGroupRows: Int get() = largestGroup
    /** Rows retained RIGHT NOW for the open group (<= [maxGroupRows]). */
    val bufferedRows: Int get() = buffer.size
    /** Closed keys retained RIGHT NOW by the ordering guard (<= [recentKeyMemory]).
     *  Together with [bufferedRows] this is the grouper's entire live state. */
    val retainedKeys: Int get() = recentKeys.size
    /** The configured ordering-guard window — the bound [retainedKeys] respects. */
    val recentKeyWindow: Int get() = recentKeyMemory

    override fun accept(batch: RowBatch): ProcessOutput {
        val out = ArrayList<Row>()
        for (row in batch.rows) {
            val key = keyColumns.map { row[it] }
            if (currentKey == null) {
                currentKey = key
            } else if (key != currentKey) {
                out += closeGroup()   // this is what registers currentKey as closed
                if (recentKeys.containsKey(key)) {
                    throw OutOfOrderGroupException(
                        "key $key reappeared within the last $recentKeyMemory closed groups — " +
                        "the source is not ordered by ${keyColumns.joinToString(", ")}; add an " +
                        "ORDER BY (groupConsecutiveBy will not silently merge split groups)")
                }
                currentKey = key
            }
            if (buffer.size >= maxGroupRows) {
                throw GroupTooLargeException(
                    "group $currentKey exceeded maxGroupRows=$maxGroupRows — raise the bound " +
                    "deliberately or use a partitioned aggregation; this limit exists so a " +
                    "skewed key cannot silently consume the heap")
            }
            buffer += row
        }
        return ProcessOutput.rows(declaredOutput, out)
    }

    override fun finish(): ProcessOutput =
        if (currentKey == null) ProcessOutput.none()
        else ProcessOutput.rows(declaredOutput, closeGroup())

    private fun closeGroup(): List<Row> {
        val key = currentKey ?: return emptyList()
        largestGroup = maxOf(largestGroup, buffer.size)
        val rows = summarize(key, buffer.toList())
        rows.forEach {
            require(it.schema == declaredOutput) {
                "summarize returned a row whose schema is not the declared output schema"
            }
        }
        buffer.clear()
        recentKeys[key] = Unit   // evicts the oldest once the window is full
        groupsSeen++
        emitted += rows.size
        return rows
    }

    override fun close() {
        buffer.clear()
        recentKeys.clear()
    }
}

/** The source was not ordered by the grouping key — a caller error, never silent. */
class OutOfOrderGroupException(message: String) : IllegalStateException(message)

/** A single group exceeded its declared row bound. */
class GroupTooLargeException(message: String) : IllegalStateException(message)

/**
 * A List<Row> that refuses to exceed its budget. Stateful processors should use
 * this instead of a bare ArrayList so "bounded" is enforced rather than intended.
 */
class BoundedRowBuffer(private val maxRows: Int) {
    private val rows = ArrayList<Row>()
    init { require(maxRows > 0) { "maxRows must be positive" } }

    val size: Int get() = rows.size
    fun isEmpty(): Boolean = rows.isEmpty()
    fun snapshot(): List<Row> = rows.toList()
    fun clear() = rows.clear()

    fun add(row: Row) {
        if (rows.size >= maxRows) {
            throw GroupTooLargeException(
                "buffer exceeded maxRows=$maxRows — bounded by construction, so this is a " +
                "budget decision to make explicitly, not a heap to grow silently")
        }
        rows += row
    }
}

/**
 * Drive [processor] over [batches], preserving lazy streaming: nothing is
 * materialized beyond what the processor itself holds, and cancellation is
 * checked between batches. [close] always runs — success, failure, or cancel.
 */
internal fun runProcessor(
    processor: BatchProcessor,
    batches: Sequence<RowBatch>,
    cancelToken: CancelToken = CancelToken.none(),
): Sequence<RowBatch> = sequence {
    try {
        for (batch in batches) {
            cancelToken.throwIfCancelled()
            require(batch.size <= processor.maxRows) {
                "batch of ${batch.size} rows exceeds the processor's declared bound " +
                "${processor.maxRows} — lower Transfer.Options.readBatchSize or raise the bound"
            }
            yieldAll(processor.accept(batch).batches)
        }
        cancelToken.throwIfCancelled()
        yieldAll(processor.finish().batches)
    } finally {
        processor.close()
    }
}
