package com.pkgrove.pkgrovekit.spring

import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/**
 * Bridge into a SPRING-owned transaction (HEL-172). PkgroveKit work executed
 * through [joinCurrent] must use
 * [com.pkgrove.pkgrovekit.jdbc.TransactionPolicy.JoinExisting]: Spring owns
 * commit/rollback exclusively, PkgroveKit only appends work — the same
 * caller-owned contract as
 * [com.pkgrove.pkgrovekit.jdbc.TransactionState.PENDING_IN_CALLER_TRANSACTION].
 */
object SpringTransactions {

    /**
     * Run [block] on the connection bound to the ACTIVE Spring-managed
     * transaction for [dataSource] (`@Transactional` / `TransactionTemplate`).
     *
     * - No active transaction → [MissingSpringTransactionException] BEFORE any
     *   pool interaction: handing out an unbound auto-commit connection would
     *   turn "joined" writes into immediately-committed ones.
     * - The transaction must be managed FOR [dataSource] (its
     *   `DataSourceTransactionManager`/`JdbcTransactionManager`); a transaction
     *   on a different `DataSource` yields an unbound auto-commit connection,
     *   which `JoinExisting` then rejects loudly.
     * - The connection is released via [DataSourceUtils.releaseConnection] on
     *   every path; the transaction-bound connection itself stays open for
     *   Spring to commit or roll back — never committed, rolled back, or
     *   closed here.
     */
    fun <T> joinCurrent(dataSource: DataSource, block: (Connection) -> T): T {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw MissingSpringTransactionException(
                "no active Spring-managed transaction: SpringTransactions.joinCurrent requires " +
                    "@Transactional (or TransactionTemplate) around the call — PkgroveKit never " +
                    "commits or rolls back on Spring's behalf")
        }
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            return block(connection)
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }
}

/** [SpringTransactions.joinCurrent] was called outside an active Spring
 *  transaction — raised BEFORE any connection is fetched from the pool. */
class MissingSpringTransactionException(message: String) : IllegalStateException(message)
