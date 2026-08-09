package com.pkgrove.pkgrovekit.oracle

import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider

/**
 * Service-loaded contribution of the Oracle dialect (HEL-235). Registered in
 * `META-INF/services/com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider` so a
 * framework adapter can offer the `oracle` config id iff this module is on the
 * classpath — without any adapter compile-time dependency on this module.
 */
class OracleDialectProvider : SqlDialectProvider {
    override val id: String = "oracle"
    override fun dialect(): SqlDialect = OracleDialect
}
