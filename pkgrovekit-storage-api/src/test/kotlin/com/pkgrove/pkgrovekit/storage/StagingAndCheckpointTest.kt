package com.pkgrove.pkgrovekit.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * HEL-236 guarantees 2 + scenario 4: staged atomic publish (no atomic rename
 * assumed), deterministic abandoned-staging cleanup, and conditional-write
 * checkpoints where concurrent workers conflict visibly.
 */
class StagingAndCheckpointTest {

    private val store = InMemoryObjectStore()

    @Test
    fun `publish is invisible until the manifest commit then complete`() {
        val staging = StagingArea(store, "data/out", "run-1")
        staging.stage("part-1", ContentSource.of("p1"))
        staging.stage("part-2", ContentSource.of("p2"))
        assertEquals(2, staging.stagedObjects().size)
        // nothing outside staging yet
        assertEquals(0, store.list("data/out/run-1/").count())

        staging.publish(
            plan = mapOf(
                staging.stageKey("part-1") to ObjectKey("data/out/run-1/part-1"),
                staging.stageKey("part-2") to ObjectKey("data/out/run-1/part-2"),
            ),
            manifestKey = ObjectKey("data/out/run-1/manifest.json"),
            manifestBody = ContentSource.of("{}"),
        )
        assertTrue(store.exists(ObjectKey("data/out/run-1/manifest.json")))
        assertTrue(store.exists(ObjectKey("data/out/run-1/part-1")))
        // staging fully cleaned after commit
        assertEquals(0, store.list("data/out/${StagingArea.STAGING_SEGMENT}/").count())
    }

    @Test
    fun `losing the manifest race rolls back this run's copies`() {
        // the winner already published its manifest at the SAME manifest key
        store.put(ObjectKey("data/out/manifest.json"), ContentSource.of("{\"winner\":true}"))

        val staging = StagingArea(store, "data/out", "run-loser")
        staging.stage("part-1", ContentSource.of("loser-part"))
        val e = assertThrows(PreconditionFailedException::class.java) {
            staging.publish(
                plan = mapOf(staging.stageKey("part-1") to ObjectKey("data/out/run-loser/part-1")),
                manifestKey = ObjectKey("data/out/manifest.json"),
                manifestBody = ContentSource.of("{\"winner\":false}"),
            )
        }
        assertFalse(e.retrySafe)
        // the loser's copied final object was rolled back deterministically
        assertFalse(store.exists(ObjectKey("data/out/run-loser/part-1")))
        // the winner's manifest is untouched
        store.get(ObjectKey("data/out/manifest.json")).use {
            assertEquals("{\"winner\":true}", it.stream().readBytes().toString(Charsets.UTF_8))
        }
        // the loser's staging survives for diagnosis, then discard() clears it
        assertEquals(1, staging.stagedObjects().size)
        assertEquals(1L, staging.discard())
    }

    @Test
    fun `staging requires conditional create BEFORE any data movement`() {
        val limited = object : ObjectStore by store {
            override val capabilities = StorageCapabilities(provider = "no-cond", supported = emptySet())
        }
        val e = assertThrows(CapabilityRejectedException::class.java) {
            StagingArea(limited, "data/out", "run-x")
        }
        assertTrue(StorageCapability.CONDITIONAL_CREATE in e.missing)
        // nothing was written anywhere
        assertEquals(0, store.list("").count())
    }

    @Test
    fun `cleanupAbandoned reaps only runs whose newest object is stale`() {
        var now = Instant.parse("2026-08-09T00:00:00Z")
        val clock = object : Clock() {
            override fun instant(): Instant = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
        val timedStore = InMemoryObjectStore(clock)

        val old = StagingArea(timedStore, "data/out", "run-old", clock)
        old.stage("a", ContentSource.of("x"))

        now = now.plus(Duration.ofHours(3))
        val fresh = StagingArea(timedStore, "data/out", "run-fresh", clock)
        fresh.stage("a", ContentSource.of("y"))

        now = now.plus(Duration.ofMinutes(30))
        val reaped = StagingArea.cleanupAbandoned(timedStore, "data/out", Duration.ofHours(2), clock)
        assertEquals(1L, reaped)
        assertEquals(0, old.stagedObjects().size)
        assertEquals(1, fresh.stagedObjects().size)
    }

    @Test
    fun `checkpoints advance by conditional create and conflict typed`() {
        val checkpoints = CheckpointStore(store, "transfers/t1")
        assertNull(checkpoints.latest())

        val c1 = checkpoints.save("""{"offset":100}""", expectedSequence = null)
        assertEquals(1L, c1.sequence)
        val c2 = checkpoints.save("""{"offset":250}""", expectedSequence = c1.sequence)
        assertEquals(2L, c2.sequence)

        val latest = checkpoints.latest()!!
        assertEquals(2L, latest.sequence)
        assertEquals("""{"offset":250}""", latest.data)

        // a concurrent worker still holding sequence 1 must NOT overwrite
        val e = assertThrows(PreconditionFailedException::class.java) {
            checkpoints.save("""{"offset":175}""", expectedSequence = c1.sequence)
        }
        assertFalse(e.retrySafe)
        // committed progress intact
        assertEquals("""{"offset":250}""", checkpoints.latest()!!.data)
    }

    @Test
    fun `two workers racing the same next checkpoint - exactly one wins`() {
        val checkpoints = CheckpointStore(store, "transfers/race")
        val base = checkpoints.save("start", null)
        val workerA = CheckpointStore(store, "transfers/race")
        val workerB = CheckpointStore(store, "transfers/race")
        workerA.save("a-progress", base.sequence)
        assertThrows(PreconditionFailedException::class.java) {
            workerB.save("b-progress", base.sequence)
        }
        assertEquals("a-progress", checkpoints.latest()!!.data)
    }

    @Test
    fun `checkpoint store requires conditional create up front`() {
        val limited = object : ObjectStore by store {
            override val capabilities = StorageCapabilities(provider = "no-cond", supported = emptySet())
        }
        assertThrows(CapabilityRejectedException::class.java) {
            CheckpointStore(limited, "transfers/t2")
        }
    }
}
