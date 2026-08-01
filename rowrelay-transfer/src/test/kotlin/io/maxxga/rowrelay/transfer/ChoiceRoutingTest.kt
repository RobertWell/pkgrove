package io.maxxga.rowrelay.transfer

import io.maxxga.rowrelay.core.Choice
import io.maxxga.rowrelay.core.RowBatch
import io.maxxga.rowrelay.core.partitionByChoice
import io.maxxga.rowrelay.jdbc.JdbcBatchWriter
import io.maxxga.rowrelay.jdbc.JdbcReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/** HEL-167 proof scenario 2: a Choice.Left / Choice.Right route whose branches
 *  feed DIFFERENT downstream pipelines — the validate → (reject | accept) ETL
 *  pattern, built from the pure algebra + the real reader/writer primitives. */
class ChoiceRoutingTest {

    @field:TempDir lateinit var tmp: Path

    @Test
    fun `Choice route sends Left and Right rows to different sinks`() {
        val srcUrl = "jdbc:duckdb:${tmp.resolve("s.db")}"
        val dstUrl = "jdbc:duckdb:${tmp.resolve("d.db")}"
        DriverManager.getConnection(srcUrl).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE src (id BIGINT, name VARCHAR)")
                st.execute("INSERT INTO src SELECT range, 'n' || range FROM range(50)")
            }
        }

        // read
        val rows = DriverManager.getConnection(srcUrl).use { c ->
            JdbcReader.open(c, "SELECT id, name FROM src ORDER BY id").use { it.toList() }
        }
        val schema = rows.first().schema

        // route via the algebra: even id -> Left (reject), odd id -> Right (accept)
        val (rejected, accepted) = rows.partitionByChoice { r ->
            if ((r["id"] as Long) % 2 == 0L) Choice.left(r) else Choice.right(r)
        }
        assertEquals(25, rejected.size)
        assertEquals(25, accepted.size)

        // the two Choice paths feed two different sinks
        DriverManager.getConnection(dstUrl).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE accepted (id BIGINT, name VARCHAR)")
                st.execute("CREATE TABLE rejected (id BIGINT, name VARCHAR)")
            }
            JdbcBatchWriter.write(c, "INSERT INTO accepted VALUES (?, ?)",
                sequenceOf(RowBatch(schema, accepted)))
            JdbcBatchWriter.write(c, "INSERT INTO rejected VALUES (?, ?)",
                sequenceOf(RowBatch(schema, rejected)))

            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM accepted").use { it.next(); assertEquals(25L, it.getLong(1)) }
                st.executeQuery("SELECT count(*) FROM rejected").use { it.next(); assertEquals(25L, it.getLong(1)) }
                // a Right (accepted) row is odd-id, never in the reject sink
                st.executeQuery("SELECT count(*) FROM accepted WHERE id % 2 = 0").use { it.next(); assertEquals(0L, it.getLong(1)) }
            }
        }
    }
}
