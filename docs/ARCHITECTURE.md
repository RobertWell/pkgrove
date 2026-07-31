# RowRelay architecture and dependency boundaries

```
rowrelay-core      zero-dep contracts: Schema/Row/RowBatch, warnings,
                   ConversionPolicy, CancelToken, Identifiers, OperationReport
      ▲
rowrelay-jdbc      java.sql only: JdbcReader (streaming), JdbcBatchWriter
                   (commit policies), JdbcSchemas, ValueReader seam, SqlDialect
      ▲                       ▲                    ▲
rowrelay-jdbi      rowrelay-oracle        rowrelay-duckdb
(jdbi3-core)       (driver compileOnly)   (driver consumer-supplied)
      ▲
rowrelay-transfer  dialect-agnostic engine over SqlDialect + the batch
                   primitives; direction = which side is source vs target
```

Why consumers import only what they need:

- **core** has no dependencies at all — safe anywhere, including model-only use.
- **jdbc never references JDBI** (hard boundary, enforced by module deps):
  a JDBC-only application cannot receive JDBI transitively.
- **dialect modules are direction-neutral**: each database adapter serves as
  source (its `ValueReader`) and target (its `SqlDialect`) — no `-to-`/`-from-`
  artifacts.
- **drivers are consumer-controlled**: `rowrelay-oracle` compiles against
  ojdbc (`compileOnly`) but never ships it; DuckDB likewise.
- **integration-tests is never published** — it holds cross-module scenarios
  and the compiled README examples.

Out of scope by design: ORM behavior, CDC/continuous replication, distributed
execution, REST/HTTP/UI models, authentication flows, app-specific allowlists,
and any replacement of JDBI's own APIs (JDBI callers keep normal handles,
transactions, and mappers).

Origin: extracted from AuditPatchX's production Oracle/JDBI table access and
QuerySkiff's bounded DuckDB engine; the AuditPatchX pilot replaced the
duplicated read path with behavior parity proven by its own live-Oracle suite.
