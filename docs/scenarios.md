# PkgroveKit dependency recipes (choose by scenario)

PkgroveKit is a **hierarchy of small, single-purpose modules** so you pay only
for the capability you explicitly select (HEL-235). Add the module(s) for your
scenario — the version comes from the `pkgrovekit-bom` platform, so you never
repeat it — and you get exactly the transitive modules that capability needs and
nothing else. Each recipe below is backed by an executable consumer fixture under
`consumer-fixtures/`; the "present"/"absent" lists are asserted by CI.

Use the BOM once, then omit per-module versions:

```kotlin
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.5.0"))
    // ... versionless module deps below ...
}
```

Maven:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.pkgrove</groupId>
      <artifactId>pkgrovekit-bom</artifactId>
      <version>0.5.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

> **Drivers are always consumer-controlled.** Every dialect adapter declares its
> JDBC driver `compileOnly`, so no recipe pulls a driver transitively — add the
> exact driver (and version) your deployment needs. This keeps CVE exposure and
> driver upgrades in your hands.

---

## 1. JDBC only

Direct dynamic JDBC reads/writes over one connection — no transfer engine, no
JDBI, no bundled dialect.

```kotlin
implementation("com.pkgrove:pkgrovekit-jdbc")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-core` |
| **Notably absent** | transfer, jdbi, every dialect, coordination, all frameworks |
| **Consumer-owned** | your JDBC driver; a `SqlDialect` (hand-rolled or add an adapter) |
| **Intended user** | low-level dynamic data access with full control over SQL and dialect |

## 2. Oracle + DuckDB (two adapters, no engine)

Two dialect adapters side by side. Proves adapters are mutually independent.

```kotlin
implementation("com.pkgrove:pkgrovekit-oracle")
implementation("com.pkgrove:pkgrovekit-duckdb")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-core`, `pkgrovekit-jdbc` |
| **Notably absent** | transfer, jdbi, postgres, coordination, all frameworks |
| **Consumer-owned** | `ojdbc11` and `duckdb_jdbc` drivers |
| **Intended user** | code that speaks both Oracle and DuckDB dialects without the transfer engine |

## 3. PostgreSQL + transfer engine

Batch DB→DB transfer with a Postgres endpoint, plain JVM (no framework).

```kotlin
implementation("com.pkgrove:pkgrovekit-postgres")
implementation("com.pkgrove:pkgrovekit-transfer")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-core`, `pkgrovekit-jdbc`, `kotlinx-coroutines-core` |
| **Notably absent** | oracle, duckdb, jdbi, coordination, all frameworks |
| **Consumer-owned** | the PostgreSQL driver |
| **Intended user** | a framework-free service running transfers against Postgres |

## 4. Spring Boot + PostgreSQL

Boot auto-configures a `Relay` over your existing `DataSource` beans.

```kotlin
implementation("com.pkgrove:pkgrovekit-spring-boot-starter")
implementation("com.pkgrove:pkgrovekit-postgres")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-core`, `pkgrovekit-jdbc`, `pkgrovekit-transfer` |
| **Notably absent** | oracle, duckdb, jdbi, **quarkus** (spring ↛ quarkus), all coordination |
| **Consumer-owned** | Spring Boot itself (the starter keeps Spring `compileOnly`); the Postgres driver |
| **Intended user** | a Boot service transferring against Postgres |

The starter compile-depends **only** on transfer; the `postgres` dialect id is
resolved at runtime via `SqlDialectProvider` (ServiceLoader) from the dialect
module you added — so adding `-postgres` never drags in `-oracle`/`-duckdb`.

## 5. Quarkus + Oracle

CDI-injected `Relay` over Quarkus-managed Agroal datasources.

```kotlin
implementation("com.pkgrove:pkgrovekit-quarkus")
implementation("com.pkgrove:pkgrovekit-oracle")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-core`, `pkgrovekit-jdbc`, `pkgrovekit-transfer` |
| **Notably absent** | postgres, duckdb, jdbi, **spring** (quarkus ↛ spring), all coordination |
| **Consumer-owned** | Quarkus / CDI / Agroal (all `compileOnly` in the adapter); the Oracle driver |
| **Intended user** | a Quarkus service transferring against Oracle |

Same ServiceLoader story as Spring: the adapter carries no dialect module; the
built-in generic `ansi` dialect is always available for engines with no adapter.

## 6. JDBI

JDBI-first data access plus the first-class `JdbiTransfer` facade.

```kotlin
implementation("com.pkgrove:pkgrovekit-jdbi")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-core`, `pkgrovekit-jdbc`, `pkgrovekit-transfer`, `jdbi3-core` |
| **Notably absent** | every dialect adapter, coordination, all frameworks |
| **Consumer-owned** | your JDBC driver; a `SqlDialect` for the `JdbiTransfer` target |
| **Intended user** | apps already using JDBI `Handle`s |

`JdbiTransfer.run(...)` takes a transfer public type (`Transfer.Options`), so
`pkgrovekit-transfer` is present by design. A **read-only** JDBI consumer that
uses only `JdbiReader`/`JdbiBatchWriter` and never touches `JdbiTransfer` may
exclude transfer with no loss of function — for example (Maven):

```xml
<dependency>
  <groupId>com.pkgrove</groupId>
  <artifactId>pkgrovekit-jdbi</artifactId>
  <exclusions>
    <exclusion>
      <groupId>com.pkgrove</groupId>
      <artifactId>pkgrovekit-transfer</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

This is exactly how AuditPatchX (jdbi + oracle, reads only) consumes PkgroveKit.

## 7. XA / Narayana (distributed ACID, opt-in)

Atomic 2PC across two XA-capable databases via the Narayana transaction manager,
standalone (non-app-server).

```kotlin
implementation("com.pkgrove:pkgrovekit-narayana")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-coordination-api`, `pkgrovekit-jta`, `jakarta.transaction-api`, `narayana-jta` |
| **Notably absent** | the **entire** data-access spine (core/jdbc/transfer/dialects), jdbi, saga, all frameworks |
| **Consumer-owned** | XA `DataSource`s for your databases |
| **Intended user** | a workflow that genuinely needs distributed ACID across two XA resources |

The coordination layer is orthogonal to data access — it never drags in the
spine. If you also transfer data, add the data-access modules you need
separately.

## 8. Saga (compensation, opt-in)

Compensation-based coordination for resources that cannot join XA (DuckDB,
files, HTTP services, long-running workflows) — explicitly **not** ACID.

```kotlin
implementation("com.pkgrove:pkgrovekit-saga")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-coordination-api` |
| **Notably absent** | jta, narayana, the entire data-access spine, all frameworks |
| **Consumer-owned** | your compensating actions |
| **Intended user** | a coordinated multi-step workflow without distributed ACID |

## 9. S3-compatible object storage (opt-in, HEL-236)

Stream datasets, staging artifacts, manifests, checkpoints and quarantine
evidence to MinIO / Amazon S3 / other S3-compatible storage — next to (never
inside) the database workflow. See [storage.md](storage.md) for the full
scenario tutorials.

```kotlin
implementation("com.pkgrove:pkgrovekit-transfer")   // or just -jdbc
implementation("com.pkgrove:pkgrovekit-storage-s3")
```

| | |
|---|---|
| **Transitive present** | `pkgrovekit-storage-api`, `software.amazon.awssdk:s3` (+ its sync Apache transport; **netty async client excluded**) |
| **Notably absent** | every dialect, jdbi, coordination, all frameworks, MinIO SDK |
| **Consumer-owned** | bucket provisioning + credentials (default AWS chain or explicit static keys) |
| **Intended user** | a data workflow exporting/importing datasets through object storage |

The reverse guarantee matters more: recipes 1–8 resolve **zero**
`software.amazon.awssdk` artifacts. That is asserted from the consumer side by
the `jdbc-only` and `postgres-transfer` fixtures (`forbiddenGroups`) and from
the producer side by `assertModuleHierarchy` (storage-s3 is the only module
whose runtime may carry the AWS SDK). Vendor-neutral code can depend on
`pkgrovekit-storage-api` alone — core + JDK only, includes an
`InMemoryObjectStore` for tests.

---

## Verifying these boundaries yourself

```bash
./gradlew publishToMavenLocal            # publish this build locally
./gradlew -p consumer-fixtures verifyAllFixtures
```

Each fixture resolves the real published POMs from mavenLocal and asserts the
present/absent lists above; `consumer-fixtures/<name>/build/runtime-classpath.txt`
records the full resolved runtime classpath as evidence. The declared graph is
enforced in one shot by `./gradlew assertModuleHierarchy` against
`gradle/allowed-dependencies.txt`.
