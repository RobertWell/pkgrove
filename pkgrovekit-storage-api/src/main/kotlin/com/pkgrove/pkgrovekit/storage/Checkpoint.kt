package com.pkgrove.pkgrovekit.storage

import java.time.Clock
import java.time.Instant

/**
 * Resumable transfer checkpoints with conditional writes (HEL-236 scenario 4).
 *
 * Design: checkpoints are an APPEND-ONLY sequence of immutable objects
 * (`<prefix>/checkpoint-<seq>.json`), each created with
 * [WriteCondition.IfAbsent]. Advancing means creating sequence `latest + 1`;
 * when two workers race, exactly one create succeeds and the loser gets a
 * [PreconditionFailedException] — it must reload [latest] and re-plan, so
 * concurrent workers can never silently overwrite each other's progress.
 * Deliberately built on CONDITIONAL_CREATE only (universally the best-supported
 * conditional primitive across S3-compatible providers) rather than
 * etag-If-Match update-in-place.
 *
 * The payload is caller-defined ([Checkpoint.data]); keep it a POSITION
 * (keys, offsets, watermarks) — never row values or credentials.
 */
class CheckpointStore(
    private val store: ObjectStore,
    private val prefix: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(prefix.isNotEmpty() && !prefix.endsWith("/")) {
            "prefix must be non-empty without a trailing '/'"
        }
        // fail BEFORE any transfer starts relying on resumability
        store.capabilities.require("resumable checkpoints", StorageCapability.CONDITIONAL_CREATE)
    }

    data class Checkpoint(
        val sequence: Long,
        val data: String,
        val createdAt: Instant,
        val key: ObjectKey,
    )

    private fun keyFor(sequence: Long): ObjectKey =
        ObjectKey("$prefix/checkpoint-%012d.json".format(sequence))

    /** The newest committed checkpoint, or null when none exists yet. */
    fun latest(): Checkpoint? {
        val newest = store.list("$prefix/checkpoint-").lastOrNull() ?: return null
        return read(newest.key)
    }

    /**
     * Commit the next checkpoint. [expectedSequence] is the fence: pass the
     * sequence you resumed from (or null for "first checkpoint") and the write
     * lands at `expectedSequence + 1` — if another worker got there first this
     * throws [PreconditionFailedException] WITHOUT touching its progress.
     */
    fun save(data: String, expectedSequence: Long?): Checkpoint {
        val sequence = (expectedSequence ?: 0L) + 1
        val createdAt = clock.instant()
        val body = buildString {
            append("{\"sequence\":").append(sequence)
            append(",\"createdAt\":").append(Json.quote(createdAt.toString()))
            append(",\"data\":").append(Json.quote(data))
            append('}')
        }
        val key = keyFor(sequence)
        store.put(
            key, ContentSource.of(body),
            PutOptions(contentType = "application/json", condition = WriteCondition.IfAbsent),
        )
        return Checkpoint(sequence, data, createdAt, key)
    }

    private fun read(key: ObjectKey): Checkpoint {
        val root = store.get(key).use {
            Json.parse(it.stream().readBytes().toString(Charsets.UTF_8))
        } as? Map<*, *> ?: throw IllegalArgumentException("checkpoint '$key' is not a JSON object")
        return Checkpoint(
            sequence = (root["sequence"] as Json.RawNumber).text.toLong(),
            data = root["data"] as String,
            createdAt = Instant.parse(root["createdAt"] as String),
            key = key,
        )
    }
}
