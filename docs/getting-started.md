# Getting started

## Modules — import only what you need

| Artifact | Adds | Transitive deps |
|---|---|---|
| `pkgrovekit-core` | Schema/Row/RowBatch model, warnings, policies, cancellation, safe identifiers, `Choice`, `WorkflowOutcome` | Kotlin stdlib only |
| `pkgrovekit-jdbc` | streaming reads, batch writer, transaction policies, `SqlDialect` contract, `Databases` registry | core (no JDBI, no drivers) |
| `pkgrovekit-jdbi` | the same operations through a JDBI `Handle` | jdbc + `jdbi3-core` |
| `pkgrovekit-oracle` | Oracle dialect + `oracle.sql.*` normalization | jdbc (driver is yours, `compileOnly`) |
| `pkgrovekit-duckdb` | DuckDB dialect | jdbc (driver is yours) |
| `pkgrovekit-postgres` | PostgreSQL dialect (+ uuid/json/jsonb/array) | jdbc (driver is yours) |
| `pkgrovekit-transfer` | `Relay` golden path, transfer engine, workflow executors | jdbc (+ kotlinx-coroutines) |

JDBC-only consumers never receive JDBI transitively. Database drivers stay
consumer-controlled.

## Dependency setup

Gradle (Kotlin DSL):

```kotlin
repositories {
    mavenCentral()
    // GitHub Packages requires a token with read:packages even for public
    // repositories — keep it in user-level settings, never in the repo:
    maven("https://maven.pkg.github.com/RobertWell/pkgrovekit")
}
dependencies {
    // ≥0.3.0 publishes under the Maven-Central-verified namespace com.pkgrove;
    // 0.2.0 and earlier remain at com.pkgrove.pkgrovekit in the same registries.
    implementation("com.pkgrove:pkgrovekit-transfer:0.3.0")
    implementation("com.pkgrove:pkgrovekit-duckdb:0.3.0")   // your adapters
}
```

Maven:

```xml
<dependency>
    <groupId>com.pkgrove</groupId>
    <artifactId>pkgrovekit-transfer</artifactId>
    <version>0.3.0</version>
</dependency>
```

Java package names are unchanged (`com.pkgrove.pkgrovekit.*`) — only the Maven
coordinates moved.

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

PkgroveKit *borrows* connections from your pools and never closes the pools;
close the `Relay` (it is `AutoCloseable`) at shutdown.

3. **Define an immutable plan, execute, handle the typed outcome** — see the
   [homepage golden path](../README.md#the-golden-path). Every homepage snippet
   is a real CI-compiled test in
   `integration-tests/src/test/kotlin/com/pkgrove/pkgrovekit/it/QuickStartExamples.kt`.

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
| JDBI | 3.4x (`pkgrovekit-jdbi`) |
| Oracle | tested against `gvenzl/oracle-free` 23 (live integration suite) |
| PostgreSQL | tested against `postgres:16` (live integration suite) |
| DuckDB | 1.1.x |
