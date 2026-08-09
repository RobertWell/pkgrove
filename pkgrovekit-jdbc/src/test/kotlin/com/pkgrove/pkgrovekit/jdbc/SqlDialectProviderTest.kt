package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.Column
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HEL-235: verifies the [SqlDialectProvider] discovery contract used by the
 * framework adapters. A test-only provider is registered under
 * `src/test/resources/META-INF/services`, so this module proves the mechanism
 * with no dependency on any concrete dialect module.
 */
class SqlDialectProviderTest {

    @Test
    fun `loadAll discovers registered providers keyed by lower-cased id`() {
        val map = SqlDialectProvider.loadAll()
        assertTrue("fake" in map, "expected the test provider id 'fake' in $map")
        assertSame(FakeDialectProvider.DIALECT, map["fake"])
    }

    @Test
    fun `loadById is case-insensitive and returns null for unknown ids`() {
        assertSame(FakeDialectProvider.DIALECT, SqlDialectProvider.loadById("FAKE"))
        assertNull(SqlDialectProvider.loadById("does-not-exist"))
    }

    @Test
    fun `provider exposes its id`() {
        assertEquals("fake", FakeDialectProvider().id)
    }
}

/** A minimal in-test dialect + provider (registered in test resources). */
class FakeDialectProvider : SqlDialectProvider {
    override val id: String = "fake"
    override fun dialect(): SqlDialect = DIALECT

    companion object {
        val DIALECT: SqlDialect = object : SqlDialect {
            override val name = "fake"
            override fun typeFor(column: Column): String? = "TEXT"
        }
    }
}
