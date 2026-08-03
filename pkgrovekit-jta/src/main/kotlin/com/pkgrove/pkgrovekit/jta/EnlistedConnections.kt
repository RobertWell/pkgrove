package com.pkgrove.pkgrovekit.jta

import com.pkgrove.pkgrovekit.coordination.ConcurrentScopeAccessException
import com.pkgrove.pkgrovekit.coordination.EnlistedConnectionViolationException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection

/**
 * Guard proxy over an ENLISTED connection (HEL-170 proof #6): inside a
 * coordinator-owned global transaction PkgroveKit must never locally commit,
 * roll back or close — those verbs belong to the transaction manager. The
 * proxy also pins the connection to the scope's owner thread (proof #5) and
 * dies when the scope ends, so an escaped reference cannot touch a branch
 * after the transaction completed.
 */
internal object EnlistedConnections {

    private val FORBIDDEN = setOf("commit", "rollback", "close", "abort")

    fun guard(delegate: Connection, ownerThread: Thread, scopeClosed: () -> Boolean): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
            Handler(delegate, ownerThread, scopeClosed),
        ) as Connection

    private class Handler(
        private val delegate: Connection,
        private val ownerThread: Thread,
        private val scopeClosed: () -> Boolean,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                // identity/hygiene calls are always safe
                "toString" -> return "EnlistedConnection(${delegate})"
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.get(0)
            }
            if (scopeClosed()) {
                throw EnlistedConnectionViolationException(
                    "enlisted connection used after its global-transaction scope completed",
                )
            }
            if (Thread.currentThread() !== ownerThread) {
                throw ConcurrentScopeAccessException(
                    "enlisted connection touched from thread '${Thread.currentThread().name}' " +
                        "but its branch belongs to '${ownerThread.name}' — one thread per branch",
                )
            }
            if (method.name in FORBIDDEN) {
                throw EnlistedConnectionViolationException(
                    "'${method.name}' is forbidden on an enlisted connection — the transaction " +
                        "manager owns commit/rollback/close for this branch (use JoinExisting semantics)",
                )
            }
            if (method.name == "setAutoCommit" && args?.get(0) == true) {
                throw EnlistedConnectionViolationException(
                    "auto-commit cannot be re-enabled on an enlisted connection",
                )
            }
            return try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }
}
