# Adapters

Each adapter implements the `SqlDialect` contract (target side) and, where the
driver returns vendor classes, a `ValueReader` (source side). Adapters are
usable in both directions; drivers stay consumer-controlled (`compileOnly`).

- [Oracle](oracle.md) — `rowrelay-oracle`
- [DuckDB](duckdb.md) — `rowrelay-duckdb`
- [PostgreSQL](postgres.md) — `rowrelay-postgres`

Adding a new adapter: implement `SqlDialect.typeFor/bindValue/identifierCase`
(+ `upsertSql` if the engine has native upsert, `supportsSavepoints` if real),
extend `ValueReader.Default` for vendor value classes, and prove it with a
live-container integration suite mirroring `PostgresTransferIT`.
