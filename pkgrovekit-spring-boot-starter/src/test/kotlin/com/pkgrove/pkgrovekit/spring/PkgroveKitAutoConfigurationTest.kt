package com.pkgrove.pkgrovekit.spring

import com.pkgrove.pkgrovekit.transfer.Relay
import com.pkgrove.pkgrovekit.transfer.TransferOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.function.Supplier

/**
 * Auto-configuration behavior against the REAL framework and a REAL pool/DB:
 * happy path (a), named multi-datasource resolution (b), user-bean backoff (c),
 * quiet backoff on an absent/disabled tree, and startup validation (f).
 */
class PkgroveKitAutoConfigurationTest {

    @field:TempDir
    lateinit var tempDir: Path

    // (a) one DataSource bean, specs resolve it implicitly; a real transfer runs
    @Test
    fun `single datasource - relay bean exists and executes a real transfer`() {
        val pool = duckPool(tempDir.resolve("happy.db"), "happyPool")
        try {
            pool.exec(
                "CREATE TABLE src (id BIGINT, name VARCHAR)",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b'), (3, 'c')")
            refreshedContext(
                listOf("pkgrovekit.databases.main.dialect=duckdb",
                       "pkgrovekit.databases.aux.dialect=duckdb"),
                mapOf("mainDataSource" to pool),
            ).use { ctx ->
                assertEquals(1, ctx.getBeanNamesForType(Relay::class.java).size)
                val relay = ctx.getBean(Relay::class.java)
                val plan = relay.transfer("copy-src") {
                    from(SpringDatabaseKey("main")) {
                        query("select id, name from src where id <= :max")
                        bind("max", 2L)
                    }
                    to(SpringDatabaseKey("aux"), "dst")
                }
                val outcome = relay.execute(plan)
                assertTrue(outcome is TransferOutcome.Completed, "outcome: $outcome")
                assertEquals(2L, (outcome as TransferOutcome.Completed).report.rowsAffected)
                assertEquals(2L, pool.count("dst"))
            }
        } finally {
            pool.close()
        }
    }

    // (b) two DataSource beans referenced by datasource-bean; both usable
    @Test
    fun `named datasource beans - cross-database transfer through both`() {
        val src = duckPool(tempDir.resolve("named-src.db"), "srcPool")
        val dst = duckPool(tempDir.resolve("named-dst.db"), "dstPool")
        try {
            src.exec(
                "CREATE TABLE ledger_rows (id BIGINT, name VARCHAR)",
                "INSERT INTO ledger_rows VALUES (1, 'a'), (2, 'b'), (3, 'c')")
            refreshedContext(
                listOf(
                    "pkgrovekit.databases.ledger.dialect=duckdb",
                    "pkgrovekit.databases.ledger.datasource-bean=srcPool",
                    "pkgrovekit.databases.mart.dialect=duckdb",
                    "pkgrovekit.databases.mart.datasource-bean=dstPool",
                    "pkgrovekit.databases.mart.max-connections=2",
                    "pkgrovekit.databases.mart.default-policy=Atomic",
                ),
                mapOf("srcPool" to src, "dstPool" to dst),
            ).use { ctx ->
                val relay = ctx.getBean(Relay::class.java)
                val plan = relay.transfer("ledger-to-mart") {
                    from(SpringDatabaseKey("ledger")) { query("select id, name from ledger_rows") }
                    to(SpringDatabaseKey("mart"), "copied")
                }
                val outcome = relay.execute(plan)
                assertTrue(outcome is TransferOutcome.Completed, "outcome: $outcome")
                assertEquals(3L, dst.count("copied"))   // landed in the SECOND pool's database
            }
        } finally {
            src.close()
            dst.close()
        }
    }

    // (c) user-supplied Relay bean wins; the auto-configured one backs off
    @Test
    fun `user relay bean - autoconfiguration backs off to the exact instance`() {
        val userRelay = Relay.build { }
        refreshedContext(listOf("pkgrovekit.databases.main.dialect=duckdb")) { ctx ->
            // no DataSource beans exist: if the autoconfig did NOT back off it
            // would fail refresh, so success here is itself part of the proof
            ctx.registerBean("customRelay", Relay::class.java, Supplier { userRelay })
        }.use { ctx ->
            assertEquals(1, ctx.getBeanNamesForType(Relay::class.java).size)
            assertSame(userRelay, ctx.getBean(Relay::class.java))
        }
    }

    // absent tree → quiet backoff (the DatabasesDeclaredCondition choice)
    @Test
    fun `wholly absent configuration - refresh succeeds with no relay bean`() {
        refreshedContext(emptyList()).use { ctx ->
            assertEquals(0, ctx.getBeanNamesForType(Relay::class.java).size)
        }
    }

    @Test
    fun `pkgrovekit disabled - no relay bean even with databases declared`() {
        refreshedContext(
            listOf("pkgrovekit.enabled=false",
                   "pkgrovekit.databases.main.dialect=duckdb"),
        ).use { ctx ->
            assertEquals(0, ctx.getBeanNamesForType(Relay::class.java).size)
        }
    }

    // (f) startup validation: refresh fails naming the offending key
    @Test
    fun `unknown dialect - context refresh fails naming key and value`() {
        val pool = duckPool(tempDir.resolve("baddialect.db"), "badDialectPool")
        try {
            assertRefreshFails(
                listOf("pkgrovekit.databases.main.dialect=sqlserver"),
                mapOf("mainDataSource" to pool),
                "pkgrovekit.databases.main.dialect", "sqlserver", "duckdb")
        } finally {
            pool.close()
        }
    }

    @Test
    fun `missing datasource bean - context refresh fails listing candidates`() {
        val pool = duckPool(tempDir.resolve("badbean.db"), "badBeanPool")
        try {
            assertRefreshFails(
                listOf("pkgrovekit.databases.main.dialect=duckdb",
                       "pkgrovekit.databases.main.datasource-bean=ghostPool"),
                mapOf("mainDataSource" to pool),
                "pkgrovekit.databases.main.datasource-bean", "ghostPool", "mainDataSource")
        } finally {
            pool.close()
        }
    }

    @Test
    fun `ambiguous datasources without bean name - context refresh fails listing candidates`() {
        val a = duckPool(tempDir.resolve("amb-a.db"), "ambPoolA")
        val b = duckPool(tempDir.resolve("amb-b.db"), "ambPoolB")
        try {
            assertRefreshFails(
                listOf("pkgrovekit.databases.main.dialect=duckdb"),
                mapOf("poolA" to a, "poolB" to b),
                "pkgrovekit.databases.main", "poolA", "poolB")
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `unknown default policy - context refresh fails naming key and value`() {
        val pool = duckPool(tempDir.resolve("badpolicy.db"), "badPolicyPool")
        try {
            assertRefreshFails(
                listOf("pkgrovekit.databases.main.dialect=duckdb",
                       "pkgrovekit.databases.main.default-policy=TwoPhase"),
                mapOf("mainDataSource" to pool),
                "pkgrovekit.databases.main.default-policy", "TwoPhase", "JoinExisting")
        } finally {
            pool.close()
        }
    }
}
