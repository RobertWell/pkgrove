# RowRelay adoption — consolidated per-module decision table (HEL-171)

Cycle-3 portfolio assessment, regenerated 2026-08-03 against the live working tree of every
repo under the CodeBase root. Every active module appears **exactly once**. Verdicts:
**ADOPTED** (consumes `com.pkgrove:rowrelay-*` today), **CANDIDATE** (JVM + JDBC data
movement — a real integration proposal exists or should), **LIBRARY** (RowRelay itself),
**N/A-\*** (structurally out of scope: frontend / Python / no-DB / GitOps).

## Inventory reconciliation

The original assessment said "~32 modules, ~27 NOT_APPLICABLE" as a grouped summary. The
complete enumeration below yields **48 modules**: 30 assessable application/service modules
(the "~32" of the summary, now exact), plus 9 RowRelay/library modules and 9 pure-GitOps
directories the summary had collapsed. Counts: **1 ADOPTED · 5 CANDIDATE · 9 LIBRARY ·
5 N/A-frontend · 15 N/A-python · 5 N/A-no-db · 9 N/A-gitops** (49 rows incl. the datakit
sibling flagged at the end).

## Full-detail rows — ADOPTED + CANDIDATE

| Field | AuditPatchX/backend | hello-stock/backend | hello-stock/AdvancedFeatures | QuerySkiff/backend-jvm | FinControl/backend | hello-cred-portal |
|---|---|---|---|---|---|---|
| **Verdict** | **ADOPTED** | **CANDIDATE** → [HEL-190](https://linear.app/hellostock/issue/HEL-190) | **CANDIDATE** → [HEL-190](https://linear.app/hellostock/issue/HEL-190) | **CANDIDATE** → [HEL-191](https://linear.app/hellostock/issue/HEL-191) | CANDIDATE (weak) | CANDIDATE (weak) |
| Runtime | Kotlin/Quarkus (Maven) | Kotlin/Quarkus (Maven) | Kotlin/Quarkus (Maven) | Kotlin (Maven) | Kotlin/Quarkus (Maven) | Kotlin/Quarkus |
| Current data path | JDBI + Oracle via RowRelay (read/compare); app-shape write helpers (HEL-162 audited boundary) | JDBI3 + quarkus-jdbc-postgresql, hand-rolled batch/upsert repos | Near-duplicate `org.mystock.repository` tree of backend | Plain JDBC: DuckDB + Trino connections, hand-rolled | Hibernate ORM Panache (+1 JDBC import path) | sqlite-jdbc + Agroal, 1 SQL file |
| SQL surface | 5 files | **61 files** (`StockHistRealRepositoryImpl` 888 ln, `StockHistNormalizedRepositoryImpl` 737 ln) | 20 files (`StockHistRealRepositoryImpl` 882 ln) | 6 files (`ApiResource` 309 ln, `Registrar` 188 ln) | 3 files (`Import.kt` 473 ln) | 1 file |
| Ownership model | Quarkus Agroal datasource; per-request `jdbi.inTransaction` | Quarkus Agroal; JDBI handles | Quarkus Agroal; JDBI handles | Hand-rolled per-request connections | Quarkus Agroal via ORM | Quarkus Agroal |
| Duplication vs RowRelay | Resolved: identifiers/read/planning delegate; write path documented app-shape (`docs/hel-162-write-path-rowrelay-audit.md`) | ~600–1,000 lines of batch/upsert/retry mechanics duplicating `JdbiBatchWriter`+`PostgresDialect.upsertSql` | The SAME lines again (md5-divergent copies — bugs fixed twice) | Connection/read/type handling duplicating `JdbcReader`/`DuckDbDialect` | Low — ORM owns the mapping; only `Import.kt` is JDBC-ish | Trivial surface |
| Expected RowRelay entry point | (in place) `JdbiReader`+`OracleValueReader`+`OracleDialect` | `JdbiBatchWriter` inside existing `useHandle` scope + `PostgresDialect.upsertSql/insertSql` (batch-write methods ONLY) | Same as backend, separate commit | `JdbcReader`+`DuckDbDialect` for result paths; Trino stays vendor-JDBC | None now; revisit only if import/export grows a bulk path | None now — surface too small to pay for a dependency |
| Risks | — (baseline; parity 141/0/3 gate) | **Highest-risk integration**: prod daily history/order pipeline ×2 services; PG lowercase-fold vs created identifiers; numeric scale/timestamp parity | Same + copy-divergence | Engine flag duckdb/trino split; soak (HEL-131) in flight — do not churn mid-soak | ORM double-mapping risk — forcing RowRelay under Panache adds a second row model for no dedup win | Dependency cost > value |
| Test/CI impact | Suite already CI-gated w/ live Oracle | Testcontainers-PG suites must stay green; add parity gate (golden day, conflict branch, identifier test) | Same, separate run | Soak gate + existing IT | None | None |
| Operational impact | None (shipped) | Rollout behind `Legacy*` impls; digest-pinned deploy ×2 services | Same | None until post-soak | None | None |
| Rollback | n/a | Keep `Legacy*` impls one release; injection flip, no rebuild | Same | Revert commit | n/a | n/a |
| Size / Confidence | — | M per module / high | M / high | M / medium | S / high (that the answer is "not now") | S / high (same) |

## Complete inventory — every remaining module, exactly once

| Module (repo/path) | Runtime | Data path | Ownership | Verdict | Evidence / note |
|---|---|---|---|---|---|
| rowrelay/rowrelay-core | Kotlin/JVM | library core | n/a | LIBRARY | the library itself |
| rowrelay/rowrelay-jdbc | Kotlin/JVM | JDBC engine | n/a | LIBRARY | " |
| rowrelay/rowrelay-jdbi | Kotlin/JVM | JDBI facade | n/a | LIBRARY | " |
| rowrelay/rowrelay-oracle | Kotlin/JVM | Oracle dialect | n/a | LIBRARY | " |
| rowrelay/rowrelay-postgres | Kotlin/JVM | PG dialect | n/a | LIBRARY | " |
| rowrelay/rowrelay-duckdb | Kotlin/JVM | DuckDB dialect | n/a | LIBRARY | " |
| rowrelay/rowrelay-transfer | Kotlin/JVM | transfer engine | n/a | LIBRARY | " |
| rowrelay/integration-tests | Kotlin/JVM | live-DB tests | n/a | LIBRARY | never published |
| AuditPatchX/frontend | JS/Vite | REST | none | N/A-frontend | SPA |
| hello-stock/frontend | JS/Vite | REST | none | N/A-frontend | SPA |
| QuerySkiff/frontend | JS/Vite | REST | none | N/A-frontend | SPA |
| FinControl/frontend | JS/Vite | REST | none | N/A-frontend | SPA |
| WeddingPage | static HTML/JS | Google Sheets | none | N/A-frontend | Apps Script |
| hello-stock/StockTrend | Python | psycopg2 + boto3 | per-script | N/A-python | JVM library can't help |
| hello-stock/StableStock | Python | psycopg2 + fastapi | per-script | N/A-python | " |
| hello-stock/StockTraderServer | Python/FastAPI | psycopg2 + shioaji | per-request | N/A-python | " |
| hello-stock/local_dry_run | Python | cx_Oracle + SQLAlchemy | engine | N/A-python | " |
| hello-stock/transaction_collector | Python/FastAPI | shioaji only | none | N/A-python | no DB |
| hello-stock/ShioajiDryRunServer | Python/FastAPI | shioaji only | none | N/A-python | no DB |
| hello-stock/BasicStockPoolPro | Python | psycopg2 | per-script | N/A-python | |
| hello-stock/BasicPoolAnalysis | Python | oracledb + sqlite3 | per-script | N/A-python | |
| hello-stock/StockTrendPro | Python | boto3/S3 | none | N/A-python | object storage |
| hello-stock/ML_TRAIN | Python | training files | none | N/A-python | |
| hello-stock/TransactionAnalysis | Python | oracledb | per-script | N/A-python | |
| StableStock (standalone RL) | Python | oracledb | per-script | N/A-python | RL repo |
| QuerySkiff/backend (legacy py) | Python/FastAPI | duckdb-python | per-request | N/A-python | retirement = HEL-149 |
| HouseSurvey | Python/Flask | sqlite3 | per-request | N/A-python | |
| hello-bob | Python | sqlite3 | per-script | N/A-python | |
| hello-stock/CentralPanel | Python | REST panel | none | N/A-no-db | |
| hello-stock/health_check | Python | REST checks | none | N/A-no-db | |
| hello-stock/DB_INIT | SQL DDL | schema scripts | apply-once | N/A-no-db | not an app runtime |
| FinControl (root pom) | Maven reactor | none | none | N/A-no-db | aggregator |
| HelloLineBot | Kotlin/Quarkus | REST/AI only | none | N/A-no-db | JVM but **zero** SQL/JDBC |
| hello-stock/argocd · helm · operations · hello-stock-prod | YAML | none | none | N/A-gitops | 4 dirs |
| hello-sre | Helm/YAML (+py exporters) | monitoring-only reads | none | N/A-gitops | sql-exporter ≠ data movement |
| hello-sre-cred-portal (observability dirs) | YAML | none | none | N/A-gitops | |
| ArgoCD | Shell/YAML | none | none | N/A-gitops | |
| ryanlin2-control | YAML | none | none | N/A-gitops | prod GitOps |

**Flag — `datakit/`**: a full JVM data-movement library mirroring RowRelay's exact module
layout under groupId `internal.datakit` — the pre-rename twin left from the HEL-120 era.
It is NOT consumed by anything RowRelay-relevant; recommend deletion/archival as Cycle-4
hygiene (it will otherwise rot into a confusing shadow of the published library).

Skipped (non-modules): tooling/cache dirs (`node_modules`, `.claude`, `.playwright-mcp`),
empty dirs (`remote`, `hello-quarkus-stack`), model/data artifacts (`ML_TRAIN/runtime`,
`catboost_info`, `artifacts`), scratch/asset files at repo roots.
