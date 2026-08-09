package com.pkgrove.pkgrovekit.jta

import com.pkgrove.pkgrovekit.coordination.ConcurrentScopeAccessException
import com.pkgrove.pkgrovekit.coordination.EnlistedConnectionViolationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HEL-234 (issue §3: enlistment/cleanup branches): the enlisted-connection
 * guard proxy — forbidden local transaction verbs, thread pinning, use-after-
 * scope death, auto-commit protection, hygiene calls, and transparent
 * delegation including exception unwrapping.
 */
class EnlistedConnectionsTest {

    /** Recording fake delegate. */
    private class Fake {
        var autoCommitSet: Boolean? = null
        var thrown = false
        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java),
        ) { _, m, args ->
            when (m.name) {
                "isClosed" -> false
                "setAutoCommit" -> { autoCommitSet = args!![0] as Boolean; null }
                "createStatement" -> { thrown = true; throw SQLException("driver refused") }
                "toString" -> "fake-delegate"
                else -> throw UnsupportedOperationException(m.name)
            }
        } as Connection
    }

    private fun guarded(
        fake: Fake = Fake(),
        owner: Thread = Thread.currentThread(),
        closed: AtomicBoolean = AtomicBoolean(false),
    ): Connection = EnlistedConnections.guard(fake.connection, owner) { closed.get() }

    @Test
    fun `local transaction verbs are forbidden — the TM owns this branch`() {
        val c = guarded()
        for (call in listOf<Pair<String, (Connection) -> Unit>>(
            "commit" to { it.commit() },
            "rollback" to { it.rollback() },
            "close" to { it.close() },
            "abort" to { it.abort(Runnable::run) },
        )) {
            val e = assertThrows<EnlistedConnectionViolationException> { call.second(c) }
            assertTrue(call.first in (e.message ?: ""), "${call.first}: ${e.message}")
        }
    }

    @Test
    fun `auto-commit cannot be re-enabled but disabling stays delegated`() {
        val fake = Fake()
        val c = guarded(fake)
        assertThrows<EnlistedConnectionViolationException> { c.autoCommit = true }
        c.autoCommit = false
        assertEquals(false, fake.autoCommitSet)
    }

    @Test
    fun `safe calls delegate transparently`() {
        assertFalse(guarded().isClosed)
    }

    @Test
    fun `delegate exceptions surface unwrapped — not as InvocationTargetException`() {
        val fake = Fake()
        val e = assertThrows<SQLException> { guarded(fake).createStatement() }
        assertEquals("driver refused", e.message)
        assertTrue(fake.thrown)
    }

    @Test
    fun `a dead scope kills every use of an escaped reference`() {
        val closed = AtomicBoolean(false)
        val c = guarded(closed = closed)
        assertFalse(c.isClosed)              // alive while the scope is open
        closed.set(true)
        val e = assertThrows<EnlistedConnectionViolationException> { c.isClosed }
        assertTrue("scope completed" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `the branch is pinned to its owner thread`() {
        val c = guarded()
        var caught: Throwable? = null
        val t = Thread { caught = runCatching { c.isClosed }.exceptionOrNull() }
        t.name = "intruder"
        t.start(); t.join()
        assertTrue(caught is ConcurrentScopeAccessException, "$caught")
        assertTrue("intruder" in (caught?.message ?: ""), caught?.message ?: "")
    }

    @Test
    fun `hygiene calls stay safe even after the scope dies`() {
        val closed = AtomicBoolean(true)
        val c = guarded(closed = closed)
        assertTrue(c.toString().startsWith("EnlistedConnection("), c.toString())
        assertEquals(System.identityHashCode(c), c.hashCode())
        assertEquals(c, c)
        assertNotEquals(c, guarded(closed = closed))
    }
}
