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
