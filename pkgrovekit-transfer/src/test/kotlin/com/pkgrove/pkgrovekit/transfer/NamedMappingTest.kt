package com.pkgrove.pkgrovekit.transfer

import com.pkgrove.pkgrovekit.core.Column
import com.pkgrove.pkgrovekit.core.Schema
import com.pkgrove.pkgrovekit.core.ValueKind
import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.NamedSql
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/** HEL-119: named mapping resolution + named transfer behavior on real DBs. */
class NamedMappingTest {

    private lateinit var source: Connection
    private lateinit var target: Connection

    @BeforeEach
    fun setUp() {
        source = DriverManager.getConnection("jdbc:duckdb:")
        target = DriverManager.getConnection("jdbc:duckdb:")
        source.createStatement().use { st ->
            st.execute("CREATE TABLE app_user (source_user VARCHAR, source_display VARCHAR, updated_at TIMESTAMP)")
            st.execute("""INSERT INTO app_user SELECT 'u' || range, 'User ' || range,
                          TIMESTAMP '2026-07-01 00:00:00' + INTERVAL (range) DAY FROM range(20)""")
        }
    }

    @AfterEach
    fun tearDown() { source.close(); target.close() }

    private fun targetRows(sql: String): List<List<Any?>> =
        target.createStatement().use { st ->
            val rs = st.executeQuery(sql)
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
        }

    // ── plan resolution (no database needed) ────────────────────────────────

    private val sourceSchema = Schema(listOf(
        Column("source_user", ValueKind.TEXT, "VARCHAR"),
        Column("source_display", ValueKind.TEXT, "VARCHAR"),
        Column("updated_at", ValueKind.TEMPORAL, "TIMESTAMP")))

    @Test
    fun `plan resolves renames constants and omissions deterministically`() {
        val mapping = Mapping.build {
            "source_user" mapsTo "user_name"
            "source_display" mapsTo "display_name"
            omit("updated_at")
            constant("origin", "lan")
        }
        val plan = mapping.resolve(sourceSchema)
        assertEquals(listOf("user_name", "display_name", "origin"),
                     plan.targetSchema.columns.map { it.name })
        assertEquals(Mapping.Source.FromColumn(0, "source_user"), plan.sources[0])
        assertEquals(Mapping.Source.Constant("lan"), plan.sources[2])
        assertTrue(plan.toString().contains("source_user -> user_name"))
    }

    @Test
    fun `plan rejects unknown duplicate and colliding names before writing`() {
        assertThrows(Mapping.MappingException::class.java) {
            Mapping.build { "no_such_col" mapsTo "x" }.resolve(sourceSchema)
        }.also { assertTrue(it.message!!.contains("no_such_col")) }

        assertThrows(Mapping.MappingException::class.java) {
            Mapping.build {
                "source_user" mapsTo "a"
                "SOURCE_USER" mapsTo "b"      // same source twice (case-insensitive)
            }.resolve(sourceSchema)
        }

        assertThrows(Mapping.MappingException::class.java) {
            Mapping.build {
                "source_user" mapsTo "display_name"   // collides with pass-through
                "source_display" mapsTo "display_name"
            }.resolve(sourceSchema)
        }
    }

    @Test
    fun `duplicate source labels are rejected at schema construction`() {
        // SELECT a, b AS a — the schema itself refuses ambiguity, by name
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Schema(listOf(Column("a", ValueKind.TEXT, "T"), Column("A", ValueKind.TEXT, "T")))
        }
        assertTrue(ex.message!!.contains("duplicate"))
    }

    // ── live transfers ──────────────────────────────────────────────────────

    @Test
    fun `named params plus rename mapping transfer`() {
        val report = Transfer.run(
            source,
            "SELECT * FROM app_user WHERE source_user <> :excluded AND updated_at >= :updated_after",
            mapOf("excluded" to "u5",
                  "updated_after" to java.sql.Timestamp.valueOf("2026-07-01 00:00:00")),
            target, DuckDbDialect, "users",
            Transfer.Options(mapping = Mapping.build {
                "source_user" mapsTo "user_name"
                "source_display" mapsTo "display_name"
            }))
        assertTrue(report.completed)
        assertEquals(19L, report.rowsAffected)   // 20 minus the excluded one
        val row = targetRows("SELECT user_name, display_name FROM users WHERE user_name = 'u7'")
        assertEquals(listOf(listOf("u7", "User 7")), row)
    }

    @Test
    fun `missing named parameter is rejected before execution`() {
        val ex = assertThrows(NamedSql.MissingParametersException::class.java) {
            Transfer.run(source, "SELECT * FROM app_user WHERE source_user = :who",
                         emptyMap<String, Any?>(), target, DuckDbDialect, "users")
        }
        assertEquals(listOf("who"), ex.missing)
        // nothing was created
        assertThrows(Exception::class.java) { targetRows("SELECT * FROM users") }
    }

    @Test
    fun `select order does not change where values land`() {
        target.createStatement().use {
            it.execute("CREATE TABLE dest (user_name VARCHAR, display_name VARCHAR)")
        }
        val mapping = Mapping.build {
            "source_user" mapsTo "user_name"
            "source_display" mapsTo "display_name"
            omit("updated_at")
        }
        // column order A
        Transfer.run(source, "SELECT source_user, source_display, updated_at FROM app_user WHERE source_user = :u",
                     mapOf("u" to "u1"), target, DuckDbDialect, "dest",
                     Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping))
        // column order B (reversed) — same mapping outcome by NAME
        Transfer.run(source, "SELECT updated_at, source_display, source_user FROM app_user WHERE source_user = :u",
                     mapOf("u" to "u2"), target, DuckDbDialect, "dest",
                     Transfer.Options(mode = SqlDialect.TargetMode.APPEND, mapping = mapping))
        assertEquals(
            listOf(listOf("u1", "User 1"), listOf("u2", "User 2")),
            targetRows("SELECT user_name, display_name FROM dest ORDER BY user_name"))
    }

    @Test
    fun `explicit named-key upsert updates matches and inserts the rest`() {
        target.createStatement().use {
            it.execute("CREATE TABLE dest (user_name VARCHAR PRIMARY KEY, display_name VARCHAR)")
            it.execute("INSERT INTO dest VALUES ('u1', 'OLD NAME')")
        }
        val report = Transfer.run(
            source, "SELECT source_user, source_display FROM app_user WHERE source_user IN (:a, :b)",
            mapOf("a" to "u1", "b" to "u2"),
            target, DuckDbDialect, "dest",
            Transfer.Options(
                mode = SqlDialect.TargetMode.APPEND,
                mapping = Mapping.build {
                    "source_user" mapsTo "user_name"
                    "source_display" mapsTo "display_name"
                },
                upsertKeys = listOf("user_name")))
        assertTrue(report.completed)
        assertEquals(
            listOf(listOf("u1", "User 1"), listOf("u2", "User 2")),   // u1 UPDATED, u2 inserted
            targetRows("SELECT user_name, display_name FROM dest ORDER BY user_name"))
    }

    @Test
    fun `upsert with unknown key column is rejected by name`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Transfer.run(source, "SELECT source_user FROM app_user LIMIT 1", emptyMap<String, Any?>(),
                         target, DuckDbDialect, "dest",
                         Transfer.Options(upsertKeys = listOf("nope")))
        }
        assertTrue(ex.message!!.contains("nope"))
    }
}
