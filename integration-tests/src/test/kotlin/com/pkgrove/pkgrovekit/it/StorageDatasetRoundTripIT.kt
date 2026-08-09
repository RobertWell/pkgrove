package com.pkgrove.pkgrovekit.it

import com.pkgrove.pkgrovekit.duckdb.DuckDbDialect
import com.pkgrove.pkgrovekit.jdbc.JdbcBatchWriter
import com.pkgrove.pkgrovekit.jdbc.JdbcReader
import com.pkgrove.pkgrovekit.jdbc.SqlDialect
import com.pkgrove.pkgrovekit.postgres.PostgresValueReader
import com.pkgrove.pkgrovekit.storage.ObjectDataset
import com.pkgrove.pkgrovekit.storage.StagingArea
import com.pkgrove.pkgrovekit.storage.s3.S3Credentials
import com.pkgrove.pkgrovekit.storage.s3.S3ObjectStore
import com.pkgrove.pkgrovekit.storage.s3.S3StorageConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager

/**
 * HEL-236 acceptance: the COMPLETE database → S3-compatible object storage →
 * database path, live. Rows stream out of PostgreSQL (never fully
 * materialized), land as a bounded-part dataset on MinIO committed by a
 * conditional manifest, then stream back into DuckDB with per-part checksum +
 * row-count verification. Non-Oracle by construction so it rides the BLOCKING
 * `postgresIntegrationTest` CI gate.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageDatasetRoundTripIT {

    private lateinit var pg: PostgreSQLContainer<*>
    private lateinit var minio: MinIOContainer
    private lateinit var pgc: Connection
    private lateinit var store: S3ObjectStore

    private val rows = 4_000

    @BeforeAll
    fun start() {
        pg = PostgreSQLContainer("postgres:16-alpine")
        pg.start()
        minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
        minio.start()

        pgc = DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password)
        pgc.createStatement().use { st ->
            st.execute(
                """CREATE TABLE trade (
                    id BIGINT PRIMARY KEY, symbol VARCHAR(12) NOT NULL,
                    price NUMERIC(12,4), traded_on DATE, note TEXT)""",
            )
            st.execute(
                """INSERT INTO trade
                   SELECT g, 'SYM' || (g % 50), (g % 997) * 1.25,
                          DATE '2026-01-01' + (g % 365), 'note-' || g
                   FROM generate_series(0, ${rows - 1}) g""",
            )
        }

        store = S3ObjectStore.open(
            S3StorageConfig(
                region = "us-east-1",
                endpoint = URI.create(minio.s3URL),
                credentials = S3Credentials.Static(minio.userName, minio.password),
            ),
            bucket = "pkgrovekit-e2e",
        )
        store.createBucketIfMissing()
    }

    @AfterAll
    fun stop() {
        runCatching { store.close() }
        runCatching { pgc.close() }
        runCatching { pg.stop() }
        runCatching { minio.stop() }
    }

    @Test
    fun `postgres to minio dataset to duckdb - complete and verified`() {
        // 1) STREAM out of Postgres into a manifest-committed MinIO dataset
        val export = JdbcReader.open(
            pgc, "SELECT id, symbol, price, traded_on, note FROM trade ORDER BY id",
            emptyList(),
            JdbcReader.ReadOptions(fetchSize = 500, valueReader = PostgresValueReader()),
        ).use { stream ->
            ObjectDataset.export(
                store, "exports/trades", "run-e2e-1", stream.schema,
                stream.batches(500),
                ObjectDataset.ExportOptions(maxPartBytes = 64 * 1024), // force several parts
            )
        }
        assertEquals(rows.toLong(), export.manifest.totalRows)
        assertTrue(export.manifest.parts.size > 1, "expected a multi-part dataset")
        // committed atomically: manifest present, staging fully gone
        assertTrue(store.exists(export.manifestKey))
        assertEquals(0, store.list("exports/trades/${StagingArea.STAGING_SEGMENT}/").count())

        // 2) STREAM the dataset back into DuckDB (checksums + counts verified)
        DriverManager.getConnection("jdbc:duckdb:").use { duck ->
            val handle = ObjectDataset.open(store, export.manifestKey)
            val dialect = DuckDbDialect
            duck.createStatement().use { st ->
                st.execute(dialect.createTableDdl("trade_back", handle.schema, SqlDialect.TargetMode.CREATE))
            }
            val adapted = handle.batches().map { batch ->
                com.pkgrove.pkgrovekit.core.RowBatch(
                    batch.schema,
                    batch.rows.map { row ->
                        com.pkgrove.pkgrovekit.core.Row(
                            batch.schema,
                            row.values.mapIndexed { i, v -> dialect.bindValue(v, batch.schema[i]) },
                        )
                    },
                )
            }
            val report = JdbcBatchWriter.write(
                duck, dialect.insertSql("trade_back", handle.schema), adapted,
                JdbcBatchWriter.WriteOptions(),
            )
            assertTrue(report.completed)
            assertEquals(rows.toLong(), report.rowsAffected)

            // 3) VALUE fidelity across the full loop, not just counts
            duck.createStatement().use { st ->
                val rs = st.executeQuery(
                    "SELECT count(*), sum(id), min(symbol), max(note) FROM trade_back",
                )
                rs.next()
                assertEquals(rows.toLong(), rs.getLong(1))
                assertEquals((rows.toLong() - 1) * rows / 2, rs.getLong(2))
                assertEquals("SYM0", rs.getString(3))
                assertEquals("note-999", rs.getString(4)) // lexicographic max
                val spot = st.executeQuery(
                    "SELECT symbol, price, traded_on, note FROM trade_back WHERE id = 1234",
                )
                spot.next()
                assertEquals("SYM${1234 % 50}", spot.getString(1))
                assertEquals(0, BigDecimal("${(1234 % 997) * 1.25}").compareTo(spot.getBigDecimal(2)))
                assertEquals(java.sql.Date.valueOf(java.time.LocalDate.of(2026, 1, 1).plusDays((1234 % 365).toLong())), spot.getDate(3))
                assertEquals("note-1234", spot.getString(4))
            }
        }
    }
}
