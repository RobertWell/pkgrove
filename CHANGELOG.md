# Changelog

All notable changes to RowRelay. Pre-stable: breaking changes may occur in any
0.x release and are listed here with migration notes.

**Release-version policy:** published coordinates are **immutable releases** —
never a mutable `-SNAPSHOT`. MAJOR = breaking API / major workflow redesign,
MINOR = backward-compatible downstream enhancement, PATCH = backward-compatible
fix; `0.x` is not a blanket exception for breaking changes. Development builds
carry commit identity (`-Pdev` → `<release>-dev.<sha>`) and are never published.

## 0.2.0

First **immutable release** (the earlier `0.1.x-SNAPSHOT` coordinates were
mutable pre-policy development builds and are retired — do not depend on them).
This is a MINOR from the 0.1.0 bootstrap: everything since is additive /
backward-compatible for existing `JdbcReader`/`JdbiReader`/`Transfer` consumers
(the new `Workflows` surface is added, not a breaking change to the core).

- Correctness: fail-visible transaction cleanup (rollback/autoCommit-restore
  failures surfaced, not swallowed; new TransactionState.UNCERTAIN) across all
  write paths; key-only upsert emits DO NOTHING / insert-only MERGE.
- Functional workflow algebra: Choice<L,R> (business routing) + typed
  WorkflowOutcome (Completed/Partial/Failed/Cancelled); coroutine structured
  executor (bounded per-db concurrency, fail-fast/supervised, cancellation-
  preserving); partitionByChoice; staged SourceFlow→ExecutableFlow types make
  an incomplete flow unrepresentable at the executor. See docs/adr/0001.
- Postgres adapter: first-class `uuid`, `json`/`jsonb`, and array columns —
  `PostgresDialect.typeFor` recreates the real Postgres type (not TEXT) and
  `bindValue` reconstructs the value from text (UUID / typed PGobject), with
  `PostgresValueReader` normalizing driver values (PGobject/Array → text, no
  warning). Live PG→PG round-trip proven (integration-tests). Foreign-DB targets
  land these as text.
- Security: pgjdbc 42.7.3 -> 42.7.13 (CVE-2026-42198, CVE-2026-54291 HIGH).
- CI: publish gate + ci `check` aligned (integration-tests run informationally;
  the reliable unit/module/dialect + compiled-doc-example suite is the gate);
  publish workflow refuses any non-immutable (`-SNAPSHOT`/`-dev`) version.

## 0.1.0 (bootstrap — historical)

HEL-127 PostgreSQL adapter + migration boundary:

- `rowrelay-postgres`: PostgresDialect (lowercase-fold identifier policy —
  the mirror of Oracle's; TEXT/BYTEA/NUMERIC/TIMESTAMPTZ type table; native
  ON CONFLICT upsert; savepoints supported; driver compileOnly). Live
  testcontainers suite: Postgres↔DuckDB both directions, named params,
  rename mapping, upsert, savepoint-per-batch. docs/MIGRATION.md defines
  the data-movement vs schema-evolution boundary (Flyway/Liquibase own
  schema history; RowRelay owns bulk movement + verification).


HEL-125 functional workflow API:

- `Workflows.from(key, sql, named).filter{}.transform{}.to(key, dialect,
  table)` — immutable definitions (keys + SQL + options; never connections/
  credentials), executed via pluggable `WorkflowExecutor`s
  (`SequentialExecutor`, bounded `ParallelExecutor` that respects HEL-128
  lease budgets; branches never share connections). Per-flow results.
  Apache River evaluated for the distributed slot and REJECTED (retired in
  the Apache Attic; fails our own supply-chain bar) — the seam stays open
  for a maintained backend. See docs/WORKFLOWS.md.


HEL-128 resource ownership & lifecycle core:

- `DatabaseKey` typed identities + `Databases` registry: application-owned
  pools (borrow/return, never close the pool) vs RowRelay-managed
  (AutoCloseable, reverse-order, idempotent close); duplicate registrations
  fail at build time. Per-key connection budgets (fair), acquisition
  timeout with bounded actionable failure, cancellation-aware waiting,
  uncertain-transaction invalidation (never returned as healthy),
  deterministic key-ordered multi-database acquisition (deadlock
  avoidance), vendor-neutral metrics, deterministic leak tests.
  docs/RESOURCES.md defines the scoped model, streaming/backpressure rules,
  DuckDB in-memory lifecycle, and distributed-worker payload rules.


HEL-126 selectable transaction policies:

- Typed `TransactionPolicy` (Atomic / Chunked / SavepointPerBatch /
  JoinExisting / AutoCommit) executed by `TransactionalWriter`, returning a
  machine-readable `TransactionOutcome` (state, committed/rolled-back rows,
  chunk ranges, checkpoint, `RetrySafety`) — partial completion cannot be
  mistaken for success. Caller-owned transactions are never committed/closed;
  unsupported combinations fail before any row is processed. Adapters report
  `supportsSavepoints` (Oracle yes, DuckDB JDBC no). See docs/TRANSACTIONS.md
  for the cross-database no-global-atomicity boundary and scheduler rules.


HEL-119 named transfer (added after the initial bootstrap):

- `NamedSql` (rowrelay-jdbc): `:user_name` parameters for the direct JDBC
  path — state-aware parsing (literals/quoted identifiers/comments/`::`),
  repeated names, exact missing-name rejection, unused-parameter policies;
  positional binding remains as the low-level compatibility form.
- `Mapping` / `MappingPlan` (rowrelay-transfer): named source-to-target
  mapping with renames, constants, omissions; case-insensitive, validated
  before writing, order-independent, inspectable plan.
- Explicit named-key upsert: `Transfer.Options.upsertKeys` → Oracle `MERGE` /
  DuckDB `ON CONFLICT` (target uniqueness on keys required).
- Three defects caught BY the live-Oracle suite and fixed:
  Oracle vendor type codes (-101/-102 TZ timestamps, 100/101 binary
  float/double) now classify correctly; a deterministic identifier-case
  policy (`SqlDialect.identifierCase`, Oracle = uppercase-then-quote) makes
  generated DDL/DML match unquoted-created objects; temporal columns take
  JDBC-standard type names from the type CODE so Oracle's datetime-valued
  DATE columns cannot silently lose their time component in a date-only
  target.
- Live-Oracle integration suite (testcontainers, auto-skipped without
  Docker): both directions, both access paths, type fidelity incl.
  NVARCHAR2 unicode, CLOB with literal colons, BLOB/RAW bytes, TZ
  timestamps, and all-null rows.


Initial capability set, extracted from production code in AuditPatchX and
QuerySkiff (see the HEL-120 pilot — behavior parity proven by the consumer's
own integration suite, 135/135):

- `rowrelay-core`: dynamic `Schema`/`Row`/`RowBatch` model, `DataWarning`
  (nothing lossy is silent), `ConversionPolicy` (REJECT default),
  `CancelToken`, no-echo safe-identifier gate, `OperationReport` with
  failed-batch/row-range identification.
- `rowrelay-jdbc`: streaming parameterized reads with bounded memory,
  schema discovery without DTOs, `ValueReader` normalization seam,
  batch writer with AllOrNothing / PerChunk commit policies,
  `SqlDialect` contract.
- `rowrelay-jdbi`: first-class JDBI entry point; equivalence with the JDBC
  path by construction; caller-owned-transaction semantics (never commits
  inside your transaction; rejects PerChunk there).
- `rowrelay-oracle`: Oracle dialect (NUMBER p/s, VARCHAR2→CLOB overflow,
  NUMBER(1) boolean, RAW/BLOB by size, TZ-aware temporals) +
  `OracleValueReader` normalizing `oracle.sql.*`.
- `rowrelay-duckdb`: DuckDB dialect incl. java.time→java.sql bind adaptation.
- `rowrelay-transfer`: bidirectional SQL-in/data-out engine with target
  modes, conversion policies, bounded memory, honest partial reports.

Renamed from the internal working name `datakit`
(`internal.datakit:*` coordinates are dead; migrate imports
`datakit.*` → `io.maxxga.rowrelay.*`).
