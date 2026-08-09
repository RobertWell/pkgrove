package com.pkgrove.pkgrovekit.oracle

import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class OracleDialectProviderTest {

    @Test
    fun `provider contributes the oracle id and singleton`() {
        val provider = OracleDialectProvider()
        assertEquals("oracle", provider.id)
        assertSame(OracleDialect, provider.dialect())
    }

    @Test
    fun `service loader discovers the oracle dialect`() {
        assertSame(OracleDialect, SqlDialectProvider.loadById("Oracle"))
    }
}
