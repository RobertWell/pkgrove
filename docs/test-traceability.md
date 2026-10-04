# PkgroveKit scenario-to-test traceability matrix (HEL-129)

Maps every validation scenario / acceptance criterion of the source issues
(HEL-119/120/125/126/127/128) to the automated test(s) that verify it. This file
is the **authored** half; `scripts/gen-test-inventory.sh` is the **generated**
half — it lists every `@Test` in source (532 methods, 2026-08-09) and fails CI if
any test *class* named here no longer exists (drift guard). Both run in the GitHub
`check` job (non-Docker); the Docker ITs run in the non-blocking `integration` job
and on the self-hosted/local run.

Levels: **U**=unit (no DB), **C**=adapter contract (embedded DuckDB, no Docker),
**I**=integration (live testcontainer DB), **E2E**=cross-executor, **S**=stress,
**F**=fault-injection. CI tier: **PR**=blocking, **INT**=integration(non-blocking).

## Enforced coverage gates (HEL-234)

Coverage is a **gate, not a report**: every production module runs under JaCoCo
(pinned 0.8.11) and its `check` fails below the threshold. Ratchet policy:
when a measured baseline exceeds its gate, raise the gate — never lower one to
match a regression (owner-approved exception required).

| Scope | Line | Branch | Enforced by |
|---|---|---|---|
| `pkgrovekit-jdbc`, `pkgrovekit-transfer`, `pkgrovekit-jta`, `pkgrovekit-coordination-api` (critical) | ≥ 85% | ≥ 75% | `jacocoTestCoverageVerification` on each module's `check` |
| every other production module | ≥ 80% | ≥ 70% | `jacocoTestCoverageVerification` on each module's `check` |
| repository-wide (merged, incl. any integration exec data present) | ≥ 80% | ≥ 70% | `./gradlew jacocoAggregatedVerification` (CI `check` job) |

Local commands:

```
./gradlew check -x :integration-tests:test        # unit/contract suites + per-module coverage gates
./gradlew jacocoAggregatedReport                  # merged XML+HTML at build/reports/jacoco/
./gradlew jacocoAggregatedVerification            # repo-wide 80/70 floor
./gradlew :integration-tests:postgresIntegrationTest   # blocking Postgres/DuckDB container suite
./gradlew :integration-tests:test                 # full container suite incl. Oracle-Free (resourced host)
```

CI (`.github/workflows/ci.yml`): the `check` job enforces every gate above and
uploads `**/build/reports/jacoco/**` as artifacts; `integration-postgres` is a
**blocking** PR check. The **required, SHA-tied Oracle gate** is the GitLab
`integration-oracle` job on the LAN self-hosted privileged runner
(`.gitlab-ci.yml`, every push/MR, no allow_failure); the GitHub hosted-runner
`integration-oracle` job stays informational only because that *runner* is
flaky — the gate itself is not.

## Owner-mandated gates (HEL-234, 2026-08-09)

| Gate | Threshold (FAILS below) | Where |
|---|---|---|
| Changed-code coverage | 80% of the coverable lines a change touched | `jacocoDiffCoverageCheck` — GitHub `check` (PR/push step), GitLab `diff-coverage` (merge-blocking) |
| Mutation score (PIT) | jdbc 60 / transfer 60 / coordination-api 70 / jta 70 % killed | `./gradlew mutationTest` — GitLab `mutation` (scheduled, threshold hard-fails) |
| Live-Oracle integration | any test failure | GitLab `integration-oracle` (LAN runner, required per SHA) |
| Soak leak/boundedness | lease/session/thread leak deltas, 256 MB post-GC heap ceiling, heap growth trend | `TransferSoakIT` (**S**) — GitLab `stress-soak` (scheduled, 12 min; trend CSV retained 365 days) |

Evidence + artifact locations: `docs/release-evidence.md`;
`scripts/gen-release-evidence.sh` ties SHA → test counts → coverage → gates in
both CIs.

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

## HEL-236 — S3-compatible object storage

| Scenario | Test(s) | Level | DB | Tier | State |
|---|---|---|---|---|---|
| Vendor-neutral store contract (put/get/list/delete/copy, conditional writes, checksums, multipart, range) | `ObjectStoreContractTest` (in-memory reference); `MinioObjectStoreIT` (same semantics, real MinIO via AWS SDK, path-style) | U / I | MinIO | PR | PASS |
| Key validation + capability fail-fast + typed outcomes | `KeysAndCapabilitiesTest`; `S3ConfigTest` (pre-I/O rejection, secret-redacted toString, presigned-query redaction) | U | — | PR | PASS |
| Bounded multipart + abort on failure/cancellation + deterministic incomplete-upload cleanup | `MultipartTransferTest` (part-buffer bound, abort, capability gates); `MinioMultipartIT` (128 MiB heap-bounded round trip, provider-side abort, `abortIncompleteUploads`, conditional complete) | U / I | MinIO | PR | PASS |
| Staged atomic publish + abandoned-staging cleanup + checkpoint conflicts | `StagingAndCheckpointTest`; `MinioWorkflowsIT` | U / I | MinIO | PR | PASS |
| Manifest-committed datasets: bounded parts, corruption/truncation refusal, interrupted-export cleanup, quarantine redaction | `DatasetAndFormatTest`; `JsonTest`; `MinioWorkflowsIT` | U / I | MinIO | PR | PASS |
| Adapter edges: CRC32C, degraded-capability local verification, `wrap` escape hatch, transport-failure retry verdicts | `MinioAdapterEdgeIT` | I | MinIO | PR | PASS |
| Complete database → object storage → database | `StorageDatasetRoundTripIT` (Postgres → MinIO dataset → DuckDB, value-fidelity asserted) | E2E | PG+MinIO | PR (blocking `integration-postgres`) | PASS |
| Consumer dependency isolation (no AWS SDK for db-only users) | `assertModuleHierarchy` storage-leak check; consumer fixtures `jdbc-only`/`postgres-transfer` (`forbiddenGroups`) + `storage-s3` | — | — | PR | PASS |
| Amazon S3 cloud smoke | `AmazonS3SmokeIT` — opt-in via `PKGROVEKIT_S3_SMOKE_BUCKET` + protected credentials (docs/storage.md); never in PR CI | I | AWS S3 | manual/release | OPT-IN |

## HEL-602 — functional operation DSL (REST + MCP + auth + validation)

Levels: **U**=unit (no container), **F**=real-framework (live Arc container in
`integration-tests-quarkus`). No Docker is required for any row below.

| Scenario | Test(s) | Level | Tier | State |
|---|---|---|---|---|
| The one-screen declaration produces the declared catalogue (read / idempotent write / restOnly / mcpOnly / internalOnly) | `OperationDslTest` (the issue's exact example, in `Fixtures.kt`) | U | PR | PASS |
| Fail-closed classification: undeclared surface, doubly-declared surface, blank reason, duplicate id, duplicate REST binding, duplicate MCP tool, missing handler, missing codec, invalid id/tool/path, schema-vs-rule drift, REST path param absent from schema | `SurfaceClassificationTest` | U | PR | PASS |
| Policy refuses a sensitive category on MCP; an explicit reasoned `mcpOverride` admits it; a blank override reason is refused | `SurfaceClassificationTest` | U | PR | PASS |
| Registry immutability + lookups + exclusion/classification enumerations | `SurfaceClassificationTest` | U | PR | PASS |
| Pipeline composition and ORDER (`audit -> authenticate -> decode -> validate -> authorizeOperation -> domainAuthorization -> idempotency -> handle`), extra stages, duplicate/empty rejection, handler-less pipeline | `PipelineTest` | U | PR | PASS |
| Auth: wrong-shaped credential and rejected credential are `unauthenticated`; a surface with NO profile is unauthenticatable rather than open; roles satisfy scopes; missing scope is `forbidden` naming what is missing | `PipelineTest` | U | PR | PASS |
| Idempotency: replay, per-operation key scoping, no replay for a failed call, no replay without a key | `PipelineTest` | U | PR | PASS |
| Audit + metrics hooks invoked with SURFACE ATTRIBUTION for success, replay and failure (including an unattributable attempt); `audited(false)`/`metered(false)` suppress them; tracer wraps the call; duration comes from the injected clock | `PipelineTest` | U | PR | PASS |
| Validation built-ins and their stable codes; non-`required` rules skip an absent value; cross-field rules; validation summary | `ValidationTest` | U | PR | PASS |
| Dependency-free JSON parse/write + field-attributing accessors (the reason core needs no Jackson) | `JsonTest` | U | PR | PASS |
| One shared error model: every `OperationError` code + HTTP status + wire envelope; the cause never reaches the wire | `ErrorsTest` | U | PR | PASS |
| Path templates: parameter extraction, empty-segment refusal, percent-decoding, duplicate-parameter refusal (one implementation serves the registry check and the dispatcher) | `RestPathTemplateTest` | U | PR | PASS |
| Gallery descriptors + JSON export + copyable DSL snippet + whole-catalogue export | `DescriptorTest` | U | PR | PASS |
| `SurfaceContract` (the test utility consumers inherit): identical answers agree, a changed status/error-code/detail-field is reported, a REWORDED message is not an alarm, and `assertAgree` names the call and every difference | `SurfaceContractTest`; used for real by `OperationSurfacesTest` | U,F | PR | PASS |
| `ParityCheck`: unregistered operation, duplicate tool, duplicate route, a WITHHELD operation appearing in a live MCP listing, missing-from-listing, tightened-policy preview | `ParityCheckTest` | U | PR | PASS |
| REST adapter: routing, path/query/body merge with PATH precedence, 201 vs 200-on-replay, every error status, malformed body, percent-encoded segments, custom error envelope, audit attribution | `RestDispatcherTest` | U | PR | PASS |
| **Security proof:** a REST_ONLY sensitive operation is absent from `toolsList()`, `handles()` is false under every plausible name, `call()` returns `not_found`, and the handler is NEVER invoked | `McpDispatcherTest`; `OperationSurfacesTest` (live container) | U,F | PR | PASS |
| MCP adapter: tool descriptors + input schema, write/experimental hints an agent reads, blank/malformed arguments, scope and auth failures, replay, audit attribution, legacy-overlap fail-fast | `McpDispatcherTest` | U | PR | PASS |
| Quarkus: registry assembled from CDI-discovered `OperationModule` beans; STARTUP check rejects an undeclared surface / duplicate tool / policy refusal naming the beans; ambiguous `SurfacePolicy` is a hard failure; configured refusal categories | `OperationRegistryProducerTest` | U | PR | PASS |
| Framework-identity seam: ambient and host-extracted credential profiles, blank-subject refusal, audited-claim selection, internal-job scopes refusing remote surfaces | `QuarkusAuthProfilesTest` | U | PR | PASS |
| **Real framework:** live Arc container produces the registry from CDI beans; REST and MCP present DIFFERENT credentials and route to ONE handler; each surface rejects the other's credential; validation output is byte-identical across surfaces; an idempotency key makes a cross-surface retry safe; the mcpOnly helper has no HTTP route; the parity check passes against the live tool listing; INTERNAL is not a scope bypass | `OperationSurfacesTest` | F | PR | PASS |

### Honest limit on the boot-failure proof

`OperationSurfacesTest` proves the startup bean REJECTS a bad catalogue inside
the live container (the real `OperationRegistryProducer`, the real
`Instance<OperationModule>`, plus one bad module), and that it names the
operation and the contributing beans. It does **not** assert the JVM-exit half:
asserting a *failed boot* needs QuarkusUnitTest from
the `quarkus-junit5-internal` artifact, which this repository's version catalog and
dependency-verification metadata do not cover. The container starting at all is
itself the gate passing on the good catalogue.

## Honest gaps (tracked, NOT claimed complete)

| Not-yet-covered scenario | Why | Disposition |
|---|---|---|
| River executor task-payload/worker-loss tests | River integration is **not implemented** (the ADR keeps it allowed-but-gated). HEL-125 says: "Before River exists, use the deterministic test executor" — which `StructuredExecutorTest` does. | Out of scope until a River module exists. |
| Literal 10-Oracle + 2-DuckDB multi-registration config | The *deterministic* multi-DB concurrency/ordering/leak assertions are covered by `DatabasesTest`/`StructuredExecutorTest` over fake+DuckDB registrations (the mechanics are DB-agnostic). The literal 10×Oracle real-container matrix is disproportionate (10× 5 GB Oracle Free containers) and adds no new *code path*. | Deferred; deterministic coverage stands in. Flagged for owner call. |
| Scheduled **stress/soak tier** as a distinct CI schedule | Stress scenarios exist as tests (`LifecycleStressIT`, budget/concurrency) but run in the non-blocking `integration` job, not a separate scheduled tier. | Follow-up: add a scheduled workflow. Non-blocking for correctness. |
| Operation-layer **JVM-exit boot failure** | Asserting a failed Quarkus boot requires QuarkusUnitTest (the `quarkus-junit5-internal` artifact), which is not in the version catalog or the generated dependency-verification metadata (owner directive: minimise new external artifacts). The startup BEAN's refusal is proven in the live container (`OperationSurfacesTest`), and a good catalogue booting is the positive half. | Deferred; add with the next metadata refresh if the owner wants the JVM-exit assertion. |
| Slow-target **backpressure** dedicated assertion | `SlowTargetBackpressureIT`: slow sink throttles the source — rows materialized never lead the sink by more than one read batch (asserted at EVERY write step, live PG, 2k×10KB rows), plus coarse heap ceiling refuting whole-corpus buffering. Runs in the scheduled `stress-soak` CI tier. | DONE (HEL-129). |

Rename note: the RowRelay→PkgroveKit rename + Maven-coordinate migration is tracked separately in **HEL-225** — not mixed into this test-matrix closure.
