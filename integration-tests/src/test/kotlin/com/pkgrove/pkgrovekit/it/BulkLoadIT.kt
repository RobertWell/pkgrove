package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.BulkLoadException
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.postgres.PostgresCopyLoader
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.Transfer
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

/**
 * HEL-161 acceptance: the native bulk paths (Postgres COPY, DuckDB Appender)
 * against LIVE engines at benchmark scale (100k rows) — identical outcomes to
 * the batched path, measured speedup, and all-or-nothing failure atomicity.
 * Skipped without Docker (Postgres); the DuckDB half is in-process.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkLoadIT {

    private val rows = 100_000L

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var pgc: Connection
    private lateinit var duck: Connection

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        pgc = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        duck = DriverManager.getConnection("jdbc:duckdb:")
        // benchmark corpus lives in DuckDB (cheap to generate) and includes the
        // CSV-hostile shapes: nulls, empty strings, commas, quotes, newlines,
        // unicode, decimals, timestamps
        duck.createStatement().use { st ->
            st.execute("""CREATE TABLE corpus AS
                SELECT range AS id,
                       CASE WHEN range % 11 = 0 THEN NULL
                            WHEN range % 13 = 0 THEN ''
                            WHEN range % 17 = 0 THEN 'a,"quoted"' || chr(10) || 'line-' || range
                            ELSE '標籤-' || range END AS label,
                       CASE WHEN range % 7 = 0 THEN NULL
                            -- outer CAST: DuckDB division returns DOUBLE; we want the
                            -- DECIMAL path (appendBigDecimal / CSV toPlainString) exercised
                            ELSE CAST(CAST(range AS DECIMAL(18,4)) / 3 AS DECIMAL(18,4)) END AS price,
                       TIMESTAMP '2026-01-01 00:00:00' + INTERVAL (range % 10000) MINUTE AS ts,
                       range % 2 = 0 AS ok
                FROM range($rows)""")
        }
    }

    @AfterAll
    fun stop() {
        runCatching { duck.close() }
        runCatching { pgc.close() }
        runCatching { pg.stop() }
    }

    private fun pgChecksum(table: String): List<Any> =
        pgc.createStatement().use { st ->
            val rs = st.executeQuery("""
                SELECT count(*), count(label), count(price),
                       coalesce(sum(id), 0)::text,
                       coalesce(sum(price), 0)::text,
                       count(*) FILTER (WHERE label = ''),
                       count(*) FILTER (WHERE label LIKE '%' || chr(10) || '%'),
                       coalesce(max(ts)::text, '')
                FROM "$table"""")
            rs.next()
            (1..8).map { rs.getObject(it) }
        }

    @Test
    fun `pg copy loads 100k rows identically to batched insert and faster`() {
        val opts = Transfer.Options(readBatchSize = 5_000, fetchSize = 5_000)

        val tBatched = System.nanoTime()
        val batched = Transfer.run(duck, "SELECT * FROM corpus", emptyList(),
                                   pgc, PostgresDialect, "dest_batched", opts)
        val batchedMs = (System.nanoTime() - tBatched) / 1_000_000

        val tBulk = System.nanoTime()
        val bulk = Transfer.run(duck, "SELECT * FROM corpus", emptyList(),
                                pgc, PostgresDialect, "dest_copy",
                                opts.copy(useBulkLoad = true))
        val bulkMs = (System.nanoTime() - tBulk) / 1_000_000

        assertTrue(batched.completed && bulk.completed)
        assertEquals(rows, batched.rowsAffected)
        assertEquals(rows, bulk.rowsAffected)
        assertTrue(bulk.warnings.none { it.code == "BULK_LOAD_UNAVAILABLE" }) {
            "COPY must actually run: ${bulk.warnings}"
        }
        // identical content, including NULL-vs-empty-string and newline fidelity
        assertEquals(pgChecksum("dest_batched"), pgChecksum("dest_copy"))

        println("HEL-161 benchmark (100k rows, DuckDB -> Postgres): " +
                "batched INSERT ${batchedMs}ms vs COPY ${bulkMs}ms " +
                "(x${"%.1f".format(batchedMs.toDouble() / bulkMs.coerceAtLeast(1))})")
        // The honest acceptance claim: COPY must not be SLOWER. On loaded CI
        // hardware a strict multiple would flake; the magnitude is printed.
        assertTrue(bulkMs <= batchedMs) {
            "COPY (${bulkMs}ms) slower than batched (${batchedMs}ms)"
        }
    }

    @Test
    fun `pg copy round-trips hostile values exactly`() {
        Transfer.run(duck, "SELECT * FROM corpus WHERE id IN (0, 13, 17, 6, 5)", emptyList(),
                     pgc, PostgresDialect, "hostile",
                     Transfer.Options(useBulkLoad = true))
        pgc.createStatement().use { st ->
            val rs = st.executeQuery(
                """SELECT id, label, price FROM "hostile" ORDER BY id""")
            val seen = mutableMapOf<Long, Pair<String?, BigDecimal?>>()
            while (rs.next()) {
                seen[rs.getLong(1)] = rs.getString(2) to (rs.getObject(3) as BigDecimal?)
            }
            assertEquals(5, seen.size)
            assertEquals(null, seen[0L]!!.first)                     // 0 % 11 = 0 -> NULL
            assertEquals("", seen[13L]!!.first)                      // empty string, NOT null
            assertEquals("a,\"quoted\"\nline-17", seen[17L]!!.first) // comma+quote+newline
            assertEquals("標籤-5", seen[5L]!!.first)                  // unicode
            assertEquals(0, BigDecimal("2").compareTo(seen[6L]!!.second!!))   // 6/3 exact
            assertEquals(null, seen[0L]!!.second)                    // 0 % 7 = 0 -> NULL price
        }
    }

    @Test
    fun `pg copy failure commits nothing`() {
        pgc.createStatement().use { st ->
            st.execute("""CREATE TABLE strict_dest (id BIGINT, label VARCHAR(5),
                          price NUMERIC(18,4), ts TIMESTAMP, ok BOOLEAN)""")
        }
        // labels exceed VARCHAR(5) -> COPY fails server-side mid-stream
        val e = assertThrows(BulkLoadException::class.java) {
            Transfer.run(duck, "SELECT * FROM corpus WHERE id BETWEEN 1 AND 1000", emptyList(),
                         pgc, PostgresDialect, "strict_dest",
                         Transfer.Options(useBulkLoad = true,
                                          mode = SqlDialect.TargetMode.APPEND))
        }
        assertEquals(false, e.report.completed)
        pgc.createStatement().use { st ->
            val rs = st.executeQuery("""SELECT count(*) FROM "strict_dest"""")
            rs.next()
            assertEquals(0L, rs.getLong(1)) { "failed COPY must leave zero rows" }
        }
        assertTrue(pgc.autoCommit) { "autoCommit must be restored after failure" }
    }

    @Test
    fun `copy loader name appears in the dialect wiring`() {
        assertEquals("postgres-copy", PostgresCopyLoader.name)
        assertEquals(PostgresCopyLoader, PostgresDialect.bulkLoader())
    }

    @Test
    fun `duckdb appender loads 100k rows from postgres identically to batched insert`() {
        // seed Postgres side once from the corpus (COPY, proven above)
        Transfer.run(duck, "SELECT * FROM corpus", emptyList(),
                     pgc, PostgresDialect, "pg_src", Transfer.Options(useBulkLoad = true))

        val opts = Transfer.Options(readBatchSize = 5_000, fetchSize = 5_000)
        val tBatched = System.nanoTime()
        val batched = Transfer.run(pgc, "SELECT * FROM pg_src", emptyList(),
                                   duck, DuckDbDialect, "duck_batched", opts)
        val batchedMs = (System.nanoTime() - tBatched) / 1_000_000

        val tBulk = System.nanoTime()
        val bulk = Transfer.run(pgc, "SELECT * FROM pg_src", emptyList(),
                                duck, DuckDbDialect, "duck_bulk",
                                opts.copy(useBulkLoad = true))
        val bulkMs = (System.nanoTime() - tBulk) / 1_000_000

        assertTrue(batched.completed && bulk.completed)
        assertEquals(rows, bulk.rowsAffected)
        assertTrue(bulk.warnings.none { it.code == "BULK_LOAD_UNAVAILABLE" }) {
            "Appender must actually run: ${bulk.warnings}"
        }
        // identical content by full checksum on the DuckDB side
        fun duckChecksum(table: String): List<Any?> =
            JdbcReader.open(duck, """
                SELECT count(*), count(label), count(price),
                       coalesce(sum(id), 0)::VARCHAR, coalesce(sum(price), 0)::VARCHAR,
                       coalesce(max(ts)::VARCHAR, '')
                FROM "$table"""").use { s -> s.toList().single().values }
        assertEquals(duckChecksum("duck_batched"), duckChecksum("duck_bulk"))

        println("HEL-161 benchmark (100k rows, Postgres -> DuckDB): " +
                "batched INSERT ${batchedMs}ms vs Appender ${bulkMs}ms " +
                "(x${"%.1f".format(batchedMs.toDouble() / bulkMs.coerceAtLeast(1))})")
        assertTrue(bulkMs <= batchedMs) {
            "Appender (${bulkMs}ms) slower than batched (${batchedMs}ms)"
        }
    }
}
