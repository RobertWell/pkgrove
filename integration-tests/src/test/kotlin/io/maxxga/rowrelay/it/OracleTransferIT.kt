package io.maxxga.rowrelay.it

import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbc.SqlDialect
import io.maxxga.rowrelay.oracle.OracleDialect
import io.maxxga.rowrelay.oracle.OracleValueReader
import io.maxxga.rowrelay.transfer.Mapping
import io.maxxga.rowrelay.transfer.Transfer
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-119 live-Oracle validation (scenarios 1–3, 10): named parameters and
 * named mapping across a REAL Oracle (testcontainers gvenzl/oracle-free) in
 * both directions and both access paths. Skipped automatically when Docker
 * is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleTransferIT {

    private lateinit var oracle: OracleContainer
    private lateinit var oconn: Connection

    @BeforeAll
    fun startOracle() {
        oracle = OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withDatabaseName("testdb").withUsername("test").withPassword("test")
        oracle.start()
        oconn = DriverManager.getConnection(oracle.jdbcUrl, oracle.username, oracle.password)
        oconn.createStatement().use { st ->
            st.execute("""CREATE TABLE app_user (
                user_name VARCHAR2(50) PRIMARY KEY,
                display_name NVARCHAR2(100),
                score NUMBER(10,2),
                joined_on DATE,
                updated_at TIMESTAMP,
                tz_at TIMESTAMP WITH TIME ZONE,
                notes CLOB,
                avatar BLOB,
                token RAW(16))""")
            st.execute("""INSERT INTO app_user VALUES (
                'ann', N'安妮 Ann', 12.34, DATE '2026-01-15',
                TIMESTAMP '2026-07-01 10:30:00',
                TIMESTAMP '2026-07-01 10:30:00 +08:00',
                'notes: with a :colon and unicode 標籤',
                HEXTORAW('DEADBEEF'), HEXTORAW('00112233445566778899AABBCCDDEEFF'))""")
            st.execute("""INSERT INTO app_user (user_name, display_name, score, joined_on,
                          updated_at, tz_at, notes, avatar, token)
                          VALUES ('bob', N'Bob', NULL, NULL, NULL, NULL, NULL, NULL, NULL)""")
        }
    }

    @AfterAll
    fun stopOracle() {
        runCatching { oconn.close() }
        runCatching { oracle.stop() }
    }

    private fun freshDuck(): Connection = DriverManager.getConnection("jdbc:duckdb:")

    private val readOracle = Transfer.Options(sourceValueReader = OracleValueReader())

    // Scenario 1: Oracle -> DuckDB with :user_name
    @Test
    fun `oracle to duckdb with named parameter`() {
        freshDuck().use { duck ->
            val report = Transfer.run(
                oconn, "SELECT user_name, display_name, score FROM app_user WHERE user_name = :user_name",
                mapOf("user_name" to "ann"),
                duck, DuckDbDialect, "users", readOracle)
            assertTrue(report.completed)
            assertEquals(1L, report.rowsAffected)
            JdbcReader.open(duck, "SELECT * FROM \"users\"").use { rows ->
                val r = rows.toList().single()
                assertEquals("安妮 Ann", r["display_name"])          // NVARCHAR2 unicode
                assertEquals(0, BigDecimal("12.34").compareTo(r["score"] as BigDecimal))
            }
        }
    }

    // Scenario 2: identical outcome through the JDBI access path
    @Test
    fun `jdbc and jdbi paths produce equivalent oracle transfer results`() {
        val viaJdbc = freshDuck().use { duck ->
            Transfer.run(oconn, "SELECT user_name, score FROM app_user WHERE user_name = :u",
                         mapOf("u" to "ann"), duck, DuckDbDialect, "t", readOracle)
            JdbcReader.open(duck, "SELECT * FROM \"t\"").use { it.toList().map { r -> r.values } }
        }
        val jdbi = Jdbi.create(oracle.jdbcUrl, oracle.username, oracle.password)
        val viaJdbi = jdbi.withHandle<List<List<Any?>>, Exception> { handle ->
            freshDuck().use { duck ->
                Transfer.run(handle.connection,
                             "SELECT user_name, score FROM app_user WHERE user_name = :u",
                             mapOf("u" to "ann"), duck, DuckDbDialect, "t", readOracle)
                JdbcReader.open(duck, "SELECT * FROM \"t\"").use { it.toList().map { r -> r.values } }
            }
        }
        assertEquals(viaJdbc, viaJdbi)
    }

    // Scenario 3: DuckDB -> Oracle named batch insert + MERGE upsert
    @Test
    fun `duckdb to oracle batch insert then named-key upsert`() {
        freshDuck().use { duck ->
            duck.createStatement().use { st ->
                st.execute("CREATE TABLE src (source_user VARCHAR, source_score DECIMAL(10,2))")
                st.execute("INSERT INTO src SELECT 'w' || range, range * 1.25 FROM range(40)")
            }
            oconn.createStatement().use {
                it.execute("CREATE TABLE relay_dest (user_name VARCHAR2(50) PRIMARY KEY, score NUMBER(10,2))")
            }
            val mapping = Mapping.build {
                "source_user" mapsTo "user_name"
                "source_score" mapsTo "score"
            }
            val insert = Transfer.run(
                duck, "SELECT * FROM src WHERE source_user <> :skip", mapOf("skip" to "w39"),
                oconn, OracleDialect, "relay_dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping,
                                 readBatchSize = 10,
                                 commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(2)))
            assertTrue(insert.completed)
            assertEquals(39L, insert.rowsAffected)

            // upsert: change one row's score at the source, MERGE it in
            duck.createStatement().use { it.execute("UPDATE src SET source_score = 999.99 WHERE source_user = 'w0'") }
            val upsert = Transfer.run(
                duck, "SELECT * FROM src WHERE source_user IN (:a, :b)", mapOf("a" to "w0", "b" to "w39"),
                oconn, OracleDialect, "relay_dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping,
                                 upsertKeys = listOf("user_name")))
            assertTrue(upsert.completed)
            oconn.createStatement().use { st ->
                val rs = st.executeQuery(
                    "SELECT score FROM relay_dest WHERE user_name = 'w0'")
                rs.next()
                assertEquals(0, BigDecimal("999.99").compareTo(rs.getBigDecimal(1)))  // updated
                val rs2 = st.executeQuery("SELECT count(*) FROM relay_dest")
                rs2.next()
                assertEquals(40, rs2.getInt(1))                                       // w39 inserted
            }
        }
    }

    // Scenario 10: type fidelity round trip (NUMBER/DATE/TS/TZ/CLOB/BLOB/RAW/null/unicode)
    @Test
    fun `type fidelity oracle to duckdb`() {
        freshDuck().use { duck ->
            Transfer.run(oconn, "SELECT * FROM app_user WHERE user_name = :u", mapOf("u" to "ann"),
                         duck, DuckDbDialect, "full_row", readOracle)
            JdbcReader.open(duck, "SELECT * FROM \"full_row\"").use { rows ->
                val r = rows.toList().single()
                assertEquals("ann", r["user_name"])
                assertEquals("安妮 Ann", r["display_name"])
                assertEquals(0, BigDecimal("12.34").compareTo(r["score"] as BigDecimal))
                assertTrue(r["joined_on"] is java.time.LocalDateTime)   // Oracle DATE carries time
                assertTrue(r["updated_at"] is java.time.LocalDateTime)
                assertTrue(r["tz_at"] != null)                          // TZ-aware value survived
                assertEquals("notes: with a :colon and unicode 標籤", r["notes"])  // CLOB + literal colon
                assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                                  r["avatar"] as ByteArray)             // BLOB bytes
                assertEquals(16, (r["token"] as ByteArray).size)        // RAW bytes
            }
            // and the all-null row transfers as nulls, not failures
            Transfer.run(oconn, "SELECT score, notes, avatar FROM app_user WHERE user_name = :u",
                         mapOf("u" to "bob"), duck, DuckDbDialect, "null_row", readOracle)
            JdbcReader.open(duck, "SELECT * FROM \"null_row\"").use { rows ->
                val r = rows.toList().single()
                assertNull(r["score"]); assertNull(r["notes"]); assertNull(r["avatar"])
            }
        }
    }

    // HEL-126 live Oracle transaction matrix: savepoint-per-batch preserves
    // earlier batches through a poison batch; Atomic rolls the whole thing back.
    private val txSchema = io.maxxga.rowrelay.core.Schema(listOf(
        io.maxxga.rowrelay.core.Column("id", io.maxxga.rowrelay.core.ValueKind.NUMERIC, "NUMBER", precision = 9)))
    private fun txBatch(values: List<Long?>) = io.maxxga.rowrelay.core.RowBatch(
        txSchema, values.map { io.maxxga.rowrelay.core.Row(txSchema, listOf(it)) })

    @Test
    fun `savepoint-per-batch on oracle keeps earlier batches and skips the poison one`() {
        oconn.createStatement().use { it.execute("CREATE TABLE sp_dest (id NUMBER(9) NOT NULL)") }
        val batches = sequenceOf(
            txBatch(listOf(1L, 2L)), txBatch(listOf(3L, null)), txBatch(listOf(5L)))
        val o = io.maxxga.rowrelay.jdbc.TransactionalWriter.write(
            oconn, "INSERT INTO sp_dest VALUES (?)", batches,
            io.maxxga.rowrelay.jdbc.TransactionPolicy.SavepointPerBatch, OracleDialect)
        assertEquals(io.maxxga.rowrelay.jdbc.TransactionState.PARTIALLY_COMMITTED, o.state)
        assertEquals(2L, o.committedRows)           // batch 1 survived the batch-2 poison
        oconn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sp_dest"); rs.next()
            assertEquals(2, rs.getInt(1))
        }
    }

    @Test
    fun `atomic policy on oracle rolls the whole transfer back on a poison row`() {
        oconn.createStatement().use { it.execute("CREATE TABLE atomic_dest (id NUMBER(9) NOT NULL)") }
        val batches = sequenceOf(txBatch(listOf(1L, 2L)), txBatch(listOf(3L, null)))
        // Atomic surfaces failure by THROWING with the honest outcome attached —
        // never a silent partial. The exception carries ROLLED_BACK + safe-to-retry.
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            io.maxxga.rowrelay.jdbc.TransactionWriteException::class.java) {
            io.maxxga.rowrelay.jdbc.TransactionalWriter.write(
                oconn, "INSERT INTO atomic_dest VALUES (?)", batches,
                io.maxxga.rowrelay.jdbc.TransactionPolicy.Atomic, OracleDialect)
        }
        assertEquals(io.maxxga.rowrelay.jdbc.TransactionState.ROLLED_BACK, ex.outcome.state)
        assertEquals(0L, ex.outcome.committedRows)
        assertEquals(io.maxxga.rowrelay.jdbc.RetrySafety.SAFE_NOTHING_COMMITTED, ex.outcome.retrySafety)
        oconn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM atomic_dest"); rs.next()
            assertEquals(0, rs.getInt(1))           // all-or-nothing: nothing committed
        }
    }
}
