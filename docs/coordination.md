# Cross-database coordination (HEL-170)

Ordinary PkgroveKit transfers are **not** globally atomic, and the standard
modules never pretend otherwise: one database resource, one local
`TransactionPolicy`, honest `TransactionOutcome`s. When cross-database
atomicity is genuinely required, the **optional** coordination layer provides
it — visible in your imports, never ambient.

## Module map

| Module | What it gives you | Pulls in |
| -- | -- | -- |
| `pkgrovekit-coordination-api` | Inert plans, participant capabilities, typed validation, typed global outcomes | nothing (JDK only) |
| `pkgrovekit-jta` | Interpretation of `Xa2Pc` plans through an external Jakarta `TransactionManager` | `jakarta.transaction-api` |
| `pkgrovekit-narayana` | Standalone Narayana wiring (`Narayana.standalone(...)`) | Narayana + jboss-logging |
| `pkgrovekit-saga` | Compensation interpreter for non-XA resources | nothing beyond the API |

The root build task `assertCoordinationIsolation` (wired into `check`) fails CI
if any of these ever leaks onto a standard module's runtime classpath.

## Choosing a strategy

| You need | Use | Guarantee | Cost |
| -- | -- | -- | -- |
| One database, all-or-nothing | `TransactionPolicy.Atomic` (standard modules) | local ACID | none |
| One database inside a caller's transaction | `TransactionPolicy.JoinExisting` | caller's ACID | none |
| Two+ **XA-capable** databases, atomic | `CoordinationPolicy.Xa2Pc` via `pkgrovekit-jta` | global 2PC through the TM | recovery log, coordinator identity, XA-enabled resources |
| Non-XA resources (DuckDB, files, HTTP) | `CoordinationPolicy.Saga` via `pkgrovekit-saga` | completion **or compensation** — no isolation, no atomic visibility | idempotent steps, durable journal for resumability |
| Large cross-DB loads without 2PC | staging table + `Atomic` publish swap | atomic *visibility* at publish | staging space, no cross-DB atomicity mid-load |

DuckDB is treated as **non-XA always** — registration in `XaParticipants`
requires a real `XADataSource`, so a DuckDB/local participant cannot even be
declared XA-capable, and `Plans.validate` rejects it from `Xa2Pc` plans before
any connection is opened.

## XA quick start (two PostgreSQL resources, standalone Narayana)

```kotlin
val participants = XaParticipants.build {
    register("orders-db", pgXaDataSourceA)
    register("billing-db", pgXaDataSourceB)
}
val runtime = Narayana.standalone(
    objectStoreDir = Path.of("/var/lib/myapp/tx-store"),
    nodeIdentifier = "myapp-node-1",
)
val coordinator = runtime.coordinator(participants)

val plan = CoordinationPlan(
    CoordinationPolicy.Xa2Pc(Duration.ofSeconds(30)),
    participants.declarations(),
)

val result = coordinator.inGlobalTransaction(plan) { scope ->
    // Enlisted connections + JoinExisting: PkgroveKit appends, the TM commits.
    TransactionalWriter.write(
        scope.connection(ParticipantId("orders-db")), insertOrdersSql,
        orderBatches, TransactionPolicy.JoinExisting)
    TransactionalWriter.write(
        scope.connection(ParticipantId("billing-db")), insertBillingSql,
        billingBatches, TransactionPolicy.JoinExisting)
}

when (val o = result.outcome) {
    is GlobalOutcome.Committed       -> log.info("done: ${o.txId}")
    is GlobalOutcome.RolledBack      -> log.warn("rolled back: ${o.cause.message}")
    is GlobalOutcome.HeuristicMixed  -> alertOps("MIXED ${o.txId}: manual reconcile", o.participants)
    is GlobalOutcome.InDoubt         -> alertOps("IN DOUBT ${o.txId}: run recovery", o.participants)
    is GlobalOutcome.RecoveryPending -> alertOps("RECOVERY PENDING ${o.txId}", o.participants)
}
```

(The compiled, container-proven version of this lives in
`integration-tests/.../CoordinationXaIT.kt`.)

## Hard rules the types enforce

- No `xa = true` flag exists on ordinary `Transfer.Options` — a global
  transaction is a different *plan*, not a tweak.
- PkgroveKit never commits, rolls back or closes an enlisted connection: the
  scope hands out guard proxies that throw `EnlistedConnectionViolationException`
  on those verbs (and on use after scope completion), and pins each branch to
  the scope's owner thread (`ConcurrentScopeAccessException` otherwise).
- Invalid participant/policy combinations fail in `validate`/preflight with
  typed `PlanViolation`s — never after writes begin.
- The 2PC protocol itself belongs to the external TM (Narayana in the provided
  adapter). PkgroveKit contains no prepare/commit voting logic.

## Operational requirements (XA)

- **Recovery log (object store)**: `Narayana.standalone(objectStoreDir = …)`
  must point at durable storage. It is the only way in-doubt branches get
  resolved after a crash. Back it up like data.
- **Coordinator identity**: `nodeIdentifier` must be unique per coordinator and
  stable across restarts (recovery matches branches by it; ≤ 28 chars).
- **Timeouts**: `Xa2Pc(timeout)` is applied per transaction via the TM;
  resources hold locks until completion or timeout — keep it tight.
- **Crash recovery**: after a crash, restart with the same store + node id and
  run `NarayanaRuntime.recoveryScan()` (or a dedicated recovery process).
  `InDoubt` / `HeuristicMixed` / `RecoveryPending` outcomes reference the
  `TransactionId` to correlate with the store.
- **Postgres**: XA requires `max_prepared_transactions > 0` on every
  participating server (the integration proof runs with 16).
- **Oracle**: `oracle.jdbc.xa.client.OracleXADataSource` fits the same
  `XaParticipants.register(...)` seam; the reproducible CI proof uses two
  PostgreSQL containers, and Oracle-specific validation remains
  environment-gated like the other Oracle ITs (`PKGROVEKIT_ORACLE_URL`).

## Saga boundary (what it is NOT)

`pkgrovekit-saga` gives completion-or-compensation with a journal and
reverse-order compensation. It does **not** give isolation, atomic visibility,
or automatic durability (the in-memory journal loses progress on crash — plug a
durable `SagaJournal` for resumable sagas). Steps must be idempotent under
their `idempotencyKey`: on resume, steps the journal recorded as `COMPLETED`
are skipped for execution but still compensated if a later step fails.
`MANUAL_INTERVENTION_REQUIRED` is a real state — surface it to operators.
