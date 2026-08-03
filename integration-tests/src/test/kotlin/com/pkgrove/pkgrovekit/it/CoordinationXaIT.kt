package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.coordination.CoordinationPlan
import com.pkgrove.pkgrovekit.coordination.CoordinationPolicy
import com.pkgrove.pkgrovekit.coordination.GlobalOutcome
import com.pkgrove.pkgrovekit.coordination.Participant
import com.pkgrove.pkgrovekit.coordination.ParticipantCapability
import com.pkgrove.pkgrovekit.coordination.ParticipantId
import com.pkgrove.pkgrovekit.coordination.PlanRejectedException
import com.pkgrove.pkgrovekit.coordination.PlanValidation
import com.pkgrove.pkgrovekit.coordination.PlanViolation
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.TransactionPolicy
import com.pkgrove.pkgrovekit.jdbc.TransactionalWriter
import com.pkgrove.pkgrovekit.jta.XaParticipants
import com.pkgrove.pkgrovekit.narayana.Narayana
import com.pkgrove.pkgrovekit.narayana.NarayanaRuntime
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Test
import org.postgresql.xa.PGXADataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration

/**
 * HEL-170 proofs 1–3 against two REAL, independently configured XA-capable
 * PostgreSQL resources, coordinated by Narayana (an established external TM —
 * no custom 2PC anywhere in PkgroveKit). Postgres needs
 * `max_prepared_transactions > 0` for XA; both containers enable it.
 *
 * The work runs the real PkgroveKit write path ([TransactionalWriter] with
 * [TransactionPolicy.JoinExisting]) over the ENLISTED connections — proving the
 * documented integration: the library appends, the coordinator commits.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoordinationXaIT {

    private lateinit var pgA: PostgreSQLContainer<*>
    private lateinit var pgB: PostgreSQLContainer<*>
    private lateinit var runtime: NarayanaRuntime
    private lateinit var participants: XaParticipants

    private val idA = ParticipantId("pg-a")
    private val idB = ParticipantId("pg-b")

    private lateinit var objectStore: Path

    @BeforeAll
    fun start() {
        objectStore = java.nio.file.Files.createTempDirectory("pkgrovekit-xa-store")
        fun pg() = PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_prepared_transactions=16")
        pgA = pg().also { it.start() }
        pgB = pg().also { it.start() }

        fun xaDs(c: PostgreSQLContainer<*>) = PGXADataSource().apply {
            setUrl(c.jdbcUrl)
            user = c.username
            password = c.password
        }
        participants = XaParticipants.build {
            register(idA, xaDs(pgA))
            register(idB, xaDs(pgB))
        }
        runtime = Narayana.standalone(objectStore, nodeIdentifier = "pkgrovekit-it")

        plain(pgA) { it.execute("CREATE TABLE ledger_a (id BIGINT PRIMARY KEY)") }
        plain(pgB) { it.execute("CREATE TABLE ledger_b (id BIGINT PRIMARY KEY, CHECK (id < 100))") }
    }

    @AfterAll
    fun stop() {
        runtime.close()
        pgA.stop(); pgB.stop()
        objectStore.toFile().deleteRecursively()
    }

    @BeforeEach
    fun clean() {
        plain(pgA) { it.execute("DELETE FROM ledger_a") }
        plain(pgB) { it.execute("DELETE FROM ledger_b") }
    }

    private fun plain(c: PostgreSQLContainer<*>, block: (java.sql.Statement) -> Unit) {
        DriverManager.getConnection(c.jdbcUrl, c.username, c.password).use { conn ->
            conn.createStatement().use(block)
        }
    }

    private fun count(c: PostgreSQLContainer<*>, table: String): Int {
        var n = -1
        plain(c) { st ->
            val rs = st.executeQuery("SELECT count(*) FROM $table")
            rs.next(); n = rs.getInt(1)
        }
        return n
    }

    private fun plan(timeout: Duration = Duration.ofSeconds(30)) = CoordinationPlan(
        CoordinationPolicy.Xa2Pc(timeout),
        listOf(
            Participant(idA, ParticipantCapability.XaCapable),
            Participant(idB, ParticipantCapability.XaCapable),
        ),
    )

    private val schema = Schema(listOf(Column("id", ValueKind.NUMERIC, "BIGINT", precision = 18)))
    private fun batch(vararg ids: Long) = RowBatch(schema, ids.map { Row(schema, listOf(it)) })

    // ── proof 1: global commit across two resources ─────────────────────────
    @Test
    fun `two-resource XA commit makes both writes durable atomically`() {
        val coordinator = runtime.coordinator(participants)

        val result = coordinator.inGlobalTransaction(plan()) { scope ->
            // real PkgroveKit write path over the enlisted connections:
            // JoinExisting appends and NEVER commits — the TM does.
            val a = TransactionalWriter.write(
                scope.connection(idA), "INSERT INTO ledger_a VALUES (?)",
                sequenceOf(batch(1, 2, 3)), TransactionPolicy.JoinExisting,
            )
            val b = TransactionalWriter.write(
                scope.connection(idB), "INSERT INTO ledger_b VALUES (?)",
                sequenceOf(batch(1, 2, 3)), TransactionPolicy.JoinExisting,
            )
            a.committedRows + b.committedRows
        }

        assertInstanceOf(GlobalOutcome.Committed::class.java, result.outcome)
        assertEquals(3, count(pgA, "ledger_a"))
        assertEquals(3, count(pgB, "ledger_b"))
    }

    // ── proof 2: second-participant failure rolls back BOTH resources ───────
    @Test
    fun `a failure in the second participant rolls back both resources`() {
        val coordinator = runtime.coordinator(participants)

        val result = coordinator.inGlobalTransaction(plan()) { scope ->
            TransactionalWriter.write(
                scope.connection(idA), "INSERT INTO ledger_a VALUES (?)",
                sequenceOf(batch(10, 11)), TransactionPolicy.JoinExisting,
            )
            // ledger_b has CHECK (id < 100): 999 violates it and throws mid-work
            scope.connection(idB).prepareStatement("INSERT INTO ledger_b VALUES (?)").run {
                setLong(1, 999)
                executeUpdate()
            }
            error("unreachable")
        }

        assertInstanceOf(GlobalOutcome.RolledBack::class.java, result.outcome)
        assertNull(result.value)
        assertEquals(0, count(pgA, "ledger_a"), "participant A must have rolled back too")
        assertEquals(0, count(pgB, "ledger_b"))
    }

    // ── proof 3: a non-XA participant never reaches any database ────────────
    @Test
    fun `a non-XA participant is rejected before any database effect`() {
        val coordinator = runtime.coordinator(participants)
        val withDuck = CoordinationPlan(
            CoordinationPolicy.Xa2Pc(Duration.ofSeconds(5)),
            plan().participants + Participant(ParticipantId("duckdb"), ParticipantCapability.LocalJdbc),
        )

        val validation = coordinator.validate(withDuck)
        val invalid = assertInstanceOf(PlanValidation.Invalid::class.java, validation)
        assertTrue(invalid.violations.any { it is PlanViolation.NonXaParticipant })

        val ex = assertThrows(PlanRejectedException::class.java) {
            coordinator.inGlobalTransaction(withDuck) { scope ->
                TransactionalWriter.write(
                    scope.connection(idA), "INSERT INTO ledger_a VALUES (?)",
                    sequenceOf(batch(50)), TransactionPolicy.JoinExisting,
                )
            }
        }
        assertTrue(ex.violations.isNotEmpty())
        assertEquals(0, count(pgA, "ledger_a"), "rejection must precede any effect")
        assertEquals(0, count(pgB, "ledger_b"))
    }
}
