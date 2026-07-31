# RowRelay transaction policies (HEL-126)

> RowRelay never hides commit, rollback, partial completion, or retry
> semantics. Every policy states who commits, what a failure destroys, and
> whether a retry can duplicate effects.

## Selecting a policy

`TransactionalWriter.write(connection, dml, batches, policy, dialect)` runs a
target-side write under a typed `TransactionPolicy` and returns a
machine-readable `TransactionOutcome` (or throws `TransactionWriteException`
carrying the honest partial outcome).

| Policy | Commits | On failure | Retry safety | Notes |
|---|---|---|---|---|
| `Atomic` | once, at the end | everything rolls back | `SAFE_NOTHING_COMMITTED` | lock/undo footprint grows with the transfer — bound row counts operationally |
| `Chunked(rowsPerCommit)` | every N rows | open chunk rolls back; committed chunks STAY | `UNSAFE_PARTIAL_COMMITTED` + checkpoint | never reported as atomic; resume from `checkpoint.nextRow` or use idempotent writes (named-key upsert) |
| `SavepointPerBatch` | once, at the end | failed batch rolls back TO ITS SAVEPOINT; earlier batches survive and commit | per outcome | adapters report `supportsSavepoints` (Oracle: yes; DuckDB JDBC: no → fails EARLY) |
| `JoinExisting` | **never** — the caller commits | caller's decision | `CALLER_OWNED` | fails early on auto-commit connections; chunked commits inside a joined transaction are structurally rejected |
| `AutoCommit` | every statement | everything before the failure is already committed | exact accounting | explicit opt-in only; never a default for multi-batch ETL |

Defaults: the transfer engine's default remains all-or-nothing (`Atomic`
semantics). There is no silent auto-commit fallback anywhere: if a policy
cannot be established, the operation fails before processing rows.

## Read-only sources

Reads run on the caller's source connection; set it read-only /
snapshot-isolated per the source database's own capabilities. RowRelay does
not promise a universal cross-database snapshot model — DuckDB reads are
snapshot-isolated per its MVCC; Oracle statement-level consistency applies
per its versioning. Source and target lifecycles are always separate.

## The cross-database boundary — no fake global atomicity

An Oracle ↔ DuckDB transfer uses TWO independent transaction resources:

```
source snapshot/read transaction
            ↓
RowRelay bounded batches
            ↓
target atomic or chunked write transactions
```

RowRelay never claims the pair is one distributed transaction. The outcome
model keeps the sides distinct: rows read (stream metrics) vs rows committed
on the target (`TransactionOutcome`). A source that keeps moving between a
failure and a retry is the CALLER's consistency decision. XA/distributed
transactions are explicitly out of scope; any future support needs its own
design, participating-resource verification, and operational justification.

## Scheduler / retry-layer rules (HEL-125 executors, incl. any future River backend)

An execution layer may schedule, order, retry, checkpoint, and detect worker
loss — but taking a task off a queue does NOT make its database effects
exactly-once. For every effectful task the graph must know: the transaction
policy, the idempotency story (e.g. named-key upsert), retry eligibility
(`RetrySafety`), the checkpoint semantics (`TransferCheckpoint`), and whether
the task may have committed before completion was recorded (crash-after-
commit). `UNSAFE_PARTIAL_COMMITTED` means a blind full retry duplicates rows:
resume from the checkpoint or make the write idempotent. Parallel branches
never share a connection or local transaction.

## Oracle / DuckDB capability notes

- **Oracle**: full policy set incl. `SavepointPerBatch`; caller-managed and
  JDBI-handle transactions via `JoinExisting`; isolation selection stays with
  the caller's connection where the driver supports it.
- **DuckDB**: fully transaction-capable (BEGIN/COMMIT/ROLLBACK, snapshot
  isolation) — Atomic/Chunked/JoinExisting/AutoCommit all first-class. Its
  JDBC driver lacks java.sql savepoints, so `SavepointPerBatch` fails early
  with an actionable error (capability-reported, not discovered mid-write).
  In-memory DuckDB databases are per-connection: a caller-owned transaction
  and the writer must share the SAME connection object.
