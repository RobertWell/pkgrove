# RowRelay functional workflow API (HEL-125)

## Shape

A workflow **definition** is immutable data — typed `DatabaseKey`s, SQL,
named parameters, composed row transforms, and sink options. No connections,
credentials, pools, or handles ever live inside a definition, which is what
makes it safe to hand to ANY executor (local or distributed) and trivially
testable.

```kotlin
val flow = Workflows
    .from(SalesOracle, "SELECT * FROM app_user WHERE updated_at >= :since",
          mapOf("since" to since))
    .filter { (it["status"] as String) == "active" }
    .transform(::normalizeCustomer)          // schema-preserving; null drops
    .to(AnalyticsFile, DuckDbDialect, "customer",
        Transfer.Options(mapping = Mapping.build { "user_name" mapsTo "customer_name" }))

val results = Workflows.SequentialExecutor.execute(listOf(flow), databases)
```

Execution acquires leases from the HEL-128 `Databases` registry (same-key
flows share one lease; cross-database flows use deterministic key-ordered
dual acquisition), applies transforms per bounded batch inside the transfer
engine (bounded memory preserved), and releases everything on every path.
Renames/constants/omissions stay in `Mapping` (by name); `transform` is for
value-level work and filtering.

## Executors

| Executor | Behavior |
|---|---|
| `SequentialExecutor` | flows run one after another — the default |
| `ParallelExecutor(n)` | independent flows fan out, capped by `n` AND by each database's lease budget; branches never share a connection (each flow leases its own) |

`FlowResult` carries the per-flow report or error — one failed flow never
aborts its siblings silently; the caller sees exactly which flows succeeded.

## Distributed backend: the executor evaluation

The full executor-architecture decision — coroutines (default), Arrow
(optional interop), Temporal/Pekko/River (evaluated seams), Flink/Jet (out of
scope) — lives in **`docs/adr/0001-workflow-executor-architecture.md`**.

**Apache River is NOT banned** (stance updated 2026-08-01, HEL-167). It is
retired in the Apache Attic with a smaller maintenance community, so it is not
the recommended default — but its lightweight discovery/leasing model is a
legitimate optional candidate. Any River/JGDMS adapter must live in a separate
optional module, add no transitive dependency to `rowrelay-core` or the default
executor, pass CVE/Java/operational review, name a maintenance owner, and prove
a real lightweight advantage before adoption. Every distributed backend — River
or otherwise — obeys the rules already encoded here: definitions carry keys not
credentials (`docs/RESOURCES.md`), retries obey `RetrySafety`/checkpoints
(`docs/TRANSACTIONS.md`), queue-take is not exactly-once, completion records
only after cleanup + transaction outcome.

## Boundaries

No DSL for arbitrary DAGs, no scheduler, no CDC — flows are linear
read→transform→write pipelines by design; compose them in application code
or an external scheduler. The workflow layer adds no new dependency (it
lives in rowrelay-transfer).
