# Getting started

## Modules — import only what you need

| Artifact | Adds | Transitive deps |
|---|---|---|
| `rowrelay-core` | Schema/Row/RowBatch model, warnings, policies, cancellation, safe identifiers, `Choice`, `WorkflowOutcome` | Kotlin stdlib only |
| `rowrelay-jdbc` | streaming reads, batch writer, transaction policies, `SqlDialect` contract, `Databases` registry | core (no JDBI, no drivers) |
| `rowrelay-jdbi` | the same operations through a JDBI `Handle` | jdbc + `jdbi3-core` |
| `rowrelay-oracle` | Oracle dialect + `oracle.sql.*` normalization | jdbc (driver is yours, `compileOnly`) |
| `rowrelay-duckdb` | DuckDB dialect | jdbc (driver is yours) |
| `rowrelay-postgres` | PostgreSQL dialect (+ uuid/json/jsonb/array) | jdbc (driver is yours) |
| `rowrelay-transfer` | `Relay` golden path, transfer engine, workflow executors | jdbc (+ kotlinx-coroutines) |

JDBC-only consumers never receive JDBI transitively. Database drivers stay
consumer-controlled.

## Dependency setup

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    // GitHub Packages requires a token with read:packages even for public
    // repositories — keep it in user-level settings, never in the repo:
    maven("https://maven.pkg.github.com/RobertWell/rowrelay")
}
dependencies {
    implementation("io.maxxga.rowrelay:rowrelay-transfer:0.2.0")
    implementation("io.maxxga.rowrelay:rowrelay-duckdb:0.2.0")   // your adapters
}
```

Maven:

```xml
<dependency>
    <groupId>io.maxxga.rowrelay</groupId>
    <artifactId>rowrelay-transfer</artifactId>
    <version>0.2.0</version>
</dependency>
```

## Your first workflow

1. **Declare identities** — one object per database:

```kotlin
object Sales : DatabaseKey("sales-db")
object Analytics : DatabaseKey("analytics-db")
```

2. **Configure once at startup** — pools + dialects (+ optional lease budgets):

```kotlin
val relay = Relay.build {
    database(Sales, salesDataSource, OracleDialect, maxConnections = 8)
    database(Analytics, analyticsDataSource, DuckDbDialect)
}
```

RowRelay *borrows* connections from your pools and never closes the pools;
close the `Relay` (it is `AutoCloseable`) at shutdown.

3. **Define an immutable plan, execute, handle the typed outcome** — see the
   [homepage golden path](../README.md#the-golden-path). Every homepage snippet
   is a real CI-compiled test in
   `integration-tests/src/test/kotlin/io/maxxga/rowrelay/it/QuickStartExamples.kt`.

## Java consumers

The full JDBC surface is Java-friendly (compiled in CI —
`integration-tests/.../JavaConsumerExample.java`):

```java
try (JdbcReader.RowStream rows =
         JdbcReader.open(connection, "SELECT * FROM t ORDER BY id")) {
    Schema schema = rows.getSchema();
    Row first = rows.toList().get(0);
    Object name = first.get("name");     // dynamic access, no DTOs
}
```

The Kotlin-DSL `Relay` builder is usable from Java but reads best from Kotlin;
Java services typically use `Transfer.run` + `Mapping.build` directly
(see [reference](reference/low-level.md)).

## Compatibility

| Component | Supported |
|---|---|
| Java | 21+ |
| Kotlin | 1.9+ |
| JDBI | 3.4x (`rowrelay-jdbi`) |
| Oracle | tested against `gvenzl/oracle-free` 23 (live integration suite) |
| PostgreSQL | tested against `postgres:16` (live integration suite) |
| DuckDB | 1.1.x |
