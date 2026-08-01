# Configuration reference

| Option | Where | Default | Meaning |
|---|---|---|---|
| `fetchSize` | `JdbcReader.ReadOptions` | 1000 | JDBC cursor fetch size (memory bound) |
| `queryTimeoutSeconds` | `ReadOptions` | 0 (off) | statement-level timeout |
| `cancelToken` | read/write/transfer options | none | cooperative cancel + deadline (`CancelToken.withTimeout`) |
| `commitPolicy` | `JdbcBatchWriter.WriteOptions` | `AllOrNothing` | or `PerChunk(n)` chunked commits with resume info |
| `mode` | `Transfer.Options` | `CREATE` | `CREATE_OR_REPLACE` / `APPEND` / `TEMPORARY` / `FAIL_IF_EXISTS` |
| `conversionPolicy` | `Transfer.Options` | `REJECT` | `STRINGIFY` / `SKIP` — always warn, never silent |
| `mapping` | `Transfer.Options` | identity | named renames / constants / omissions (`Mapping.build`) |
| `upsertKeys` | `Transfer.Options` | off | explicit named-key upsert (Oracle MERGE / ON CONFLICT; keys need target uniqueness) |
| `unusedPolicy` | named reads | `WARN` | `REJECT` / `IGNORE` for bind-map entries the SQL never uses |
| `readBatchSize` | `Transfer.Options` | 1000 | rows per in-flight batch (bounded memory) |
| `maxConnections` | `Relay.build` / `Databases.build` | pool-bound | per-database lease budget (fair) |
| `maxConcurrency` | `executeStructured` | flow count | bounded parallel branches |
| `policy` | `executeStructured` | `FAIL_FAST` | or `SUPERVISED` (siblings keep running) |

Relay DSL shorthands: `atomic()` → `AllOrNothing`; `chunked(n)` → `PerChunk(n)`;
`upsertBy(keys)` → `upsertKeys` + default `mode = APPEND`; `rename`/`omit` →
`Mapping.build`.
