package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.CancelToken
import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Row
import com.pkgrove.pkgrovekit.core.RowBatch
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-228: the stateful categories must do real grouped work WITHOUT losing the
 * bounded-streaming guarantee. These tests assert the BOUND, not just the
 * result — a grouping that quietly buffered the dataset would still produce
 * correct sums, so correctness alone proves nothing.
 */
class StatefulTest {

    private val src = Schema(listOf(
        Column("customer_id", ValueKind.NUMERIC, "BIGINT", precision = 18),
        Column("amount", ValueKind.NUMERIC, "DECIMAL(10,2)", precision = 10, scale = 2)))
    private val out = Schema(listOf(
        Column("customer_id", ValueKind.NUMERIC, "BIGINT", precision = 18),
        Column("total", ValueKind.NUMERIC, "DECIMAL(18,2)", precision = 18, scale = 2)))

    private lateinit var duck: Connection

    @BeforeEach fun setUp() { duck = DriverManager.getConnection("jdbc:duckdb:") }
    @AfterEach fun tearDown() { duck.close() }

    private fun row(cust: Long, amt: Long) = Row(src, listOf(cust, java.math.BigDecimal(amt)))

    private fun grouper(maxGroupRows: Int = 100) = ConsecutiveGrouper(
        listOf("customer_id"), maxGroupRows, out,
    ) { key, rows ->
        val total = rows.fold(java.math.BigDecimal.ZERO) { acc, r ->
            acc + (r["amount"] as java.math.BigDecimal)
        }
        listOf(Row(out, listOf(key[0], total)))
    }

    @Test
    fun `consecutive grouping aggregates each key once and emits on key change`() {
        val g = grouper()
        val emitted = mutableListOf<Row>()
        emitted += g.accept(RowBatch(src, listOf(row(1, 10), row(1, 5), row(2, 7)))).batches.flatMap { it.rows }
        emitted += g.accept(RowBatch(src, listOf(row(2, 3), row(3, 1)))).batches.flatMap { it.rows }
        emitted += g.finish().batches.flatMap { it.rows }

        assertEquals(listOf(1L to 15L, 2L to 10L, 3L to 1L),
            emitted.map { (it["customer_id"] as Long) to (it["total"] as java.math.BigDecimal).toLong() })
        assertEquals(3L, g.groups)
        // group 1 spanned rows within one batch, group 2 SPANNED TWO BATCHES —
        // the whole point of carrying state across the boundary
        assertEquals(2, g.largestGroupRows)
    }

    @Test
    fun `memory is bounded by the largest group, not the dataset`() {
        // 50k rows across 25k keys, 2 rows each, streamed in 500-row batches.
        // If the grouper buffered the dataset this would hold 50k rows; the
        // assertion is on the OBSERVED high-water mark, not on the output.
        val g = grouper(maxGroupRows = 10)
        var emitted = 0L
        val batches = sequence {
            var cust = 0L
            while (cust < 25_000) {
                val rows = ArrayList<Row>(500)
                repeat(250) {
                    rows += row(cust, 1); rows += row(cust, 2)
                    cust++
                }
                yield(RowBatch(src, rows))
            }
        }
        for (b in batches) emitted += g.accept(b).batches.sumOf { it.size }
        emitted += g.finish().batches.sumOf { it.size }

        assertEquals(25_000L, emitted)
        assertEquals(25_000L, g.groups)
        assertTrue(g.largestGroupRows <= 10) {
            "high-water mark ${g.largestGroupRows} exceeded the declared bound — not streaming"
        }
    }

    @Test
    fun `a group larger than its declared bound fails loudly instead of eating the heap`() {
        val g = grouper(maxGroupRows = 3)
        val ex = assertThrows(GroupTooLargeException::class.java) {
            g.accept(RowBatch(src, List(5) { row(1, 1) }))
        }
        assertTrue(ex.message!!.contains("maxGroupRows=3"))
    }

    @Test
    fun `an unordered source is rejected rather than silently split into two aggregates`() {
        val g = grouper()
        val ex = assertThrows(OutOfOrderGroupException::class.java) {
            // key 1 reappears after key 2 closed it — classic missing ORDER BY
            g.accept(RowBatch(src, listOf(row(1, 10), row(2, 5), row(1, 99))))
        }
        assertTrue(ex.message!!.contains("ORDER BY"))
    }

    @Test
    fun `BoundedRowBuffer refuses to exceed its budget`() {
        val buf = BoundedRowBuffer(2)
        buf.add(row(1, 1)); buf.add(row(1, 2))
        assertThrows(GroupTooLargeException::class.java) { buf.add(row(1, 3)) }
        assertEquals(2, buf.size)
    }

    @Test
    fun `close runs on failure and on cancellation`() {
        var closed = 0
        val exploding = object : BatchProcessor {
            override val maxRows = 10
            override fun accept(batch: RowBatch): ProcessOutput = error("boom")
            override fun close() { closed++ }
        }
        assertThrows(IllegalStateException::class.java) {
            runProcessor(exploding, sequenceOf(RowBatch(src, listOf(row(1, 1))))).toList()
        }
        assertEquals(1, closed) { "close() must run on the failure path" }

        val cancelled = CancelToken().apply { cancel() }
        var closed2 = 0
        val ok = object : BatchProcessor {
            override val maxRows = 10
            override fun accept(batch: RowBatch) = ProcessOutput.none()
            override fun close() { closed2++ }
        }
        assertThrows(Exception::class.java) {
            runProcessor(ok, sequenceOf(RowBatch(src, listOf(row(1, 1)))), cancelled).toList()
        }
        assertEquals(1, closed2) { "close() must run on the cancellation path" }
    }

    @Test
    fun `processing stays lazy - the source is not drained ahead of the sink`() {
        var produced = 0
        val g = grouper()
        val lazySource = sequence {
            repeat(1_000) { i ->
                produced++
                yield(RowBatch(src, listOf(row(i.toLong(), 1))))
            }
        }
        // take only the first 3 outputs; a non-lazy pipeline would have produced all 1000
        runProcessor(g, lazySource).take(3).toList()
        assertTrue(produced <= 5) { "source produced $produced batches for 3 outputs — not lazy" }
    }

    @Test
    fun `end-to-end through Relay - grouped rows land in a real database`() {
        duck.createStatement().use { st ->
            st.execute("CREATE TABLE sales (customer_id BIGINT, amount DECIMAL(10,2))")
            st.execute("""INSERT INTO sales
                -- // is INTEGER division in DuckDB; / is float and ::BIGINT would ROUND,
                -- silently producing 101 keys instead of 100 (fixture bug, not a library one)
                SELECT (range // 3)::BIGINT, ((range % 3) + 1)::DECIMAL(10,2) FROM range(300)""")
        }
        val report = Transfer.run(
            duck, "SELECT customer_id, amount FROM sales ORDER BY customer_id", emptyList(),
            duck, DuckDbDialect, "customer_totals",
            Transfer.Options(
                readBatchSize = 64,
                processor = { grouper(maxGroupRows = 16) }))

        assertTrue(report.completed)
        assertEquals(100L, report.rowsAffected) { "300 rows / 3 per customer = 100 groups" }
        JdbcReader.open(duck,
            "SELECT count(*) n, sum(\"total\") s FROM \"customer_totals\"").use { s ->
            val r = s.toList().single()
            assertEquals(100L, (r["n"] as Number).toLong())
            // every customer has amounts 1+2+3 = 6  ->  100 * 6 = 600
            assertEquals(600L, (r["s"] as Number).toLong())
        }
    }
}
