package com.pkgrove.pkgrovekit.duckdb.s3

import com.pkgrove.pkgrovekit.core.DataWarning
import java.sql.Connection
import java.util.UUID

/**
 * HEL-236: publish the result of a DuckDB query as ONE object at an
 * [ObjectKey], with atomic-replace semantics. `COPY TO 's3://…'` alone writes
 * the final key in place, so a mid-write failure would leave a corrupt or
 * partial object where consumers read — the real work here is that this can
 * NEVER happen:
 *
 * ```
 * write s3://bucket/key.staging-<runId>   (COPY — failure cannot touch final)
 *   -> verify (read the staging object back, row-count must match the COPY)
 *   -> replace (server-side CopyObject staging -> final; atomic per object)
 *   -> delete staging (best-effort; failure = orphan, REPORTED, final is live)
 * ```
 *
 * Consistency caveat (documented, by design): CopyObject-then-delete is two
 * operations, not one. A crash between them leaves the CORRECT final object
 * plus a staging orphan — never a corrupt final. Orphans carry the
 * `.staging-<runId>` infix (see [ObjectKey.staging]) and are safe to
 * garbage-collect. On S3/MinIO a (Copy)Object PUT is atomic — readers see the
 * old object or the new one, never bytes of both — but S3 copy can also
 * return `200 OK` with an error document in the body, which
 * [SigV4ObjectStoreOps] checks for explicitly.
 *
 * Re-running a publish is idempotent at the destination: each run stages under
 * a fresh runId and replaces the SAME final key — no duplicates accumulate.
 *
 * HEL-264 (training-record publication) is the first consumer: training
 * records land in DuckDB, then publish here as Parquet under
 * `s3://model-results/...`.
 */
class S3Publisher @JvmOverloads constructor(
    private val session: S3Session,
    /** The replace/delete seam. Defaults to the built-in SigV4 client against
     *  [session]'s endpoint; substitute a stub in tests or a consumer's SDK. */
    private val ops: ObjectStoreOps = SigV4ObjectStoreOps(session),
) {

    /** Object serialization formats DuckDB can COPY to AND read back for the
     *  verify step. */
    enum class Format(internal val copyOptions: String, internal val readerFunction: String) {
        PARQUET("FORMAT PARQUET", "read_parquet"),
        CSV("FORMAT CSV, HEADER", "read_csv_auto"),
    }

    data class Options(
        val format: Format = Format.PARQUET,
        /** Identifies one publish attempt (staging-key suffix). Default: a
         *  fresh UUID per call — pass a stable id only if the caller manages
         *  its own attempt identity. */
        val runId: String? = null,
        /** Run [S3Session.configure] on the connection first (idempotent). */
        val configureSession: Boolean = true,
        /** Forwarded to [S3Session.configure] — disable on air-gapped hosts. */
        val installExtension: Boolean = true,
    )

    /** Where a failed publish stopped. In EVERY failure stage the final object
     *  is untouched — that is the invariant, not a best case. */
    enum class Stage { WRITE_STAGING, VERIFY, REPLACE }

    /**
     * The typed result — mirrors `TransferOutcome`'s shape: partial states are
     * unrepresentable as success, and a staging orphan is always REPORTED,
     * never silent.
     */
    sealed interface PublishOutcome {
        val target: ObjectKey
        val runId: String

        /** The final object at [target] now holds this run's data. [rows] is
         *  the verified row count. [stagingOrphan] is non-null only when the
         *  post-replace cleanup failed — the publish itself succeeded and the
         *  orphan (also warned as `STAGING_ORPHAN`) is safe to delete. */
        data class Published(
            override val target: ObjectKey,
            override val runId: String,
            val rows: Long,
            val stagingOrphan: ObjectKey? = null,
            val warnings: List<DataWarning> = emptyList(),
        ) : PublishOutcome

        /** The publish failed at [stage]; the PRIOR final object at [target]
         *  is untouched. [stagingOrphan] names the staging key this run used —
         *  it may or may not exist (a WRITE_STAGING failure can abort before
         *  or during the object write) and is safe to delete either way. */
        data class Failed(
            override val target: ObjectKey,
            override val runId: String,
            val stage: Stage,
            val stagingOrphan: ObjectKey,
            val cause: Exception,
        ) : PublishOutcome
    }

    /** Raised in the VERIFY stage when the staging object's read-back row
     *  count disagrees with what COPY reported. */
    class VerificationException(message: String) : RuntimeException(message)

    /**
     * Publish `sourceSql`'s result set from [connection] (a DuckDB connection)
     * to [target]. The source SQL is caller-owned, exactly like Transfer's;
     * the object URI side is validated by [ObjectKey].
     */
    @JvmOverloads
    fun publish(
        connection: Connection,
        sourceSql: String,
        target: ObjectKey,
        options: Options = Options(),
    ): PublishOutcome {
        if (options.configureSession) session.configure(connection, options.installExtension)
        return publish(DuckDbCopyPort(connection, options.format), sourceSql, target, options)
    }

    /** The orchestration against the two seams — what the unit tests prove. */
    internal fun publish(
        port: CopyPort,
        sourceSql: String,
        target: ObjectKey,
        options: Options = Options(),
    ): PublishOutcome {
        val runId = options.runId ?: UUID.randomUUID().toString()
        val staging = target.staging(runId)

        // 1. WRITE the staging object — the final key is never written here,
        //    so any failure (including mid-write) cannot corrupt it.
        val reported = try {
            port.copyTo(sourceSql, staging.uri)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw e
        } catch (e: Exception) {
            return PublishOutcome.Failed(target, runId, Stage.WRITE_STAGING, staging, e)
        }

        // 2. VERIFY the staging object end-to-end (read back THROUGH the object
        //    store, not from local state) before it may become final.
        val rows = try {
            val readBack = port.countRows(staging.uri)
            if (reported >= 0 && readBack != reported) {
                throw VerificationException(
                    "staging object ${staging.uri} read back $readBack rows " +
                        "but COPY reported $reported"
                )
            }
            readBack
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw e
        } catch (e: Exception) {
            return PublishOutcome.Failed(target, runId, Stage.VERIFY, staging, e)
        }

        // 3. REPLACE: server-side copy staging -> final. Atomic per object on
        //    S3/MinIO; a refused/failed copy leaves the prior final in place.
        try {
            ops.copyObject(target.bucket, staging.key, target.key)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw e
        } catch (e: Exception) {
            return PublishOutcome.Failed(target, runId, Stage.REPLACE, staging, e)
        }

        // 4. CLEANUP is best-effort: the publish already succeeded, so a
        //    failed delete degrades to a reported orphan, never to Failed.
        return try {
            ops.deleteObject(staging.bucket, staging.key)
            PublishOutcome.Published(target, runId, rows)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt(); throw e
        } catch (e: Exception) {
            PublishOutcome.Published(
                target, runId, rows,
                stagingOrphan = staging,
                warnings = listOf(
                    DataWarning(
                        code = "STAGING_ORPHAN",
                        message = "publish succeeded but deleting the staging object " +
                            "${staging.uri} failed (${e.message}) — the orphan is safe to delete",
                    )
                ),
            )
        }
    }

    /**
     * The DuckDB seam: how bytes reach and are read back from the staging URI.
     * Pluggable so the atomic-replace orchestration is provable without an
     * object store; the default is DuckDB `COPY TO` / `read_parquet`.
     */
    interface CopyPort {
        /** Write the query result to [stagingUri]; return the row count COPY
         *  reported, or -1 when the driver gives none. */
        fun copyTo(sourceSql: String, stagingUri: String): Long

        /** Row count of the object at [uri], read back through the store. */
        fun countRows(uri: String): Long
    }

    /**
     * The replace/delete seam — the two S3 operations DuckDB's httpfs does not
     * expose. [SigV4ObjectStoreOps] is the built-in implementation.
     */
    interface ObjectStoreOps {
        /** Server-side copy within [bucket]; must fail loudly (including the
         *  S3 "200 with error body" case) rather than half-succeed. */
        fun copyObject(bucket: String, sourceKey: String, destinationKey: String)

        fun deleteObject(bucket: String, key: String)
    }

    private class DuckDbCopyPort(
        private val connection: Connection,
        private val format: Format,
    ) : CopyPort {
        override fun copyTo(sourceSql: String, stagingUri: String): Long {
            connection.createStatement().use { st ->
                val hasResult = st.execute(
                    "COPY ($sourceSql) TO '$stagingUri' (${format.copyOptions})"
                )
                if (hasResult) {
                    st.resultSet.use { rs -> if (rs.next()) return rs.getLong(1) }
                } else if (st.updateCount >= 0) {
                    return st.updateCount.toLong()
                }
                return -1
            }
        }

        override fun countRows(uri: String): Long =
            connection.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM ${format.readerFunction}('$uri')")
                    .use { rs -> rs.next(); rs.getLong(1) }
            }
    }
}
