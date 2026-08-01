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

Inside a caller-owned JDBI transaction, RowRelay writers append without
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

## Caller-owned transactions, savepoints, checkpoints

See [TRANSACTIONS.md](../TRANSACTIONS.md) — `TransactionPolicy`
(Atomic / Chunked / SavepointPerBatch / JoinExisting / AutoCommit) executed by
`TransactionalWriter`, returning a `TransactionOutcome` with chunk ranges,
checkpoint, and `RetrySafety`.
