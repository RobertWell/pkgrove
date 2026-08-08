# DuckDB adapter (`pkgrovekit-duckdb`)

- **Type mapping**: standard DuckDB DDL types from the common model.
- **Bind adaptation**: `java.time` → `java.sql` where the JDBC driver requires
  it.
- **Upsert**: native `ON CONFLICT ... DO UPDATE` keyed on `upsertBy` columns —
  the target needs a PRIMARY KEY/UNIQUE constraint on the keys; key-only
  tables degrade to `DO NOTHING`.
- **Savepoints**: NOT supported by the JDBC driver (`supportsSavepoints =
  false`) — `SavepointPerBatch` fails before any row, by design.
- **In-memory vs file-backed**: an in-memory database (`jdbc:duckdb:`) is
  per-connection — use a file-backed database when multiple connections must
  see one store (see RESOURCES.md).

## S3 object-storage scenarios (`com.pkgrove.pkgrovekit.duckdb.s3`) — HEL-236

Optional, dependency-free: publish the result of a DuckDB query as **one
object** on any S3-compatible store (MinIO, AWS S3) with **atomic-replace**
semantics. DuckDB's `httpfs` extension carries the bytes; this package is the
configuration plus the target type plus the replace choreography — not new
transport code.

```kotlin
val session = S3Session(
    endpoint = "minio.lan:9000",          // host:port, no scheme
    accessKeyId = key, secretAccessKey = secret,
    useSsl = false,                        // MinIO-on-LAN default
    urlStyle = S3Session.UrlStyle.PATH,
)
val outcome = S3Publisher(session).publish(
    duckDbConnection,
    "SELECT * FROM training_record WHERE run_date = current_date",
    ObjectKey("model-results", "training/records.parquet"),
)
when (outcome) {
    is S3Publisher.PublishOutcome.Published -> log(outcome.rows, outcome.stagingOrphan)
    is S3Publisher.PublishOutcome.Failed    -> alert(outcome.stage, outcome.cause)
}
```

- **`S3Session`** loads `httpfs` and registers a `CREATE OR REPLACE SECRET`
  (endpoint, credentials, `URL_STYLE`, `USE_SSL`) on the connection —
  idempotent, credentials never appear in `toString`/exceptions.
- **`ObjectKey`** is a write TARGET that is an object key (`s3://bucket/key`),
  not a table. Strictly validated: the URI is embedded in SQL literals, so
  quote/control/whitespace characters are refused outright.

### Atomic replace

`COPY TO 's3://…'` writes the final key in place, so a mid-write failure would
leave a corrupt object where consumers read. `S3Publisher` therefore NEVER
writes the final key directly:

1. **Write** to a staging key — `<final-key>.staging-<runId>` in the same
   bucket (runId = fresh UUID per publish unless supplied).
2. **Verify** — the staging object is read back *through the object store* and
   its row count must match what `COPY` reported.
3. **Replace** — server-side S3 `CopyObject` staging → final (atomic per
   object: readers see the old object or the new one, never a mix), then
4. **Delete** the staging key (best-effort).

Every failure stage returns a typed `PublishOutcome.Failed` with the **prior
final object untouched** — that is the invariant, not a best case. Staging
orphans are allowed but always **reported** (`Failed.stagingOrphan`, or a
`STAGING_ORPHAN` warning when only the cleanup failed); they are recognizable
by the `.staging-` infix and safe to garbage-collect. Re-running a publish is
idempotent at the destination: same final key, no duplicates.

**Consistency caveat** (documented by design): `CopyObject`-then-delete is two
operations, not one. A crash between them leaves the *correct* final object
plus a staging orphan — never a corrupt final. Additionally, S3's copy API may
return `200 OK` with an error document in the body; the built-in
`SigV4ObjectStoreOps` (AWS Signature V4 over `java.net.http`, zero new
dependencies, pinned against AWS's published signing example) checks for
`CopyObjectResult` explicitly. Consumers preferring their own SDK can
substitute any `S3Publisher.ObjectStoreOps`.

### HEL-264 consumer note

Training-record publication is the first consumer: records land in DuckDB
(via `Transfer`/`Relay` with `DuckDbDialect` as the target), then publish here
as Parquet under `s3://model-results/...`. Compose the typed outcomes — run
the transfer, and on `TransferOutcome.Completed` call `S3Publisher.publish`
as the terminal step; a `Failed`/`Partial` transfer never reaches the object
store, and a failed publish never corrupts the previously published object.
