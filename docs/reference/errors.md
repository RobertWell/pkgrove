# Error handling reference

On the golden path these surface as typed `TransferOutcome`s (`Rejected` for
admission problems, `Partial` with a checkpoint, `Failed`, `Cancelled`). The
underlying exceptions, for the advanced/low-level tiers:

- Unsafe runtime identifiers → `Identifiers.UnsafeIdentifierException`
  (the raw name is never echoed into messages).
- Missing named parameters → `NamedSql.MissingParametersException` listing the
  exact absent names (values are never logged or interpolated).
- Bad mappings (unknown source, duplicate target, double-mapped source) →
  `Mapping.MappingException` naming the offending columns, before any write.
- Unrepresentable types under `REJECT` → `ConversionException` naming the column.
- Failed batch writes → `JdbcBatchWriter.BatchWriteException` carrying an
  `OperationReport` with committed row count, failed batch index, and row range.
- Transaction-policy failures → `TransactionalWriter.TransactionWriteException`
  carrying the full `TransactionOutcome` (state, committed rows, checkpoint,
  `RetrySafety`); a rollback that itself fails yields state `UNCERTAIN` with
  the cleanup failure attached via `addSuppressed`.
- Cancellation → `OperationCancelledException`; open chunks are rolled back.
- Incomplete plan definitions → `Relay.PlanDefinitionException`, thrown at
  definition time — an incomplete plan can never reach an executor.
