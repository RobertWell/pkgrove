package com.pkgrove.pkgrovekit.storage

import java.time.Clock
import java.time.Duration

/**
 * Staged, atomically-published writes (HEL-236 guarantee 2). Object storage
 * has NO atomic rename, so atomicity is constructed from the primitives it
 * does have:
 *
 *  1. every artifact is first written under a run-scoped STAGING prefix
 *     (`<prefix>/.staging/<runId>/…`) that readers never look at;
 *  2. staged objects are server-side COPIED to their run-scoped final keys —
 *     still invisible, because readers only trust manifests;
 *  3. the manifest is PUT with [WriteCondition.IfAbsent] — this single
 *     conditional create IS the commit point: before it, the dataset does not
 *     exist; after it, it is complete (the manifest names every part);
 *  4. staging is deleted.
 *
 * A crash anywhere before step 3 leaves only invisible garbage, never a
 * half-published dataset. Losing the step-3 race ([PreconditionFailedException])
 * rolls back this run's copies deterministically. Abandoned staging has a
 * DETERMINISTIC ownership/cleanup rule: a run owns `.staging/<runId>/`, and
 * [cleanupAbandoned] deletes any run whose newest object is older than the
 * caller's threshold (a live writer keeps its staging young by writing).
 *
 * Requires [StorageCapability.CONDITIONAL_CREATE]; the constructor rejects a
 * provider without it BEFORE any data movement.
 */
class StagingArea(
    private val store: ObjectStore,
    val finalPrefix: String,
    val runId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(finalPrefix.isNotEmpty() && !finalPrefix.endsWith("/")) {
            "finalPrefix must be non-empty without a trailing '/'"
        }
        require(runId.isNotEmpty() && runId.none { it == '/' }) { "runId must be a single path segment" }
        // fail BEFORE data movement when the commit primitive is missing
        store.capabilities.require("staged atomic publish", StorageCapability.CONDITIONAL_CREATE)
    }

    val stagingPrefix: String = "$finalPrefix/$STAGING_SEGMENT/$runId"

    /** The staging key for a run-relative artifact name. */
    fun stageKey(name: String): ObjectKey = ObjectKey("$stagingPrefix/$name")

    /** Write one artifact into staging (delegates to [ObjectStore.put]). */
    fun stage(name: String, source: ContentSource, options: PutOptions = PutOptions()): PutResult =
        store.put(stageKey(name), source, options)

    /** Everything currently staged for THIS run. */
    fun stagedObjects(): List<ObjectSummary> = store.list("$stagingPrefix/").toList()

    /**
     * Commit: copy each staged object to its final key per [plan], then create
     * [manifestKey] conditionally, then drop staging. On a lost commit race the
     * already-copied final objects of THIS run are deleted and the
     * [PreconditionFailedException] is rethrown — the winner's dataset is
     * untouched. [plan] final keys MUST be run-unique (e.g. carry [runId]);
     * that is what makes step 2 collision-free without conditional copies.
     */
    fun publish(
        plan: Map<ObjectKey, ObjectKey>,
        manifestKey: ObjectKey,
        manifestBody: ContentSource,
        manifestOptions: PutOptions = PutOptions(contentType = "application/json"),
    ): PutResult {
        val copied = mutableListOf<ObjectKey>()
        try {
            plan.forEach { (staged, final) ->
                store.copy(staged, final)
                copied += final
            }
            val manifest = store.put(
                manifestKey, manifestBody,
                manifestOptions.copy(condition = WriteCondition.IfAbsent),
            )
            discard()
            return manifest
        } catch (e: Exception) {
            // deterministic rollback of THIS run's visible traces; staged
            // objects stay for diagnosis and fall under cleanupAbandoned
            copied.forEach { runCatching { store.delete(it) } }
            throw e
        }
    }

    /** Drop every staged object of this run. Returns deleted count. */
    fun discard(): Long = store.deletePrefix("$stagingPrefix/")

    companion object {
        const val STAGING_SEGMENT: String = ".staging"

        /**
         * Delete abandoned staging runs under [finalPrefix]: any
         * `.staging/<runId>/` whose NEWEST object is older than [olderThan].
         * Returns the number of objects deleted. Safe to run concurrently with
         * live writers as long as their threshold exceeds the writer's maximum
         * quiet period — the rule is deterministic, pick it deliberately.
         */
        @JvmStatic
        fun cleanupAbandoned(
            store: ObjectStore,
            finalPrefix: String,
            olderThan: Duration,
            clock: Clock = Clock.systemUTC(),
        ): Long {
            val root = "${finalPrefix.trimEnd('/')}/$STAGING_SEGMENT/"
            val cutoff = clock.instant().minus(olderThan)
            val byRun = store.list(root).groupBy { it.key.value.removePrefix(root).substringBefore('/') }
            var deleted = 0L
            byRun.forEach { (runId, objects) ->
                val newest = objects.mapNotNull { it.lastModified }.maxOrNull()
                // objects with unknown mtime are treated as live (never reaped)
                if (newest != null && objects.all { it.lastModified != null } && newest.isBefore(cutoff)) {
                    deleted += store.deletePrefix("$root$runId/")
                }
            }
            return deleted
        }
    }
}
