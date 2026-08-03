# Type support matrix (HEL-168)

PkgroveKit maps every source column to a dialect-independent [`ValueKind`]
(`TEXT`, `NUMERIC`, `BOOLEAN`, `TEMPORAL`, `BINARY`, `OTHER`) plus
`precision` / `scale` / `timeZoned`. Portable logic branches on **that**, never
on vendor type-name strings — so a consumer (e.g. AuditPatchX) does not need its
own per-type conversion fallbacks.

This page is the published contract: what round-trips faithfully, what is
carried with a warning, and what is refused with a clear error.

## Guarantees

- **No silent loss.** A value is never quietly truncated, rounded,
  timezone-shifted, or turned into a display string. If a type cannot be
  represented faithfully, the transfer either warns (STRINGIFY/SKIP policy) or
  fails with a `ConversionException` naming the column, its `ValueKind`, the
  source database type name, precision/scale context, and the adapter path.
- **NULL, empty, and zero are distinct.** `NULL`, an empty string, an empty
  binary, and a binary containing zero bytes each round-trip as themselves.
- **Unicode is byte-exact.** Multibyte text (incl. large payloads) is preserved.

## Oracle ↔ DuckDB

| Category | Oracle | DuckDB | ValueKind | Round-trips faithfully? |
|---|---|---|---|---|
| Text | `VARCHAR2(n)` | `VARCHAR` | TEXT | ✅ (incl. unicode, empty→NULL per Oracle) |
| Text (national) | `NVARCHAR2(n)` | `VARCHAR` | TEXT | ✅ unicode preserved |
| Text (fixed) | `CHAR(n)` / `NCHAR(n)` | `VARCHAR` | TEXT | ⚠️ carried as variable-width; Oracle blank-pads `CHAR` reads — trailing spaces are preserved as read, not re-padded on the target. See limitations. |
| Large text | `CLOB` / `NCLOB` | `VARCHAR` | TEXT | ✅ large payloads + embedded newlines (CR/LF/CRLF preserved verbatim; app-level line-ending normalization is an explicit adapter, not a silent transform) |
| Integer | `NUMBER(p)` p≤18 / `NUMBER` | `BIGINT`/`INTEGER`/`SMALLINT` | NUMERIC | ✅ incl. `Long.MAX`/`Long.MIN`; DuckDB `BIGINT` (no reported precision) preserves integer-ness instead of degrading to `DOUBLE` |
| Decimal | `NUMBER(p,s)` | `DECIMAL(p,s)` (p≤38, s≤37) | NUMERIC | ✅ full precision, no rounding; p>38 coerced to 38 (documented) |
| Big integer | `NUMBER(38)` | `DECIMAL(38,0)` | NUMERIC | ✅ |
| Float | `BINARY_FLOAT` / `BINARY_DOUBLE` | `DOUBLE` | NUMERIC | ✅ value; `NaN`/`±Infinity` where both engines accept them |
| Boolean | `NUMBER(1)` (0/1 convention) | `BOOLEAN` | BOOLEAN | ✅ true/false/NULL |
| Date+time | `DATE` | `TIMESTAMP` | TEMPORAL | ✅ Oracle `DATE` carries a time component; it is **not** dropped |
| Timestamp | `TIMESTAMP` | `TIMESTAMP` | TEMPORAL | ✅ fractional seconds (µs) preserved |
| Time-of-day | — | `TIME` | TEMPORAL | ✅ DuckDB→DuckDB µs preserved (bound as ISO text, not lossy `java.sql.Time`) |
| Timestamp+TZ | `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP WITH TIME ZONE` | TEMPORAL | ✅ instant preserved (offset normalized) |
| Binary | `RAW(n)` n≤2000 / `BLOB` | `BLOB` | BINARY | ✅ byte-exact, incl. zero bytes and empty binary |

## Known limitations (documented, never silent)

| Type | Behavior | Why / adapter |
|---|---|---|
| `TIMESTAMP WITH LOCAL TIME ZONE` | Carried as **string** with a `timestampltz-stringified` warning | No session-free faithful conversion exists; PkgroveKit refuses to guess a zone. Provide a session-aware `ValueReader` adapter if a typed round-trip is required. |
| Oracle `CHAR(n)` blank padding | Trailing spaces preserved as read; not re-padded to `n` on a variable-width target | The common model has no fixed-width flag; a `CHAR` semantic would need a model addition. Compare with an explicit trim adapter if needed. |
| Unconstrained Oracle `NUMBER` (no precision) → DuckDB | Integer-named sources preserve integer-ness; a genuinely unconstrained `NUMBER` with a fractional value maps to `DOUBLE` | With neither precision nor an integer type name, `DOUBLE` is the only general default; constrain the source (`NUMBER(p,s)`) for an exact `DECIMAL` target. |
| `INTERVAL`, `XMLTYPE`, Oracle `UUID`/`JSON`/arrays, and other vendor types | `ValueKind.OTHER` → **refused** with a `ConversionException` (column, kind, source type, adapter path), unless `ConversionPolicy.STRINGIFY`/`SKIP` is chosen | These have no faithful cross-database target; the error tells you exactly how to opt into a lossy carry or drop. |

## Unsupported-type error shape

```
no faithful duckdb type for column 'geom' (kind=OTHER, source type 'GEOMETRY').
Adapter path: set ConversionPolicy.STRINGIFY to carry it as text (with a warning),
or SKIP to drop it; for a first-class mapping, add the type to duckdbDialect.typeFor
(and a source ValueReader.normalize case if the JDBC value needs coercion).
```

## Coverage

- **Live Oracle ↔ DuckDB matrix**: `integration-tests/.../OracleTypeMatrixIT`
  (both directions, read/insert/upsert/round-trip; runs against a real Oracle
  testcontainer in CI).
- **DuckDB boundary matrix** (NULL/empty/unicode/large/zero-byte/max-precision/
  fractional-temporal): `pkgrovekit-transfer/.../TypeMatrixDuckDbTest` (runs
  everywhere; no Docker needed).
- **Dialect branch matrices**: `OracleDialectTest`, `DuckDbDialectTest`,
  `PostgresDialectTest`.
