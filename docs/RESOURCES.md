# RowRelay resource ownership and lifecycle (HEL-128)

> RowRelay borrows resources for a declared scope, guarantees cleanup for
> every completion path, and never closes infrastructure it does not own.

## Ownership modes (implemented: `Databases`)

| Mode | Who closes what | API |
|---|---|---|
| **Application-owned** | RowRelay borrows + returns connections (`close()` = pool return per the pool contract); the `DataSource`/pool itself is NEVER closed by RowRelay | `Databases.build { applicationOwned(Key, pool) }` |
| **RowRelay-managed** | the registry is `AutoCloseable`; managed resources close on `close()`, reverse registration order, idempotent | `managed(Key, ds, closer)` |
| **Caller-owned** | never enters the registry; flows through `TransactionPolicy.JoinExisting` — RowRelay never commits/rolls back/closes it | see docs/TRANSACTIONS.md |

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

## DuckDB lifecycle notes (adapter-specific, kept in the adapter)

- **In-memory** (`jdbc:duckdb:`) is per-connection: the connection IS the
  database. Register it RowRelay-managed with the owning connection held for
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
