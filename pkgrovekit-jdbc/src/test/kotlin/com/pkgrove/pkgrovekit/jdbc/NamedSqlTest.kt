package com.pkgrove.pkgrovekit.jdbc

import com.pkgrove.pkgrovekit.core.DataWarning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager

/** HEL-119 named-parameter compilation: state-aware parsing, exact missing-name
 *  reporting, unused-parameter policies, and the live JDBC named read path. */
class NamedSqlTest {

    @Test
    fun `compiles names to placeholders in order with repeats`() {
        val n = NamedSql.parse(
            "SELECT * FROM t WHERE a = :user_name AND b >= :updated_after AND c = :user_name")
        assertEquals("SELECT * FROM t WHERE a = ? AND b >= ? AND c = ?", n.sql)
        assertEquals(listOf("user_name", "updated_after", "user_name"), n.positions)
        assertEquals(listOf("user_name", "updated_after"), n.names)
        val bound = n.bind(mapOf("user_name" to "u", "updated_after" to 5))
        assertEquals(listOf("u", 5, "u"), bound)
    }

    @Test
    fun `colons in literals comments quoted identifiers and casts are not parameters`() {
        val sql = """
            SELECT 'a:literal', ":quoted_ident", x::int -- comment :not_a_param
            /* block :also_not */
            FROM t WHERE k = :real_param AND s = 'it''s :still_literal'
        """.trimIndent()
        val n = NamedSql.parse(sql)
        assertEquals(listOf("real_param"), n.positions)
        assertTrue(n.sql.contains("'a:literal'"))
        assertTrue(n.sql.contains("\":quoted_ident\""))
        assertTrue(n.sql.contains("x::int"))
        assertTrue(n.sql.contains(":not_a_param"))     // untouched, inside comment
        assertTrue(n.sql.contains("'it''s :still_literal'"))
    }

    @Test
    fun `missing parameters are rejected with their exact names`() {
        val n = NamedSql.parse("SELECT 1 WHERE a = :first AND b = :second")
        val ex = assertThrows(NamedSql.MissingParametersException::class.java) {
            n.bind(mapOf("first" to 1))
        }
        assertEquals(listOf("second"), ex.missing)
        // a PRESENT key with null value is a null bind, not a missing param
        assertEquals(listOf(1, null), n.bind(mapOf("first" to 1, "second" to null)))
    }

    @Test
    fun `unused parameters follow the configured policy`() {
        val n = NamedSql.parse("SELECT 1 WHERE a = :used")
        val params = mapOf("used" to 1, "extra" to 2)

        assertThrows(NamedSql.UnusedParametersException::class.java) {
            n.bind(params, NamedSql.UnusedParamPolicy.REJECT)
        }
        val warnings = mutableListOf<DataWarning>()
        n.bind(params, NamedSql.UnusedParamPolicy.WARN) { warnings += it }
        assertEquals("extra", warnings.single().column)
        assertTrue(warnings.single().message.let { !it.contains("2") })   // never values

        warnings.clear()
        n.bind(params, NamedSql.UnusedParamPolicy.IGNORE) { warnings += it }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `named read path end to end`() {
        DriverManager.getConnection("jdbc:duckdb:").use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE u (user_name VARCHAR, age INT)")
                st.execute("INSERT INTO u VALUES ('ann', 30), ('bob', 40)")
            }
            JdbcReader.open(conn,
                "SELECT * FROM u WHERE user_name = :user_name AND age > :min_age",
                mapOf("user_name" to "bob", "min_age" to 10)).use { rows ->
                val r = rows.toList().single()
                assertEquals("bob", r["user_name"])
            }
        }
    }
}
