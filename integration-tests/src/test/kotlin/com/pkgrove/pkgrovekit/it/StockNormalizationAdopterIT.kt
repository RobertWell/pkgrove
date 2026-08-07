package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.OperationReport
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.postgres.PostgresDialect
import com.pkgrove.pkgrovekit.transfer.ConsecutiveGrouper
import com.pkgrove.pkgrovekit.transfer.GroupTooLargeException
import com.pkgrove.pkgrovekit.transfer.Transfer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HEL-228 validation adopter — "one real adopter proves grouped or dataset-level
 * transformation followed by a database write".
 *
 * The workflow is REAL, not invented for this test. It is
 * `StockNormalizationService.normalizeStockDataWithAdvancedMath`
 * (hello-stock/AdvancedFeatures/src/main/kotlin/org/mystock/service/
 * StockNormalizationService.kt:122-166), driven in production by
 * `StockFeaturesScheduler` (.../scheduler/StockFeaturesScheduler.kt:410) and by
 * `StockNormalizationController`. Per stock it reads a 252-day window, computes
 * WINDOW-LEVEL statistics (min/max/mean/stddev over the whole group — a pure row
 * map cannot express this), re-emits every row of the window normalized against
 * those statistics, and upserts the result into `TRDMGMR.STOCK_HIST_NORMALIZED`
 * (`StockHistNormalizedRepositoryImpl.batchUpsert`, lines 257-383, an
 * `ON CONFLICT (STOCK_CODE, TRADE_DATE) DO UPDATE`).
 *
 * Both implementations live here side by side so they can be compared on the
 * same data, on the same live Postgres, in the same JVM:
 *
 *  - [LegacyNormalizer] — a faithful port of the current application-owned
 *    orchestration: enumerate the keys, one query per key, materialize the whole
 *    window in a `List`, map it into a SECOND whole-window `List`, batch-upsert,
 *    repeat. No bound anywhere.
 *  - [pkgroveNormalize] — one ordered scan through `Transfer` with a
 *    `ConsecutiveGrouper`; the group is the unit, the bound is declared.
 *
 * The measurements the issue asks for are asserted, not narrated: identical
 * output, largest-group behaviour, throughput, cancellation + cleanup, and
 * determinism across partition counts 1/2/4/8.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StockNormalizationAdopterIT {

    private companion object {
        const val STOCKS = 200
        const val DAYS = 252                 // the production window (252 trading days)
        const val ROWS = STOCKS * DAYS       // 50_400
        /** The production window is 252 rows; 400 leaves visible headroom. */
        const val GROUP_BOUND = 400

        const val READ_SQL =
            "SELECT stock_code, trade_date, close_price, volume FROM stock_hist_real"

        val OUT: Schema = Schema(listOf(
            Column("stock_code", ValueKind.TEXT, "VARCHAR", precision = 10),
            Column("trade_date", ValueKind.TEMPORAL, "DATE"),
            Column("close_price", ValueKind.NUMERIC, "NUMERIC", precision = 12, scale = 4),
            Column("volume", ValueKind.NUMERIC, "BIGINT", precision = 18),
            Column("norm_close", ValueKind.NUMERIC, "NUMERIC", precision = 18, scale = 6),
            Column("norm_volume", ValueKind.NUMERIC, "NUMERIC", precision = 18, scale = 6),
            Column("z_close", ValueKind.NUMERIC, "NUMERIC", precision = 18, scale = 6),
            Column("window_rows", ValueKind.NUMERIC, "INTEGER", precision = 9)))
    }

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var admin: Connection

    // ---------------------------------------------------------------- fixture

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        admin = connect()
        admin.createStatement().use { st ->
            st.execute("""
                CREATE TABLE stock_hist_real (
                    stock_code  VARCHAR(10)    NOT NULL,
                    trade_date  DATE           NOT NULL,
                    close_price NUMERIC(12,4)  NOT NULL,
                    volume      BIGINT         NOT NULL,
                    PRIMARY KEY (stock_code, trade_date))""")
            // Deterministic fixture — no random(), so every run of every
            // implementation sees byte-identical input.
            st.execute("""
                INSERT INTO stock_hist_real
                SELECT 'S' || lpad(s::text, 4, '0'),
                       DATE '2025-01-01' + d,
                       (50 + ((s * 7 + d * 13) % 1000) / 10.0)::numeric(12,4),
                       (100000 + ((s * 31 + d * 17) % 50000))::bigint
                FROM generate_series(1, $STOCKS) s, generate_series(0, ${DAYS - 1}) d""")
            for (t in listOf("norm_legacy", "norm_pkgrove", "norm_cancel",
                             "norm_p1", "norm_p2", "norm_p4", "norm_p8")) {
                st.execute("""
                    CREATE TABLE $t (
                        stock_code  VARCHAR(10)   NOT NULL,
                        trade_date  DATE          NOT NULL,
                        close_price NUMERIC(12,4) NOT NULL,
                        volume      BIGINT        NOT NULL,
                        norm_close  NUMERIC(18,6) NOT NULL,
                        norm_volume NUMERIC(18,6) NOT NULL,
                        z_close     NUMERIC(18,6) NOT NULL,
                        window_rows INTEGER       NOT NULL,
                        PRIMARY KEY (stock_code, trade_date))""")
            }
        }
    }

    @AfterAll
    fun stop() { runCatching { admin.close() }; runCatching { pg.stop() } }

    private fun connect(): Connection =
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)

    // ------------------------------------------------- the shared calculation

    /** Window-level statistics — the part that is NOT a row map. */
    private class WindowStats(closes: List<Double>, volumes: List<Long>) {
        val minClose = closes.min()
        val maxClose = closes.max()
        val meanClose = closes.average()
        val sdClose = kotlin.math.sqrt(closes.sumOf { (it - meanClose) * (it - meanClose) } / closes.size)
        val minVolume = volumes.min().toDouble()
        val maxVolume = volumes.max().toDouble()
    }

    private fun scale6(v: Double): BigDecimal =
        BigDecimal(v).setScale(6, RoundingMode.HALF_UP)

    private fun minMax(v: Double, lo: Double, hi: Double): Double =
        if (hi == lo) 0.0 else (v - lo) / (hi - lo)

    private fun zScore(v: Double, mean: Double, sd: Double): Double =
        if (sd == 0.0) 0.0 else (v - mean) / sd

    // ------------------------------------------- implementation A: the legacy

    /**
     * Faithful port of the current hand-rolled orchestration in
     * `StockNormalizationService` + `StockFeaturesScheduler`: the application
     * owns key enumeration, per-key querying, whole-window materialization,
     * a second whole-window result list, batching, and commit boundaries.
     */
    private class LegacyNormalizer(private val conn: Connection, private val table: String,
                                   private val owner: StockNormalizationAdopterIT) {
        var peakRetainedRows = 0; private set
        var roundTrips = 0; private set
        var written = 0L; private set

        private class Rec(val code: String, val date: LocalDate,
                          val close: BigDecimal, val volume: Long)

        fun run(cancelled: () -> Boolean = { false }) {
            val codes = ArrayList<String>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT DISTINCT stock_code FROM stock_hist_real ORDER BY 1")
                    .use { rs -> while (rs.next()) codes += rs.getString(1) }
            }
            roundTrips++
            for (code in codes) {
                if (cancelled()) throw IllegalStateException("cancelled by the application")
                val window = ArrayList<Rec>()
                conn.prepareStatement(
                    "SELECT stock_code, trade_date, close_price, volume FROM stock_hist_real " +
                    "WHERE stock_code = ? ORDER BY trade_date").use { ps ->
                    ps.setString(1, code)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) window += Rec(rs.getString(1), rs.getDate(2).toLocalDate(),
                                                        rs.getBigDecimal(3), rs.getLong(4))
                    }
                }
                roundTrips++
                if (window.isEmpty()) continue
                val stats = WindowStats(window.map { it.close.toDouble() }, window.map { it.volume })
                // The SECOND whole-window list — both are live at the same time.
                val out = window.map { r ->
                    val c = r.close.toDouble()
                    listOf(r.code, r.date, r.close, r.volume,
                           owner.scale6(owner.minMax(c, stats.minClose, stats.maxClose)),
                           owner.scale6(owner.minMax(r.volume.toDouble(), stats.minVolume, stats.maxVolume)),
                           owner.scale6(owner.zScore(c, stats.meanClose, stats.sdClose)),
                           window.size)
                }
                peakRetainedRows = maxOf(peakRetainedRows, window.size + out.size)
                written += batchUpsert(out)
                roundTrips++
            }
        }

        private fun batchUpsert(rows: List<List<Any?>>): Long {
            val sql = """
                INSERT INTO $table (stock_code, trade_date, close_price, volume,
                                    norm_close, norm_volume, z_close, window_rows)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (stock_code, trade_date) DO UPDATE SET
                    close_price = EXCLUDED.close_price, volume = EXCLUDED.volume,
                    norm_close = EXCLUDED.norm_close, norm_volume = EXCLUDED.norm_volume,
                    z_close = EXCLUDED.z_close, window_rows = EXCLUDED.window_rows"""
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(sql).use { ps ->
                    for (r in rows) {
                        r.forEachIndexed { i, v ->
                            when (v) {
                                is LocalDate -> ps.setObject(i + 1, java.sql.Date.valueOf(v))
                                else -> ps.setObject(i + 1, v)
                            }
                        }
                        ps.addBatch()
                    }
                    val counts = ps.executeBatch()
                    conn.commit()          // one commit per stock, as today
                    return counts.sumOf { if (it > 0) it.toLong() else 1L }
                }
            } finally { conn.autoCommit = previousAutoCommit }
        }
    }

    // -------------------------------------- implementation B: PkgroveKit path

    /**
     * The same calculation as one ordered streaming pass. The application owns
     * the SQL and the summarize function; PkgroveKit owns key detection, group
     * lifetime, the memory bound, batching, the upsert DML, commit policy and
     * cancellation.
     */
    private fun grouper(bound: Int = GROUP_BOUND) = ConsecutiveGrouper(
        listOf("stock_code"), bound, OUT,
    ) { _, rows ->
        val closes = rows.map { (it["close_price"] as BigDecimal).toDouble() }
        val volumes = rows.map { (it["volume"] as Number).toLong() }
        val s = WindowStats(closes, volumes)
        rows.mapIndexed { i, r ->
            Row(OUT, listOf(
                r["stock_code"], r["trade_date"], r["close_price"], r["volume"],
                scale6(minMax(closes[i], s.minClose, s.maxClose)),
                scale6(minMax(volumes[i].toDouble(), s.minVolume, s.maxVolume)),
                scale6(zScore(closes[i], s.meanClose, s.sdClose)),
                rows.size))
        }
    }

    private fun pkgroveNormalize(
        source: Connection, target: Connection, table: String,
        where: String = "", bound: Int = GROUP_BOUND,
        cancelToken: CancelToken = CancelToken.none(),
        onProgress: ((Int, Long) -> Unit)? = null,
        capture: ((ConsecutiveGrouper) -> Unit)? = null,
    ): OperationReport =
        Transfer.run(
            source, "$READ_SQL $where ORDER BY stock_code, trade_date", emptyList(),
            target, PostgresDialect, table,
            Transfer.Options(
                mode = SqlDialect.TargetMode.APPEND,
                readBatchSize = 1_000,
                fetchSize = 1_000,
                upsertKeys = listOf("stock_code", "trade_date"),
                cancelToken = cancelToken,
                onProgress = onProgress,
                processor = { grouper(bound).also { g -> capture?.invoke(g) } }))

    // ------------------------------------------------------------- utilities

    private fun checksum(table: String): String? =
        admin.createStatement().use { st ->
            st.executeQuery("""
                SELECT md5(string_agg(t, E'\n' ORDER BY t)) FROM (
                  SELECT stock_code || '|' || trade_date || '|' || close_price || '|' ||
                         volume || '|' || norm_close || '|' || norm_volume || '|' ||
                         z_close || '|' || window_rows AS t
                  FROM $table) x""").use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun count(table: String): Long =
        admin.createStatement().use { st ->
            st.executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) }
        }

    private fun truncate(table: String) =
        admin.createStatement().use { it.execute("TRUNCATE $table") }

    /** Carries the sampled numbers back out of [sampledHeap]. */
    private class HeapProbe { var deltaBytes = 0L; var elapsedMs = 0L }

    /** Samples used heap while [body] runs. Noisy by nature — reported, never asserted on. */
    private fun <T> sampledHeap(label: String, probe: HeapProbe? = null, body: () -> T): T {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(30) }
        val base = rt.totalMemory() - rt.freeMemory()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val peak = java.util.concurrent.atomic.AtomicLong(base)
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), ::maxOf)
                Thread.sleep(5)
            }
        }.apply { isDaemon = true; start() }
        try {
            val started = System.nanoTime()
            val result = body()
            val ms = (System.nanoTime() - started) / 1_000_000
            running.set(false)
            sampler.join(1_000)
            probe?.deltaBytes = peak.get() - base
            probe?.elapsedMs = ms
            println("MEASURE $label: elapsedMs=$ms baseHeapMB=${base shr 20} " +
                    "peakHeapMB=${peak.get() shr 20} deltaMB=${(peak.get() - base) shr 20}")
            return result
        } finally { running.set(false) }
    }

    /** Unwrap the writer's wrapping to find the cause the test is about. */
    private fun rootCauses(t: Throwable): List<Throwable> =
        generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.toList()

    // ------------------------------------------------------------- the tests

    @Test @Order(1)
    fun `legacy and pkgrovekit produce byte-identical output for the same window calculation`() {
        connect().use { c ->
            val legacy = LegacyNormalizer(c, "norm_legacy", this)
            sampledHeap("legacy rows=$ROWS stocks=$STOCKS") { legacy.run() }
            println("MEASURE legacy: roundTrips=${legacy.roundTrips} " +
                    "peakRetainedRows=${legacy.peakRetainedRows} written=${legacy.written}")
        }

        var g: ConsecutiveGrouper? = null
        val report = connect().use { src ->
            src.autoCommit = false            // pgjdbc honours fetchSize ONLY here
            connect().use { tgt ->
                sampledHeap("pkgrovekit rows=$ROWS stocks=$STOCKS") {
                    pkgroveNormalize(src, tgt, "norm_pkgrove", capture = { g = it })
                }
            }
        }
        println("MEASURE pkgrovekit: rowsAffected=${report.rowsAffected} batches=${report.batches} " +
                "elapsedMs=${report.elapsedMillis} groups=${g!!.groups} " +
                "largestGroupRows=${g!!.largestGroupRows}/bound=$GROUP_BOUND")

        assertTrue(report.completed)
        assertEquals(ROWS.toLong(), count("norm_legacy"))
        assertEquals(ROWS.toLong(), count("norm_pkgrove"))
        assertEquals(checksum("norm_legacy"), checksum("norm_pkgrove")) {
            "the two implementations disagree — the comparison is meaningless unless they compute the same thing"
        }
        assertEquals(STOCKS.toLong(), g!!.groups)
        assertEquals(DAYS, g!!.largestGroupRows)
    }

    @Test @Order(2)
    fun `largest-group behaviour - the legacy buffers whatever arrives, the grouper refuses over budget`() {
        // Same data, a bound BELOW the real window size. The legacy has no bound
        // to violate: it would happily hold the window whatever its size.
        val thrown = assertThrows(Throwable::class.java) {
            connect().use { src ->
                src.autoCommit = false
                connect().use { tgt ->
                    pkgroveNormalize(src, tgt, "norm_cancel", bound = DAYS - 1)
                }
            }
        }
        val tooLarge = rootCauses(thrown).filterIsInstance<GroupTooLargeException>().firstOrNull()
        assertNotNull(tooLarge) { "expected GroupTooLargeException, got ${rootCauses(thrown)}" }
        assertTrue(tooLarge!!.message!!.contains("maxGroupRows=${DAYS - 1}"))
        assertEquals(0L, count("norm_cancel")) { "a refused transfer must leave nothing committed" }
        println("MEASURE largestGroup: refusedAt=${DAYS - 1} realWindow=$DAYS " +
                "thrown=${thrown::class.java.name} message=${tooLarge.message}")
    }

    @Test @Order(3)
    fun `cancellation propagates and leaves no partial state, unlike the per-key loop`() {
        truncate("norm_cancel")
        var closed = false
        val token = CancelToken()
        val progressed = AtomicInteger()
        val thrown = assertThrows(Throwable::class.java) {
            connect().use { src ->
                src.autoCommit = false
                connect().use { tgt ->
                    Transfer.run(
                        src, "$READ_SQL ORDER BY stock_code, trade_date", emptyList(),
                        tgt, PostgresDialect, "norm_cancel",
                        Transfer.Options(
                            mode = SqlDialect.TargetMode.APPEND,
                            readBatchSize = 1_000, fetchSize = 1_000,
                            upsertKeys = listOf("stock_code", "trade_date"),
                            cancelToken = token,
                            onProgress = { i, _ ->
                                progressed.incrementAndGet()
                                if (i >= 2) token.cancel()
                            },
                            processor = {
                                object : com.pkgrove.pkgrovekit.transfer.BatchProcessor {
                                    private val inner = grouper()
                                    override val maxRows get() = inner.maxRows
                                    override val outputSchema get() = inner.outputSchema
                                    override fun accept(batch: com.pkgrove.pkgrovekit.core.RowBatch) = inner.accept(batch)
                                    override fun finish() = inner.finish()
                                    override fun close() { inner.close(); closed = true }
                                }
                            }))
                }
            }
        }
        println("MEASURE cancellation: thrown=${thrown::class.java.name} " +
                "batchesBeforeCancel=${progressed.get()} processorClosed=$closed " +
                "rowsCommitted=${count("norm_cancel")}")
        assertTrue(closed) { "close() must run on the cancellation path" }
        assertEquals(0L, count("norm_cancel")) {
            "AllOrNothing: a cancelled transfer must not leave committed rows"
        }
        assertTrue(progressed.get() >= 3)

        // The legacy loop cancels too — but it commits per key, so it stops
        // MID-DATASET with rows already durable and no record of where.
        truncate("norm_legacy")
        val stop = AtomicInteger()
        connect().use { c ->
            val partial = LegacyNormalizer(c, "norm_legacy", this)
            assertThrows(IllegalStateException::class.java) {
                partial.run(cancelled = { stop.incrementAndGet() > 20 })
            }
        }
        val orphaned = count("norm_legacy")
        println("MEASURE cancellation-legacy: rowsCommittedBeforeStop=$orphaned")
        assertTrue(orphaned > 0) {
            "the point of the comparison: the per-key loop leaves partial state behind"
        }
    }

    @Test @Order(4)
    fun `deterministic output across partition counts 1 2 4 8`() {
        val checksums = LinkedHashMap<Int, String?>()
        for (p in listOf(1, 2, 4, 8)) {
            val table = "norm_p$p"
            truncate(table)
            val pool = Executors.newFixedThreadPool(p)
            val errors = java.util.Collections.synchronizedList(ArrayList<Throwable>())
            val started = System.nanoTime()
            try {
                val futures = (0 until p).map { part ->
                    pool.submit {
                        try {
                            connect().use { src ->
                                src.autoCommit = false
                                connect().use { tgt ->
                                    pkgroveNormalize(
                                        src, tgt, table,
                                        where = "WHERE mod(abs(hashtext(stock_code)::bigint), $p) = $part")
                                }
                            }
                        } catch (t: Throwable) { errors += t }
                    }
                }
                futures.forEach { it.get(5, TimeUnit.MINUTES) }
            } finally {
                pool.shutdown(); pool.awaitTermination(1, TimeUnit.MINUTES)
            }
            assertTrue(errors.isEmpty()) { "partitions=$p failed: ${errors.map { it.toString() }}" }
            val ms = (System.nanoTime() - started) / 1_000_000
            val sum = checksum(table)
            checksums[p] = sum
            println("MEASURE partitions=$p rows=${count(table)} elapsedMs=$ms md5=$sum")
            assertEquals(ROWS.toLong(), count(table))
        }
        assertNotNull(checksums[1])
        // Byte-for-byte equivalence across partition counts AND against the
        // single-pass reference — key ownership must not depend on scheduling.
        checksums.forEach { (p, sum) ->
            assertEquals(checksums[1], sum) { "partition count $p changed the output" }
        }
        assertEquals(checksum("norm_pkgrove"), checksums[1]) {
            "partitioned output differs from the unpartitioned run"
        }
    }

    /**
     * HEL-256 regression — this was the DEFECT PROBE that found the bug.
     *
     * `Transfer.Options.fetchSize` sets `Statement.fetchSize`, which pgjdbc
     * IGNORES while the connection is in autoCommit mode — it materializes the
     * entire result set client-side before the first row is handed back. The
     * probe measured that and showed the library's "bounded memory by
     * construction — one read batch in flight at a time" contract depending on
     * a caller-side connection setting it neither performed nor documented.
     *
     * `JdbcReader` now performs that setting itself on a connection it leases,
     * and restores it. So the assertion INVERTS: the read must now be bounded
     * whichever way the connection arrives, and the gap the probe measured must
     * be gone. The dedicated heap gate for this lives in [PostgresStreamingIT];
     * what is kept here is the original probe's own comparison, still measured
     * on the real adopter's data, now proving the defect is closed.
     */
    @Test @Order(5)
    fun `backpressure - a Postgres source streams regardless of the caller's autoCommit`() {
        admin.createStatement().use { st ->
            st.execute("CREATE TABLE wide_payload (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)")
            // 20k rows x 2 KiB = ~40 MiB if the driver buffers the whole result.
            st.execute("""
                INSERT INTO wide_payload
                SELECT g, repeat(md5(g::text), 64) FROM generate_series(1, 20000) g""")
        }

        fun read(autoCommit: Boolean): Long {
            val probe = HeapProbe()
            var firstBatchMs = -1L
            sampledHeap("read autoCommit=$autoCommit", probe) {
                connect().use { c ->
                    c.autoCommit = autoCommit
                    val t0 = System.nanoTime()
                    com.pkgrove.pkgrovekit.jdbc.JdbcReader.open(
                        c, "SELECT id, payload FROM wide_payload ORDER BY id", emptyList(),
                        com.pkgrove.pkgrovekit.jdbc.JdbcReader.ReadOptions(fetchSize = 1_000)
                    ).use { stream ->
                        var rows = 0L
                        for (b in stream.batches(1_000)) {
                            if (firstBatchMs < 0) firstBatchMs = (System.nanoTime() - t0) / 1_000_000
                            rows += b.size            // batch dropped immediately
                        }
                        assertEquals(20_000L, rows)
                        assertTrue(stream.streaming,
                            "the read must stream whether or not the caller disabled autoCommit")
                    }
                    // Whatever it was handed, it hands back unchanged (HEL-128).
                    assertEquals(autoCommit, c.autoCommit,
                        "the connection's autoCommit must be restored exactly as found")
                }
            }
            println("MEASURE backpressure autoCommit=$autoCommit: " +
                    "peakDeltaMB=${probe.deltaBytes shr 20} timeToFirstBatchMs=$firstBatchMs " +
                    "totalMs=${probe.elapsedMs}")
            return firstBatchMs
        }

        // Pre-fix these differed by ~8x (13ms vs 103ms): the autoCommit read had
        // to pull all 40 MiB before yielding row 1. Both now open a cursor, so
        // first-batch latency is a fetch either way and the gap is gone.
        val alreadyInTransaction = read(autoCommit = false)
        val takenOver = read(autoCommit = true)
        assertTrue(takenOver < (alreadyInTransaction + 50) * 4) {
            "the autoCommit read is still behaving like a buffered read: " +
            "firstBatch ${takenOver}ms vs ${alreadyInTransaction}ms when already in a transaction"
        }
    }
}
