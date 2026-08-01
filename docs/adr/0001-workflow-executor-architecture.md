# ADR 0001 — Workflow executor architecture

Status: **Accepted** (HEL-167) · Supersedes the "no River implementation" note in WORKFLOWS.md (HEL-125).

## Context

RowRelay owns an immutable, inspectable **workflow algebra** (business routing via
`Choice<L,R>`, pure `map`/`filter`, effectful write nodes, typed
`WorkflowOutcome`). *How* a plan runs — scheduling, bounded parallelism,
cancellation, resource budgets, transaction affinity — is the **executor's**
job. The core rule:

> RowRelay owns the workflow language. Execution backends execute it without
> leaking backend-specific types into application plans.

Plans therefore contain **no** coroutine scope, thread pool, Temporal, Pekko, or
River objects. Executors are chosen behind one neutral seam:

```kotlin
interface WorkflowExecutor {
    suspend fun <A> execute(plan: ExecutablePlan<A>): WorkflowOutcome<A>
}
```

An executor **validates portability before execution** (a distributed backend
rejects a plan node that captures non-serializable local state rather than
silently falling back to local). Effectful nodes declare transaction,
idempotency, retry, checkpoint, and resource requirements; pure nodes are
distinguishable and freely replayable.

## Decision

**Default executor = Kotlin coroutines** (structured concurrency). Everything
else is optional, isolated, and evaluated below. `rowrelay-core` and the neutral
plan contracts take **zero** dependency on any executor.

## Options evaluated

### Kotlin coroutines — DEFAULT
Structured concurrency gives parent/child cancellation with no orphan work,
natural bounded parallelism (`Semaphore` + `limitedParallelism`), and a reusable
scope lifecycle (no thread-pool-per-call). Ties cleanly to the HEL-128 per-
database permit budgets and HEL-126 transaction affinity. **Adopt** for the
default local executor. Cost: a `kotlinx-coroutines-core` dependency on the
executor module only (not core, not the JDBC/dialect modules).

### Arrow — OPTIONAL interop
Arrow's `Either`/`Raise`/typed-error/resource conventions align with our
`Choice`/`WorkflowOutcome`. **Do not** make Arrow mandatory in the smallest core
module (dependency weight + Java-consumer ergonomics). Offer an optional
`rowrelay-arrow` interop module if a consumer wants `Either` conversions; our
`Choice` deliberately mirrors Left/Right so the mapping is trivial.

### Temporal — FUTURE durable seam only
The right tool when a workflow must survive process/machine failure (durable
timers, history replay). Database effects would run in Temporal *activities*;
Temporal types must never touch RowRelay core contracts. **Do not build** a
production Temporal backend here — only keep the `WorkflowExecutor` seam and the
effect-node portability rules that would let an adapter slot in later. Adoption
trigger: a real "must not lose in-flight ETL across a restart" requirement.

### Apache Pekko — proven distributed-worker need only
Actor/cluster model; justified only for dynamic distributed workers, cluster
membership, service discovery, or capability-aware dispatch. **Do not** adopt
for local parallel branches (coroutines already cover that). Adoption trigger: a
verified need for a self-forming worker cluster.

### Apache River / JGDMS — ALLOWED optional module (stance reversed)
Reversing HEL-125's "no River implementation": River is **not banned**. Its
lightweight discovery, leasing, and worker-capability-registration model is a
legitimate candidate for a *small* distributed executor, and lightweightness +
operational simplicity are valid criteria — not to be dismissed solely because
the project is in the Attic. **But** it is retired with a smaller maintenance
community, so any River/JGDMS adapter must:
- live in a separate optional module (`rowrelay-flow-river`);
- add **no** transitive dependency to `rowrelay-core` or the default executor;
- preserve the identical backend-neutral plan;
- pass dependency, CVE, Java-version, and operational review;
- **document a named owner** for maintenance, security patches, and releases;
- support clean removal/replacement without touching workflow definitions;
- demonstrate a real footprint/deployment/discovery/leasing advantage over
  coroutines (+ Pekko/Temporal) before adoption.
Adoption trigger: a distributed need where River's lighter footprint measurably
beats the maintained alternatives AND an owner accepts the retired-project risk.

### Flink / Hazelcast Jet — OUT of scope
Large-scale stream/batch platforms, not default RowRelay dependencies. Revisit
only if RowRelay's product scope deliberately expands into distributed
stream/batch processing.

## Consequences

- `rowrelay-core` stays framework-neutral: `Choice`, `WorkflowOutcome`, plan
  nodes — no executor types (enforced by the module graph + a public-API check).
- The coroutine executor is the only one built now; the others are documented
  seams with explicit adoption triggers, satisfying the HEL-167 evaluation
  criterion without premature dependencies.
- `docs/WORKFLOWS.md` is updated to point here and drop the absolute
  "no River" language.
- Business `Choice.Left`/`Right` and execution `Completed`/`Partial`/`Failed`/
  `Cancelled` remain separate — no backend may collapse them.

## Not decided here
The concrete coroutine executor implementation (bounded permits, fail-fast vs
supervised branch policy, transaction-affinity serialization) and its proof
scenarios — those land with the executor code, respecting HEL-126 (transaction
policy) and HEL-128 (connection ownership).
