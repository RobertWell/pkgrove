package com.pkgrove.pkgrovekit.duckdb.s3

/**
 * HEL-236: a write TARGET that is an object key — `s3://bucket/key` — rather
 * than a table. The Parquet/CSV object at [uri] is the deliverable; how it is
 * replaced atomically is [S3Publisher]'s contract.
 *
 * Validation is deliberately strict: the URI is embedded in DuckDB SQL string
 * literals, so characters that could escape the literal are refused outright
 * (parity with `Identifiers` — refuse, never quote-and-hope).
 */
data class ObjectKey(val bucket: String, val key: String) {

    init {
        require(bucket.isNotBlank()) { "bucket must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }
        require(!key.startsWith("/")) { "key must not start with '/'" }
        for ((what, v) in listOf("bucket" to bucket, "key" to key)) {
            require(FORBIDDEN.none { it in v }) {
                "$what contains a character not allowed in an object target " +
                    "(quote/backslash/control/whitespace)"
            }
        }
    }

    /** The final, consumer-visible URI. */
    val uri: String get() = "s3://$bucket/$key"

    /**
     * The staging key for one publish attempt: `<key>.staging-<runId>` in the
     * SAME bucket (same-bucket keeps the replace step a server-side
     * CopyObject). Orphans from failed runs are recognizable — and safe to
     * garbage-collect — by the `.staging-` infix.
     */
    fun staging(runId: String): ObjectKey = ObjectKey(bucket, "$key.staging-$runId")

    override fun toString(): String = uri

    private companion object {
        val FORBIDDEN: List<String> =
            listOf("'", "\"", "\\", "\n", "\r", "\t", " ") +
                (0..31).map { it.toChar().toString() }
    }
}
