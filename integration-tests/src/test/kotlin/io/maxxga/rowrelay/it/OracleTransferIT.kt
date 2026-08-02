package io.maxxga.rowrelay.it

import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbc.SqlDialect
import io.maxxga.rowrelay.jdbi.JdbiTransfer
import io.maxxga.rowrelay.oracle.OracleDialect
import io.maxxga.rowrelay.oracle.OracleValueReader
import io.maxxga.rowrelay.transfer.Mapping
import io.maxxga.rowrelay.transfer.Transfer
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    // Scenario 3b (HEL-160): DuckDB -> Oracle through the first-class JDBI transfer
    // facade, with the write bound to a caller-owned JDBI transaction. The whole
    // transfer (table establish + inserts) is atomic with the caller's transaction:
    // a rollback leaves nothing, a commit persists everything — no handle.connection
    // unwrap.
    @Test
    fun `duckdb to oracle via jdbi transfer facade honors the caller transaction`() {
        freshDuck().use { duck ->
            duck.createStatement().use { st ->
                st.execute("CREATE TABLE jsrc (source_user VARCHAR, source_score DECIMAL(10,2))")
                st.execute("INSERT INTO jsrc SELECT 'j' || range, range * 2.5 FROM range(20)")
            }
            oconn.createStatement().use {
                it.execute("CREATE TABLE jdbi_dest (user_name VARCHAR2(50), score NUMBER(10,2))")
            }
            val mapping = Mapping.build {
                "source_user" mapsTo "user_name"
                "source_score" mapsTo "score"
            }
            val jdbi = Jdbi.create(oracle.jdbcUrl, oracle.username, oracle.password)

            // (a) caller rolls back -> the appended rows never persist
            class Abort : RuntimeException()
            assertThrows(Abort::class.java) {
                jdbi.useHandle<Exception> { h ->
                    h.useTransaction<Exception> { txh ->
                        JdbiTransfer.run(duck, "SELECT * FROM jsrc", emptyMap(),
                            txh, OracleDialect, "jdbi_dest",
                            Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping))
                        throw Abort()
                    }
                }
            }
            oconn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM jdbi_dest"); rs.next()
                assertEquals(0, rs.getInt(1))   // rolled back with the caller
            }

            // (b) PerChunk inside the caller's transaction is rejected loudly
            jdbi.useHandle<Exception> { h ->
                h.useTransaction<Exception> { txh ->
                    assertThrows(IllegalArgumentException::class.java) {
                        JdbiTransfer.run(duck, "SELECT * FROM jsrc", emptyMap(),
                            txh, OracleDialect, "jdbi_dest",
                            Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping,
                                commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(5)))
                    }
                }
            }

            // (c) caller commits -> all rows persist through the JDBI facade
            val report = jdbi.withHandle<io.maxxga.rowrelay.core.OperationReport, Exception> { h ->
                h.inTransaction<io.maxxga.rowrelay.core.OperationReport, Exception> { txh ->
                    JdbiTransfer.run(duck, "SELECT * FROM jsrc", emptyMap(),
                        txh, OracleDialect, "jdbi_dest",
                        Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping))
                }
            }
            assertTrue(report.completed)
            assertEquals(20L, report.rowsAffected)
            oconn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM jdbi_dest"); rs.next()
                assertEquals(20, rs.getInt(1))
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

    // ---- HEL-168: parameterized Oracle ↔ DuckDB type-fidelity matrix ----
    // Each case seeds one typed Oracle column, transfers Oracle→DuckDB, and
    // asserts the read-back DuckDB value is faithful (no truncation/rounding/
    // tz-shift/stringification). Boundary fixtures: unicode, empty, big integer,
    // fractional temporal, TZ offset, large CLOB, binary zero bytes/empty.

    class MCase(val label: String, val oracleType: String, val insertSql: String,
                val check: (Any?) -> Unit) { override fun toString() = label }

    private fun oracleToDuck(): List<MCase> = listOf(
        MCase("varchar2 unicode", "VARCHAR2(50)", "'安妮 Ann'") { assertEquals("安妮 Ann", it) },
        MCase("nvarchar2 unicode", "NVARCHAR2(50)", "N'標籤 café'") { assertEquals("標籤 café", it) },
        MCase("char fixed-width padded", "CHAR(8)", "'ab'") {
            // Oracle blank-pads CHAR; padding is preserved as-read (documented).
            assertEquals("ab", (it as String).trimEnd()); assertEquals(8, it.length)
        },
        MCase("number big integer", "NUMBER(38)", "99999999999999999999999999999999999999") {
            assertEquals(0, java.math.BigDecimal("99999999999999999999999999999999999999")
                .compareTo(it as java.math.BigDecimal))
        },
        MCase("number decimal", "NUMBER(10,2)", "12.34") {
            assertEquals(0, BigDecimal("12.34").compareTo(it as BigDecimal))
        },
        MCase("binary_double", "BINARY_DOUBLE", "3.141592653589793d") {
            assertEquals(3.141592653589793, it)
        },
        MCase("date carries time", "DATE",
              "TO_DATE('2026-08-02 09:15:30','YYYY-MM-DD HH24:MI:SS')") {
            assertEquals(java.time.LocalDateTime.parse("2026-08-02T09:15:30"), it)
        },
        MCase("timestamp fractional", "TIMESTAMP",
              "TIMESTAMP '2026-08-02 13:45:30.123456'") {
            assertEquals(java.time.LocalDateTime.parse("2026-08-02T13:45:30.123456"), it)
        },
        MCase("timestamp with time zone", "TIMESTAMP WITH TIME ZONE",
              "TIMESTAMP '2026-08-02 13:45:30.123456 +02:00'") {
            assertEquals(java.time.OffsetDateTime.parse("2026-08-02T13:45:30.123456+02:00").toInstant(),
                (it as java.time.OffsetDateTime).toInstant())
        },
        // >4000 chars must be assembled in CLOB context — a bare RPAD(...,8000)
        // is VARCHAR2-bound (4000) and throws in SQL. TO_CLOB first, then concat.
        MCase("clob large with newlines", "CLOB",
              "TO_CLOB('line1' || CHR(10)) || RPAD('x', 4000, 'x') || RPAD('y', 4000, 'y')") {
            val s = it as String
            assertTrue(s.startsWith("line1\n"))
            assertEquals(6 + 8000, s.length)              // full payload, not truncated
            assertEquals('x', s[6]); assertEquals('y', s[s.length - 1])
        },
        MCase("blob bytes", "BLOB", "HEXTORAW('DEADBEEF')") {
            assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
                it as ByteArray)
        },
        MCase("raw bytes", "RAW(4)", "HEXTORAW('00FF00FF')") {
            assertArrayEquals(byteArrayOf(0, 0xFF.toByte(), 0, 0xFF.toByte()), it as ByteArray)
        },
    )

    @org.junit.jupiter.params.ParameterizedTest(name = "oracle->duckdb: {0}")
    @org.junit.jupiter.params.provider.MethodSource("oracleToDuck")
    fun `oracle to duckdb type matrix`(c: MCase) {
        val tbl = "tm_" + c.label.replace(Regex("[^a-zA-Z0-9]"), "_")
        oconn.createStatement().use { st ->
            runCatching { st.execute("DROP TABLE $tbl") }
            st.execute("CREATE TABLE $tbl (v ${c.oracleType})")
            st.execute("INSERT INTO $tbl (v) VALUES (${c.insertSql})")
        }
        freshDuck().use { duck ->
            Transfer.run(oconn, "SELECT v FROM $tbl", emptyMap(), duck, DuckDbDialect, "t", readOracle)
            JdbcReader.open(duck, "SELECT v FROM \"t\"").use { rows ->
                c.check(rows.toList().single()["v"])
            }
            // NULL of the same type also round-trips as null (not a failure)
            oconn.createStatement().use { it.execute("INSERT INTO $tbl (v) VALUES (NULL)") }
            Transfer.run(oconn, "SELECT v FROM $tbl WHERE v IS NULL", emptyMap(),
                         duck, DuckDbDialect, "tn", readOracle)
            JdbcReader.open(duck, "SELECT v FROM \"tn\"").use { rows ->
                assertNull(rows.toList().single()["v"])
            }
        }
    }

    // DuckDB → Oracle round trip: create in DuckDB, transfer to Oracle (APPEND),
    // upsert one row, read back through Oracle — exercises insert + update/upsert
    // + comparison for the core cross-writable types.
    @Test
    fun `duckdb to oracle type round trip with upsert`() {
        freshDuck().use { duck ->
            duck.createStatement().use { st ->
                st.execute("""CREATE TABLE d (k VARCHAR, txt VARCHAR, num DECIMAL(12,3),
                              big BIGINT, ts TIMESTAMP, bin BLOB)""")
                st.execute("""INSERT INTO d VALUES
                    ('a', '標籤 café', 123.456, 9223372036854775807,
                     TIMESTAMP '2026-08-02 01:02:03.456789', '\xDE\xAD'::BLOB)""")
            }
            oconn.createStatement().use {
                it.execute("""CREATE TABLE d_dest (k VARCHAR2(10) PRIMARY KEY, txt NVARCHAR2(50),
                              num NUMBER(12,3), big NUMBER(38), ts TIMESTAMP, bin BLOB)""")
            }
            val ins = Transfer.run(duck, "SELECT * FROM d", emptyMap<String, Any?>(),
                oconn, OracleDialect, "d_dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND))
            assertEquals(1L, ins.rowsAffected)

            // change the numeric + upsert on the key -> MERGE
            duck.createStatement().use { it.execute("UPDATE d SET num = 999.999 WHERE k = 'a'") }
            val up = Transfer.run(duck, "SELECT * FROM d", emptyMap<String, Any?>(),
                oconn, OracleDialect, "d_dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND, upsertKeys = listOf("k")))
            assertTrue(up.completed)

            oconn.createStatement().use { st ->
                val rs = st.executeQuery("SELECT txt, num, big, ts, bin FROM d_dest WHERE k = 'a'")
                rs.next()
                assertEquals("標籤 café", rs.getString("txt"))                       // unicode via NVARCHAR2
                assertEquals(0, BigDecimal("999.999").compareTo(rs.getBigDecimal("num")))  // upsized value
                assertEquals(0, BigDecimal("9223372036854775807").compareTo(rs.getBigDecimal("big"))) // Long.MAX exact
                assertEquals(java.time.LocalDateTime.parse("2026-08-02T01:02:03.456789"),
                             rs.getTimestamp("ts").toLocalDateTime())               // fractional preserved
                assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), rs.getBytes("bin"))
                assertEquals(1, st.executeQuery("SELECT count(*) FROM d_dest").let { it.next(); it.getInt(1) })
            }
        }
    }
}
