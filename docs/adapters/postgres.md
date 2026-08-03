# PostgreSQL adapter (`pkgrovekit-postgres`)

- **Identifier policy**: lowercase-then-quote — the mirror of Oracle's rule;
  generated SQL matches objects created without quotes (Postgres folds down).
- **Type mapping**: TEXT/VARCHAR(n), BOOLEAN, BYTEA, SMALLINT→NUMERIC ladder by
  precision/scale, DATE/TIME/TIMESTAMP/TIMESTAMPTZ by kind + TZ.
- **uuid / json / jsonb / arrays** are first-class: `typeFor` recreates the
  real Postgres type from the pgjdbc type name (`_int4` → `int4[]`);
  `bindValue` reconstructs values from text (uuid → `java.util.UUID`,
  json/jsonb/array → typed `PGobject`); `PostgresValueReader` normalizes
  source values (PGobject → JSON text, `java.sql.Array` → array literal)
  without warnings. PG→PG round-trips preserve the exact types; foreign
  targets receive the text form.
- **Upsert**: native `ON CONFLICT ... DO UPDATE` (unique/PK required on keys);
  key-only tables degrade to `DO NOTHING`.
- **Savepoints**: supported (`SavepointPerBatch` proven live).
- **Live proof**: `integration-tests/.../PostgresTransferIT.kt`
  (testcontainers `postgres:16-alpine`): both directions, named params,
  rename mapping, upsert, savepoint-per-batch, exotic-type round-trip.
- **Migration boundary**: PkgroveKit owns bulk data movement; schema evolution
  belongs to Flyway/Liquibase — see [MIGRATION.md](../MIGRATION.md).
