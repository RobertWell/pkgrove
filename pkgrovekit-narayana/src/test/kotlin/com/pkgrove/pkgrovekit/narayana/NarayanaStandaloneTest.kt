package com.pkgrove.pkgrovekit.narayana

import com.arjuna.ats.arjuna.common.arjPropertyManager
import com.pkgrove.pkgrovekit.jta.XaParticipants
import jakarta.transaction.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import javax.sql.XADataSource

/**
 * HEL-234 (issue §3: Narayana adapter lifecycle): standalone configuration,
 * REAL transaction-manager begin/commit/rollback lifecycle, validation of the
 * coordinator-identity contract, coordinator wiring, and shutdown — against
 * the real Narayana TM (no app server, temp object store).
 */
class NarayanaStandaloneTest {

    @Test
    fun `standalone configures the store identity and timeout then serves a working TM`(@TempDir store: Path) {
        Narayana.standalone(
            objectStoreDir = store,
            nodeIdentifier = "hel234-node",
            defaultTimeoutSeconds = 33,
        ).use { runtime ->
            // configuration landed where recovery will look for it
            assertEquals(store.toAbsolutePath().toString(),
                arjPropertyManager.getObjectStoreEnvironmentBean().objectStoreDir)
            assertEquals("hel234-node", arjPropertyManager.getCoreEnvironmentBean().nodeIdentifier)
            assertEquals(33, arjPropertyManager.getCoordinatorEnvironmentBean().defaultTimeout)

            // REAL lifecycle: begin -> active -> commit
            val tm = runtime.transactionManager
            assertEquals(Status.STATUS_NO_TRANSACTION, tm.status)
            tm.begin()
            assertEquals(Status.STATUS_ACTIVE, tm.status)
            tm.commit()
            assertEquals(Status.STATUS_NO_TRANSACTION, tm.status)

            // begin -> rollback leaves no transaction behind
            tm.begin()
            tm.rollback()
            assertEquals(Status.STATUS_NO_TRANSACTION, tm.status)

            // marking rollback-only forces the rollback path on commit
            tm.begin()
            tm.setRollbackOnly()
            assertThrows<jakarta.transaction.RollbackException> { tm.commit() }
            assertEquals(Status.STATUS_NO_TRANSACTION, tm.status)
        }
    }

    @Test
    fun `node identifier is validated against the XID branch space`(@TempDir store: Path) {
        assertThrows<IllegalArgumentException> {
            Narayana.standalone(store, nodeIdentifier = " ")
        }
        assertThrows<IllegalArgumentException> {
            Narayana.standalone(store, nodeIdentifier = "x".repeat(29))
        }
        // 28 chars is the documented maximum — accepted
        Narayana.standalone(store, nodeIdentifier = "n".repeat(28)).use { }
    }

    @Test
    fun `coordinator wires this runtime's TM to the registered participants`(@TempDir store: Path) {
        Narayana.standalone(store).use { runtime ->
            val xaDs = Proxy.newProxyInstance(
                javaClass.classLoader, arrayOf(XADataSource::class.java),
            ) { _, m, _ -> throw UnsupportedOperationException(m.name) } as XADataSource
            val coordinator = runtime.coordinator(XaParticipants.build {
                register("orders", xaDs)
            })
            assertNotNull(coordinator)
        }
    }

    @Test
    fun `close without a recovery manager is a safe no-op and the TM is a process singleton`(@TempDir store: Path) {
        val a = Narayana.standalone(store)
        val b = Narayana.standalone(store)
        assertSame(a.transactionManager, b.transactionManager,
            "standalone TM is per-process; call sites share it")
        a.close()
        b.close()
        // still usable after closes (no recovery manager was started)
        val tm = b.transactionManager
        tm.begin(); tm.rollback()
        assertEquals(Status.STATUS_NO_TRANSACTION, tm.status)
    }
}
