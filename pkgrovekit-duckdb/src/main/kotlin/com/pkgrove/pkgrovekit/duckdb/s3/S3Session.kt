package com.pkgrove.pkgrovekit.duckdb.s3

import java.sql.Connection

/**
 * HEL-236: S3-compatible object-storage session configuration for a DuckDB
 * connection. DuckDB's `httpfs` extension already speaks S3 (proven against
 * the LAN MinIO) — this type is the CONFIGURATION of that capability, not new
 * transport code: it loads `httpfs` and registers a DuckDB secret carrying the
 * endpoint, credentials, URL style, and SSL mode.
 *
 * Optional by construction: nothing else in `pkgrovekit-duckdb` references
 * this package, and no new dependency is introduced (the S3 dialogue is
 * DuckDB's own plus `java.net.http` for the replace step — see
 * [SigV4ObjectStoreOps]).
 */
data class S3Session(
    /** `host:port` — NO scheme; SSL is a separate, explicit choice ([useSsl]). */
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val region: String = "us-east-1",
    /** MinIO-on-LAN default. Set true when the endpoint terminates TLS. */
    val useSsl: Boolean = false,
    /** MinIO wants PATH (`endpoint/bucket/key`); AWS defaults to VHOST. */
    val urlStyle: UrlStyle = UrlStyle.PATH,
    /** Name of the DuckDB secret this session owns (CREATE OR REPLACE). */
    val secretName: String = "pkgrovekit_s3",
) {
    enum class UrlStyle(internal val duckDbValue: String) {
        PATH("path"), VHOST("vhost")
    }

    init {
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        require("://" !in endpoint) {
            "endpoint must be host:port without a scheme — SSL is the useSsl flag"
        }
        require(SECRET_NAME.matches(secretName)) {
            "secretName must match ${SECRET_NAME.pattern}"
        }
    }

    /**
     * Configure [connection] for this session: `INSTALL httpfs` (a no-op when
     * already installed; set [installExtension] false on air-gapped hosts that
     * pre-bundle it), `LOAD httpfs`, then `CREATE OR REPLACE SECRET` with this
     * session's endpoint/credentials. Idempotent — safe to call per publish.
     */
    @JvmOverloads
    fun configure(connection: Connection, installExtension: Boolean = true) {
        connection.createStatement().use { st ->
            for (sql in configurationSql(installExtension)) st.execute(sql)
        }
    }

    /** The exact statements [configure] runs — internal so tests can pin the
     *  escaping without a live connection. Credentials appear ONLY here and in
     *  the DuckDB secret store; never in exceptions or toString. */
    internal fun configurationSql(installExtension: Boolean): List<String> = buildList {
        if (installExtension) add("INSTALL httpfs")
        add("LOAD httpfs")
        add(
            "CREATE OR REPLACE SECRET $secretName (" +
                "TYPE S3, " +
                "KEY_ID '${sq(accessKeyId)}', " +
                "SECRET '${sq(secretAccessKey)}', " +
                "REGION '${sq(region)}', " +
                "ENDPOINT '${sq(endpoint)}', " +
                "URL_STYLE '${urlStyle.duckDbValue}', " +
                "USE_SSL $useSsl)"
        )
    }

    /** Never leak the secret through logs/toString (data-class default would). */
    override fun toString(): String =
        "S3Session(endpoint=$endpoint, region=$region, useSsl=$useSsl, " +
            "urlStyle=$urlStyle, secretName=$secretName, accessKeyId=***, secretAccessKey=***)"

    private fun sq(v: String) = v.replace("'", "''")

    private companion object {
        val SECRET_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
