# S3-compatible object storage (HEL-236)

PkgroveKit data workflows can stream datasets, staging artifacts, manifests,
checkpoints and audit evidence to **S3-compatible object storage** — without
ever pulling storage dependencies into database-only consumers.

Two modules, strictly opt-in:

```text
pkgrovekit-storage-api   vendor-neutral concepts: ObjectStore, ObjectKey,
        ↑                ContentSource, checksums, conditional writes,
        │                multipart, capabilities, StagingArea, ObjectDataset,
        │                CheckpointStore, QuarantineWriter, InMemoryObjectStore
pkgrovekit-storage-s3    the AWS SDK for Java 2.x adapter (S3ObjectStore) —
                         MinIO and Amazon S3 are the officially tested targets
```

The product contract is **S3-compatible storage, not MinIO**: the same
`software.amazon.awssdk:s3` client talks to MinIO in CI and to Amazon S3 in
production — that identity is the compatibility proof. There is no MinIO SDK
anywhere.

## Dependencies

```kotlin
dependencies {
    implementation(platform("com.pkgrove:pkgrovekit-bom:0.5.0"))
    implementation("com.pkgrove:pkgrovekit-storage-s3")   // brings storage-api
    // storage-api alone (core + JDK only, with InMemoryObjectStore) if you
    // only write provider-neutral workflow code or tests:
    // implementation("com.pkgrove:pkgrovekit-storage-api")
}
```

What you get — and don't: `pkgrovekit-storage-s3` selects `s3` and its sync
Apache transport only (the netty async client is excluded); it never drags in
the AWS SDK BOM surface. Database-only consumers resolve **zero**
`software.amazon.awssdk` artifacts — enforced by `assertModuleHierarchy` and
the `jdbc-only` / `postgres-transfer` consumer fixtures.

## MinIO local development

```bash
docker run -d --name minio -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio:RELEASE.2025-09-07T16-13-09Z server /data --console-address ':9001'
```

```kotlin
import com.pkgrove.pkgrovekit.storage.*
import com.pkgrove.pkgrovekit.storage.s3.*
import java.net.URI

val store = S3ObjectStore.open(
    S3StorageConfig(
        region = "us-east-1",                      // MinIO accepts any region
        endpoint = URI.create("http://localhost:9000"),
        credentials = S3Credentials.Static("minioadmin", "minioadmin"),
        // pathStyleAccess defaults to true for custom endpoints
        // profile defaults to S3CompatibilityProfile.minio()
    ),
    bucket = "my-data",
)
store.createBucketIfMissing()   // dev/test convenience — never used in prod

store.put(ObjectKey("hello/world.txt"), ContentSource.of("hi"))
store.get(ObjectKey("hello/world.txt")).use { println(it.stream().readBytes().decodeToString()) }
store.close()
```

## Amazon S3 production configuration

```kotlin
val store = S3ObjectStore.open(
    S3StorageConfig(
        region = "ap-northeast-1",
        // no endpoint → Amazon S3; no credentials → the DEFAULT CHAIN
        // (env vars, profile files, IMDS/IRSA role) — never hardcode keys
    ),
    bucket = "prod-datasets",
    prefix = "pkgrovekit",       // every key this store touches lives under it
)
```

Lifecycle ownership: the *consumer* owns bucket provisioning, policies, and
credentials. The library never creates production buckets, never broadens
policies, and never logs credentials or presigned query strings. Recommended
bucket hygiene for multipart workloads: an S3 lifecycle rule with
`AbortIncompleteMultipartUpload` (the library aborts on failure/cancellation;
the rule is the backstop for SIGKILL) — `S3ObjectStore.abortIncompleteUploads(olderThan)`
is the in-library equivalent for MinIO-class deployments.

Security guidance, in one list: prefer the default credential chain; use
`S3Credentials.Static` only for MinIO/service accounts (its `toString` is
redacted); `trustAllTls` is for throwaway dev endpoints only; `ObjectKey`
validation rejects query strings/`..`/control characters so keys stay loggable;
`PresignedUrl.toString()` redacts the signed query — pass `presigned.url` only
to the consumer who is *meant* to fetch it.

## Oracle/PostgreSQL → dataset on S3 (streaming export)

Rows stream straight from a `JdbcReader` into bounded object parts; a manifest
is committed **only after every part succeeded** — a crashed export is
invisible, not half-published.

```kotlin
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.postgres.PostgresValueReader
import com.pkgrove.pkgrovekit.storage.ObjectDataset

val export = JdbcReader.open(
    pgConnection, "SELECT * FROM trade WHERE traded_on >= :d", mapOf("d" to since),
    JdbcReader.ReadOptions(fetchSize = 1_000, valueReader = PostgresValueReader()),
).use { stream ->
    ObjectDataset.export(
        store, prefix = "exports/trades", runId = "run-20260809-1",
        schema = stream.schema, batches = stream.batches(1_000),
        ObjectDataset.ExportOptions(maxPartBytes = 16 * 1024 * 1024),
    )
}
println("published ${export.manifest.totalRows} rows in ${export.manifest.parts.size} parts")
```

Memory bound: ONE encoded part (`maxPartBytes`) at a time. The Oracle side is
identical (use the Oracle dialect's value reader). Parts are `jsonl-v1` —
typed, dependency-free, exact numerics. For **Parquet**, use the engine that
already owns the format: `pkgrovekit-duckdb`'s `com.pkgrove.pkgrovekit.duckdb.s3`
package publishes query results as Parquet objects via DuckDB `httpfs`
([adapters/duckdb.md](adapters/duckdb.md)) — a deliberate scope cut that keeps
`storage-api` free of the Hadoop dependency tree.

## S3 dataset → DuckDB/PostgreSQL import (streaming)

```kotlin
val handle = ObjectDataset.open(store, export.manifestKey)
val dialect = PostgresDialect() // or DuckDbDialect
targetConnection.createStatement().use {
    it.execute(dialect.createTableDdl("trade_import", handle.schema, SqlDialect.TargetMode.CREATE))
}
JdbcBatchWriter.write(
    targetConnection, dialect.insertSql("trade_import", handle.schema),
    handle.batches().map { b -> /* dialect.bindValue per column if needed */ b },
    JdbcBatchWriter.WriteOptions(),
)
```

Every part is verified while streaming (manifest SHA-256 at part EOF, then
per-part and total row counts); corruption throws `ChecksumMismatchException`,
truncation throws a typed refusal — never silently-partial data. The complete
PostgreSQL → MinIO → DuckDB loop is proven live in
`integration-tests/…/StorageDatasetRoundTripIT.kt`.

## Staging + manifest publish (why there is no rename)

Object storage has **no atomic rename**, so `StagingArea` builds atomicity
from what S3 actually offers:

1. write everything under `<prefix>/.staging/<runId>/` — readers never look there;
2. server-side copy to run-unique final keys — still invisible, readers only
   trust manifests;
3. `PUT` the manifest with `If-None-Match: *` (**the commit point** — one
   conditional create);
4. delete staging.

A crash before step 3 leaves only invisible garbage. Losing the step-3 race
throws `PreconditionFailedException` and rolls back that run's copies.
Abandoned staging has a deterministic owner (`.staging/<runId>/`) and reaper:
`StagingArea.cleanupAbandoned(store, prefix, olderThan)` deletes runs whose
newest object is older than your threshold.

## Resumable transfer checkpoints

```kotlin
val checkpoints = CheckpointStore(store, "checkpoints/trades-export")
val resume = checkpoints.latest()               // null on first run
// ... transfer from the position in resume?.data ...
checkpoints.save("""{"lastId":48210}""", expectedSequence = resume?.sequence)
```

Checkpoints are an append-only sequence of conditionally-created objects.
Two workers advancing from the same point: exactly one `save` wins; the loser
gets `PreconditionFailedException` and must reload — concurrent workers can
never silently overwrite each other. Requires `CONDITIONAL_CREATE` (checked in
the constructor, before any transfer work starts).

## Failed-row quarantine

```kotlin
val quarantine = QuarantineWriter(store, "quarantine/imports", runId, redactColumns = setOf("email", "ssn"))
quarantine.write(schema, rejectedRows, reason = "conversion failed")
quarantine.writeSummary("conversion failed")
```

Declared-sensitive columns are replaced by an irreversible `sha256:<16-hex>`
fingerprint (equal values keep equal fingerprints — traceability without
exposure); keys carry only runId + counters; the summary carries counts, never
values.

## Capability model — provider differences fail early

"S3-compatible" varies. Every store publishes `capabilities`
(`StorageCapability`: multipart, conditional create/update, SHA-256/CRC32C
checksums, versioning, SSE, presigned URLs, object lock, consistent listing —
plus `StorageLimits` part/object sizes). Workflows call
`capabilities.require(...)` **before moving data** and fail with a typed
`CapabilityRejectedException` naming what is missing — `StagingArea`,
`CheckpointStore`, `ObjectDataset` and `MultipartTransfer` all do this for you.

- **Officially tested**: MinIO (Testcontainers, every CI run — pinned
  `minio/minio:RELEASE.2025-09-07T16-13-09Z`) and Amazon S3 (opt-in smoke
  test below).
- **Expected compatible, not verified**: Cloudflare R2, Ceph RGW, Wasabi,
  Backblaze B2 S3 API, DigitalOcean Spaces. Start from
  `S3CompatibilityProfile.generic(name, capabilities)` and declare **only what
  you have verified against that provider** — known divergence points are
  exactly the capability axes (R2: no object lock, historically limited
  conditional writes; B2: no versioning-suspend, multipart minimums; RGW:
  consistency depends on deployment). An over-claimed capability turns the
  fail-fast gate into a mid-transfer surprise.

Retry semantics ride the same honesty: reads/deletes are idempotent and
retried by the SDK; a write is retried only when its `ContentSource` is
`repeatable` — a one-shot stream fails typed (`StorageIoException.retrySafe =
false`) instead of being blindly re-sent.

## Large objects — bounded multipart

```kotlin
MultipartTransfer.upload(
    store, ObjectKey("bulk/dump.bin"), inputStream,   // length may be unknown
    MultipartTransfer.Options(partSizeBytes = 8 * 1024 * 1024, concurrency = 4),
)
```

Peak memory is `concurrency × partSizeBytes` by construction. Per-part SHA-256
is provider-verified where supported. Failure or cancellation **aborts** the
multipart upload — no invisible billed part garbage; `abortIncompleteUploads`
is the deterministic backstop.

## Amazon S3 smoke test (opt-in, protected credentials)

The MinIO suite runs in every CI build. The Amazon S3 smoke test
(`pkgrovekit-storage-s3/src/test/…/AmazonS3SmokeIT.kt`) **never** runs in
normal CI or pull requests: it activates only when the environment provides

```bash
export PKGROVEKIT_S3_SMOKE_BUCKET=<dedicated-test-bucket>   # must already exist
export PKGROVEKIT_S3_SMOKE_REGION=ap-northeast-1            # optional
# AWS credentials via the default chain (protected CI secrets / assumed role)
./gradlew :pkgrovekit-storage-s3:test --tests '*AmazonS3SmokeIT*'
```

Run it from a release pipeline with protected secrets or by a release engineer
with a scoped role; keys are UUID-prefixed and deleted afterwards. Do not wire
these credentials into pull-request CI.

## Testing your own code

`InMemoryObjectStore` (in `pkgrovekit-storage-api`, zero dependencies) is a
full-capability, strongly-consistent reference implementation — unit-test your
storage workflows against it and keep MinIO for integration tests.
