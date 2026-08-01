# RowRelay

Reusable Kotlin data library for **dynamic JDBC/JDBI data access** and
**bidirectional batch transfer** between databases — without DTOs, entities,
generated classes, or per-table mapping code.

- **Status:** pre-stable (`0.1.0-SNAPSHOT`). APIs may change before `1.0.0`.
- **Requirements:** Java 21+, Kotlin 1.9+ (consumers may be pure Java).
- **Framework-neutral:** no Quarkus/Spring/JPA/REST dependencies in any artifact.

## What it does

- Execute parameterized SQL and consume rows/schemas discovered **at runtime**
  (`Schema` / `Row` / `RowBatch`), streaming with bounded memory.
- Use **JDBC directly** or **JDBI as a first-class entry point** — both paths
  produce identical schemas and values by construction.
- **Transfer** the result of any read SQL into another database
  (Oracle ↔ DuckDB and beyond via the `SqlDialect` contract), with explicit
  target modes, conversion policies, batch sizes, and commit policies.
- Fail honestly: lossy conversions warn or reject (never silent), partial
  completion is always reported with the failed batch and row range.

## Non-goals

ORM behavior, CDC/replication, distributed execution, REST/UI concerns,
application allowlists — see `docs/ARCHITECTURE.md`.

## Modules — import only what you need

| Artifact | Adds | Transitive deps |
|---|---|---|
| `rowrelay-core` | Schema/Row/RowBatch model, warnings, policies, cancellation, safe identifiers | Kotlin stdlib only |
| `rowrelay-jdbc` | streaming reads, batch writer, `SqlDialect` contract | core (no JDBI, no drivers) |
| `rowrelay-jdbi` | the same operations through a JDBI `Handle` | jdbc + `jdbi3-core` |
| `rowrelay-oracle` | Oracle dialect + `oracle.sql.*` normalization | jdbc (driver is yours, `compileOnly`) |
| `rowrelay-duckdb` | DuckDB dialect | jdbc (driver is yours) |
| `rowrelay-transfer` | the bidirectional transfer engine | jdbc |

JDBC-only consumers never receive JDBI transitively. Database drivers stay
consumer-controlled.

## Dependency setup

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenLocal()          // during the pre-stable phase
    mavenCentral()
}
dependencies {
    implementation("io.maxxga.rowrelay:rowrelay-jdbc:0.1.0-SNAPSHOT")
}
```

Maven:

```xml
<dependency>
    <groupId>io.maxxga.rowrelay</groupId>
    <artifactId>rowrelay-jdbc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

GitHub Packages (once published): repository
`https://maven.pkg.github.com/RobertWell/rowrelay` — note GitHub Packages
requires a token with `read:packages` even for public repositories; keep it in
user-level Gradle/Maven settings, never in the repo.

## Quick start (Kotlin)

Every snippet below is **compiled and run in CI** — see
`integration-tests/src/test/kotlin/io/maxxga/rowrelay/it/QuickStartExamples.kt`.

Read a query without DTOs (JDBC):

```kotlin
DriverManager.getConnection(url).use { connection ->
    JdbcReader.open(connection, "SELECT * FROM trades WHERE id < ?", listOf(10L)).use { rows ->
        println(rows.schema)                    // runtime-discovered columns
        for (row in rows) {
            val symbol: Any? = row["symbol"]    // access by name, no DTO
        }
    }
}
```

Named parameters through JDBI:

```kotlin
val jdbi = Jdbi.create(url)
val batch = jdbi.withHandle<RowBatch, Exception> { handle ->
    JdbiReader.readAll(handle, "SELECT * FROM trades WHERE symbol = :sym",
                       mapOf("sym" to "SYM7"))
}
```

Transfer a query result into another database:

```kotlin
val report = Transfer.run(
    source, "SELECT * FROM trades WHERE id < ?", listOf(25L),
    target, DuckDbDialect, "trades_copy",
    Transfer.Options(
        readBatchSize = 10,
        commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1)))
check(report.completed && report.rowsAffected == 25L)
```

Named parameters and named mapping (the recommended transfer form — compiled
in CI, see `NamedMappingTest` and `OracleTransferIT`):

```kotlin
// :user_name style named parameters — colons in literals/comments/casts are
// understood; missing names are rejected BEFORE execution, listed exactly.
val report = Transfer.run(
    oracle, """
        SELECT * FROM app_user
        WHERE user_name = :user_name AND updated_at >= :updated_after
    """.trimIndent(),
    mapOf("user_name" to "ann", "updated_after" to since),
    duck, DuckDbDialect, "users",
    Transfer.Options(
        sourceValueReader = OracleValueReader(),           // oracle.sql.* normalized
        mapping = Mapping.build {
            "source_user" mapsTo "user_name"               // rename by NAME
            "source_display" mapsTo "display_name"
            omit("internal_flag")                          // target default applies
            constant("origin", "oracle-prod")              // same value every row
        },
        upsertKeys = listOf("user_name")))                 // explicit MERGE/ON CONFLICT
```

Values land by **name**, never by SELECT order — reordering the source query
cannot change the target mapping. The resolved plan is inspectable:
`mapping.resolve(schema)` returns a `MappingPlan` you can assert on.

## Golden path — managed workflows

The recommended shape for real work: register your databases once, describe the
flow as **immutable data** (typed keys + SQL, no connections inside), and run it
through the managed **structured executor**. Your code carries no `Connection`,
`commit`, `rollback`, or thread choreography — the runtime leases connections
from the registry, bounds concurrency per database, and returns a **typed
outcome** (`Completed` / `Partial` / `Failed` / `Cancelled` — partial completion
can never be mistaken for success).

```kotlin
Databases.build {
    applicationOwned(Source, sourceDataSource)   // your pool; RowRelay borrows, never closes it
    applicationOwned(Target, targetDataSource)
}.use { databases ->
    val flow = Workflows
        .from(Source, "SELECT id, symbol, price FROM trades WHERE price > :min",
              mapOf("min" to 30.0))
        .to(Target, DuckDbDialect, "big_trades")

    val outcome = Workflows.executeStructured(listOf(flow), databases)
    check(outcome is WorkflowOutcome.Completed)
}
```

Independent flows fan out under a bounded budget (`maxConcurrency` and each
database's lease budget); `BranchPolicy.SUPERVISED` keeps siblings running and
retains every outcome, `FAIL_FAST` cancels them on the first failure.
`Choice<L,R>` routes rows down different pipelines (validate → accept | reject)
without conflating a business `Left` with an execution failure. The executor is
pluggable — see `docs/adr/0001-workflow-executor-architecture.md`.

## Quick start (Java)

Compiled in CI — see `integration-tests/.../JavaConsumerExample.java`.

```java
try (JdbcReader.RowStream rows =
         JdbcReader.open(connection, "SELECT * FROM t ORDER BY id")) {
    Schema schema = rows.getSchema();
    Row first = rows.toList().get(0);
    Object name = first.get("name");     // dynamic access, no DTOs
}
```

## Configuration reference

| Option | Where | Default | Meaning |
|---|---|---|---|
| `fetchSize` | `JdbcReader.ReadOptions` | 1000 | JDBC cursor fetch size (memory bound) |
| `queryTimeoutSeconds` | `ReadOptions` | 0 (off) | statement-level timeout |
| `cancelToken` | read/write/transfer options | none | cooperative cancel + deadline (`CancelToken.withTimeout`) |
| `commitPolicy` | `JdbcBatchWriter.WriteOptions` | `AllOrNothing` | or `PerChunk(n)` chunked commits with resume info |
| `mode` | `Transfer.Options` | `CREATE` | `CREATE_OR_REPLACE` / `APPEND` / `TEMPORARY` / `FAIL_IF_EXISTS` |
| `conversionPolicy` | `Transfer.Options` | `REJECT` | `STRINGIFY` / `SKIP` — always warn, never silent |
| `mapping` | `Transfer.Options` | identity | named renames / constants / omissions (`Mapping.build`) |
| `upsertKeys` | `Transfer.Options` | off | explicit named-key upsert (Oracle MERGE / DuckDB ON CONFLICT; keys need target uniqueness) |
| `unusedPolicy` | named reads | `WARN` | `REJECT` / `IGNORE` for bind-map entries the SQL never uses |
| `readBatchSize` | `Transfer.Options` | 1000 | rows per in-flight batch (bounded memory) |

## Error handling

- Unsafe runtime identifiers → `Identifiers.UnsafeIdentifierException`
  (the raw name is never echoed into messages).
- Missing named parameters → `NamedSql.MissingParametersException` listing the
  exact absent names (values are never logged or interpolated).
- Bad mappings (unknown source, duplicate target, double-mapped source) →
  `Mapping.MappingException` naming the offending columns, before any write.
- Unrepresentable types under `REJECT` → `ConversionException` naming the column.
- Failed batch writes → `JdbcBatchWriter.BatchWriteException` carrying an
  `OperationReport` with committed row count, failed batch index, and row range.
- Cancellation → `OperationCancelledException`; open chunks are rolled back.

## Compatibility

| Component | Supported |
|---|---|
| Java | 21+ |
| Kotlin | 1.9+ |
| JDBI | 3.4x (`rowrelay-jdbi`) |
| Oracle | tested against `gvenzl/oracle-free` 23 via the AuditPatchX pilot suite |
| DuckDB | 1.1.x |

## Versioning

Pre-stable `0.1.x`. No release is overwritten after publication. `1.0.0` waits
for real-consumer adoption and API-stability confidence. Maven Central is
deliberately deferred (separate namespace-verification/signing process).

See `CHANGELOG.md` and `docs/ARCHITECTURE.md`.
