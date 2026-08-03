# Oracle adapter (`pkgrovekit-oracle`)

- **Identifier policy**: UPPERCASE-then-quote — generated SQL matches objects
  created without quotes (Oracle folds unquoted names up). One policy, applied
  to every identifier.
- **Type mapping**: NUMBER(p,s) by precision/scale; VARCHAR2 with CLOB overflow
  at 4000; the NUMBER(1) boolean convention; RAW/BLOB by size; TZ-aware
  temporal mapping. Oracle DATE carries a time component — temporal columns
  take JDBC-standard type names from the type CODE so datetime-valued DATE
  columns cannot silently lose time in a date-only target.
- **Source normalization**: `OracleValueReader` normalizes `oracle.sql.*`
  (TIMESTAMP/DATE/TIMESTAMPTZ, CLOB materialization, vendor type codes
  -101/-102/100/101). TIMESTAMPLTZ is deliberately carried as string **with a
  warning** rather than guessing a zone.
- **Upsert**: native `MERGE` keyed on `upsertBy` columns; a key-only table
  degrades to insert-only MERGE (no empty `WHEN MATCHED`).
- **Savepoints**: supported (`SavepointPerBatch` works; proven live).
- **Live proof**: `integration-tests/.../OracleTransferIT.kt` (testcontainers
  `gvenzl/oracle-free`): both directions, both access paths, type fidelity
  (NVARCHAR2 unicode, CLOB with literal colons, BLOB/RAW, TZ timestamps,
  all-null rows), savepoint + atomic transaction scenarios.
