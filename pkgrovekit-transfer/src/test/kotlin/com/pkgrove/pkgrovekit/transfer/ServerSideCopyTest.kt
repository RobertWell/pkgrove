package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.ConversionPolicy
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-224: native same-database server-side copy (INSERT … SELECT push-down).
 * A same-connection transfer must push down to one server-side statement (row
 * parity, no client round-trip) and fall back to client-side streaming — with a
 * visible warning — for anything that cannot be expressed as a pure
 * column-select copy.
 */
class ServerSideCopyTest {

    /** One connection is BOTH source and target — that is "same database". */
    private lateinit var db: Connection

    @BeforeEach
    fun setUp() {
        db = DriverManager.getConnection("jdbc:duckdb:")
        db.createStatement().use { st ->
            st.execute("CREATE TABLE src (id BIGINT, label VARCHAR, price DECIMAL(10,2))")
            st.execute("""INSERT INTO src
                SELECT range, '標籤-' || range, range * 1.50 FROM range(40)""")
        }
    }

    @AfterEach
    fun tearDown() = db.close()

    private fun count(table: String): Long =
        db.createStatement().use { st ->
            val rs = st.executeQuery("SELECT count(*) FROM \"$table\"")
            rs.next(); rs.getLong(1)
        }

    private fun warningCodes(report: com.pkgrove.pkgrovekit.core.OperationReport) =
        report.warnings.map { it.code }

    @Test
    fun `same-database copy pushes down to one server-side statement with row parity`() {
        val report = Transfer.run(
            db, "SELECT * FROM src WHERE id < ? ORDER BY id", listOf(25L),
            db, DuckDbDialect, "dest",
            Transfer.Options(useServerSideCopy = true))

        assertTrue(report.completed)
        assertEquals(25L, report.rowsAffected)
        assertEquals(1, report.batches, "a server-side copy is one statement, not N batches")
        assertTrue(warningCodes(report).none { it == "SERVER_SIDE_COPY_UNAVAILABLE" },
                   "the push-down must actually be taken: ${report.warnings}")
        assertEquals(25L, count("dest"))

        // fidelity: the copy went through the server, values are intact
        JdbcReader.open(db, "SELECT * FROM \"dest\" WHERE \"id\" = 7").use { s ->
            assertEquals("標籤-7", s.toList().single()["label"])
        }
    }

    @Test
    fun `rename and omit are pushed into the SELECT list`() {
        val report = Transfer.run(
            db, "SELECT id, label, price FROM src WHERE id < 5 ORDER BY id", emptyList(),
            db, DuckDbDialect, "dest",
            Transfer.Options(
                useServerSideCopy = true,
                mapping = Mapping.build { "label" mapsTo "title"; omit("price") }))

        assertTrue(report.completed)
        assertEquals(5L, report.rowsAffected)
        JdbcReader.open(db, "SELECT * FROM \"dest\" ORDER BY \"id\" LIMIT 1").use { s ->
            val row = s.toList().single()
            assertEquals("標籤-0", row["title"])          // renamed column present
            assertTrue(!row.schema.contains("price"))     // omitted column absent
        }
    }

    @Test
    fun `different databases fall back to streaming with a warning`() {
        val other = DriverManager.getConnection("jdbc:duckdb:")
        try {
            val report = Transfer.run(
                db, "SELECT * FROM src WHERE id < 10", emptyList(),
                other, DuckDbDialect, "dest",
                Transfer.Options(useServerSideCopy = true))
            assertTrue(report.completed)
            assertEquals(10L, report.rowsAffected)
            assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(report), report.warnings.toString())
            assertTrue(report.warnings.any { "different connections" in it.message })
        } finally {
            other.close()
        }
    }

    @Test
    fun `a dialect without server-side copy falls back with a warning`() {
        // wrapper reports no server-side copy; everything else delegates to DuckDB
        val noCopy = object : SqlDialect by DuckDbDialect {
            override val supportsServerSideCopy = false
        }
        val report = Transfer.run(
            db, "SELECT * FROM src WHERE id < 8", emptyList(),
            db, noCopy, "dest", Transfer.Options(useServerSideCopy = true))
        assertTrue(report.completed)
        assertEquals(8L, count("dest"))
        assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(report))
        assertTrue(report.warnings.any { "no server-side copy" in it.message })
    }

    @Test
    fun `a constant column mapping falls back with a warning`() {
        val report = Transfer.run(
            db, "SELECT id, label FROM src WHERE id < 6", emptyList(),
            db, DuckDbDialect, "dest",
            Transfer.Options(
                useServerSideCopy = true,
                mapping = Mapping.build { constant("source_tag", "batch-1") }))
        assertTrue(report.completed)
        assertEquals(6L, count("dest"))
        assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(report))
        assertTrue(report.warnings.any { "constant columns" in it.message }, report.warnings.toString())
        // the streaming fallback still wrote the constant column
        JdbcReader.open(db, "SELECT * FROM \"dest\" LIMIT 1").use { s ->
            assertEquals("batch-1", s.toList().single()["source_tag"])
        }
    }

    @Test
    fun `a row transform forces the client-side path`() {
        val report = Transfer.run(
            db, "SELECT id, label FROM src WHERE id < 30", emptyList(),
            db, DuckDbDialect, "dest",
            Transfer.Options(
                useServerSideCopy = true,
                rowTransform = { r -> if ((r["id"] as Long) % 2 == 0L) r else null }))
        assertTrue(report.completed)
        assertEquals(15L, report.rowsAffected)          // transform filtered odds client-side
        assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(report))
        assertTrue(report.warnings.any { "row transform" in it.message })
    }

    @Test
    fun `upsert keys and bulk load are refused by the push-down`() {
        // pre-create with a key so the STREAMING upsert fallback succeeds
        db.createStatement().use {
            it.execute("CREATE TABLE up (id BIGINT PRIMARY KEY, label VARCHAR)")
        }
        val upsert = Transfer.run(
            db, "SELECT id, label FROM src WHERE id < 3", emptyList(),
            db, DuckDbDialect, "up",
            Transfer.Options(useServerSideCopy = true, upsertKeys = listOf("id"),
                             mode = SqlDialect.TargetMode.APPEND))
        assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(upsert))
        assertTrue(upsert.warnings.any { "upsert keys are set" in it.message })
        assertEquals(3L, count("up"))

        db.createStatement().use { it.execute("DROP TABLE IF EXISTS dest2") }
        val bulk = Transfer.run(
            db, "SELECT id, label FROM src WHERE id < 3", emptyList(),
            db, DuckDbDialect, "dest2",
            Transfer.Options(useServerSideCopy = true, useBulkLoad = true))
        assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(bulk))
        assertTrue(bulk.warnings.any { "bulk load" in it.message })
    }

    @Test
    fun `a processor forces the client-side path`() {
        val outSchema = Schema(listOf(Column("id", com.pkgrove.pkgrovekit.core.ValueKind.NUMERIC, "BIGINT")))
        val report = Transfer.run(
            db, "SELECT id FROM src WHERE id < 4 ORDER BY id", emptyList(),
            db, DuckDbDialect, "dest",
            Transfer.Options(
                useServerSideCopy = true,
                processor = { object : BatchProcessor {
                    override val maxRows = 1_000
                    override val outputSchema = outSchema
                    override fun accept(batch: com.pkgrove.pkgrovekit.core.RowBatch) =
                        ProcessOutput.of(batch)
                } }))
        assertTrue(report.completed)
        assertTrue("SERVER_SIDE_COPY_UNAVAILABLE" in warningCodes(report))
        assertTrue(report.warnings.any { "stateful processor" in it.message })
    }

    @Test
    fun `a failing server-side copy rolls back and propagates`() {
        // APPEND into a table that does not exist: the INSERT … SELECT fails, is
        // rolled back, and the exception propagates (no partial write claimed).
        assertThrows(Exception::class.java) {
            Transfer.run(
                db, "SELECT id, label FROM src", emptyList(),
                db, DuckDbDialect, "missing_table",
                Transfer.Options(useServerSideCopy = true,
                                 mode = SqlDialect.TargetMode.APPEND))
        }
        assertThrows(Exception::class.java) { count("missing_table") }  // nothing created
    }

    @Test
    fun `SKIP conversion policy still copies the representable columns server-side`() {
        // no unrepresentable columns here, but exercise the adaptSchema path
        val report = Transfer.run(
            db, "SELECT id, label FROM src WHERE id < 12", emptyList(),
            db, DuckDbDialect, "dest",
            Transfer.Options(useServerSideCopy = true, conversionPolicy = ConversionPolicy.SKIP))
        assertTrue(report.completed)
        assertEquals(12L, report.rowsAffected)
        assertNull(report.warnings.firstOrNull { it.code == "SERVER_SIDE_COPY_UNAVAILABLE" })
    }
}
