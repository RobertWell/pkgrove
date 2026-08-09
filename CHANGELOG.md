# Changelog

All notable changes to PkgroveKit. Pre-stable: breaking changes may occur in any
0.x release and are listed here with migration notes.

## Unreleased

- HEL-236: **optional S3-compatible object storage** — two new modules,
  strictly opt-in (ship with the next release; no version bump here).
  **`pkgrovekit-storage-api`** is vendor-neutral (depends on core + JDK only):
  `ObjectStore` (put/get/list/delete/copy, typed `StorageException` outcomes,
  validated `ObjectKey`s that reject query strings/`..`/control chars),
  streaming `ContentSource` with an explicit `repeatable` retry contract,
  SHA-256/CRC32C `Checksum`s verified server-side where the provider can and
  client-side otherwise (mismatch always fails typed, never stores corrupt
  data), conditional writes (`WriteCondition.IfAbsent`/`IfMatch`), a
  **capability model** (`StorageCapabilities.require(...)` fails BEFORE data
  movement with a typed `CapabilityRejectedException` listing what the
  provider lacks), bounded multipart (`MultipartTransfer`: peak memory =
  `concurrency × partSize`; failure/cancellation ABORTS the upload),
  **staged atomic publish** (`StagingArea`: staging prefix → server-side copy
  to run-unique keys → conditional-create manifest as the single commit point —
  object storage has no atomic rename and none is assumed; deterministic
  abandoned-staging cleanup), manifest-committed row datasets
  (`ObjectDataset` + dependency-free `jsonl-v1` format; per-part sha256 + row
  counts verified on read), append-only conditional **checkpoints**
  (`CheckpointStore`: concurrent workers conflict typed, never overwrite),
  redacted failed-row **quarantine** (`QuarantineWriter`: sha256 fingerprints,
  value-free keys/summaries), and a full-capability `InMemoryObjectStore`
  reference/testing implementation. **`pkgrovekit-storage-s3`** implements the
  API with **AWS SDK for Java 2.x** (`software.amazon.awssdk:s3` + sync Apache
  transport only — netty async client excluded, no SDK BOM surface): custom
  endpoint/region/credentials (redacted `toString`)/path-style/retries/
  timeouts/TLS, per-provider `S3CompatibilityProfile`s (amazonS3/minio/
  generic), adapter-level presigned GETs whose textual form redacts the signed
  query, incomplete-upload listing + deterministic `abortIncompleteUploads`
  cleanup, and an `S3ObjectStore.wrap(S3Client)` escape hatch. **MinIO is the
  CI-tested target** (Testcontainers, pinned image, same AWS client as
  production — that identity is the compatibility proof); Amazon S3 is the
  reference cloud target via an opt-in protected-credentials smoke test
  (`AmazonS3SmokeIT`, docs/storage.md). The complete **database → object
  storage → database** path is proven live (Postgres → MinIO dataset → DuckDB,
  `StorageDatasetRoundTripIT`, rides the blocking CI gate). Dependency
  isolation is enforced from both sides: `assertModuleHierarchy` now fails if
  any module except storage-s3 carries `software.amazon.awssdk`/`io.minio` at
  runtime, and the consumer fixtures gain `forbiddenGroups` assertions
  (`jdbc-only`/`postgres-transfer` prove ZERO AWS SDK artifacts) plus a new
  `storage-s3` fixture (asserts the storage graph and that netty stays out).
  Docs: `docs/storage.md` scenario tutorials, scenarios.md recipe 9, README
  use-case rows, ADR-0003 edge table. Complements the DuckDB-native
  Parquet-on-S3 publisher (`com.pkgrove.pkgrovekit.duckdb.s3`, below) — that
  path stays the Parquet answer; `storage-api` deliberately does not take the
  Hadoop dependency tree.

- HEL-235: **explicit, acyclic module hierarchy with minimal transitive deps**,
  enforced in CI. The allowed pkgrovekit→pkgrovekit graph (with required Gradle
  scope) is now a machine-readable map at `gradle/allowed-dependencies.txt`,
  enforced by a new `assertModuleHierarchy` task that rides `check` and fails on
  an undeclared/mis-scoped edge, a cycle, an adapter→adapter dependency, a
  framework leaking into core/jdbc/transfer, jdbi on a jdbc-only classpath,
  JTA/Narayana on a standard module, a driver leaking transitively, a test-only
  dep on a published runtime classpath, a published-POM boundary divergence, or a
  BOM gap. **Framework adapters are now decoupled from concrete dialects:** a new
  `SqlDialectProvider` service-loader SPI (in `pkgrovekit-jdbc`, contributed by
  each dialect module) lets `pkgrovekit-spring-boot-starter` and
  `pkgrovekit-quarkus` depend **only** on `pkgrovekit-transfer` and resolve
  dialects at runtime — so a "Spring + Postgres" (or "Quarkus + Oracle") consumer
  no longer transitively carries the other dialect modules. Adds a new
  **`pkgrovekit-bom`** (`java-platform`, zero runtime deps) so consumers omit
  per-module versions; there is deliberately no `pkgrovekit-all` aggregate. Eight
  standalone consumer fixtures (`consumer-fixtures/`) prove each scenario's
  boundary against `mavenLocal`, wired into CI (`module-hierarchy` job). Docs:
  README reworked to a scenario-first "choose your use case" guide,
  `docs/scenarios.md` with the 8 dependency recipes, and
  `docs/adr/0003-module-hierarchy.md`. Build-internal only — **no version bump**;
  the boundary changes take effect on the next release and preserve every type
  existing 0.5.0 consumers import (jdbi still `api`-exposes transfer for the
  `JdbiTransfer` facade; read-only jdbi consumers may exclude transfer).

- HEL-224: native same-database **server-side copy** (`INSERT … SELECT`
  push-down). When a transfer's source and target are the SAME physical
  connection, `Transfer.Options.useServerSideCopy = true` (or `serverSideCopy()`
  on the `Relay` sink DSL) emits one server-side `INSERT INTO dst (cols) SELECT
  cols FROM (<sourceSql>) …` instead of round-tripping every row through the
  client — no CLOB/BLOB values cross the process, no read-side stringification.
  `SqlDialect` gains `supportsServerSideCopy` (opt-in, default false) and a
  dialect-quoted `serverSideCopySql(...)` builder; `postgres`, `oracle`, and
  `duckdb` opt in. The path falls back to the existing row-streaming writer with
  a visible `SERVER_SIDE_COPY_UNAVAILABLE` warning whenever it cannot be
  expressed as a pure column-select copy (different databases, a dialect without
  server-side copy, a `rowTransform`/`processor`, `upsertKeys`, `useBulkLoad`, or
  a constant-column mapping). Scope is `INSERT … SELECT` only — correlated
  UPDATE-by-key is deferred per the HEL-224 audit. This is the mode AuditPatchX's
  compare-apply path can delegate to for same-database moves.

- HEL-259 (docs): added a researcher-facing **Security Policy**
  (`SECURITY.md` at the repository root, GitHub-recognized) covering supported
  versions, private vulnerability reporting via GitHub Private Vulnerability
  Reporting (no public issue for an unpatched flaw), response/triage
  expectations, coordinated disclosure, researcher credit, and the maintainer
  private-report → patched-release → GHSA/CVE workflow with exact Maven
  coordinate/version mapping (`com.pkgrove:pkgrovekit-*` and legacy
  `com.pkgrove:rowrelay-*`). The existing internal security-control
  documentation moved to `docs/security-controls.md` (clearly separated from the
  external policy) and its owner repository-settings checklist gained the
  Private-vulnerability-reporting toggle, dependency graph/submission, secret
  scanning + push protection, notification recipients, and the accurate required
  `main` checks. No code, scanner, or gate changes — the HEL-124 detection
  controls are unchanged. References `docs/RELEASING.md` for the security-release
  cut rather than duplicating it.

- HEL-236: optional S3-compatible object-storage scenarios in
  `pkgrovekit-duckdb` (`com.pkgrove.pkgrovekit.duckdb.s3`, zero new
  dependencies). `S3Session` configures DuckDB `httpfs` + a `CREATE OR REPLACE
  SECRET` for the endpoint; `ObjectKey` is a write target that is an object key
  (`s3://bucket/key`) rather than a table; `S3Publisher` publishes a query
  result as ONE Parquet/CSV object with atomic-replace semantics
  (staging-key write → read-back verify → server-side `CopyObject` → delete
  staging) and a typed `PublishOutcome` — no failure mode can leave a corrupt
  or partial final object, and staging orphans are always reported, never
  silent. The replace/delete pair runs over a built-in SigV4 client
  (`java.net.http`, pinned against AWS's published signing example);
  substitutable via `S3Publisher.ObjectStoreOps`. Proven against real MinIO in
  `S3ObjectPublishIT`. First consumer: HEL-264 training-record publication.
  See `docs/adapters/duckdb.md`.

## 0.5.0 — 2026-08-07

- HEL-256 (defect): "bounded memory by construction" was not enforced on a
  PostgreSQL source. `JdbcReader` set `Statement.fetchSize` and nothing else,
  but pgjdbc opens a server-side cursor only when the connection is out of
  autocommit — in autocommit it ignores `fetchSize` and materializes the whole
  result set client-side, silently. The guarantee therefore held only by luck
  of caller configuration, and a framework-managed `DataSource` (Quarkus/Agroal,
  Spring/Hikari — the HEL-172 adapters) hands out `autoCommit=true`. Measured on
  a 195 MiB result set: 214 MiB live-retained heap and 698 ms to first batch,
  versus 2 MiB and 43 ms once streaming.
  The requirement is now dialect-driven, not a Postgres special case: new
  `StreamingContract` is the single place per-dialect streaming preconditions
  are recorded (Postgres needs autoCommit off; MySQL/MariaDB need the
  `Integer.MIN_VALUE` fetch-size sentinel; Oracle honours `fetchSize` directly;
  DuckDB is in-process), declared per adapter via `SqlDialect.streaming` and
  otherwise detected from the driver's own product name.
  Enforcement respects HEL-128 ownership, via `ReadOptions.ownership` /
  `Transfer.Options.sourceConnectionOwnership`: on a `LEASED` connection
  `autoCommit` is taken over and **restored exactly as found** on success,
  exception, and cancellation (the same contract `JdbcBatchWriter` already had
  on the write side, and safe by construction because the take-over happens only
  when `autoCommit` was already true, i.e. no caller transaction exists); on a
  `CALLER_OWNED` connection nothing is mutated and a driver that cannot stream
  produces `StreamingUnavailableException` at open with an actionable message
  instead of a silent unbounded buffer. A connection already inside a caller
  transaction is the GOOD case and is never refused. Where source and target
  share one physical connection the writer's commit would close the cursor, so
  that case stays buffered and says so through a `not-streaming` `DataWarning`
  on the report rather than overstating the bound. `RowStream.streaming`
  reports whether the bound actually holds. Source-compatible: existing call
  sites get streaming by default.

- HEL-255 (defect, unreleased code): `ConsecutiveGrouper` retained EVERY
  distinct grouping key for the whole transfer — ~113 bytes/key, 108 MB at
  1M groups — while `largestGroupRows` went on reporting the per-group
  bound as healthy, so the instrumentation showed a bound holding while
  retention grew without limit. The out-of-order guard now keeps only the
  last `recentKeyMemory` closed keys (new parameter on `ConsecutiveGrouper`
  and `groupConsecutiveBy`, default 10 000, ~1 MB), so total retained state
  is `maxGroupRows` rows + `recentKeyMemory` keys and is independent of the
  number of groups (measured: 108.2 MB -> 1.1 MB at 1M groups). The guard's
  guarantee narrowed with it and the class doc now states it exactly: a key
  reappearing WITHIN the window always throws `OutOfOrderGroupException`; a
  key reappearing further back is NOT detected and yields two aggregates
  for one key. New `bufferedRows` / `retainedKeys` / `recentKeyWindow`
  expose the real live state. Source-compatible: existing call sites keep
  the default window.

- HEL-228 (phase 1 of 6): bounded STATEFUL transformations that keep the
  streaming contract. `BatchProcessor` is an explicit, intention-revealing
  seam — a stateful step can never masquerade as a pure `rowTransform`,
  because the two are different options with different memory contracts.
  `ConsecutiveGrouper` (Relay: `groupConsecutiveBy(...)`) aggregates
  consecutive rows sharing an ordered key, bounded by a REQUIRED
  `maxGroupRows` (no default — the budget is a decision), and refuses
  rather than corrupts: a group over budget throws `GroupTooLargeException`,
  and a key reappearing after its group closed throws
  `OutOfOrderGroupException` naming the missing ORDER BY instead of silently
  emitting two partial aggregates for one key. A processor may declare an
  `outputSchema`, which then drives table establishment and the INSERT/upsert
  DML — grouped output reshapes rows, so the target must describe what is
  actually written. `close()` runs on success, failure and cancellation;
  laziness and cancellation are preserved (asserted, not assumed).
  Categories 4-6 from the issue (partitioned keyed aggregation with spill,
  materialized stages, checkpoint/restart) are deliberately NOT included:
  they change the streaming guarantee and are sequenced after adopter
  evidence for these. New `docs/transformations.md` is the decision guide
  across all five categories — SQL/engine first, then row mapping, bounded
  batches, ordered grouping — stating each mode's memory contract, what it
  requires of the source, and how it fails, including the ordering guard's
  exact (bounded, incomplete) reach and the streaming precondition the
  memory bound now depends on.

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
