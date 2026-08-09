package com.pkgrove.pkgrovekit.duckdb

import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class DuckDbDialectProviderTest {

    @Test
    fun `provider contributes the duckdb id and singleton`() {
        val provider = DuckDbDialectProvider()
        assertEquals("duckdb", provider.id)
        assertSame(DuckDbDialect, provider.dialect())
    }

    @Test
    fun `service loader discovers the duckdb dialect`() {
        assertSame(DuckDbDialect, SqlDialectProvider.loadById("duckdb"))
    }
}
