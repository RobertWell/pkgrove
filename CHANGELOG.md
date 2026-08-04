# Changelog

All notable changes to PkgroveKit. Pre-stable: breaking changes may occur in any
0.x release and are listed here with migration notes.

## Unreleased

- HEL-161: opt-in native bulk-load fast path for transfers. New
  `BulkLoader` capability on `SqlDialect` with two implementations:
  `PostgresCopyLoader` (COPY FROM STDIN CSV via pgjdbc CopyManager) and
  `DuckDbAppenderLoader` (native Appender API). Enable per transfer with
  `Transfer.Options(useBulkLoad = true)` or per sink with the Relay DSL
  `bulkLoad()` flag. Contract parity with the batched path: values are
  bind-adapted identically, the load is all-or-nothing (failure rolls back
  everything and throws `BulkLoadException` with an honest report), and the
  caller's autoCommit is restored. Refusals never fail the transfer — upsert
  keys, caller-supplied TargetWriters, non-native connections, BINARY
  columns (text protocols can't carry them), and — for the positional DuckDB
  Appender — APPEND-mode tables whose physical column set/order differs from
  the transfer schema (extra/default/generated columns) fall back to batched
  INSERT with a `BULK_LOAD_UNAVAILABLE` warning. Benchmarked in `BulkLoadIT` (100k rows,
  live engines) with identical row/checksum outcomes to the batched path.
- HEL-172: optional framework adapters. `pkgrovekit-quarkus` (CDI producer over
  Agroal-managed datasources; explicit MP-Config datasource-to-dialect mapping,
  no classpath scanning; BlockingBoundary event-loop guard; JTA delegates to
  pkgrovekit-jta) and `pkgrovekit-spring-boot-starter` (auto-configuration over
  existing DataSource beans incl. HikariCP; backs off to user-supplied Relay
  beans; SpringTransactions.joinCurrent resolves the @Transactional-bound
  connection via DataSourceUtils and fails clearly outside a transaction).
  Framework pools are APPLICATION_OWNED — never closed, never duplicated;
  startup validation fails fast on unknown dialects / missing beans. Standard
  modules stay framework-free (assertCoordinationIsolation now also forbids
  org.springframework/io.quarkus/io.agroal/com.zaxxer). See
  docs/framework-adapters.md.
- HEL-170: optional cross-database transaction coordination. New modules
  `pkgrovekit-coordination-api` (inert plans, capabilities, typed validation +
  global outcomes incl. InDoubt/HeuristicMixed/RecoveryPending),
  `pkgrovekit-jta` (interpretation through an external Jakarta TM; enlisted
  connections are guard-proxied — local commit/rollback/close forbidden, one
  connection per branch, thread-affine scopes), `pkgrovekit-narayana`
  (standalone Narayana wiring + recovery scan), `pkgrovekit-saga`
  (compensation interpreter with journal, explicitly non-ACID). Standard
  modules stay JTA-free — enforced by `assertCoordinationIsolation` in `check`.
  Proven by a two-resource XA commit/rollback/reject suite against real
  Postgres (testcontainers) via Narayana. See docs/coordination.md.

## 0.4.0 — 2026-08-03

**BREAKING — project renamed: RowRelay → PkgroveKit** (the "rowrelay" name is
registered by another party). The verified Maven namespace is unchanged.

- Maven coordinates: `com.pkgrove:rowrelay-<module>` → `com.pkgrove:pkgrovekit-<module>`.
- Java packages: `io.maxxga.rowrelay.*` → `com.pkgrove.pkgrovekit.*` (now aligned
  with the verified namespace). Class names are unchanged — migration is a
  dependency-coordinate bump plus a mechanical import rewrite:
  `s/io\.maxxga\.rowrelay/com.pkgrove.pkgrovekit/`.
- The old `rowrelay-*` artifacts up to 0.3.0 remain on Maven Central (immutable)
  but are DEPRECATED and will receive no further releases.
- Also in this release (post-0.3.0 mainline): HEL-128 fail-visible cleanup +
  pool-invalidation contract + coroutine-to-JDBC cancellation bridge
  (`CancelToken.linked`, `Databases.CleanupException`, `invalidator` hooks);
  HEL-129 real-pool (HikariCP) lifecycle integration matrix; HEL-168/160/159
  DuckDB type-matrix and JDBI transfer facade improvements.

**Release-version policy:** published coordinates are **immutable releases** —
never a mutable `-SNAPSHOT`. MAJOR = breaking API / major workflow redesign,
MINOR = backward-compatible downstream enhancement, PATCH = backward-compatible
fix; `0.x` is not a blanket exception for breaking changes. Development builds
carry commit identity (`-Pdev` → `<release>-dev.<sha>`) and are never published.

## 0.3.0 (2026-08-02)

**Coordinate migration (HEL-189):** the Maven `groupId` moves from
`com.pkgrove.pkgrovekit` to **`com.pkgrove`** — the namespace verified on Maven
Central — so 0.3.0 is `com.pkgrove:pkgrovekit-*` on every target (GitLab, GitHub
Packages, and eventually Central). Migration: change only the `groupId` in your
dependency; artifact ids, Java package names (`com.pkgrove.pkgrovekit.*`), and APIs
are unchanged. `com.pkgrove.pkgrovekit:*:0.2.0` stays available (immutable) in the
GitLab/GitHub registries.

- HEL-160: first-class JDBI transfer facade (`JdbiTransfer`) — transfers into a
  JDBI `Handle` with `JdbiBatchWriter` transaction semantics; PerChunk inside a
  caller-owned transaction is rejected before the target table is established.
- HEL-168: Oracle↔DuckDB type-fidelity matrix + two data-loss fixes — DuckDB
  targets no longer degrade precision-less integer sources (BIGINT etc.) to
  DOUBLE, and `LocalTime` binds losslessly (ISO string) instead of via
  second-precision timezone-shifted `java.sql.Time`. Rich unsupported-type
  errors (column, kind, source type, adapter path); published type matrix in
  `docs/reference/type-matrix.md`.
- HEL-159: DuckDB dialect branch-matrix tests; key-only upsert now degrades to
  `ON CONFLICT DO NOTHING` (was invalid empty `DO UPDATE SET`), matching Postgres.
- HEL-129: live lifecycle/stress matrix; cancellation propagates
  `OperationCancelledException` unwrapped with an honest partial report, and
  `Relay.execute` accepts a cancel token.

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

- `pkgrovekit-postgres`: PostgresDialect (lowercase-fold identifier policy —
  the mirror of Oracle's; TEXT/BYTEA/NUMERIC/TIMESTAMPTZ type table; native
  ON CONFLICT upsert; savepoints supported; driver compileOnly). Live
  testcontainers suite: Postgres↔DuckDB both directions, named params,
  rename mapping, upsert, savepoint-per-batch. docs/MIGRATION.md defines
  the data-movement vs schema-evolution boundary (Flyway/Liquibase own
  schema history; PkgroveKit owns bulk movement + verification).


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
  pools (borrow/return, never close the pool) vs PkgroveKit-managed
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

- `NamedSql` (pkgrovekit-jdbc): `:user_name` parameters for the direct JDBC
  path — state-aware parsing (literals/quoted identifiers/comments/`::`),
  repeated names, exact missing-name rejection, unused-parameter policies;
  positional binding remains as the low-level compatibility form.
- `Mapping` / `MappingPlan` (pkgrovekit-transfer): named source-to-target
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

- `pkgrovekit-core`: dynamic `Schema`/`Row`/`RowBatch` model, `DataWarning`
  (nothing lossy is silent), `ConversionPolicy` (REJECT default),
  `CancelToken`, no-echo safe-identifier gate, `OperationReport` with
  failed-batch/row-range identification.
- `pkgrovekit-jdbc`: streaming parameterized reads with bounded memory,
  schema discovery without DTOs, `ValueReader` normalization seam,
  batch writer with AllOrNothing / PerChunk commit policies,
  `SqlDialect` contract.
- `pkgrovekit-jdbi`: first-class JDBI entry point; equivalence with the JDBC
  path by construction; caller-owned-transaction semantics (never commits
  inside your transaction; rejects PerChunk there).
- `pkgrovekit-oracle`: Oracle dialect (NUMBER p/s, VARCHAR2→CLOB overflow,
  NUMBER(1) boolean, RAW/BLOB by size, TZ-aware temporals) +
  `OracleValueReader` normalizing `oracle.sql.*`.
- `pkgrovekit-duckdb`: DuckDB dialect incl. java.time→java.sql bind adaptation.
- `pkgrovekit-transfer`: bidirectional SQL-in/data-out engine with target
  modes, conversion policies, bounded memory, honest partial reports.

Renamed from the internal working name `datakit`
(`internal.datakit:*` coordinates are dead; migrate imports
`datakit.*` → `com.pkgrove.pkgrovekit.*`).
