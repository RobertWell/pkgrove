# Low-level and advanced APIs

These are fully supported escape hatches below the golden path. Reach for them
deliberately — see [workflow-style.md](../workflow-style.md) for the tiers.

All snippets are compiled in CI (`integration-tests/.../QuickStartExamples.kt`,
`NamedMappingTest`, `OracleTransferIT`).

## Read a query without DTOs (JDBC)

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

Positional `?` parameters are this tier's compatibility form; prefer `:named`
everywhere else.

## Named parameters through JDBI

```kotlin
val jdbi = Jdbi.create(url)
val batch = jdbi.withHandle<RowBatch, Exception> { handle ->
    JdbiReader.readAll(handle, "SELECT * FROM trades WHERE symbol = :sym",
                       mapOf("sym" to "SYM7"))
}
```

Inside a caller-owned JDBI transaction, PkgroveKit writers append without
committing and reject `PerChunk` loudly — chunk-committing inside someone
else's transaction would break their atomicity.

## A single explicit transfer

```kotlin
val report = Transfer.run(
    source, "SELECT * FROM trades WHERE id < ?", listOf(25L),
    target, DuckDbDialect, "trades_copy",
    Transfer.Options(
        readBatchSize = 10,
        commitPolicy = JdbcBatchWriter.CommitPolicy.PerChunk(1)))
check(report.completed && report.rowsAffected == 25L)
```

## Named parameters + named mapping (advanced transfer form)

```kotlin
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

Values land by **name**, never by SELECT order. The resolved plan is
inspectable: `mapping.resolve(schema)` returns a `MappingPlan` you can assert
on.

## Native bulk load (HEL-161)

Engines with a native ingest protocol can skip prepared-statement binding
entirely — Postgres COPY (`PostgresCopyLoader`) and the DuckDB Appender
(`DuckDbAppenderLoader`). Opt in per transfer:

```kotlin
val report = Transfer.run(
    source, "SELECT * FROM trades", emptyList(),
    pgConnection, PostgresDialect, "trades_copy",
    Transfer.Options(useBulkLoad = true))
```

or per Relay sink:

```kotlin
to(Analytics, "trades_copy") { bulkLoad() }
```

Same data contract as the batched path (values are bind-adapted by the same
dialect hook), all-or-nothing regardless of `commitPolicy`, and honest
fallback: when the fast path can't serve the request — upsert keys set, a
caller-supplied `TargetWriter` owns the write, a non-native connection, a
BINARY column a text protocol can't carry, or (DuckDB) an APPEND-mode table
whose physical column set/order doesn't positionally match the transfer
schema (the Appender is positional; extra/default columns need the named
INSERT) — the transfer silently degrades to batched INSERT and records a
`BULK_LOAD_UNAVAILABLE` warning in the report.
A mid-stream failure rolls the whole load back and throws `BulkLoadException`
with the partial report attached. Benchmarked in
`integration-tests/.../BulkLoadIT.kt` (100k rows against live engines).

## Caller-owned transactions, savepoints, checkpoints

See [TRANSACTIONS.md](../TRANSACTIONS.md) — `TransactionPolicy`
(Atomic / Chunked / SavepointPerBatch / JoinExisting / AutoCommit) executed by
`TransactionalWriter`, returning a `TransactionOutcome` with chunk ranges,
checkpoint, and `RetrySafety`.
