# Choosing a transformation mode

> Push the work into SQL first. Reach for a PkgroveKit transformation only for
> what the engine cannot express — and pick the mode whose memory contract you
> can state out loud.

PkgroveKit moves and shapes data. It is **not** a query engine, and it does not
reimplement SQL aggregation, joins, windows, or sorting as a DSL. Every mode
below costs you something the database was already going to do for free, so
they are listed in the order you should reach for them.

## The ladder

| | Mode | API | Rows in → out | Retained state | Requires of the source | Fails by |
|---|---|---|---|---|---|---|
| 1 | **SQL / the engine** | your `query(...)` text | n → m | whatever the engine spills to its own temp space | nothing | the database's own error |
| 2 | **Row mapping** | `transform { }` / `Transfer.Options.rowTransform` | 1 → 0 or 1, schema preserved | none | nothing | your lambda throws → `TransferOutcome.Failed` |
| 3 | **Bounded batches** | `processBatches { }` / `Transfer.Options.processor` | one batch → 0..n | whatever your `BatchProcessor` holds, capped on input by its `maxRows` | nothing | `IllegalArgumentException` when a read batch exceeds `maxRows` |
| 4 | **Ordered grouping** | `groupConsecutiveBy(...)` | one key's consecutive rows → 0..n | ≤ `maxGroupRows` rows **+** ≤ `recentKeyMemory` keys | `ORDER BY` on the key columns | `GroupTooLargeException`, `OutOfOrderGroupException` |
| 5 | **Materialized / keyed aggregation** | — | — | — | — | **not implemented** — see below |

All of rows 2–4 stream: nothing is materialized beyond what the mode's own
declared bound allows, the source cursor advances only as fast as the target
accepts, and `close()` runs on success, failure, and cancellation. Row 1 is
bounded because PkgroveKit never sees the intermediate state at all.

## 1. Do it in SQL

**What it is.** `GROUP BY`, window functions, `ORDER BY`, joins, `DISTINCT` —
written in the source query, executed by the engine, streamed back as ordinary
rows.

**When to choose it.** By default. The database has statistics, indexes, spill
files, and a decade of tuning for exactly this; a JVM `HashMap` has none of
them. If the calculation is expressible in SQL, express it in SQL and let the
transfer be a plain transfer.

```kotlin
val monthly = relay.transfer("supplier-monthly-totals") {
    from(Warehouse) {
        query("""
            select supplier_id,
                   date_trunc('month', invoiced_at) as month,
                   sum(amount)                      as total,
                   count(*)                         as invoices
            from invoices
            where invoiced_at >= :since
            group by supplier_id, date_trunc('month', invoiced_at)
        """.trimIndent())
        bind("since", since)
    }
    to(Reporting, table = "supplier_monthly") {
        upsertBy("supplier_id", "month")
    }
}
```

**Memory.** One read batch in flight (`readBatchSize`, default 1 000).

**How it fails.** As a SQL error, before any write — `TransferOutcome.Failed`
with the driver's exception.

## 2. Row mapping — `transform { }`

**What it is.** A pure function `(Row) -> Row?` applied after the read and
before mapping. Return `null` to drop the row. Chained `transform` calls
compose in order; `filter { }` is `transform` with a predicate.

**When to choose it.** Per-row normalization, redaction, defaulting, filtering
— anything that needs only the row in front of it.

```kotlin
transform { row ->
    val price = row["price"] as BigDecimal
    if (price <= BigDecimal.ZERO) null                    // drop
    else Row(row.schema, row.values.toMutableList().also {
        it[row.schema.indexOf("symbol")] = (row["symbol"] as String).uppercase()
    })
}
```

**Memory.** None beyond the row. Fully streaming.

**Requires of the source.** Nothing.

**How it fails.** A thrown exception propagates to the outcome; it is never
swallowed. The transform **must preserve the row's schema** — a returned row
with a different schema fails the transfer. Renames, omissions, and constants
belong to the mapping (`rename` / `omit` / `Mapping`), not here.

> **The issue's prose says "1 row in, 0..n out". The code does not.**
> `rowTransform` is `(Row) -> Row?`: one row in, **zero or one** out, same
> schema. Fan-out (1 → n) and reshaping need mode 3 or 4.

## 3. Bounded batches — `processBatches { }`

**What it is.** A `BatchProcessor` sees one `RowBatch` at a time and returns a
`ProcessOutput` (`none()`, `of(batch)`, or `rows(schema, rows)`). `finish()`
flushes anything held after the last batch; `close()` always runs.

```kotlin
interface BatchProcessor {
    val maxRows: Int                       // the INPUT bound, enforced by the pipeline
    val outputSchema: Schema? get() = null // null = shape unchanged
    fun accept(batch: RowBatch): ProcessOutput
    fun finish(): ProcessOutput = ProcessOutput.none()
    fun close() {}
}
```

**When to choose it.** Work that is vectorized or amortized over a chunk —
scoring a model on 5 000 rows at once, a bulk enrichment call, an encoder that
is cheaper per batch than per row.

**A batch is not a business group.** Batch boundaries are set by
`readBatchSize`, not by your data. Two rows of the same customer can land in
different batches, and the last batch is short. If your unit of work is "all
rows for one key", you want mode 4, not this.

```kotlin
val scored = Schema(listOf(
    Column("id",    ValueKind.NUMERIC, "BIGINT"),
    Column("score", ValueKind.NUMERIC, "NUMERIC", precision = 18, scale = 6)))

to(Reporting, table = "risk_scores") {
    processBatches {
        object : BatchProcessor {
            override val maxRows = 5_000                  // ≥ readBatchSize
            override val outputSchema = scored
            override fun accept(batch: RowBatch): ProcessOutput =
                ProcessOutput.rows(scored, model.scoreAll(batch.rows).map { (id, s) ->
                    Row(scored, listOf(id, s))
                })
        }
    }
}
```

**Memory.** Bounded on the input side by `maxRows`, which the pipeline
enforces. Whatever your implementation *retains* across batches is yours to
bound — use `BoundedRowBuffer(maxRows)`, which throws `GroupTooLargeException`
rather than growing, instead of a bare `ArrayList`.

**Requires of the source.** Nothing.

**How it fails.** A read batch larger than `maxRows` throws
`IllegalArgumentException` naming both numbers ("lower
`Transfer.Options.readBatchSize` or raise the bound") — so keep
`maxRows >= readBatchSize`. Anything your `accept`/`finish` throws propagates,
and `close()` still runs.

**Reshaping.** When `outputSchema` is non-null it drives table establishment
*and* the generated INSERT/upsert — the target describes what is actually
written, not what was read.

## 4. Ordered grouping — `groupConsecutiveBy(...)`

**What it is.** Consecutive rows sharing the key columns are gathered and handed
to `summarize(key, rows)` when the key changes. The returned rows must carry the
declared `outputSchema`.

```kotlin
val normalized = Schema(listOf(
    Column("stock_code", ValueKind.TEXT,    "VARCHAR", precision = 10),
    Column("trade_date", ValueKind.TEMPORAL,"DATE"),
    Column("z_close",    ValueKind.NUMERIC, "NUMERIC", precision = 18, scale = 6)))

relay.transfer("normalize-windows") {
    from(Market) {
        query("""
            select stock_code, trade_date, close_price
            from stock_hist_real
            order by stock_code, trade_date          -- REQUIRED
        """.trimIndent())
    }
    to(Market, table = "stock_hist_normalized") {
        upsertBy("stock_code", "trade_date")          // implies APPEND mode
        groupConsecutiveBy(
            "stock_code",
            maxGroupRows = 400,                       // required: no default
            outputSchema = normalized,
        ) { _, rows ->
            val closes = rows.map { (it["close_price"] as BigDecimal).toDouble() }
            val mean = closes.average()
            val sd = sqrt(closes.sumOf { (it - mean) * (it - mean) } / closes.size)
            rows.mapIndexed { i, r ->
                Row(normalized, listOf(r["stock_code"], r["trade_date"],
                    BigDecimal(if (sd == 0.0) 0.0 else (closes[i] - mean) / sd)
                        .setScale(6, RoundingMode.HALF_UP)))
            }
        }
    }
}
```

The same thing on the advanced tier is `ConsecutiveGrouper` handed to
`Transfer.Options(processor = { ... })` — it is a `BatchProcessor`, so mode 4 is
mode 3 with the grouping already written for you.

**When to choose it.** A calculation over all rows of a key that SQL cannot
express (or cannot express readably) — a model scored per window, a
path-dependent walk, a summary that re-emits every input row. If it *is*
expressible as `GROUP BY` or a window function, go back to mode 1.

**Requires of the source.** An `ORDER BY` on exactly the key columns. Grouping
is *consecutive*, not keyed: without the sort you do not get an error, you get
mode 4's documented limitation below.

**Memory.** `maxGroupRows` rows **plus** `recentKeyMemory` keys — both set by
the caller, and **independent of how many groups the dataset has**.
`maxGroupRows` is required with no default so the budget is a decision rather
than an accident.

### The ordering guarantee, exactly

This is the part that has been wrong in this project's documentation before, so
it is stated precisely (HEL-255):

- The last **`recentKeyMemory`** closed keys are retained (default
  `ConsecutiveGrouper.DEFAULT_RECENT_KEY_MEMORY` = 10 000, roughly 1 MB at the
  ~113 bytes/key measured).
- A key that reappears **while it is still inside that window** always throws
  `OutOfOrderGroupException`, naming the missing `ORDER BY`. Nothing is written.
- A key that reappears **after more than `recentKeyMemory` other groups have
  closed is NOT detected.** Its rows are summarized as a second, separate group
  for the same key, and you get two partial aggregates with no error.

The window catches the interleaving a missing or partial `ORDER BY` actually
produces. Catching an arbitrarily distant reappearance would mean retaining
every key ever seen — memory proportional to the dataset, which is precisely the
defect this bound replaced (108 MB at 1M groups, while the per-group metric went
on reporting the bound as healthy). Widen the window if your data warrants it;
you are buying guard reach with ~113 bytes per key.

**How it fails.**

| Condition | Result |
|---|---|
| a group exceeds `maxGroupRows` | `GroupTooLargeException` naming the key and the bound; with the default `AllOrNothing` commit policy, **nothing is committed** |
| a key reappears within `recentKeyMemory` closed groups | `OutOfOrderGroupException` naming the missing `ORDER BY` |
| a key reappears beyond the window | **silently two aggregates** — the limitation above |
| `summarize` returns a row whose schema ≠ `outputSchema` | `IllegalArgumentException` |
| cancellation | `TransferOutcome.Cancelled`, `close()` ran, nothing committed under `AllOrNothing` |

**Observability.** `ConsecutiveGrouper` exposes `groups`, `emittedRows`,
`largestGroupRows` (high-water mark of the open-group buffer — compare against
`maxGroupRows` for headroom), and the two live-state figures `bufferedRows` and
`retainedKeys`, plus `recentKeyWindow`. Total live state is
`bufferedRows + retainedKeys`; `largestGroupRows` alone says nothing about total
retention. Note the processor is built by a factory the transfer owns, so to
read these you must capture the instance yourself:

```kotlin
var grouper: ConsecutiveGrouper? = null
Transfer.Options(processor = {
    ConsecutiveGrouper(listOf("stock_code"), 400, normalized) { _, rows -> summarize(rows) }
        .also { grouper = it }
})
```

## 5. Materialized / partitioned keyed aggregation — not implemented

There is **no** `aggregateBy`, no partitioned keyed aggregation, no spill-to-disk
state, and no checkpoint/restart in the code today. HEL-228 proposes them and
they are tracked separately (HEL-243 partitioned keyed aggregation, HEL-244
spill policy, HEL-245 materialized stages + checkpoint, HEL-246 stateful
observability), each gated on a named adopter.

Until one lands, aggregation over a key the source **cannot** `ORDER BY` is not
a PkgroveKit feature. Do it in SQL (mode 1), or partition the work yourself:
run one plan per key range with a `WHERE` predicate, which is how the validated
adopter demonstrates deterministic parallelism across 1/2/4/8 partitions.

## The memory bound is conditional — on streaming

Modes 2–4 claim bounded memory *given a streaming source*. That is now enforced
rather than assumed (HEL-256), but enforcement depends on who owns the source
connection, because `Statement.fetchSize` is only a hint and the drivers that
need more ignore it **silently** — the symptom is heap proportional to the
result set, never an error.

Set via `Transfer.Options.sourceConnectionOwnership` (see
[RESOURCES.md](RESOURCES.md) for the per-dialect requirements):

| Ownership | Behaviour |
|---|---|
| `LEASED` (default) | PkgroveKit satisfies the driver's streaming requirement (e.g. Postgres `autoCommit = false`) and restores the connection exactly as found on success, failure, and cancellation |
| `CALLER_OWNED` | nothing is mutated; if the driver then cannot stream, the read throws `StreamingUnavailableException` **at open** rather than buffering the whole result set. Usually a non-event — a connection genuinely inside your transaction is already out of autocommit, which is what streaming needs |
| `SHARED_WITH_WRITER` | selected automatically when source and target are the same physical connection. Streaming is impossible (the writer's commit would close the cursor), so the read stays **buffered** and emits a `not-streaming` `DataWarning` on the report. Memory is not bounded on this path |

Two practical consequences for grouped work:

- A `Relay` plan whose `from(...)` and `to(...)` name the **same**
  `DatabaseKey` runs on one leased connection and therefore takes the
  `SHARED_WITH_WRITER` path. Use separate keys (or separate databases) when the
  result set is large enough to matter, and check
  `report.warnings` for code `not-streaming`.
- On the low-level tier, `JdbcReader.RowStream.streaming` tells you directly
  whether the bound holds for that read.

## When NOT to use PkgroveKit for this

Push it into SQL when any of these is true:

- **The engine can express it.** `GROUP BY`, `sum`/`avg`/`percentile`, window
  functions, `ORDER BY`, joins, deduplication via `DISTINCT ON` / `ROW_NUMBER`.
  A grouper that only sums a column is a `GROUP BY` you paid JVM heap for.
- **The group can be arbitrarily large.** `maxGroupRows` is a hard refusal, not
  a soft target. A skewed key that occasionally has ten million rows will fail
  the transfer; the database will spill to disk and finish.
- **The key cannot be sorted cheaply.** Mode 4 requires `ORDER BY`. If that
  sort is the expensive part, aggregate in the engine and transfer the
  aggregate.
- **Source and target are the same database.** Then it is an
  `INSERT ... SELECT` and there is no transfer to perform.
- **You want a join.** PkgroveKit reads one result set. Join in the query.

Use a PkgroveKit mode when the calculation genuinely leaves SQL: JVM library
code, a model, a path-dependent walk, or a shape the engine cannot produce — and
when you also want PkgroveKit's ownership of batching, the upsert DML, the
commit boundary, cancellation, and cleanup. The validated adopter is exactly
that case: window statistics computed in Kotlin, re-emitted per row, upserted in
one ordered pass with one commit instead of one query and one commit per key.
