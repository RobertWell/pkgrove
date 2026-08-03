package com.pkgrove.pkgrovekit.narayana

import com.arjuna.ats.arjuna.common.arjPropertyManager
import com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean
import com.arjuna.ats.arjuna.recovery.RecoveryManager
import com.arjuna.common.internal.util.propertyservice.BeanPopulator
import com.pkgrove.pkgrovekit.jta.JtaCoordinator
import com.pkgrove.pkgrovekit.jta.XaParticipants
import jakarta.transaction.TransactionManager
import java.nio.file.Path

/**
 * Standalone Narayana wiring (HEL-170).
 *
 * OPERATIONAL REQUIREMENTS a deployment must own (see docs/coordination.md):
 *  - OBJECT STORE: [objectStoreDir] is the transaction recovery log. It must be
 *    durable, writable, and NOT shared by two coordinators with the same
 *    [nodeIdentifier]. Losing it after a crash loses the ability to resolve
 *    in-doubt branches.
 *  - COORDINATOR IDENTITY: [nodeIdentifier] must be unique per coordinator and
 *    STABLE across restarts — recovery matches in-doubt branches by it.
 *  - CRASH RECOVERY: after a crash, either restart the application with the
 *    same store + node id and run [NarayanaRuntime.recoveryScan], or run a
 *    dedicated recovery process. In-doubt/heuristic outcomes stay in the store
 *    until resolved.
 */
object Narayana {

    /**
     * Configure Narayana for standalone use and return a runtime holding the
     * jakarta [TransactionManager]. Call once per process.
     */
    fun standalone(
        objectStoreDir: Path,
        nodeIdentifier: String = "pkgrovekit",
        defaultTimeoutSeconds: Int = 60,
        startRecoveryManager: Boolean = false,
    ): NarayanaRuntime {
        require(nodeIdentifier.isNotBlank() && nodeIdentifier.length <= 28) {
            "nodeIdentifier must be 1..28 chars (XID branch space), was '$nodeIdentifier'"
        }
        val dir = objectStoreDir.toAbsolutePath().toString()
        // Narayana keeps three store instances; point them all below objectStoreDir.
        BeanPopulator.getDefaultInstance(ObjectStoreEnvironmentBean::class.java)
            .objectStoreDir = dir
        BeanPopulator.getNamedInstance(ObjectStoreEnvironmentBean::class.java, "communicationStore")
            .objectStoreDir = dir
        BeanPopulator.getNamedInstance(ObjectStoreEnvironmentBean::class.java, "stateStore")
            .objectStoreDir = dir
        arjPropertyManager.getCoreEnvironmentBean().nodeIdentifier = nodeIdentifier
        arjPropertyManager.getCoordinatorEnvironmentBean().defaultTimeout = defaultTimeoutSeconds

        val recovery = if (startRecoveryManager) {
            RecoveryManager.manager(RecoveryManager.INDIRECT_MANAGEMENT).also { it.initialize() }
        } else null

        val tm = com.arjuna.ats.jta.TransactionManager.transactionManager()
        return NarayanaRuntime(tm, recovery)
    }
}

/** Holds the configured TM; closing terminates the recovery manager if started. */
class NarayanaRuntime internal constructor(
    val transactionManager: TransactionManager,
    private val recovery: RecoveryManager?,
) : AutoCloseable {

    /** Coordinator over this runtime's TM for the given XA participants. */
    fun coordinator(participants: XaParticipants): JtaCoordinator =
        JtaCoordinator(transactionManager, participants)

    /** Run one synchronous recovery pass (resolves in-doubt branches it can). */
    fun recoveryScan() {
        (recovery ?: RecoveryManager.manager(RecoveryManager.DIRECT_MANAGEMENT)).scan()
    }

    override fun close() {
        recovery?.terminate()
    }
}
