package io.maxxga.rowrelay.it;

import io.maxxga.rowrelay.core.Row;
import io.maxxga.rowrelay.core.RowBatch;
import io.maxxga.rowrelay.core.Schema;
import io.maxxga.rowrelay.jdbc.JdbcReader;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The README's Java quick start, compiled and run in CI — proves the public
 * API stays practical from plain Java (HEL-123: Java-compatible requirement).
 */
class JavaConsumerExample {

    @Test
    void readRowsFromJava() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE t (id BIGINT, name VARCHAR)");
                st.execute("INSERT INTO t SELECT range, 'n' || range FROM range(5)");
            }
            // --- README: quick-start-java ---
            try (JdbcReader.RowStream rows =
                     JdbcReader.open(connection, "SELECT * FROM t ORDER BY id")) {
                Schema schema = rows.getSchema();
                List<Row> all = rows.toList();
                Row first = all.get(0);
                Object name = first.get("name");     // dynamic access, no DTOs
                assertEquals("n0", name);
                assertEquals(2, schema.getSize());
            }
            // --- end README ---
        }
    }

    @Test
    void batchesFromJava() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE t (id BIGINT)");
                st.execute("INSERT INTO t SELECT range FROM range(25)");
            }
            try (JdbcReader.RowStream rows =
                     JdbcReader.open(connection, "SELECT * FROM t")) {
                int[] batches = {0};
                for (RowBatch b : io.maxxga.rowrelay.it.SequenceBridge.toIterable(rows.batches(10))) {
                    batches[0]++;
                    if (b.getSize() > 10) throw new AssertionError("batch too large");
                }
                assertEquals(3, batches[0]);
            }
        }
    }
}
