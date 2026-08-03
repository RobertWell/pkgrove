# Framework adapters: Quarkus & Spring Boot (HEL-172)

PkgroveKit is framework-neutral; these optional adapters let Quarkus and Spring
Boot applications consume their **framework-owned** datasources through normal
dependency injection — no manual connection plumbing, no hidden second pool,
and no framework dependency ever reaches the standard modules (CI-enforced by
`assertCoordinationIsolation`).

## Decision table

| Environment | Adapter | Pool owner | Transaction integration |
| -- | -- | -- | -- |
| Plain JVM | standard PkgroveKit modules | application / PkgroveKit configuration | local policies |
| Quarkus | `pkgrovekit-quarkus` | Quarkus / Agroal | local, `JoinExisting`, optional JTA via `pkgrovekit-jta` |
| Spring Boot | `pkgrovekit-spring-boot-starter` | Spring / HikariCP (or supplied pool) | local, or Spring-bound `JoinExisting` via `SpringTransactions.joinCurrent` |

## Ownership rules (both adapters)

- The injected `DataSource` / `AgroalDataSource` / `HikariDataSource` is
  **FRAMEWORK-owned**. Adapters register it with `Relay.build { database(...) }`,
  which uses `Databases`' `APPLICATION_OWNED` mode — `Relay.close()` provably
  never closes such pools (see `Databases.close()`), so context/application
  shutdown leaves the framework pool untouched.
- Adapters never construct a datasource or pool. If a referenced datasource
  bean/name doesn't exist, startup fails with the candidates listed — nothing
  is silently created.
- Ownership stays visible: registration goes through the same
  `applicationOwned` model as plain-JVM usage (HEL-128).

## Quarkus (`pkgrovekit-quarkus`)

```kotlin
// application.properties
// quarkus.datasource.jdbc.url=...                  (default datasource)
// quarkus.datasource."warehouse".jdbc.url=...      (named datasource)
// pkgrovekit.databases.main.dialect=postgres        (uses the default datasource)
// pkgrovekit.databases.wh.datasource=warehouse
// pkgrovekit.databases.wh.dialect=postgres

@ApplicationScoped
class ImportService(private val relay: Relay) {
    fun importData(): TransferOutcome {
        BlockingBoundary.assertBlockingAllowed()   // JDBC is blocking — worker threads only
        val plan = relay.transfer("import") {
            from(DatabaseKey("main")) { query("select * from source_table") }
            into(DatabaseKey("wh"), "target_table") { atomic() }
        }
        return relay.execute(plan)
    }
}
```

- Datasource-to-key mapping is **explicit config**, never classpath scanning.
- Blocking boundary: JDBC work must not run on Vert.x event-loop threads.
  `BlockingBoundary.assertBlockingAllowed()` is the guard; run PkgroveKit
  operations from worker threads (`@Blocking` on reactive endpoints).
- JTA: to join a Quarkus-managed global transaction, build a
  `JtaCoordinator(injectedTransactionManager, participants)` from
  `pkgrovekit-jta` (HEL-170). The adapter does not reinvent transaction
  plumbing.

## Spring Boot (`pkgrovekit-spring-boot-starter`)

```yaml
# application.yml
pkgrovekit:
  databases:
    primary:
      dialect: postgres          # datasource-bean optional when exactly one DataSource bean exists
    reporting:
      datasource-bean: reportingDataSource
      dialect: duckdb
```

```kotlin
@Service
class ReconciliationService(
    private val relay: Relay,
    private val primary: DataSource,
) {
    @Transactional
    fun reconcile() {
        // Spring owns the transaction; PkgroveKit joins the BOUND connection.
        SpringTransactions.joinCurrent(primary) { connection ->
            TransactionalWriter.write(
                connection, upsertSql, batches, TransactionPolicy.JoinExisting)
        }
        // Spring commits or rolls back — never PkgroveKit.
    }
}
```

- `SpringTransactions.joinCurrent` resolves the **transaction-bound** connection
  via `DataSourceUtils` (never an unrelated pool checkout) and throws
  `MissingSpringTransactionException` *before touching the pool* when no
  Spring-managed transaction is active. It never commits or rolls back the
  surrounding transaction.
- Auto-configuration backs off when the application defines its own `Relay`
  bean; `pkgrovekit.enabled=false` disables it entirely.
- Startup validation: unknown dialects, missing/ambiguous datasource beans and
  invalid policy names fail context refresh — not the first production
  transfer.

## Transaction boundaries (summary)

| Mode | Who commits | How to invoke |
| -- | -- | -- |
| PkgroveKit-owned local | PkgroveKit (policy) | `relay.execute(plan)` / writer with `Atomic`/`Chunked`… |
| Framework-owned | the framework | Spring: `SpringTransactions.joinCurrent` + `JoinExisting`; Quarkus JTA: enlisted connection + `JoinExisting` |
| Global (XA) | external TM | `pkgrovekit-jta` / `pkgrovekit-narayana` — see docs/coordination.md |

## Troubleshooting

- **Missing bean / wrong qualifier**: startup error lists candidate DataSource
  beans (Spring) or configured Quarkus datasource names — fix
  `datasource-bean` / `.datasource`.
- **Pool exhaustion**: PkgroveKit leases and returns per operation; check the
  framework pool's own metrics first, then `Databases.metrics()` lease gauges.
  Cap PkgroveKit's concurrent leases per key with `max-connections`.
- **`MissingSpringTransactionException`**: you called `joinCurrent` outside
  `@Transactional` — either add the transaction or use a PkgroveKit-owned
  policy instead.
- **Event-loop violation (Quarkus)**: move the call to a worker thread
  (`@Blocking`); the guard exists precisely to stop silent event-loop stalls.
