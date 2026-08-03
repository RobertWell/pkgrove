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
- **Explicit transactions**: atomic / chunked / savepoint policies; partial
  completion is structurally distinct from success, with the exact resume point.
- **Cancellation**: cooperative tokens propagate to `Statement.cancel`; never
  normalized into a business failure.
- **Mapping validation**: renames/omissions validated against the real schema
  before any write; values land by NAME, never by SELECT order.
- **Nothing lossy is silent**: conversions warn or reject; unrepresentable
  types are named, unsafe identifiers refused (and never echoed).

## Learn more

| | |
|---|---|
| [Getting started](docs/getting-started.md) | dependency setup, modules, first workflow |
| [Workflow style](docs/workflow-style.md) | conventions, API tiers, fan-out/concurrency |
| [Transactions](docs/TRANSACTIONS.md) | outcomes, retries, checkpoints, policies |
| [Adapters](docs/adapters/) | Oracle, DuckDB, PostgreSQL specifics |
| [Reference](docs/reference/) | low-level JDBC/JDBI APIs, configuration, errors |
| [Architecture](docs/ARCHITECTURE.md) · [ADRs](docs/adr/) | boundaries and decisions |
| [Security](docs/SECURITY.md) | CVE gate, SBOM, dependency verification |

Versioning: pre-stable `0.x`, immutable releases only (MAJOR = breaking API or
major workflow redesign, MINOR = backward-compatible enhancement, PATCH = fix;
`0.x` is not a blanket exception). Dev builds carry commit identity
(`-Pdev` → `0.2.0-dev.<sha>`) and are never published. See `CHANGELOG.md`.

## License

MIT — see [`LICENSE`](LICENSE). © 2026 RobertWell.
