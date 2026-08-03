package com.pkgrove.pkgrovekit.jta

import jakarta.transaction.HeuristicMixedException
import jakarta.transaction.HeuristicRollbackException
import jakarta.transaction.RollbackException
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.SystemException
import jakarta.transaction.Transaction
import jakarta.transaction.TransactionManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.XAConnection
import javax.transaction.xa.XAResource
import javax.transaction.xa.Xid

/** Scriptable Jakarta TM double: records calls, throws what the test tells it to. */
internal class FakeTransactionManager : TransactionManager {
    val calls = mutableListOf<String>()
    var commitError: Exception? = null
    var statusAfterCommitFailure: Int = Status.STATUS_NO_TRANSACTION
    var timeoutSeconds: Int = -1

    private var current: FakeTransaction? = null

    override fun begin() {
        calls.add("begin")
        current = FakeTransaction(this)
    }

    override fun commit() {
        calls.add("commit")
        commitError?.let {
            current = null
            when (it) {
                is RollbackException, is HeuristicMixedException,
                is HeuristicRollbackException, is SystemException,
                -> throw it
                else -> throw it
            }
        }
        current = null
    }

    override fun rollback() {
        calls.add("rollback")
        current = null
    }

    override fun getStatus(): Int =
        if (calls.contains("commit") && commitError != null) statusAfterCommitFailure
        else if (current != null) Status.STATUS_ACTIVE
        else Status.STATUS_NO_TRANSACTION

    override fun getTransaction(): Transaction =
        current ?: throw IllegalStateException("no transaction")

    override fun setRollbackOnly() { calls.add("setRollbackOnly") }
    override fun setTransactionTimeout(seconds: Int) { timeoutSeconds = seconds; calls.add("timeout:$seconds") }
    override fun suspend(): Transaction = throw UnsupportedOperationException()
    override fun resume(tobj: Transaction?) = throw UnsupportedOperationException()
}

internal class FakeTransaction(private val tm: FakeTransactionManager) : Transaction {
    val enlisted = mutableListOf<XAResource>()
    val delisted = mutableListOf<Pair<XAResource, Int>>()

    override fun enlistResource(xaRes: XAResource): Boolean {
        tm.calls.add("enlist")
        enlisted.add(xaRes)
        return true
    }

    override fun delistResource(xaRes: XAResource, flag: Int): Boolean {
        tm.calls.add("delist:$flag")
        delisted.add(xaRes to flag)
        return true
    }

    override fun commit() = throw UnsupportedOperationException("commit goes through the TM")
    override fun rollback() = throw UnsupportedOperationException("rollback goes through the TM")
    override fun getStatus(): Int = Status.STATUS_ACTIVE
    override fun registerSynchronization(sync: Synchronization?) = Unit
    override fun setRollbackOnly() = Unit
    override fun toString(): String = "fake-tx"
}

/** Recording java.sql.Connection double built on a dynamic proxy. */
internal class RecordingConnection {
    val invocations = mutableListOf<String>()

    val connection: Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
        InvocationHandler { _, method: Method, args ->
            invocations.add(method.name + (args?.joinToString(",", "(", ")") { "$it" } ?: "()"))
            when (method.returnType) {
                Void.TYPE -> null
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.String::class.java -> null
                else -> null
            }
        },
    ) as Connection
}

internal class FakeXAConnection : XAConnection {
    val physical = RecordingConnection()
    var closed = false
    val xaResource: FakeXAResource = FakeXAResource()

    override fun getXAResource(): XAResource = xaResource
    override fun getConnection(): Connection = physical.connection
    override fun close() { closed = true }
    override fun addConnectionEventListener(listener: javax.sql.ConnectionEventListener?) = Unit
    override fun removeConnectionEventListener(listener: javax.sql.ConnectionEventListener?) = Unit
    override fun addStatementEventListener(listener: javax.sql.StatementEventListener?) = Unit
    override fun removeStatementEventListener(listener: javax.sql.StatementEventListener?) = Unit
}

internal class FakeXAResource : XAResource {
    override fun commit(xid: Xid?, onePhase: Boolean) = Unit
    override fun end(xid: Xid?, flags: Int) = Unit
    override fun forget(xid: Xid?) = Unit
    override fun getTransactionTimeout(): Int = 0
    override fun isSameRM(xares: XAResource?): Boolean = xares === this
    override fun prepare(xid: Xid?): Int = XAResource.XA_OK
    override fun recover(flag: Int): Array<Xid> = emptyArray()
    override fun rollback(xid: Xid?) = Unit
    override fun setTransactionTimeout(seconds: Int): Boolean = true
    override fun start(xid: Xid?, flags: Int) = Unit
}

internal class FakeXADataSource : javax.sql.XADataSource {
    val handedOut = mutableListOf<FakeXAConnection>()

    override fun getXAConnection(): XAConnection =
        FakeXAConnection().also { handedOut.add(it) }

    override fun getXAConnection(user: String?, password: String?): XAConnection = getXAConnection()
    override fun getLogWriter(): java.io.PrintWriter? = null
    override fun setLogWriter(out: java.io.PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): java.util.logging.Logger = throw java.sql.SQLFeatureNotSupportedException()
}
