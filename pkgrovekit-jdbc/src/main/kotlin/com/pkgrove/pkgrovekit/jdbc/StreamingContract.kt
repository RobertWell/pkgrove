package com.pkgrove.pkgrovekit.jdbc

import java.sql.Connection

/**
 * Who may reconfigure the connection a read runs on (HEL-256), expressed
 * inside the HEL-128 ownership model.
 *
 * HEL-128 separates application-owned pools, PkgroveKit-managed resources, and
 * caller-owned connections/transactions. For a READ the only distinction that
 * changes behavior is narrower than that: may PkgroveKit adjust this
 * connection's session state for the lifetime of the stream, or not?
 */
enum class ConnectionOwnership {

    /**
     * PkgroveKit controls this connection for the read's scope — a
     * [Databases.withConnection] lease, or a connection handed to a PkgroveKit
     * entry point that is not participating in a caller transaction. Session
     * state needed for streaming is taken over and restored before the scope
     * ends: exactly the contract [JdbcBatchWriter] has always had on the write
     * side ("takes over the connection's autoCommit for the duration and
     * restores it after").
     *
     * The take-over is safe by construction because it happens ONLY when
     * autoCommit is already true — which means no caller transaction is in
     * flight, so there is nothing of the caller's to damage. See
     * [JdbcReader.open].
     */
    LEASED,

    /**
     * The caller owns this connection AND its transaction: JTA-enlisted,
     * Spring-bound, or [TransactionPolicy.JoinExisting]. PkgroveKit reads its
     * settings and never writes them. If the driver then cannot stream, the
     * read REFUSES at open rather than silently buffering the whole result set
     * — a loud, fixable error beats an invisible unbounded allocation.
     *
     * Note this is usually a non-event: a connection that really is inside a
     * caller transaction already has autoCommit off, which is precisely what
     * streaming needs. The refusal fires only for the contradictory case —
     * declared caller-owned, yet in autoCommit, on a driver that needs it off.
     */
    CALLER_OWNED,

    /**
     * PkgroveKit's to use, but it MUST NOT be reconfigured for streaming
     * because another part of the SAME operation depends on its transaction
     * state — specifically a transfer whose target writer holds this same
     * physical connection. A server-side cursor lives inside the read
     * transaction, so that writer's commit would close the cursor mid-stream.
     *
     * Neither taking over (breaks at the first commit) nor refusing (the
     * caller asked for a legitimate same-connection transfer) is right here,
     * so the read runs in whatever mode the driver offers and REPORTS via a
     * [com.pkgrove.pkgrovekit.core.DataWarning] that memory was not bounded.
     * Nothing lossy is silent.
     */
    SHARED_WITH_WRITER,
}

/**
 * Raised at open when the source driver would silently buffer the entire
 * result set and PkgroveKit is not permitted to reconfigure the connection
 * itself (see [ConnectionOwnership.CALLER_OWNED]).
 */
class StreamingUnavailableException(message: String) : IllegalStateException(message)

/**
 * What a driver requires before it will actually STREAM a result set instead
 * of materializing it client-side (HEL-256).
 *
 * `Statement.fetchSize` is only a HINT. Several drivers ignore it unless other
 * preconditions hold, and they ignore it SILENTLY — the symptom is heap
 * proportional to the result set, never an error. This type is the single
 * place those per-dialect requirements are written down, so the read path can
 * ENFORCE them instead of assuming them.
 *
 * Per-dialect requirements, each verified against the driver's own
 * documentation rather than inferred:
 *
 *  - **PostgreSQL** ([POSTGRES]) — pgjdbc opens a server-side cursor only when
 *    the connection is not in autocommit, the fetch size is > 0, and the
 *    statement is `TYPE_FORWARD_ONLY` (the JDBC default, and what
 *    `prepareStatement` gives us). The backend closes cursors at end of
 *    transaction, so under autocommit the cursor would be dead before the
 *    first fetch — the driver buffers everything instead.
 *  - **Oracle** ([HONOURS_FETCH_SIZE]) — ojdbc applies `fetchSize` directly,
 *    overriding its default row-prefetch (10). No transaction precondition.
 *  - **MySQL / MariaDB** ([MYSQL]) — Connector/J buffers by default;
 *    row-by-row streaming is requested with the sentinel fetch size
 *    `Integer.MIN_VALUE` on a `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY`
 *    statement (both JDBC defaults here). A positive fetch size also streams
 *    IF the connection was built with `useCursorFetch=true` — but that is a
 *    CONNECTION property, fixed before PkgroveKit sees the connection, so the
 *    sentinel is the only lever the read path actually has.
 *  - **DuckDB** ([NOT_APPLICABLE]) — in-process; there is no client/server
 *    boundary to buffer across and no transaction precondition.
 */
data class StreamingContract(
    /** The driver ignores `fetchSize` while the connection is in autocommit. */
    val requiresAutoCommitOff: Boolean = false,
    /**
     * A fetch size that means "stream" to this driver, overriding the caller's
     * (MySQL's `Integer.MIN_VALUE` sentinel). Null = the caller's value is
     * used as-is. Applied to a statement PkgroveKit created and owns, so it
     * mutates nothing the caller can observe.
     */
    val streamingFetchSize: Int? = null,
    /** Why, quoted verbatim into refusals and warnings so a reader never has
     *  to go looking for the driver note that explains the behavior. */
    val reason: String = "",
) {

    /** True when this driver needs nothing beyond the fetch size we already set. */
    val streamsWithFetchSizeAlone: Boolean
        get() = !requiresAutoCommitOff && streamingFetchSize == null

    companion object {

        /** The driver applies `Statement.fetchSize` as-is (Oracle). Also the
         *  conservative fallback for a driver we do not recognize: PkgroveKit
         *  enforces only requirements it has actually verified, and never
         *  invents one. */
        @JvmField
        val HONOURS_FETCH_SIZE = StreamingContract(
            reason = "the driver applies Statement.fetchSize directly")

        /** In-process engine — nothing to stream across (DuckDB). */
        @JvmField
        val NOT_APPLICABLE = StreamingContract(
            reason = "in-process engine; no client/server boundary to buffer across")

        @JvmField
        val POSTGRES = StreamingContract(
            requiresAutoCommitOff = true,
            reason = "pgjdbc opens a server-side cursor only when the connection is NOT " +
                     "in autocommit (the backend closes cursors at end of transaction); " +
                     "in autocommit it ignores fetchSize and materializes the entire " +
                     "result set client-side")

        @JvmField
        val MYSQL = StreamingContract(
            streamingFetchSize = Integer.MIN_VALUE,
            reason = "MySQL Connector/J buffers the whole result set unless the fetch " +
                     "size is the Integer.MIN_VALUE streaming sentinel")

        /**
         * The contract for [connection]'s ACTUAL driver.
         *
         * A [dialect] that declares one wins. Otherwise the driver is asked
         * directly via [java.sql.DatabaseMetaData.getDatabaseProductName] —
         * deliberately not a caller-supplied guess, because the read path
         * frequently has no source dialect at all ([com.pkgrove.pkgrovekit.transfer.Transfer]
         * knows only the TARGET dialect, and [JdbcReader] is a standalone
         * entry point). Product name is the right key precisely because the
         * buffering behavior belongs to the DRIVER, not the server: anything
         * reached through pgjdbc reports "PostgreSQL" and buffers like pgjdbc,
         * wire-compatible engines included.
         *
         * A driver that cannot answer falls back to [HONOURS_FETCH_SIZE] —
         * unchanged pre-HEL-256 behavior, never a refusal on a guess.
         */
        @JvmStatic
        @JvmOverloads
        fun of(connection: Connection, dialect: SqlDialect? = null): StreamingContract {
            dialect?.let { return it.streaming }
            val product = runCatching { connection.metaData.databaseProductName }.getOrNull()
            return forProductName(product.orEmpty())
        }

        /** Driver product name → contract; the detection table behind [of]. */
        @JvmStatic
        fun forProductName(product: String): StreamingContract {
            val p = product.lowercase()
            return when {
                p.contains("postgresql") -> POSTGRES
                p.contains("mysql") || p.contains("mariadb") -> MYSQL
                p.contains("duckdb") -> NOT_APPLICABLE
                else -> HONOURS_FETCH_SIZE
            }
        }
    }
}
