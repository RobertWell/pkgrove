# PkgroveKit architecture and dependency boundaries

```
pkgrovekit-core      zero-dep contracts: Schema/Row/RowBatch, warnings,
                   ConversionPolicy, CancelToken, Identifiers, OperationReport
      ▲
pkgrovekit-jdbc      java.sql only: JdbcReader (streaming), JdbcBatchWriter
                   (commit policies), JdbcSchemas, ValueReader seam, SqlDialect
      ▲                       ▲                    ▲
pkgrovekit-jdbi      pkgrovekit-oracle        pkgrovekit-duckdb
(jdbi3-core)       (driver compileOnly)   (driver consumer-supplied)
      ▲
pkgrovekit-transfer  dialect-agnostic engine over SqlDialect + the batch
                   primitives; direction = which side is source vs target
```

Why consumers import only what they need:

- **core** has no dependencies at all — safe anywhere, including model-only use.
- **jdbc never references JDBI** (hard boundary, enforced by module deps):
  a JDBC-only application cannot receive JDBI transitively.
- **dialect modules are direction-neutral**: each database adapter serves as
  source (its `ValueReader`) and target (its `SqlDialect`) — no `-to-`/`-from-`
  artifacts.
- **drivers are consumer-controlled**: `pkgrovekit-oracle` compiles against
  ojdbc (`compileOnly`) but never ships it; DuckDB likewise.
- **integration-tests is never published** — it holds cross-module scenarios
  and the compiled README examples.

## The operation layer (HEL-602) — a second, disjoint tree

```
pkgrovekit-operation-core   zero-dep: Operation/OperationRegistry, the
                          operations { } DSL, fail-closed SurfaceExposure,
                          SurfacePolicy, CallerContext/AuthProfile, the stage
                          pipeline, validation, OperationError, descriptors
      ▲                        ▲                        ▲
operation-rest           operation-mcp            operation-quarkus
(no jakarta.ws.rs:       (no MCP runtime:         (CDI + MP Config,
 (method, path, body)     tools/list allow-list    compileOnly; the boot-time
 -> (status, body))       + tools/call)            classification gate)
```

This tree has **no edge to `pkgrovekit-core`**, in either direction. The two
trees share no types: the data-access spine moves rows between databases, the
operation layer describes an application's callable surface. An edge would make
every REST/MCP consumer resolve a JDBC data model they never use, and every
database consumer resolve an API model they never use. There is deliberately no
adapter-to-adapter edge either, so a REST-only host never resolves the MCP
adapter.

The surface classification is the architectural point: an operation declares
exactly one of `both` / `restOnly(reason)` / `mcpOnly(reason)` /
`internalOnly(reason)`, with no default, and the registry refuses to exist
otherwise. "Can an AI client call this?" is therefore answerable by reading
`registry.classification()` rather than by auditing resource classes and tool
registrations separately. See [operations.md](operations.md).

Out of scope by design: ORM behavior, CDC/continuous replication, distributed
execution, UI models, OAuth/JWT verification (the operation layer reads a
framework-verified identity; it never implements one), MCP/HTTP transports (the
host owns its server), app-specific allowlists, and any replacement of JDBI's
own APIs (JDBI callers keep normal handles, transactions, and mappers).

REST/HTTP and authentication were out of scope entirely until HEL-602. They are
now in scope only as the strictly opt-in, framework-free operation layer above —
no published artifact in the data-access spine gained an HTTP, MCP or security
dependency, and `assertModuleHierarchy` enforces that.

Origin: extracted from AuditPatchX's production Oracle/JDBI table access and
QuerySkiff's bounded DuckDB engine; the AuditPatchX pilot replaced the
duplicated read path with behavior parity proven by its own live-Oracle suite.
