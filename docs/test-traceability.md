# PkgroveKit scenario-to-test traceability matrix (HEL-129)

Maps every validation scenario / acceptance criterion of the source issues
(HEL-119/120/125/126/127/128) to the automated test(s) that verify it. This file
is the **authored** half; `scripts/gen-test-inventory.sh` is the **generated**
half — it lists every `@Test` in source (149 methods, 2026-08-03) and fails CI if
any test *class* named here no longer exists (drift guard). Both run in the GitHub
`check` job (non-Docker); the Docker ITs run in the non-blocking `integration` job
and on the self-hosted/local run.

Levels: **U**=unit (no DB), **C**=adapter contract (embedded DuckDB, no Docker),
**I**=integration (live testcontainer DB), **E2E**=cross-executor, **S**=stress,
**F**=fault-injection. CI tier: **PR**=blocking, **INT**=integration(non-blocking).

## HEL-128 — connection-pool ownership + resource lifecycle (release-blocking)

| Scenario | Test(s) | Level | DB | Tier | State |
|---|---|---|---|---|---|
| App-owned DataSource stays open after shutdown | `DatabasesTest`: managed resources close on runtime close but application pools are untouched; `RealPoolLifecycleIT` / `OracleRealPoolLifecycleIT`: shutdown mid-flight drains… (asserts app pool still usable) | U,I | DuckDB, PG, Ora | PR,INT | PASS |
| Borrowed connection returned exactly once (physical identity) | `RealPoolLifecycleIT`: lease end RETURNS the same physical connection; `OracleRealPoolLifecycleIT`: lease end RETURNS the same physical session | I | PG, Ora | INT | PASS |
| Managed resources close once, reverse order | `DatabasesTest`: managed closer failures are aggregated…; managed resources close on runtime close | U | DuckDB | PR | PASS |
| Caller-owned tx/handle never closed/committed unexpectedly | `TransactionPolicyTest`: join existing never commits…; `JdbiPathTest`: writer inside a caller transaction appends without committing; `JdbiTransferTest`: transfer inside a caller transaction (commit/rollback with caller) | U,C | DuckDB | PR | PASS |
| Conflicting/duplicate ownership fails before work | `DatabasesTest`: duplicate registration fails at build time | U | — | PR | PASS |
| Scope cleanup on every path (success/fail/timeout/cancel/retry/shutdown) | `DatabasesTest`: leases returned on success failure…; abandoning work early…; shutdown drains…; `JdbcPathTest`: write cancellation reports the open chunk honestly | U,C | DuckDB | PR | PASS |
| Per-DB pool budget never exceeded; bounded waiter queue | `DatabasesTest`: budget bounds concurrency…; `StructuredExecutorTest`: completes bounded by maxConcurrency and per-db budget; `LifecycleStressIT`: concurrent load is bounded by the budget | U,S | DuckDB, PG | PR,INT | PASS |
| Pool-acquisition timeout → actionable bounded failure | `DatabasesTest`: budget bounds…exhaustion fails bounded not hung; `LifecycleStressIT`: exhausted budget times out bounded | U,S | DuckDB, PG | PR,INT | PASS |
| Cancel while waiting removes waiter, releases acquired | `DatabasesTest`: cancellation while waiting acquires nothing and releases nothing | U | DuckDB | PR | PASS |
| Cancellation during in-flight blocking JDBC work (coroutine→JDBC bridge) | `StructuredExecutorTest`: caller cancellation stops in-flight blocking JDBC work; `RealPoolLifecycleIT`/`OracleRealPoolLifecycleIT`: cancellation during in-flight pooled work releases pool and leases; `LifecycleStressIT`: cancellation mid-transfer rolls back | U,I,S | DuckDB, PG, Ora | PR,INT | PASS |
| Fail-visible cleanup (thrown / suppressed) | `DatabasesTest`: cleanup failure after successful work is thrown; …after failed work rides the primary as suppressed | U | DuckDB | PR | PASS |
| Broken/uncertain connection invalidated, not returned healthy | `DatabasesTest`: uncertain transaction state rolls back and pool-returns a healthy connection; failed rollback triggers genuine invalidation via the registered invalidator; `RealPoolLifecycleIT`: broken mid-transaction connection is EVICTED; `OracleRealPoolLifecycleIT`: killed mid-transaction session is EVICTED not returned as healthy | U,I | DuckDB, PG, Ora | PR,INT | PASS |
| Retry begins with fresh scope | `DatabasesTest`: retry after failure works on a healthy registry; `RealPoolLifecycleIT`/`OracleRealPoolLifecycleIT`: retry after a transient failure | U,I | DuckDB, PG, Ora | PR,INT | PASS |
| Deterministic multi-DB acquisition order (no deadlock) | `DatabasesTest`: multi-database acquisition orders by key name and releases all on failure | U | DuckDB | PR | PASS |
| Leak assertions fail the test (not just log) | every fixture asserts `metrics().activeLeases==0` / Hikari `activeConnections==0` at teardown | U,I,S | all | PR,INT | PASS |

## HEL-126 — transaction policies

| Scenario | Test(s) | Level | DB | Tier | State |
|---|---|---|---|---|---|
| Atomic commit / full rollback | `TransactionPolicyTest`: atomic commits everything or nothing; `OracleTransferIT`: atomic policy on oracle rolls the whole transfer back; `TransferTest`: fail-if-exists…/incompatible append surfaces a failed report | U,C,I | DuckDB, Ora | PR,INT | PASS |
| Chunked commit + failed-chunk reporting | `TransactionPolicyTest`: chunked reports committed ranges failed chunk and checkpoint; `JdbcPathTest`: per-chunk commit preserves completed chunks; `TransferTest`: per-chunk commit preserves completed chunks across a mid-transfer failure | U,C | DuckDB | PR | PASS |
| Savepoint-per-batch rollback | `TransactionPolicyTest`: savepoint per batch fails closed…/fails early where dialect reports no support; `OracleTransferIT`: savepoint-per-batch on oracle keeps earlier batches; `PostgresTransferIT`: savepoint-per-batch on postgres | U,I | DuckDB, Ora, PG | PR,INT | PASS |
| Caller/JDBI-owned tx joining | `TransactionPolicyTest`: join existing never commits; `JdbiTransferTest`: transfer inside a caller transaction is atomic | U,C | DuckDB | PR | PASS |
| Auto-commit explicit partial completion | `TransactionPolicyTest`: auto commit accounts partial completion exactly | U | DuckDB | PR | PASS |
| Cancellation before/during/after commit | `RelayTest`: cancelled execution yields TransferOutcome Cancelled; `JdbcPathTest`: write cancellation reports the open chunk honestly | U,C | DuckDB | PR | PASS |
| Outcomes never leak row values | `TransactionPolicyTest`: outcomes never leak row values | U | DuckDB | PR | PASS |

## HEL-127 — PostgreSQL adapter + migration boundary

| Scenario | Test(s) | Level | DB | Tier | State |
|---|---|---|---|---|---|
| PG type mapping incl. uuid/json/jsonb/array | `PostgresDialectTest`: type mapping; HEL-127 uuid, json, jsonb and array target types; uuid text binds to a real UUID; `PostgresTransferIT`: HEL-127 uuid json jsonb and array columns round-trip postgres to postgres | U,I | PG | PR,INT | PASS |
| PG identifier down-folding | `PostgresDialectTest`: postgres folds identifiers DOWN before quoting | U | — | PR | PASS |
| PG on-conflict upsert (+ key-only DO NOTHING) | `PostgresDialectTest`: on conflict upsert keyed by name; key-only table degrades to ON CONFLICT DO NOTHING; `PostgresTransferIT`: duckdb to postgres batch insert then on-conflict upsert with rename mapping | U,I | PG | PR,INT | PASS |
| PG JDBC↔JDBI facade honoring caller tx | `PostgresTransferIT`: duckdb to postgres via jdbi transfer facade honors the caller transaction | I | PG | INT | PASS |
| Migration: PG→DuckDB / cross-engine transfer | `PostgresTransferIT`: postgres to duckdb with named parameter and type fidelity | I | PG,DuckDB | INT | PASS |

## HEL-119 / HEL-125 — Oracle↔DuckDB transfer, named mapping, workflow API

| Scenario | Test(s) | Level | DB | Tier | State |
|---|---|---|---|---|---|
| Oracle↔DuckDB transfer, named params | `OracleTransferIT`: oracle to duckdb with named parameter; duckdb to oracle batch insert then named-key upsert | I | Ora,DuckDB | INT | PASS |
| JDBC↔JDBI parity | `OracleTransferIT`: jdbc and jdbi paths produce equivalent oracle transfer results; `JdbiPathTest`: jdbi and jdbc paths produce equivalent schema and rows | C,I | DuckDB,Ora | PR,INT | PASS |
| Oracle type-matrix fidelity (13 types + null) | `OracleTransferIT`: oracle to duckdb type matrix (`@ParameterizedTest`); type fidelity oracle to duckdb; duckdb to oracle type round trip | I | Ora,DuckDB | INT | PASS |
| Named mapping: rename/constant/omit, ambiguity, exact-before-normalized | `NamedMappingTest` (8 methods); `NamedSqlTest` (5 methods) | U,C | DuckDB | PR | PASS |
| Case-insensitive + collision detection | `ModelTest`: schema lookup is case-insensitive and rejects duplicates; `NamedMappingTest`: plan rejects unknown duplicate and colliding names | U | — | PR | PASS |
| Immutable workflow graph; incomplete unrepresentable | `WorkflowTest`: an incomplete flow cannot reach an executor; flow definitions carry keys not connections; `RelayTest`: incomplete plans fail at DEFINITION time | U,C | DuckDB | PR | PASS |
| Choice / Left-Right routing | `ChoiceTest` (4); `ChoiceRoutingTest`: Choice route sends Left and Right rows to different sinks | U,C | DuckDB | PR | PASS |
| Typed outcome (Completed/Partial/Failed/Cancelled/Rejected) | `WorkflowOutcomeTest` (4); `RelayTest` (golden/rejected/failed/cancelled — 8) | U,C | DuckDB | PR | PASS |
| Sequential + bounded-parallel + structured executors (graph parity) | `WorkflowTest`: sequential/parallel; `StructuredExecutorTest` (6); `QuickStartExamples`: golden path managed workflow | U,C | DuckDB | PR | PASS |
| Bounded-memory streaming; unicode/type fidelity | `JdbcPathTest`: streaming batches hold one batch at a time; `TransferTest`: sql-in data-out…unicode fidelity; `TypeMatrixDuckDbTest` (7) | U,C | DuckDB | PR | PASS |
| README/Java-consumer examples compile + run | `QuickStartExamples` (5); `JavaConsumerExample` (2) | C | DuckDB | PR* | PASS |

## HEL-120 / HEL-123 — library foundation + publication gates

| Scenario | Test(s) | Level | DB | Tier | State |
|---|---|---|---|---|---|
| Dialect type/DDL/bind correctness (Oracle/PG/DuckDB) | `OracleDialectTest` (7); `PostgresDialectTest` (7); `DuckDbDialectTest` (8) | U | — | PR | PASS |
| Identifier gate never echoes unsafe input | `ModelTest`: identifier gate validates and quotes without echoing bad names | U | — | PR | PASS |
| Supply-chain / dependency verification | GitLab `verification-metadata` job (enforced build); GitHub `security.yml` (Trivy) | — | — | PR | PASS |

## Honest gaps (tracked, NOT claimed complete)

| Not-yet-covered scenario | Why | Disposition |
|---|---|---|
| River executor task-payload/worker-loss tests | River integration is **not implemented** (the ADR keeps it allowed-but-gated). HEL-125 says: "Before River exists, use the deterministic test executor" — which `StructuredExecutorTest` does. | Out of scope until a River module exists. |
| Literal 10-Oracle + 2-DuckDB multi-registration config | The *deterministic* multi-DB concurrency/ordering/leak assertions are covered by `DatabasesTest`/`StructuredExecutorTest` over fake+DuckDB registrations (the mechanics are DB-agnostic). The literal 10×Oracle real-container matrix is disproportionate (10× 5 GB Oracle Free containers) and adds no new *code path*. | Deferred; deterministic coverage stands in. Flagged for owner call. |
| Scheduled **stress/soak tier** as a distinct CI schedule | Stress scenarios exist as tests (`LifecycleStressIT`, budget/concurrency) but run in the non-blocking `integration` job, not a separate scheduled tier. | Follow-up: add a scheduled workflow. Non-blocking for correctness. |
| Slow-target **backpressure** dedicated assertion | Bounded-memory streaming is asserted (`JdbcPathTest`); an explicit slow-sink backpressure test is not yet isolated. | Follow-up. |

Rename note: the RowRelay→PkgroveKit rename + Maven-coordinate migration is tracked separately in **HEL-225** — not mixed into this test-matrix closure.
