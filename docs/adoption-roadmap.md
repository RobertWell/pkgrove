# PkgroveKit multi-repository adoption roadmap (HEL-239)

**Purpose.** Drive library evolution from real adopters, not speculative APIs.
Every capability must be validated by at least one production consumer; the
library grows only where functionality is generic across multiple applications.
This roadmap sequences adoption across the in-house repositories, records the
reusable adoption pattern, and states what is *proven* versus *planned*.

Companion documents: the per-module verdict census lives in
[pkgrovekit-adoption-decision-table.md](pkgrovekit-adoption-decision-table.md)
(HEL-171, every module scored exactly once); this file is the **program plan**
built on top of it — status, sequencing, the reuse pattern, and onboarding.

## Coordinates and version guidance

- **Published release: `0.5.0`** on Maven Central under the verified namespace
  `com.pkgrove` (13 modules; deployment 038e91d3, tag `v0.5.0`). Java packages
  are `com.pkgrove.pkgrovekit.*` — only the Maven coordinates moved during the
  RowRelay→PkgroveKit rename (see [getting-started.md](getting-started.md)).
- **Tokenless resolve is a requirement, not a hope.** Adopters depend on the
  plain `mavenCentral()` / default Maven Central repo — no GitHub Packages
  token, no `ci_settings.xml`, no registry URL. Verified below.
- **Pin exact versions.** Every published version is immutable; no `-SNAPSHOT`.
  Dev builds (`-Pdev` → `0.5.0-dev.<sha>`) are never published and must never
  appear in a consumer POM.
- **Import only the modules a scenario needs** (module table in
  [getting-started.md](getting-started.md)). JDBC-only consumers never receive
  JDBI transitively; drivers stay consumer-controlled (`compileOnly` in the
  adapter modules).

## Per-repository adoption status

| Repo / module | Status | PkgroveKit modules | Migration surface (evidence) |
|---|---|---|---|
| **AuditPatchX/backend** | **ADOPTED** (HEL-120 / 162 / 238) | `pkgrovekit-jdbi`, `pkgrovekit-oracle`, `pkgrovekit-core` | In production. `backend/pom.xml` declares `com.pkgrove:pkgrovekit-jdbi` + `:pkgrovekit-oracle` at `${pkgrovekit.version}` = **0.4.0** (pom lines 25, 161–169); source imports `JdbiReader`, `OracleDialect`, `OracleValueReader`, `core.{Row,Choice,fold}` in `ComparePlanner.kt` and `DatabaseService.kt`. Read/compare/planning delegate to PkgroveKit; the write path is a documented app-shape boundary (`docs/hel-162-write-path-pkgrovekit-audit.md`). Parity gate 141/0/3 against live Oracle. **Action: bump 0.4.0→0.5.0** to stay current (mechanical). |
| **hello-stock/backend** | **CANDIDATE** (HEL-190) | `pkgrovekit-jdbi`, `pkgrovekit-postgres` | Not yet integrated (no `com.pkgrove` refs in `backend/pom.xml`). Uses JDBI3 + `quarkus-jdbc-postgresql` (pom lines 221, 266, 277–288) with hand-rolled batch/upsert/retry across 10+ `org.mystock.repository.*RepositoryImpl` files (`StockHistRealRepositoryImpl`, `TransactionRepositoryImpl`, `BasicStockFeatureRepositoryImpl`, …). Entry point: `JdbiBatchWriter` inside the existing `useHandle` scope + `PostgresDialect.upsertSql/insertSql` (**batch-write methods only**). **Highest-risk integration** — prod daily-history/order pipeline; roll out behind `Legacy*` impls with a parity gate. |
| **hello-stock/AdvancedFeatures** | **CANDIDATE** (HEL-190) | `pkgrovekit-jdbi`, `pkgrovekit-postgres` | Near-duplicate `org.mystock.repository` tree of the backend (`StockHistRealRepositoryImpl`, `StockHistNormalizedRepositoryImpl`, `impl/StockDataNormalizedRepositoryImpl`, …) — md5-divergent copies where the same bugs get fixed twice. Same entry point and modules as the backend, **separate commit**. Adopting here is where the *duplicated-LOC-removed* metric is actually earned. |
| **QuerySkiff/backend-jvm** | **NO-GO** (HEL-191 verdict, 2026-08-09) | — | The timeboxed spike measured PkgroveKit `Transfer` vs engine-side `CREATE TABLE AS SELECT` for virtual-dataset promotion: **CTAS is ~543× faster than default Transfer and ~7× faster than the DuckDB-appender bulk path**, because QuerySkiff's promotion target reads the *same* MinIO Parquet as the source (DuckDB `read_parquet`, Trino Hive external table) — row transfer through the JVM solves a problem QuerySkiff does not have. Verdict doc: `QuerySkiff/docs/hel-191-pkgrovekit-spike-verdict.md`. **Do not build `pkgrovekit-trino`** (fails both bars: slower than CTAS, no credible second consumer). QuerySkiff stays engine-side; revisit only if a genuine cross-engine move (not same-storage materialization) appears. |
| **FinControl/backend** | CANDIDATE (weak) | — | Hibernate ORM Panache owns the row mapping; only `Import.kt` (~473 ln) is JDBC-ish. Forcing PkgroveKit under Panache adds a second row model for **no dedup win**. Not now. |
| **hello-sre-cred-portal** | CANDIDATE (weak) | — | 1 SQL file, sqlite-jdbc + Agroal. Dependency cost > value. Not now. |

## Sequencing

1. **Keep AuditPatchX green and current** — the proof-of-reuse anchor. Bump the
   pinned coordinate 0.4.0→0.5.0 (mechanical; parity gate re-runs). Depth grew
   on 2026-08-09: the compare source read now **streams** through
   `JdbiReader.read`/`JdbiRowStream` (HEL-238), exercising the streaming API in
   production code, not just tests.
2. **hello-stock backend + AdvancedFeatures (HEL-190)** — the second materially
   different adopter (PostgreSQL batch upsert vs AuditPatchX's Oracle
   read/compare) and the largest duplicated-LOC win. Do the backend first behind
   `Legacy*` impls + a golden-day/conflict-branch/identifier parity gate, then
   apply the identical change to AdvancedFeatures as a separate commit to kill
   the copy-divergence.
3. ~~QuerySkiff~~ — **retired from the roadmap** by the HEL-191 NO-GO
   (2026-08-09): promotion stays engine-side CTAS; no read-path adoption is
   directed without a consumer pull (adding the library to a system whose
   preferred architecture is engine-side SQL would be adoption for its own
   sake). The training-record publisher (hello-stock `ML_TRAIN/publication`,
   HEL-264) instead became the first **`pkgrovekit-storage-s3`** consumer —
   a third materially different usage (object storage, atomic single-PUT).
4. **Re-evaluate the weak candidates** only if their JDBC surface grows a real
   bulk path (FinControl import/export, cred-portal). Not before.

The **two materially different consumers** acceptance now reads: AuditPatchX
(Oracle read/compare/streaming, ADOPTED) + hello-stock (PostgreSQL batch
upsert, HEL-190 in flight) — with HEL-264's storage-s3 publisher as a third
axis (object storage) once the storage modules ship in a release.

## The reusable adoption pattern (golden path)

Every adopter uses the same shape; only the dialects and SQL differ. The
homepage snippet is a CI-compiled test
(`integration-tests/.../QuickStartExamples.kt`):

```kotlin
val relay = Relay.build {
    database(Source, sourceDataSource, <SourceDialect>)   // configured ONCE, at startup
    database(Target, targetDataSource, <TargetDialect>)
}
// define an immutable plan → execute → exhaustively handle TransferOutcome
```

Pick the module set by scenario (from [getting-started.md](getting-started.md)):

| Scenario | Modules | In-repo example |
|---|---|---|
| Oracle read / compare (no ETL engine) | `pkgrovekit-jdbi` + `pkgrovekit-oracle` + `pkgrovekit-core` | AuditPatchX (shipped) |
| PostgreSQL batch upsert inside existing JDBI handles | `pkgrovekit-jdbi` + `pkgrovekit-postgres` | hello-stock backend/AdvancedFeatures (planned) |
| DuckDB streaming result reads | `pkgrovekit-jdbc` + `pkgrovekit-duckdb` | QuerySkiff (spike) |
| Full source→sink ETL | `pkgrovekit-transfer` + adapter module(s) | golden path |

**Boundary rule (HEL-239 scope §5):** application-specific policy stays out of
the library. AuditPatchX proved this — its write path is app-shaped and lives in
the consumer, documented in `docs/hel-162-write-path-pkgrovekit-audit.md`, not
pushed into PkgroveKit. A feature is added to the library only when a **second**
real consumer needs the same generic behavior; single-narrow-consumer requests
are rejected unless explicitly flagged experimental.

## How a new consumer onboards

1. Add the dialect + facade modules for your scenario at the current release
   (`com.pkgrove:pkgrovekit-*:0.5.0`) from plain Maven Central — no token, no
   custom repository (see AuditPatchX `backend/pom.xml` for a real POM).
2. Keep your JDBC driver consumer-controlled; the adapter modules declare it
   `compileOnly`.
3. Declare `DatabaseKey` identities and `Relay.build { database(...) }` once at
   startup; never open/close connections in workflow code.
4. Write plans as immutable values, execute, and exhaustively `when` over
   `TransferOutcome` (`Partial` carries the resume checkpoint).
5. Keep application-specific mapping/policy in your repo. If you need a new
   generic capability, file it against PkgroveKit **with the concrete scenario**
   — it will be accepted only if a second adopter can use it.

## API-change ledger (adopter-driven)

Record here every public-API change requested by an adopter, so growth is
traceable to concrete usage (HEL-239 scope §7):

| API change | Requested by | Linear | Status |
|---|---|---|---|
| `pkgrovekit-postgres` first-class PG adapter (upsert `ON CONFLICT`, identifier fold) | hello-stock PostgreSQL need | HEL-127 | Shipped (0.x) |
| Documented app-shape write-path boundary (kept OUT of library) | AuditPatchX | HEL-162 | Shipped (consumer-side) |
| DuckDB S3/`httpfs` object-storage helpers (`S3Session`/`S3Publisher`) | QuerySkiff MinIO Parquet | HEL-236 | **Unreleased** — pending next cut |

New entries land here as each adopter files a request; empty-speculation
features do not.

## Validation — proven vs aspirational

**Proven now:**

- **Reusable adoption is real, not theoretical.** AuditPatchX consumes
  `com.pkgrove:pkgrovekit-{jdbi,oracle}` in production with a live-Oracle parity
  gate (141/0/3). This is the existing proof that the published coordinates
  resolve and the API works for a real second-party consumer.
- **Tokenless resolve from Maven Central, verified this session** (bounded
  `curl` HEAD against `repo1.maven.org`, no auth):

  | Coordinate | 0.5.0 |
  |---|---|
  | `com.pkgrove:pkgrovekit-transfer` | HTTP 200 |
  | `com.pkgrove:pkgrovekit-duckdb` | HTTP 200 |
  | `com.pkgrove:pkgrovekit-postgres` | HTTP 200 |
  | `com.pkgrove:pkgrovekit-jdbi` | HTTP 200 |
  | `com.pkgrove:pkgrovekit-oracle` | HTTP 200 |

  All five adopter-facing modules resolve without a token, confirming a new
  consumer can `implementation("com.pkgrove:pkgrovekit-*:0.5.0")` off plain
  `mavenCentral()`.
- **Every major public API maps to a real repository scenario** — the module
  table above ties each module to a shipped or planned in-repo consumer; none is
  speculative.

**Aspirational / planned (not yet proven):**

- hello-stock backend + AdvancedFeatures adoption (HEL-190) — planned, not
  integrated; the duplicated-LOC-removed metric is estimated (~600–1,000 lines
  per module) until the migration lands.
- QuerySkiff full DuckDB S3 read path — the result-path modules are on Central,
  but the S3 convenience layer (HEL-236) is unreleased, so QuerySkiff cannot
  adopt its ideal path until the next release. The plain `JdbcReader` result
  path is adoptable today.
