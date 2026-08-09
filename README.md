# PkgroveKit

**Move rows between databases with intent-describing application code — PkgroveKit
owns the connections, statements, batching, transactions, and honest outcomes.**

- **Status:** pre-stable (`0.5.0`). APIs may change before `1.0.0`; every
  published version is an **immutable release** — no `-SNAPSHOT`.
- **Requirements:** Java 21+, Kotlin 1.9+ (consumers may be pure Java).
- **Framework-neutral:** no Quarkus/Spring/JPA/REST dependencies in any artifact.
- **Pay for what you select:** a hierarchy of small, single-purpose modules —
  add the capability you need and you get its transitive modules and nothing
  else (enforced in CI — HEL-235).

## Choose your use case

Add the module(s) for your scenario. Versions come from the `pkgrovekit-bom`
platform, so you declare them once and omit per-module versions. Full recipes
(exact deps, transitive present, notably absent, consumer-owned drivers) are in
**[docs/scenarios.md](docs/scenarios.md)** — each is backed by an executable
consumer fixture that CI asserts.

| I want to… | Add | You also get | You do NOT get |
|---|---|---|---|
| Do low-level dynamic JDBC | `pkgrovekit-jdbc` | core | transfer, jdbi, dialects, frameworks |
| Speak a specific dialect | `pkgrovekit-{oracle\|duckdb\|postgres}` | core, jdbc | the other dialects, transfer, frameworks |
| Run DB→DB transfers | `pkgrovekit-transfer` (+ a dialect) | core, jdbc, coroutines | jdbi, coordination, frameworks |
| Use JDBI handles | `pkgrovekit-jdbi` | core, jdbc, transfer, jdbi3 | dialects, coordination, frameworks |
| Wire it into **Spring Boot** | `pkgrovekit-spring-boot-starter` (+ a dialect) | core, jdbc, transfer | oracle/duckdb, **quarkus**, coordination |
| Wire it into **Quarkus** | `pkgrovekit-quarkus` (+ a dialect) | core, jdbc, transfer | postgres/duckdb, **spring**, coordination |
| Commit atomically across 2 XA DBs | `pkgrovekit-narayana` | coordination-api, jta | the whole data-access spine, saga |
| Coordinate without distributed ACID | `pkgrovekit-saga` | coordination-api | jta, narayana, the data-access spine |

**Recommended combinations:** `spring-boot-starter` + `postgres`; `quarkus` +
`oracle`; `oracle` + `duckdb` for cross-engine copies; `jdbi` alone for
JDBI-native apps. Drivers are **always consumer-controlled** — add the exact
JDBC driver your deployment needs (every adapter keeps its driver `compileOnly`).

## Four kinds of module

- **Default (the one obvious path):** `core` → `jdbc` → `transfer` + a dialect
  (`oracle`/`duckdb`/`postgres`). This is what most consumers use.
- **Advanced / low-level:** `jdbc` directly, or `jdbi` for JDBI-native code.
- **Framework adapters (opt-in):** `spring-boot-starter`, `quarkus`. Each
  depends only on `transfer` and discovers dialects at runtime via
  `SqlDialectProvider` — the framework stays `compileOnly` and never leaks onto
  a standard module. `spring ↛ quarkus` and `quarkus ↛ spring`.
- **Coordination (opt-in, orthogonal):** `coordination-api` → `jta` →
  `narayana` (distributed ACID), or `saga` (compensation). Strictly opt-in —
  JTA/Narayana/XA are **absent** from every standard module's runtime classpath
  (CI-enforced).

## The module hierarchy

```text
data-access spine                coordination (opt-in)      frameworks (opt-in)
─────────────────                ─────────────────────      ───────────────────
core                             coordination-api           spring-boot-starter ─┐
 └─ jdbc                          ├─ jta ─ narayana          quarkus ─────────────┤
     ├─ transfer                  └─ saga                                         │
     ├─ oracle                                              each depends only on ─┘
     ├─ duckdb                    dialects are discovered    transfer; dialects are
     ├─ postgres                  at RUNTIME by the          resolved via ServiceLoader
     └─ jdbi (─ transfer)         framework adapters
```

The allowed edges live in a machine-readable map
([`gradle/allowed-dependencies.txt`](gradle/allowed-dependencies.txt)) and are
enforced by `./gradlew assertModuleHierarchy` (see
[docs/adr/0003-module-hierarchy.md](docs/adr/0003-module-hierarchy.md)).

## Tutorials

| | |
|---|---|
| [Getting started](docs/getting-started.md) | dependency setup, modules, first workflow |
| [Dependency recipes](docs/scenarios.md) | the 8 scenarios above, in full |
| [Workflow style](docs/workflow-style.md) | conventions, API tiers, fan-out/concurrency |
| [Transformations](docs/transformations.md) | SQL vs row mapping vs batches vs ordered grouping |
| [Transactions](docs/TRANSACTIONS.md) | outcomes, retries, checkpoints, policies |

## The PkgroveKit coding style

1. **Infrastructure is configured once** — identities, pools, dialects, and
   budgets at startup; never in workflow code.
2. **Workflows are immutable values** — defining one performs no I/O and holds
   no connection, credential, cursor, or transaction.
3. **Invalid states are unrepresentable** — only a complete plan (source *and*
   sink) can reach an executor; an incomplete plan fails at definition time.
4. **SQL is named and readable** — multiline SQL with `:named` parameters;
   positional `?` is a low-level compatibility API.
5. **Transformations are pure** — small deterministic functions, unit-testable
   without a database.
6. **Effects and policies are explicit** — mapping, upsert identity, transaction
   shape, and batching are declared on the plan, not hidden in mechanics.
7. **Outcomes are typed** — a sealed model where partial completion can never
   read as success and cancellation is never swallowed.
8. **One obvious path, explicit escape hatches** — the managed runtime is the
   default; advanced and low-level APIs exist and are documented as such.

## The golden path

Compiled and run in CI (`integration-tests/.../QuickStartExamples.kt`):

```kotlin
Relay.build {
    database(Source, sourceDataSource, OracleDialect)    // configured ONCE, at startup
    database(Target, targetDataSource, DuckDbDialect)
}.use { relay ->
    val trades = relay.transfer("synchronize-trades") {
        from(Source) {
            query("""
                select id, symbol, price
                from trades
                where price >= :floor
            """.trimIndent())
            bind("floor", 10.0)
        }
        transform(::normalize)                           // pure, unit-testable
        to(Target, table = "trades") {
            rename("symbol", "ticker")                   // mapping by NAME
            upsertBy("id")                               // idempotent identity
        }
    }

    when (val outcome = relay.execute(trades)) {
        is TransferOutcome.Completed -> recordSuccess(outcome.report)
        is TransferOutcome.Partial   -> scheduleResume(outcome.checkpoint)
        is TransferOutcome.Rejected  -> reportInvalidPlan(outcome.reason)
        is TransferOutcome.Failed    -> reportFailure(outcome.cause)
        is TransferOutcome.Cancelled -> recordCancelled()
    }
}
```

## Who owns what

```text
your application                          PkgroveKit
────────────────────────────────────────  ─────────────────────────────────────
database identities + pools (startup)     connection leasing + budgets + release
SQL and named parameters                  statement preparation + binding
pure transformations                      streaming, batching, bounded memory
mapping / upsert / transaction CHOICE     mapping validation, DDL, MERGE/upsert
handling the typed outcome                commit/rollback choreography, cleanup,
                                          honest partial reports, cancellation
```

## Safety guarantees

- **Bounded resources**: per-database lease budgets, fetch-size-bounded
  streaming, one read batch in flight; branches never share a connection.
  The fetch-size bound is *enforced*, not assumed: `fetchSize` alone does not
  make every driver stream (pgjdbc ignores it outside a transaction and buffers
  the whole result set), so each read applies its source's declared streaming
  requirements — and where PkgroveKit may not reconfigure the connection it
  refuses or warns rather than quietly buffer. See [docs/RESOURCES.md](docs/RESOURCES.md).
- **Explicit transactions**: atomic / chunked / savepoint policies; partial
  completion is structurally distinct from success, with the exact resume point.
- **Cancellation**: cooperative tokens propagate to `Statement.cancel`; never
  normalized into a business failure.
- **Mapping validation**: renames/omissions validated against the real schema
  before any write; values land by NAME, never by SELECT order.
- **Nothing lossy is silent**: conversions warn or reject; unrepresentable
  types are named, unsafe identifiers refused (and never echoed).

## Artifact reference (detailed)

The scenario guide above is the fast path. This section is the exhaustive,
artifact-level reference.

- **Framework adapters (optional):** [docs/framework-adapters.md](docs/framework-adapters.md) —
  Quarkus (CDI/Agroal) and Spring Boot (auto-configuration/HikariCP) integration
  over framework-owned pools; `@Transactional`-bound `JoinExisting` for Spring.
  Each adapter depends only on `transfer` and discovers dialects at runtime.
- **Cross-database coordination (optional):** [docs/coordination.md](docs/coordination.md) —
  when a transfer genuinely must be atomic across two XA-capable databases
  (`pkgrovekit-coordination-api` + `pkgrovekit-jta` + `pkgrovekit-narayana`),
  and when it must NOT (saga / staging-and-publish). Standard modules never
  receive JTA/Narayana — CI enforces it (`assertModuleHierarchy`,
  `assertCoordinationIsolation`).


| | |
|---|---|
| [Dependency recipes](docs/scenarios.md) | the 8 scenarios in full: exact deps, present, absent, drivers |
| [Module hierarchy ADR](docs/adr/0003-module-hierarchy.md) · [allowed graph](gradle/allowed-dependencies.txt) | the enforced boundary + BOM design (HEL-235) |
| [Transformations](docs/transformations.md) | decision guide: SQL vs row mapping vs batches vs ordered grouping |
| [Transactions](docs/TRANSACTIONS.md) | outcomes, retries, checkpoints, policies |
| [Adapters](docs/adapters/) | Oracle, DuckDB, PostgreSQL specifics |
| [Reference](docs/reference/) | low-level JDBC/JDBI APIs, configuration, errors |
| [Architecture](docs/ARCHITECTURE.md) · [ADRs](docs/adr/) | boundaries and decisions |
| [Adoption roadmap](docs/adoption-roadmap.md) · [decision table](docs/pkgrovekit-adoption-decision-table.md) | per-repo adoption status, sequencing, reuse pattern, onboarding (HEL-239) |
| [Security policy](SECURITY.md) · [security controls](docs/security-controls.md) | reporting a vulnerability · CVE gate, SBOM, dependency verification |
| [Test traceability](docs/test-traceability.md) | scenario-to-test matrix · **enforced coverage gates** (HEL-234) |

Quality gates (HEL-234): every production module runs under JaCoCo and its
`check` **fails** below 80% line / 70% branch coverage (85/75 for the critical
`pkgrovekit-jdbc`, `pkgrovekit-transfer`, `pkgrovekit-jta`,
`pkgrovekit-coordination-api`); `./gradlew jacocoAggregatedVerification`
enforces the same floor repository-wide, and the Postgres testcontainer suite
is a blocking PR check (`:integration-tests:postgresIntegrationTest`).
Coverage reports (XML + HTML) are published as CI artifacts on every build.

Versioning: pre-stable `0.x`, immutable releases only (MAJOR = breaking API or
major workflow redesign, MINOR = backward-compatible enhancement, PATCH = fix;
`0.x` is not a blanket exception). Dev builds carry commit identity
(`-Pdev` → `0.2.0-dev.<sha>`) and are never published. See `CHANGELOG.md`.

## Security

Found a vulnerability? **Do not open a public issue.** Report it privately via
GitHub Private Vulnerability Reporting — see the [Security Policy](SECURITY.md)
for the reporting route, supported versions, response expectations, and the
coordinated-disclosure / GHSA-CVE workflow. The project's automated supply-chain
gates (CVE scan, SBOM, dependency verification, CodeQL) are documented in
[docs/security-controls.md](docs/security-controls.md).

## License

MIT — see [`LICENSE`](LICENSE). © 2026 RobertWell.
