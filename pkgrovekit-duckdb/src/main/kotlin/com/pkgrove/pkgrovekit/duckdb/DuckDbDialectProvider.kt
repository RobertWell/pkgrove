package com.pkgrove.pkgrovekit.duckdb

import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider

/**
 * Service-loaded contribution of the DuckDB dialect (HEL-235). Registered in
 * `META-INF/services/com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider` so a
 * framework adapter can offer the `duckdb` config id iff this module is on the
 * classpath — without any adapter compile-time dependency on this module.
 */
class DuckDbDialectProvider : SqlDialectProvider {
    override val id: String = "duckdb"
    override fun dialect(): SqlDialect = DuckDbDialect
}
