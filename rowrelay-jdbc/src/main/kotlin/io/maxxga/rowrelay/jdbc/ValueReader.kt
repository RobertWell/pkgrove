package io.maxxga.rowrelay.jdbc

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.DataWarning
import java.math.BigDecimal
import java.sql.Blob
import java.sql.Clob
import java.sql.ResultSet

/**
 * Normalizes driver values into the JDK types the common model allows.
 * This is the dialect extension point: adapters (Oracle, ...) extend
 * [Default] to handle vendor classes, and everything downstream stays
 * driver-free (HEL-120 capability 1's "no driver objects leak" rule).
 */
interface ValueReader {
    /**
     * Read column [index] (1-based) of the current row. Implementations must
     * return only: null, String, Boolean, ByteArray, java.math/lang numbers,
     * or java.time temporals — and report anything lossy via [warn].
     */
    fun read(rs: ResultSet, index: Int, column: Column, warn: (DataWarning) -> Unit): Any?

    /** Standard-JDBC normalization: LOBs materialized, java.sql.* → java.time. */
    open class Default : ValueReader {
        override fun read(rs: ResultSet, index: Int, column: Column,
                          warn: (DataWarning) -> Unit): Any? {
            val v = rs.getObject(index) ?: return null
            return normalize(v, column, warn)
        }

        protected open fun normalize(v: Any, column: Column,
                                     warn: (DataWarning) -> Unit): Any? = when (v) {
            is Clob -> readClob(v)
            is Blob -> v.getBytes(1, v.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            is java.sql.Timestamp -> v.toLocalDateTime()
            is java.sql.Date -> v.toLocalDate()
            is java.sql.Time -> v.toLocalTime()
            is BigDecimal, is String, is Boolean, is ByteArray,
            is Int, is Long, is Short, is Byte, is Float, is Double,
            is java.time.temporal.Temporal -> v
            is java.util.UUID -> v.toString()
            else -> {
                // Unknown driver type: never let it leak. Stringify + warn so
                // the caller can choose a policy, but the contract stays clean.
                warn(DataWarning("unrepresentable-type",
                                 "value of ${v.javaClass.name} carried as string",
                                 column.name))
                v.toString()
            }
        }

        protected fun readClob(clob: Clob): String {
            val len = clob.length()
            if (len == 0L) return ""
            return clob.getSubString(1, len.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    companion object {
        @JvmField val DEFAULT: ValueReader = Default()
    }
}
