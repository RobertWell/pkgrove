# PkgroveKit resource ownership and lifecycle (HEL-128)

> PkgroveKit borrows resources for a declared scope, guarantees cleanup for
> every completion path, and never closes infrastructure it does not own.

## Ownership modes (implemented: `Databases`)

| Mode | Who closes what | API |
|---|---|---|
| **Application-owned** | PkgroveKit borrows + returns connections (`close()` = pool return per the pool contract); the `DataSource`/pool itself is NEVER closed by PkgroveKit | `Databases.build { applicationOwned(Key, pool) }` |
| **PkgroveKit-managed** | the registry is `AutoCloseable`; managed resources close on `close()`, reverse registration order, idempotent | `managed(Key, ds, closer)` |
| **Caller-owned** | never enters the registry; flows through `TransactionPolicy.JoinExisting` — PkgroveKit never commits/rolls back/closes it | see docs/TRANSACTIONS.md |

Typed identities: `object SalesOracle : DatabaseKey("sales-oracle")` — many
instances of one adapter type coexist; duplicate registration fails at
build time; keys (never credentials, pools, or connections) are what a task
payload may carry.

## Leases, budgets, and failure semantics (implemented)

- `withConnection(key, cancel) { c -> ... }`: borrow → run → return on
  success, failure, and cancellation. A connection that failed mid-explicit-
  transaction is **invalidated** (rollback + close + `discardedConnections`
  metric), never returned as silently healthy.
- Per-key `maxConnections` budget (fair semaphore) bounds concurrency
  regardless of how many parallel branches a workflow has. Exhaustion →
  `AcquisitionTimeoutException` after the configured timeout: bounded and
  actionable, never a hung workflow. Waiters poll in slices so a
  `CancelToken` cancels a blocked acquisition promptly (acquiring nothing,
  releasing nothing).
- **Multi-database acquisition** (`withConnections`): always acquires in
  deterministic key-name order — every acquisition in the process orders
  identically, so circular waits cannot form; any failure releases every
  connection already obtained.
- Metrics (`metrics()`): active leases, waiting, timed-out acquisitions,
  discarded connections — plain values, no metrics-vendor coupling. Leak
  tests assert `open == 0 && activeLeases == 0` deterministically.

## Scoped model (design contract for the executor layer — HEL-125)

```
Runtime scope (Databases)
  └── Workflow execution scope
       └── Task-attempt scope
            ├── Connection lease        (withConnection)
            ├── Transaction scope       (TransactionPolicy)
            ├── Statement scope         (JdbcReader/Writer internals)
            ├── Result-stream scope     (RowStream: use{}-bound)
            └── Batch-writer scope
```

Children close before parents. `JdbcReader.RowStream` is AutoCloseable and
lambda/`use{}`-scoped by convention: **never** let a lazy row sequence escape
the lease that owns its connection — the executor materializes per-batch and
hands `RowBatch` values (plain data) across scope boundaries, not live
cursors. Retries always start a fresh task-attempt scope.

## Streaming and backpressure rules

The transfer engine reads ONE bounded batch at a time (`readBatchSize`) and
writes it before pulling the next — the source cursor advances only as fast
as the target accepts, so a slow target throttles reads instead of growing
memory. Buffers are the batch: bounded and configurable. Consumer
close/cancel closes statement + result set and returns the lease. Any future
async executor must preserve exactly this property (bounded in-flight
batches), and expose completion vs abandonment distinctly.

### The bound is enforced, not assumed (HEL-256)

All of the above presumes the driver is actually streaming. `Statement.fetchSize`
is only a HINT, and the drivers that need more than it ignore it **silently** —
the symptom is heap proportional to the result set, never an error. So every
read consults its source's `StreamingContract` and acts on it.

`StreamingContract` (in `pkgrovekit-jdbc`) is the single place the per-dialect
requirements are written down; `SqlDialect.streaming` declares each adapter's,
and when no dialect is supplied the contract is detected from the driver itself
via `DatabaseMetaData.getDatabaseProductName`. Product name is the right key
because the buffering behavior belongs to the **driver**: anything reached
through pgjdbc reports `PostgreSQL` and buffers like pgjdbc.

| Dialect | Needs to stream | Consequence if unmet |
|---|---|---|
| PostgreSQL | `autoCommit = false` (+ `fetchSize > 0`, `TYPE_FORWARD_ONLY`, single statement) | driver buffers the ENTIRE result set client-side |
| Oracle | nothing — ojdbc applies `fetchSize` directly, overriding row-prefetch (10) | n/a |
| MySQL / MariaDB | `fetchSize = Integer.MIN_VALUE` on a forward-only read-only statement (or a connection built with `useCursorFetch=true`) | driver buffers the entire result set |
| DuckDB | nothing — in-process, no client/server boundary | n/a |

A requirement satisfiable on the STATEMENT (MySQL's sentinel) is simply applied:
the statement is ours. A requirement on the CONNECTION (Postgres' autoCommit)
is subject to ownership, and this is where HEL-128 binds:

- **`LEASED`** (default) — PkgroveKit borrowed this connection for the read's
  scope, so it takes `autoCommit` over and **restores it exactly as found** on
  success, exception, and cancellation. Same contract `JdbcBatchWriter` has
  always had on the write side. Safe by construction: the take-over happens
  only when `autoCommit` was already `true`, which means no caller transaction
  was in flight, so the read transaction — and the rollback that ends it —
  cannot destroy anything of the caller's.
- **`CALLER_OWNED`** — a connection whose transaction the caller owns
  (JTA-enlisted, Spring-bound, `JoinExisting`). Settings are read, never
  written. If the driver then cannot stream, the read throws
  `StreamingUnavailableException` at open naming the setting and both fixes,
  rather than buffering silently. Note this is usually a non-event: such a
  connection is already out of autocommit, which is exactly what streaming
  needs — the refusal fires only for the contradictory case.
- **`SHARED_WITH_WRITER`** — source and target are one physical connection, so
  the writer's commit would close a server-side cursor mid-stream. Streaming is
  impossible at any price here, so the read proceeds buffered and emits a
  `not-streaming` `DataWarning` onto the `OperationReport`. Use separate source
  and target connections to stream. `Transfer` selects this mode automatically
  when it sees `source === target`.

Proof, not assertion: `PostgresStreamingIT` reads a 195 MiB result set through a
connection at its driver default and measures the **live** (post-collection)
heap mid-scan. Pre-fix that measured 214 MiB retained with 698 ms to first
batch; after, 2 MiB and 43 ms.

## DuckDB lifecycle notes (adapter-specific, kept in the adapter)

- **In-memory** (`jdbc:duckdb:`) is per-connection: the connection IS the
  database. Register it PkgroveKit-managed with the owning connection held for
  the runtime scope, or the dataset dies with a returned lease. Writers and
  a caller-owned transaction must share that same connection object.
- **File-backed** databases support multiple connections in one process;
  managed shutdown closes deterministically (flush on close).
- Temp tables/session state never outlive their connection — never cache
  them across leases.

## Distributed-worker rules (design; applies to any future distributed backend)

Task payloads carry `DatabaseKey` names only — never credentials,
DataSources, connections, or pools; each worker resolves keys against its
LOCAL trusted registry. Worker leasing ≠ connection leasing. Worker loss →
local runtime cleanup + transaction recovery per the declared
`TransactionPolicy`/`RetrySafety`; completion records are written only after
resource cleanup and transaction outcome recording. Retries get a fresh
attempt scope with zero inherited resources.
