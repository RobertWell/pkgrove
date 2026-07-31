# Changelog

All notable changes to RowRelay. Pre-stable: breaking changes may occur in any
0.x release and are listed here with migration notes.

## 0.1.0-SNAPSHOT (unreleased)

Initial capability set, extracted from production code in AuditPatchX and
QuerySkiff (see the HEL-120 pilot — behavior parity proven by the consumer's
own integration suite, 135/135):

- `rowrelay-core`: dynamic `Schema`/`Row`/`RowBatch` model, `DataWarning`
  (nothing lossy is silent), `ConversionPolicy` (REJECT default),
  `CancelToken`, no-echo safe-identifier gate, `OperationReport` with
  failed-batch/row-range identification.
- `rowrelay-jdbc`: streaming parameterized reads with bounded memory,
  schema discovery without DTOs, `ValueReader` normalization seam,
  batch writer with AllOrNothing / PerChunk commit policies,
  `SqlDialect` contract.
- `rowrelay-jdbi`: first-class JDBI entry point; equivalence with the JDBC
  path by construction; caller-owned-transaction semantics (never commits
  inside your transaction; rejects PerChunk there).
- `rowrelay-oracle`: Oracle dialect (NUMBER p/s, VARCHAR2→CLOB overflow,
  NUMBER(1) boolean, RAW/BLOB by size, TZ-aware temporals) +
  `OracleValueReader` normalizing `oracle.sql.*`.
- `rowrelay-duckdb`: DuckDB dialect incl. java.time→java.sql bind adaptation.
- `rowrelay-transfer`: bidirectional SQL-in/data-out engine with target
  modes, conversion policies, bounded memory, honest partial reports.

Renamed from the internal working name `datakit`
(`internal.datakit:*` coordinates are dead; migrate imports
`datakit.*` → `io.maxxga.rowrelay.*`).
