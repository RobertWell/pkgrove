package com.pkgrove.pkgrovekit.quarkus

/**
 * Guard against running blocking PkgroveKit operations on a Vert.x event-loop
 * thread (HEL-172). `Relay.execute` performs blocking JDBC I/O; on an event
 * loop it would stall EVERY request multiplexed onto that loop.
 *
 * Detection is a HEURISTIC: the current thread's name is checked for the
 * `vert.x-eventloop` marker Vert.x gives its event-loop threads. A renamed
 * thread will slip through and a deliberately mislabeled worker would
 * false-positive — this is a guard rail, not a proof. Operationally: run
 * PkgroveKit work on worker threads — e.g. a `@Blocking`-annotated endpoint,
 * a scheduler method, or an explicit worker-pool executor.
 */
object BlockingBoundary {

    private const val EVENT_LOOP_MARKER = "vert.x-eventloop"

    /**
     * Throws [IllegalStateException] when called on a Vert.x event-loop
     * thread; returns normally on any other thread. Call it at the top of any
     * code path that is about to invoke `Relay.execute` (or other blocking
     * PkgroveKit operations) from Quarkus request-handling code.
     */
    fun assertBlockingAllowed() {
        val thread = Thread.currentThread().name
        check(EVENT_LOOP_MARKER !in thread) {
            "blocking PkgroveKit operation attempted on a Vert.x event-loop " +
                "thread ('$thread'). Blocking JDBC work here stalls every request " +
                "on this event loop. Move the call to a worker thread: annotate " +
                "the endpoint/consumer with @io.smallrye.common.annotation.Blocking, " +
                "or dispatch to a worker executor. (Detection is heuristic — " +
                "thread-name based — so keep ops on worker threads regardless.)"
        }
    }
}
