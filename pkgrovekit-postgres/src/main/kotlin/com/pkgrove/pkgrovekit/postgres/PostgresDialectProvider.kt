package com.pkgrove.pkgrovekit.postgres

import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider

/**
 * Service-loaded contribution of the PostgreSQL dialect (HEL-235). Registered in
 * `META-INF/services/com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider` so a
 * framework adapter can offer the `postgres` config id iff this module is on the
 * classpath — without any adapter compile-time dependency on this module.
 */
class PostgresDialectProvider : SqlDialectProvider {
    override val id: String = "postgres"
    override fun dialect(): SqlDialect = PostgresDialect
}
