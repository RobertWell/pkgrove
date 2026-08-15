package com.pkgrove.pkgrovekit.it

import java.time.Duration
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.oracle.OracleDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.oracle.OracleContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-224 review evidence: the same-connection INSERT … SELECT push-down on a
 * REAL Oracle, with the two types the review named — CLOB and TIMESTAMP WITH
 * LOCAL TIME ZONE — proving value equality and ZERO warnings. The unit suite
 * (ServerSideCopyTest) exercises the mechanism on DuckDB only; this closes the
 * dialect-fidelity gap on live Oracle, in the required `integration-oracle`
 * CI job (dind + gvenzl/oracle-free), whose green run is the release-lineage
 * evidence.
 *
 * TSLTZ nuance done honestly: LTZ values render in the session time zone, so
 * source and copy are compared IN THE SAME SESSION — equality then means the
 * stored instants are identical, which is the fidelity claim being made.
 *
 * Charset note: the created dest folds NVARCHAR2 -> VARCHAR2 (same charset
 * family under the AL32UTF8 database charset, unicode round-trips intact —
 * the 標籤 value equality below proves it). The comparison casts both sides
 * TO_NCHAR so the MINUS is charset-consistent (ORA-12704 otherwise); the
 * review's named types, CLOB and TSLTZ, are compared with no normalisation
 * beyond same-session rendering.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleServerSideCopyIT {

    private lateinit var oracle: OracleContainer
    private lateinit var conn: Connection

    @BeforeAll
    fun startOracle() {
        oracle = OracleContainer(DockerImageName.parse("gvenzl/oracle-free:23-slim-faststart"))
            .withDatabaseName("testdb").withUsername("test").withPassword("test")
            // Same contention-tolerant startup window as the sibling Oracle ITs.
            .withStartupTimeout(Duration.ofMinutes(6)).withStartupAttempts(2)
        oracle.start()
        conn = DriverManager.getConnection(oracle.jdbcUrl, oracle.username, oracle.password)
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE copy_src (
                     id NUMBER(10) PRIMARY KEY,
                     notes CLOB,
                     seen_at TIMESTAMP WITH LOCAL TIME ZONE,
                     label NVARCHAR2(60))""")
            // A CLOB comfortably past any inline/VARCHAR2 threshold, with
            // unicode and a :colon (the named-param trap), plus NULLs — the
            // copy must carry all of it without a client round-trip.
            st.execute(
                """INSERT INTO copy_src VALUES (
                     1,
                     TO_CLOB('註記 with a :colon — ') || RPAD('x', 8000, 'x'),
                     TIMESTAMP '2026-07-01 10:30:00 +08:00',
                     N'標籤 one')""")
            st.execute(
                """INSERT INTO copy_src VALUES (
                     2, NULL, NULL, N'nulls stay null')""")
            st.execute(
                """INSERT INTO copy_src VALUES (
                     3,
                     TO_CLOB('short clob'),
                     TIMESTAMP '2026-01-15 23:59:59 -05:00',
                     NULL)""")
        }
    }

    @AfterAll
    fun stopOracle() {
        runCatching { conn.close() }
        runCatching { oracle.stop() }
    }

    private fun warningCodes(report: OperationReport) = report.warnings.map { it.code }

    @Test
    fun `same-connection Oracle copy carries CLOB and TSLTZ with value equality and zero warnings`() {
        val report = Transfer.run(
            conn, "SELECT id, notes, seen_at, label FROM copy_src ORDER BY id", emptyList<Any?>(),
            conn, OracleDialect, "copy_dest",
            Transfer.Options(useServerSideCopy = true))

        assertTrue(report.completed, "copy must complete: ${report.warnings}")
        assertEquals(3L, report.rowsAffected)
        assertEquals(1, report.batches, "server-side copy is ONE statement, not row batches")
        assertTrue(report.warnings.isEmpty(),
                   "review criterion is ZERO warnings, got: ${warningCodes(report)}")

        // Value equality, same session, straight SQL — no reader layer between
        // the claim and the database. MINUS in both directions = exact set
        // equality; DBMS_LOB.COMPARE makes CLOB equality first-class.
        conn.createStatement().use { st ->
            st.executeQuery(
                """SELECT COUNT(*) FROM (
                     SELECT id, DBMS_LOB.SUBSTR(notes, 1000, 1) n1,
                            NVL(DBMS_LOB.GETLENGTH(notes), -1) nlen,
                            CAST(seen_at AS TIMESTAMP) t, TO_NCHAR(label) FROM copy_src
                     MINUS
                     SELECT id, DBMS_LOB.SUBSTR(notes, 1000, 1),
                            NVL(DBMS_LOB.GETLENGTH(notes), -1),
                            CAST(seen_at AS TIMESTAMP), TO_NCHAR(label) FROM copy_dest)""").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1), "src rows missing from dest")
            }
            st.executeQuery(
                """SELECT COUNT(*) FROM (
                     SELECT id FROM copy_dest MINUS SELECT id FROM copy_src)""").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1), "dest must not contain extra rows")
            }
            // the full 8000+ char CLOB survived byte-for-byte, not a truncation
            st.executeQuery(
                """SELECT DBMS_LOB.COMPARE(s.notes, d.notes)
                     FROM copy_src s JOIN copy_dest d ON s.id = d.id WHERE s.id = 1""").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1), "CLOB content differs between src and dest")
            }
            // TSLTZ instant identity for the two non-null rows, same session
            st.executeQuery(
                """SELECT COUNT(*) FROM copy_src s JOIN copy_dest d ON s.id = d.id
                    WHERE (s.seen_at IS NULL AND d.seen_at IS NULL)
                       OR s.seen_at = d.seen_at""").use { rs ->
                rs.next()
                assertEquals(3, rs.getInt(1), "TSLTZ values must match row-for-row")
            }
        }
    }
}
