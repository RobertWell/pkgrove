# PkgroveKit

**Move rows between databases with intent-describing application code — PkgroveKit
owns the connections, statements, batching, transactions, and honest outcomes.**

- **Status:** pre-stable (`0.2.0`). APIs may change before `1.0.0`; every
  published version is an **immutable release** — no `-SNAPSHOT`.
- **Requirements:** Java 21+, Kotlin 1.9+ (consumers may be pure Java).
- **Framework-neutral:** no Quarkus/Spring/JPA/REST dependencies in any artifact.

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

## Learn more

- **Framework adapters (optional):** [docs/framework-adapters.md](docs/framework-adapters.md) —
  Quarkus (CDI/Agroal) and Spring Boot (auto-configuration/HikariCP) integration
  over framework-owned pools; `@Transactional`-bound `JoinExisting` for Spring.
- **Cross-database coordination (optional):** [docs/coordination.md](docs/coordination.md) —
  when a transfer genuinely must be atomic across two XA-capable databases
  (`pkgrovekit-coordination-api` + `pkgrovekit-jta` + `pkgrovekit-narayana`),
  and when it must NOT (saga / staging-and-publish). Standard modules never
  receive JTA/Narayana — CI enforces it (`assertCoordinationIsolation`).


| | |
|---|---|
| [Getting started](docs/getting-started.md) | dependency setup, modules, first workflow |
| [Workflow style](docs/workflow-style.md) | conventions, API tiers, fan-out/concurrency |
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
