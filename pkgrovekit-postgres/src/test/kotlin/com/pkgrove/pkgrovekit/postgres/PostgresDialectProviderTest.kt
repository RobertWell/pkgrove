package com.pkgrove.pkgrovekit.postgres

import com.pkgrove.pkgrovekit.jdbc.SqlDialectProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PostgresDialectProviderTest {

    @Test
    fun `provider contributes the postgres id and singleton`() {
        val provider = PostgresDialectProvider()
        assertEquals("postgres", provider.id)
        assertSame(PostgresDialect, provider.dialect())
    }

    @Test
    fun `service loader discovers the postgres dialect`() {
        // this module is on the test classpath, so its META-INF/services entry
        // must resolve the postgres id to the real dialect singleton
        assertSame(PostgresDialect, SqlDialectProvider.loadById("POSTGRES"))
    }
}
