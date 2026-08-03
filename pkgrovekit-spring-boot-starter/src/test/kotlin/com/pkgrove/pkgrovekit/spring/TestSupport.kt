package com.pkgrove.pkgrovekit.spring

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Shared real-infrastructure test kit: HikariCP pools over file-backed DuckDB
 * (in-memory DuckDB is per-connection, so a POOL needs a shared file), a
 * borrow-counting DataSource proxy, and a plain [AnnotationConfigApplicationContext]
 * harness that exercises the auto-configuration's real conditions.
 *
 * Harness note: `ApplicationContextRunner`'s `AssertableApplicationContext`
 * requires AssertJ, which is neither in the version catalog nor a transitive
 * of spring-boot-test (verified against the 3.3.5 POM) — so lifecycle-explicit
 * contexts + `TestPropertyValues` (spring-boot-test) are used throughout.
 */
internal fun duckPool(dbFile: Path, name: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:duckdb:$dbFile"
        maximumPoolSize = 2
        poolName = name
        // statement-based validation: independent of driver isValid support
        connectionTestQuery = "SELECT 1"
    })

internal fun DataSource.exec(vararg sql: String) {
    connection.use { c -> c.createStatement().use { st -> sql.forEach { st.execute(it) } } }
}

internal fun DataSource.count(table: String): Long =
    connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM \"$table\"").use { rs ->
                rs.next(); rs.getLong(1)
            }
        }
    }

/** Delegates every borrow to [delegate] and counts it — proves the Relay uses
 *  the exact bean instance (no second pool) and that a failed join fetched
 *  nothing. Deliberately NOT AutoCloseable: nothing can close the underlying
 *  pool through it. */
internal class CountingDataSource(private val delegate: DataSource) : DataSource {
    val connectionsServed = AtomicInteger()

    override fun getConnection(): Connection {
        connectionsServed.incrementAndGet()
        return delegate.connection
    }

    override fun getConnection(username: String?, password: String?): Connection {
        connectionsServed.incrementAndGet()
        return delegate.getConnection(username, password)
    }

    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
}

private fun prepare(ctx: AnnotationConfigApplicationContext, props: List<String>,
                    dataSources: Map<String, DataSource>) {
    TestPropertyValues.of(*props.toTypedArray()).applyTo(ctx)
    for ((name, ds) in dataSources) {
        // destroyMethod "" — the test owns pool shutdown, so Spring's inferred
        // AutoCloseable destruction must not race the ownership assertions
        ctx.registerBean(name, DataSource::class.java, Supplier<DataSource> { ds },
            BeanDefinitionCustomizer { bd -> bd.destroyMethodName = "" })
    }
}

/** Refreshed context with [props], [dataSources] beans, and the
 *  auto-configuration registered last (user definitions first, as in Boot). */
internal fun refreshedContext(props: List<String>,
                              dataSources: Map<String, DataSource> = emptyMap(),
                              configure: (AnnotationConfigApplicationContext) -> Unit = {}):
        AnnotationConfigApplicationContext {
    val ctx = AnnotationConfigApplicationContext()
    prepare(ctx, props, dataSources)
    configure(ctx)
    ctx.register(PkgroveKitAutoConfiguration::class.java)
    ctx.refresh()
    return ctx
}

/** Asserts context refresh FAILS and that the failure chain names every
 *  [expectedFragments] (offending property key, offending value, candidates). */
internal fun assertRefreshFails(props: List<String>, dataSources: Map<String, DataSource>,
                                vararg expectedFragments: String) {
    val ctx = AnnotationConfigApplicationContext()
    prepare(ctx, props, dataSources)
    ctx.register(PkgroveKitAutoConfiguration::class.java)
    val thrown = assertThrows(Exception::class.java) { ctx.refresh() }
    val chain = generateSequence(thrown as Throwable) { it.cause }
        .joinToString(" | ") { it.message ?: it.toString() }
    for (fragment in expectedFragments) {
        assertTrue(chain.contains(fragment),
            "expected startup failure to mention '$fragment' but was: $chain")
    }
}
