# Workflow style — conventions and boundaries

The [homepage](../README.md) states the eight style rules; this guide covers
how to apply them and where each API tier belongs.

## API tiers — pick the level you need

| Tier | Use | Key types |
|---|---|---|
| **Recommended** | Real ETL/reporting work | `Relay.build`, `relay.transfer { }`, `relay.execute`, `TransferOutcome`, `Choice`, `JdbiReader` |
| **Advanced** | Fan-out over many flows, your own orchestration, caller-owned transactions | `Workflows.from(...).to(...)`, `Workflows.executeStructured`, `WorkflowOutcome`, `Transfer.run`, `TransactionPolicy` + `TransactionalWriter` |
| **Low-level** | Building a new adapter, or legacy positional code | `JdbcReader`, `JdbcBatchWriter`, `NamedSql`, `SqlDialect`, the `Row`/`Schema`/`RowBatch` primitives |

The recommended tier keeps your code free of `Connection`, commit/rollback, and
thread choreography. The lower tiers are fully supported — use them
deliberately, not by default.

## Where responsibilities live

- **Startup**: `Relay.build { database(...) }` — identities, pools, dialects,
  budgets. Nowhere else creates or closes connections.
- **Plan definitions**: immutable values. Hold them, log them, reuse them.
  An incomplete plan throws `PlanDefinitionException` at *definition* time.
- **Pure transformations**: top-level functions like
  `fun normalizeCustomer(row: Row): Row?`. Deterministic; no resource
  acquisition, no hidden I/O, no global mutation. Unit-test them with a
  hand-built `Schema` + `Row` — no database required. When a row map is not
  enough (batches, grouped calculations), pick the mode deliberately:
  [transformations.md](transformations.md).
- **Execution**: `relay.execute(plan)` for one transfer;
  `Workflows.executeStructured(flows, databases, maxConcurrency, policy)` for
  independent fan-out under bounded per-database budgets.
- **Outcome handling**: exhaustively `when` over the sealed outcome. `Partial`
  carries the checkpoint; make resumes idempotent via `upsertBy` or a
  `WHERE`-clause offset.

## Business routing vs execution failure

`Choice<L, R>` (with `mapLeft/mapRight/bimap/fold` and `partitionByChoice`)
routes rows down different pipelines — validate → accept | reject. A business
`Left` is a *valid* workflow path, never an execution failure; execution
failure lives in the outcome types. Don't encode rejection as thrown
exceptions inside transformations.

## Concurrency

Declare parallel *relationships*; never launch threads/coroutines around JDBC
resources yourself:

```kotlin
val outcome = Workflows.executeStructured(
    flows, databases,
    maxConcurrency = 4,
    policy = BranchPolicy.SUPERVISED,   // or FAIL_FAST
)
```

- Parallelism is capped by `maxConcurrency` AND each database's lease budget.
- `FAIL_FAST` cancels siblings on the first failure; `SUPERVISED` keeps them
  running and retains every outcome (`WorkflowOutcome.Partial` names the
  failed branches).
- Branches never share a connection; cancellation propagates structurally.

## Things the style forbids in application code

- `!!` on workflow state, nullable report+error pairs.
- `catch (Throwable)` around workflows (cancellation and JVM errors must
  propagate).
- String-interpolated identifiers or values into SQL.
- Positional `?` parameters on the recommended path (low-level API only).
- Manual `commit`/`rollback`/`Savepoint` outside a deliberate advanced-tier
  caller-owned transaction (see [TRANSACTIONS.md](TRANSACTIONS.md)).
