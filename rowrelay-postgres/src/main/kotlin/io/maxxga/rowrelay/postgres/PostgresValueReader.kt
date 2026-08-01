package io.maxxga.rowrelay.postgres

import io.maxxga.rowrelay.core.Column
import io.maxxga.rowrelay.core.DataWarning
import io.maxxga.rowrelay.jdbc.ValueReader

/**
 * PostgreSQL source normalization (HEL-127): turns the pgjdbc vendor values for
 * `uuid`, `json`/`jsonb`, and array columns into clean JDK types the common
 * model allows — WITHOUT the `unrepresentable-type` warning the default reader
 * would raise for driver classes it doesn't recognize.
 *
 *  - `uuid`        → its canonical String (handled by [ValueReader.Default]).
 *  - `json`/`jsonb`→ the JSON text (from `org.postgresql.util.PGobject.value`).
 *  - arrays        → the Postgres array literal text (`{1,2,3}`), so a Postgres
 *                    target can reconstruct the exact array via [PostgresDialect.bindValue].
 *
 * Driver classes never leak downstream — every value becomes a String. The
 * driver stays consumer-controlled: this only references `PGobject`, resolved
 * from the consumer's pgjdbc at runtime.
 */
class PostgresValueReader : ValueReader.Default() {
    override fun normalize(v: Any, column: Column, warn: (DataWarning) -> Unit): Any? = when {
        // json / jsonb (and any other PGobject-carried type) → its text value.
        v is org.postgresql.util.PGobject -> v.value
        // arrays arrive as java.sql.Array; carry the canonical PG text literal.
        v is java.sql.Array -> v.toString()
        else -> super.normalize(v, column, warn)
    }
}
