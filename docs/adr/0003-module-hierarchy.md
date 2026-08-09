# ADR 0003 — Explicit, acyclic module hierarchy with minimal transitive deps

Status: **Accepted** (HEL-235) — the allowed module graph is defined, made
machine-readable, and enforced in CI; framework adapters are decoupled from
concrete dialects; a `java-platform` BOM is added.

## Context

PkgroveKit is a multi-module library published to Maven Central. Its guiding
principle is **"users pay only for the capability they explicitly select."**
Before HEL-235 that principle was honoured informally and partially:

- `assertCoordinationIsolation` already kept JTA/Narayana and the frameworks off
  the standard modules' runtime classpaths.
- BUT the framework adapters (`pkgrovekit-quarkus`, `pkgrovekit-spring-boot-starter`)
  `api`-depended on **all three** dialect modules (`oracle` + `duckdb` +
  `postgres`) purely so their config parser could map a dialect id string to a
  dialect singleton. A "Spring + Postgres" consumer therefore also carried
  Oracle and DuckDB — the opposite of the principle.
- There was no single machine-readable statement of the allowed graph, no cycle
  check, no adapter↔adapter check, no driver-leak check, and no BOM.

## Decision

### 1. The allowed graph is data, not prose

The allowed pkgrovekit→pkgrovekit edges (and their required Gradle scope) live in
`gradle/allowed-dependencies.txt`. The build reads it; humans edit it to change
the boundary.

```
core                                     coordination-api
 └─ jdbc                                   ├─ jta ─ narayana
     ├─ transfer (api coroutines)          └─ saga
     ├─ oracle    ├─ duckdb   ├─ postgres
     └─ jdbi (─ transfer)                 spring-boot-starter ─ transfer
                                          quarkus            ─ transfer
```

### 2. `api` only where a type is on the public API — every edge justified

| edge | scope | justification |
|---|---|---|
| jdbc → core | api | `Row`/`Schema`/`Column`/`Identifiers` appear on jdbc's public API |
| transfer → jdbc | api | `SqlDialect` is on the transfer public API |
| transfer → kotlinx-coroutines | api | `suspend fun executeStructured(...)` is public |
| oracle/duckdb/postgres → jdbc | api | each implements the public `SqlDialect` contract |
| jdbi → jdbc | api | `ValueReader`/schema types on the JDBI reader API |
| jdbi → transfer | api | `JdbiTransfer.run(options: Transfer.Options)` — public type |
| jta → coordination-api | api | plans/outcomes on the public API |
| jta → jakarta.transaction-api | api | callers pass a `TransactionManager` |
| saga → coordination-api | api | plans/outcomes on the public API |
| narayana → jta | api | re-exposes the JTA coordinator API |
| narayana → narayana-jta | **implementation** | the TM impl is an internal detail, not on narayana's public API |
| quarkus → transfer | api | produces a `Relay` |
| spring-boot-starter → transfer | api | produces a `Relay` |

Drivers (`ojdbc11`, `duckdb_jdbc`, `postgresql`) stay `compileOnly` in the
adapters — never transitive, always consumer-controlled.

### 3. Framework adapters no longer compile-depend on dialects

A new `SqlDialectProvider` service-loader SPI lives in `pkgrovekit-jdbc`. Each
dialect module ships one provider registered under
`META-INF/services/com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider`. The Spring
and Quarkus adapters resolve dialect ids at runtime via
`SqlDialectProvider.loadAll()` — so an adapter depends **only** on `transfer`,
and a consumer receives exactly the dialects whose modules they added. Quarkus
keeps its built-in generic `ansi` dialect. This is what makes the
"Spring + Postgres" / "Quarkus + Oracle" scenarios carry no other dialect.

`spring ↛ quarkus` and `quarkus ↛ spring` are guaranteed by the absence of any
such edge in the allowed graph (the enforcement task forbids undeclared edges).

### 4. A `java-platform` BOM, not an aggregate

`pkgrovekit-bom` publishes only `<dependencyManagement>` constraints — it adds
**no** runtime dependency of its own. Consumers import it once and omit
per-module versions. There is deliberately **no `pkgrovekit-all`** aggregate:
aggregation is the opposite of "pay for what you select."

### 5. Enforcement rides `check` (and therefore CI)

`./gradlew assertModuleHierarchy` inspects the **declared** edges and the
**resolved** runtime classpaths of every module and fails on: an undeclared or
mis-scoped edge; a cycle; an adapter→adapter dependency; a framework reaching
core/jdbc/transfer; jdbi on a jdbc-only classpath; JTA/Narayana on a standard
module; a driver leaking transitively; a test-only dep on a published runtime
classpath; a published-POM (api) edge diverging from the boundary; or a BOM gap.
Eight standalone consumer fixtures (`consumer-fixtures/`) additionally prove the
boundary from the consumer side against `mavenLocal`. Both run in the GitLab CI
`module-hierarchy` job.

## Consequences

- Downstream consumers get smaller, more honest classpaths (fewer transitive
  jars, smaller CVE surface).
- Adding a new dialect module requires no change to the framework adapters (drop
  in a `SqlDialectProvider`), and adding a new published module fails the build
  until it is added to the BOM and the allowed graph — drift is impossible.
- **Non-breaking for existing 0.5.0 consumers.** AuditPatchX (`jdbi` + `oracle`,
  reads only) and hello-stock (`jdbi` + `postgres`, planned) resolve the same
  types they import; none uses a framework adapter, so the dialect-decoupling is
  invisible to them. The one boundary NOT tightened: `pkgrovekit-jdbi` still
  `api`-depends on `pkgrovekit-transfer` because `JdbiTransfer` is a public type
  taking `Transfer.Options`. Splitting jdbi into reader-only + transfer-facade
  modules would tighten it but would be a breaking repackage; instead, read-only
  consumers exclude `pkgrovekit-transfer` (as AuditPatchX already does).

Effective on the **next** release — HEL-235 does not bump the published version.
