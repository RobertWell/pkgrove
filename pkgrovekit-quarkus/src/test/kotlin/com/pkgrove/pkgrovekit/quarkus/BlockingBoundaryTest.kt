package com.pkgrove.pkgrovekit.quarkus

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.util.concurrent.atomic.AtomicReference

class BlockingBoundaryTest {

    @Test
    fun `passes on an ordinary worker thread`() {
        assertDoesNotThrow { BlockingBoundary.assertBlockingAllowed() }
    }

    @Test
    fun `throws with an actionable message on a vertx event-loop thread`() {
        val caught = AtomicReference<Throwable?>()
        val t = Thread({
            try {
                BlockingBoundary.assertBlockingAllowed()
            } catch (e: Throwable) {
                caught.set(e)
            }
        }, "vert.x-eventloop-thread-3")
        t.start()
        t.join()
        val e = caught.get()
        assertNotNull(e, "expected a failure on the event-loop-named thread")
        assertTrue(e is IllegalStateException, "got ${e!!::class}")
        val msg = e.message ?: ""
        assertTrue("vert.x-eventloop-thread-3" in msg, msg)
        assertTrue("@io.smallrye.common.annotation.Blocking" in msg, msg)
        assertTrue("heuristic" in msg, msg)
    }

    @Test
    fun `heuristic does not misfire on similar worker names`() {
        val caught = AtomicReference<Throwable?>()
        val t = Thread({
            try {
                BlockingBoundary.assertBlockingAllowed()
            } catch (e: Throwable) {
                caught.set(e)
            }
        }, "vert.x-worker-thread-1")
        t.start()
        t.join()
        assertNull(caught.get(), "worker threads must be allowed")
    }
}
