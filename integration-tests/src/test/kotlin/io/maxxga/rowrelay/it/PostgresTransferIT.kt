package io.maxxga.rowrelay.it

import io.maxxga.rowrelay.duckdb.DuckDbDialect
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import io.maxxga.rowrelay.jdbc.SqlDialect
import io.maxxga.rowrelay.jdbc.TransactionPolicy
import io.maxxga.rowrelay.jdbc.TransactionState
import io.maxxga.rowrelay.jdbc.TransactionalWriter
import io.maxxga.rowrelay.jdbi.JdbiTransfer
import io.maxxga.rowrelay.postgres.PostgresDialect
import io.maxxga.rowrelay.postgres.PostgresValueReader
import io.maxxga.rowrelay.transfer.Mapping
import io.maxxga.rowrelay.transfer.Transfer
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager

/** HEL-127: live Postgres as source AND target (testcontainers; skipped
 *  without Docker) — named params, mapping, upsert, savepoint policy. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresTransferIT {

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var pgc: Connection

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        pgc = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        pgc.createStatement().use { st ->
            st.execute("""CREATE TABLE app_user (
                user_name VARCHAR(50) PRIMARY KEY, score NUMERIC(10,2),
                joined DATE, at TIMESTAMPTZ, blob BYTEA, note TEXT)""")
            st.execute("""INSERT INTO app_user VALUES
                ('ann', 12.34, DATE '2026-01-15', TIMESTAMPTZ '2026-07-01 10:30:00+08',
                 decode('DEADBEEF','hex'), '標籤 note with :colon'),
                ('bob', NULL, NULL, NULL, NULL, NULL)""")
        }
    }

    @AfterAll
    fun stop() { runCatching { pgc.close() }; runCatching { pg.stop() } }

    @Test
    fun `postgres to duckdb with named parameter and type fidelity`() {
        DriverManager.getConnection("jdbc:duckdb:").use { duck ->
            val report = Transfer.run(
                pgc, "SELECT * FROM app_user WHERE user_name = :u", mapOf("u" to "ann"),
                duck, DuckDbDialect, "users")
            assertTrue(report.completed)
            JdbcReader.open(duck, "SELECT * FROM \"users\"").use { s ->
                val r = s.toList().single()
                assertEquals("ann", r["user_name"])
                assertEquals(0, BigDecimal("12.34").compareTo(r["score"] as BigDecimal))
                assertEquals("標籤 note with :colon", r["note"])
                assertEquals(4, (r["blob"] as ByteArray).size)
                assertTrue(r["at"] != null)
            }
        }
    }

    @Test
    fun `duckdb to postgres batch insert then on-conflict upsert with rename mapping`() {
        DriverManager.getConnection("jdbc:duckdb:").use { duck ->
            duck.createStatement().use { st ->
                st.execute("CREATE TABLE src (source_user VARCHAR, source_score DECIMAL(10,2))")
                st.execute("INSERT INTO src SELECT 'p' || range, range * 1.5 FROM range(30)")
            }
            pgc.createStatement().use {
                it.execute("CREATE TABLE relay_dest (user_name VARCHAR(50) PRIMARY KEY, score NUMERIC(10,2))")
            }
            val mapping = Mapping.build {
                "source_user" mapsTo "user_name"
                "source_score" mapsTo "score"
            }
            val ins = Transfer.run(duck, "SELECT * FROM src", emptyMap<String, Any?>(),
                pgc, PostgresDialect, "relay_dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping,
                                 readBatchSize = 7))
            assertEquals(30L, ins.rowsAffected)

            duck.createStatement().use { it.execute("UPDATE src SET source_score = 777 WHERE source_user = 'p0'") }
            val up = Transfer.run(duck, "SELECT * FROM src WHERE source_user = :k", mapOf("k" to "p0"),
                pgc, PostgresDialect, "relay_dest",
                Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping,
                                 upsertKeys = listOf("user_name")))
            assertTrue(up.completed)
            pgc.createStatement().use { st ->
                val rs = st.executeQuery("SELECT score FROM relay_dest WHERE user_name = 'p0'")
                rs.next(); assertEquals(0, BigDecimal("777").compareTo(rs.getBigDecimal(1)))
                val rs2 = st.executeQuery("SELECT count(*) FROM relay_dest"); rs2.next()
                assertEquals(30, rs2.getInt(1))
            }
        }
    }

    // HEL-160: JDBI-path transfer INTO Postgres through the first-class facade,
    // bound to a caller-owned JDBI transaction (no handle.connection unwrap).
    @Test
    fun `duckdb to postgres via jdbi transfer facade honors the caller transaction`() {
        DriverManager.getConnection("jdbc:duckdb:").use { duck ->
            duck.createStatement().use { st ->
                st.execute("CREATE TABLE jsrc (source_user VARCHAR, source_score DECIMAL(10,2))")
                st.execute("INSERT INTO jsrc SELECT 'j' || range, range * 1.5 FROM range(25)")
            }
            pgc.createStatement().use {
                it.execute("CREATE TABLE jdbi_dest (user_name VARCHAR(50), score NUMERIC(10,2))")
            }
            val mapping = Mapping.build {
                "source_user" mapsTo "user_name"
                "source_score" mapsTo "score"
            }
            val jdbi = Jdbi.create(pg.jdbcUrl, pg.username, pg.password)

            // (a) rollback -> nothing persists
            class Abort : RuntimeException()
            assertThrows(Abort::class.java) {
                jdbi.useHandle<Exception> { h ->
                    h.useTransaction<Exception> { txh ->
                        JdbiTransfer.run(duck, "SELECT * FROM jsrc", emptyMap<String, Any?>(),
                            txh, PostgresDialect, "jdbi_dest",
                            Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping))
                        throw Abort()
                    }
                }
            }
            pgc.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM jdbi_dest"); rs.next()
                assertEquals(0, rs.getInt(1))
            }

            // (b) PerChunk inside the caller's transaction is rejected
            jdbi.useHandle<Exception> { h ->
                h.useTransaction<Exception> { txh ->
                    assertThrows(IllegalArgumentException::class.java) {
                        JdbiTransfer.run(duck, "SELECT * FROM jsrc", emptyMap<String, Any?>(),
                            txh, PostgresDialect, "jdbi_dest",
                            Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping,
                                commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(5)))
                    }
                }
            }

            // (c) commit -> all rows persist
            val report = jdbi.withHandle<io.maxxga.rowrelay.core.OperationReport, Exception> { h ->
                h.inTransaction<io.maxxga.rowrelay.core.OperationReport, Exception> { txh ->
                    JdbiTransfer.run(duck, "SELECT * FROM jsrc", emptyMap<String, Any?>(),
                        txh, PostgresDialect, "jdbi_dest",
                        Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping))
                }
            }
            assertTrue(report.completed)
            assertEquals(25L, report.rowsAffected)
            pgc.createStatement().use { st ->
                val rs = st.executeQuery("SELECT count(*) FROM jdbi_dest"); rs.next()
                assertEquals(25, rs.getInt(1))
            }
        }
    }

    @Test
    fun `HEL-127 uuid json jsonb and array columns round-trip postgres to postgres`() {
        pgc.createStatement().use { st ->
            st.execute("""CREATE TABLE exotic (
                id INT, u UUID, doc JSON, docb JSONB, nums INT4[], tags TEXT[])""")
            st.execute("""INSERT INTO exotic VALUES
                (1, '6ba7b810-9dad-11d1-80b4-00c04fd430c8'::uuid,
                 '{"a": 1}'::json, '{"b": 2, "a": 1}'::jsonb,
                 '{10,20,30}'::int4[], '{標籤,y}'::text[])""")
        }
        // a SECOND connection is the target (same DB) so the source cursor and
        // the target inserts don't share one connection.
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { tgt ->
            val report = Transfer.run(
                pgc, "SELECT * FROM exotic", emptyMap<String, Any?>(),
                tgt, PostgresDialect, "exotic_copy",
                Transfer.Options(sourceValueReader = PostgresValueReader()))
            assertTrue(report.completed, report.toString())
            assertEquals(1L, report.rowsAffected)

            // 1) the target columns were recreated as the REAL Postgres types,
            //    not stringified to TEXT.
            tgt.createStatement().use { st ->
                val types = mutableMapOf<String, String>()
                val rs = st.executeQuery(
                    "SELECT column_name, udt_name FROM information_schema.columns " +
                    "WHERE table_name = 'exotic_copy'")
                while (rs.next()) types[rs.getString(1)] = rs.getString(2)
                assertEquals("uuid", types["u"])
                assertEquals("json", types["doc"])
                assertEquals("jsonb", types["docb"])
                assertEquals("_int4", types["nums"])   // pgjdbc's array udt name
                assertEquals("_text", types["tags"])
            }
            // 2) the VALUES round-tripped exactly (typed comparisons in-DB).
            tgt.createStatement().use { st ->
                val rs = st.executeQuery("""SELECT count(*) FROM exotic_copy WHERE
                    u = '6ba7b810-9dad-11d1-80b4-00c04fd430c8'::uuid
                    AND docb = '{"a": 1, "b": 2}'::jsonb
                    AND doc::jsonb = '{"a": 1}'::jsonb
                    AND nums = '{10,20,30}'::int4[]
                    AND tags = '{標籤,y}'::text[]""")
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
    }

    @Test
    fun `savepoint-per-batch on postgres keeps earlier batches and skips the poison one`() {
        pgc.createStatement().use {
            it.execute("CREATE TABLE sp_dest (id BIGINT NOT NULL)")
        }
        val schema = io.maxxga.rowrelay.core.Schema(listOf(
            io.maxxga.rowrelay.core.Column("id", io.maxxga.rowrelay.core.ValueKind.NUMERIC, "BIGINT", precision = 18)))
        fun batch(values: List<Long?>) = io.maxxga.rowrelay.core.RowBatch(
            schema, values.map { io.maxxga.rowrelay.core.Row(schema, listOf(it)) })
        val batches = sequenceOf(
            batch(listOf(1L, 2L)), batch(listOf(3L, null)), batch(listOf(5L)))
        val o = TransactionalWriter.write(
            pgc, "INSERT INTO sp_dest VALUES (?)", batches,
            TransactionPolicy.SavepointPerBatch, PostgresDialect)
        assertEquals(TransactionState.PARTIALLY_COMMITTED, o.state)
        assertEquals(2L, o.committedRows)        // batch 1 survived the batch-2 poison
        pgc.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM sp_dest"); rs.next()
            assertEquals(2, rs.getInt(1))
        }
    }
}
