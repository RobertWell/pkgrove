# PostgreSQL adapter + the database-migration boundary (HEL-127)

## The adapter

`pkgrovekit-postgres` makes PostgreSQL a first-class relational adapter with
the same contract as Oracle/DuckDB — usable as source (standard ValueReader;
pgjdbc returns JDK types) and target (`PostgresDialect`):

- identifier policy: Postgres folds unquoted identifiers **down** — the
  dialect lowercases-then-quotes (the mirror of Oracle's rule, same
  determinism guarantee).
- types: TEXT/VARCHAR(n), BOOLEAN, BYTEA, SMALLINT/INTEGER/BIGINT/NUMERIC by
  precision/scale, DATE/TIME/TIMESTAMP/TIMESTAMPTZ by kind + TZ-ness.
- upsert: native `ON CONFLICT (keys) DO UPDATE` (target uniqueness on the
  keys required). Savepoints: supported (`SavepointPerBatch` works).
- the driver stays consumer-controlled (compileOnly), like every adapter.

Multiple named instances are the normal pattern (HEL-128 typed keys):

```kotlin
object SalesOracle : DatabaseKey("sales-oracle")
object ArchiveOracle : DatabaseKey("archive-oracle")
object BillingPostgres : DatabaseKey("billing-postgres")
object WorkingMemory : DatabaseKey("duck-mem")     // in-memory staging
object AnalyticsFile : DatabaseKey("duck-file")    // file-backed target
```

## What "migration" means here — and what it never will

PkgroveKit owns **data movement**, not schema evolution:

| In scope (PkgroveKit) | Out of scope (use Flyway/Liquibase/app DDL) |
|---|---|
| bulk extract with arbitrary read SQL + named params | versioned schema history / migration ledgers |
| target establishment per `TargetMode` (CREATE/REPLACE/APPEND/TEMP/FAIL_IF_EXISTS) from the *source-inferred* schema | hand-authored DDL evolution, indexes, constraints, grants |
| named mapping/renames/constants between shapes | cross-version data *semantics* rewrites |
| chunked/atomic/savepoint transaction policies + checkpoints for restartable loads | orchestration of multi-step app upgrades |
| per-migration verification: row counts, `OperationReport`/`TransactionOutcome` records, warnings | "rollback my schema" |

A typical migration workflow composes both: Flyway (or the app) establishes
the target schema version, then PkgroveKit flows move the data — e.g. the
QuerySkiff/hello-stock Oracle→Postgres experience: per-table
`Workflows.from(OldOracle, select).to(NewPostgres, PostgresDialect, table,
Options(mode = APPEND, transaction-safe policy))`, chunked with checkpoints
for the big tables, `upsertKeys` for idempotent re-runs, and count
verification from the reports. Staging through DuckDB (in-memory or file)
between systems is just a second flow.

This boundary is deliberate: a schema-migration framework has a versioned
ledger, environment promotion, and rollback semantics that belong to a
dedicated tool. PkgroveKit adding "just a little" of that would grow the exact
framework the HEL-120 lightweight gate forbids.
