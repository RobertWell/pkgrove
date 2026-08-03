package com.pkgrove.pkgrovekit.spring

import com.pkgrove.pkgrovekit.transfer.Relay
import com.pkgrove.pkgrovekit.transfer.TransferOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * (e) Ownership on shutdown: closing the application context destroys the
 * Relay bean (Spring invokes its inferred `close()`), which must shut down
 * PkgroveKit's registry WITHOUT closing the framework-owned pool — the
 * APPLICATION_OWNED contract of [com.pkgrove.pkgrovekit.jdbc.Databases].
 */
class PoolOwnershipTest {

    @field:TempDir
    lateinit var tempDir: Path

    @Test
    fun `context close shuts the relay down but never the application pool`() {
        val hikari = duckPool(tempDir.resolve("owned.db"), "ownedPool")
        try {
            hikari.exec(
                "CREATE TABLE src (id BIGINT, name VARCHAR)",
                "INSERT INTO src VALUES (1, 'a'), (2, 'b')")
            val counting = CountingDataSource(hikari)
            val ctx = refreshedContext(
                listOf("pkgrovekit.databases.main.dialect=duckdb",
                       "pkgrovekit.databases.aux.dialect=duckdb"),
                mapOf("mainDataSource" to counting))
            val relay = ctx.getBean(Relay::class.java)
            val plan = relay.transfer("ownership-copy") {
                from(SpringDatabaseKey("main")) { query("select id, name from src") }
                to(SpringDatabaseKey("aux"), "dst_owned")
            }

            // every borrow flows through the exact bean instance: one lease per
            // key (main + aux), zero connections from anywhere else
            val before = counting.connectionsServed.get()
            assertEquals(0, before)
            assertTrue(relay.execute(plan) is TransferOutcome.Completed)
            assertEquals(before + 2, counting.connectionsServed.get())
            assertEquals(2L, hikari.count("dst_owned"))

            ctx.close()   // destroys the Relay bean → Relay.close() runs

            // the registry IS closed: executing again fails without touching a pool
            val afterClose = relay.execute(plan)
            assertTrue(afterClose is TransferOutcome.Failed, "outcome: $afterClose")

            // ...but the framework-owned pool is untouched and fully usable
            assertFalse(hikari.isClosed)
            assertEquals(2L, hikari.count("src"))     // getConnection still works
            assertEquals(0, hikari.hikariPoolMXBean.activeConnections)
        } finally {
            hikari.close()
        }
    }
}
