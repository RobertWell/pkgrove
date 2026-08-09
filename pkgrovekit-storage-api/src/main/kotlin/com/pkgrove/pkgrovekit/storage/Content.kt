package com.pkgrove.pkgrovekit.storage

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming content for uploads (HEL-236 guarantee 1). A source is a RECIPE
 * for a stream, not a buffer: [open] returns a fresh stream per call and the
 * store reads it incrementally.
 *
 * [repeatable] is the retry contract (HEL-236 guarantee 4): a repeatable
 * source can be re-opened after a transport failure, so the write is SAFE to
 * retry; a one-shot source cannot, and an implementation must fail visibly
 * ([StorageIoException] with `retrySafe = false`) instead of silently
 * re-sending a half-consumed stream.
 */
interface ContentSource {
    /** Exact content length; null = unknown (multipart-only territory). */
    val lengthBytes: Long?

    /** True when [open] may be called more than once (retry-safe). */
    val repeatable: Boolean

    /** A FRESH stream positioned at the start of the content. */
    fun open(): InputStream

    companion object {
        /** In-memory bytes — repeatable by nature. Caller must not mutate [bytes]. */
        @JvmStatic
        fun of(bytes: ByteArray): ContentSource = object : ContentSource {
            override val lengthBytes: Long = bytes.size.toLong()
            override val repeatable: Boolean = true
            override fun open(): InputStream = ByteArrayInputStream(bytes)
        }

        /** UTF-8 text — repeatable. */
        @JvmStatic
        fun of(text: String): ContentSource = of(text.toByteArray(Charsets.UTF_8))

        /** A file — repeatable; length read per open so it must not change mid-put. */
        @JvmStatic
        fun of(file: Path): ContentSource = object : ContentSource {
            override val lengthBytes: Long = Files.size(file)
            override val repeatable: Boolean = true
            override fun open(): InputStream = Files.newInputStream(file)
        }

        /**
         * A ONE-SHOT stream of known [length]. Not retry-safe: a second [open]
         * throws, which the store surfaces as `retrySafe = false` rather than
         * corrupting the object with a partial re-send.
         */
        @JvmStatic
        fun oneShot(stream: InputStream, length: Long): ContentSource = object : ContentSource {
            private val consumed = AtomicBoolean(false)
            override val lengthBytes: Long = length
            override val repeatable: Boolean = false
            override fun open(): InputStream {
                check(consumed.compareAndSet(false, true)) {
                    "one-shot content source opened twice — a failed write over it must NOT be retried"
                }
                return stream
            }
        }
    }
}

/** Checksum algorithms the API can express; providers support a subset. */
enum class ChecksumAlgorithm { SHA256, CRC32C, MD5 }

/**
 * A content checksum. [valueBase64] is the base64 of the raw digest bytes —
 * the same encoding S3's `x-amz-checksum-*` headers use, so provider-side
 * verification and client-side verification compare the identical value.
 */
data class Checksum(val algorithm: ChecksumAlgorithm, val valueBase64: String) {
    companion object {
        /** SHA-256 of in-memory [bytes]. */
        @JvmStatic
        fun sha256(bytes: ByteArray): Checksum {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return Checksum(ChecksumAlgorithm.SHA256, Base64.getEncoder().encodeToString(digest))
        }

        /** SHA-256 computed by STREAMING [source] once (bounded buffer). */
        @JvmStatic
        fun sha256Of(source: ContentSource): Checksum {
            val md = MessageDigest.getInstance("SHA-256")
            source.open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return Checksum(ChecksumAlgorithm.SHA256, Base64.getEncoder().encodeToString(md.digest()))
        }
    }
}

/**
 * Verifies a SHA-256 checksum WHILE streaming: on the read that reaches EOF
 * the computed digest is compared and a mismatch throws
 * [ChecksumMismatchException] (HEL-236: corruption fails visibly, exactly at
 * the earliest point it is provable). Closing before EOF performs NO
 * verification — a partial read proves nothing either way.
 */
class ChecksumVerifyingInputStream(
    private val delegate: InputStream,
    private val expected: Checksum,
    private val key: ObjectKey,
) : InputStream() {
    init {
        require(expected.algorithm == ChecksumAlgorithm.SHA256) {
            "streaming verification supports SHA256 (got ${expected.algorithm})"
        }
    }

    private val md = MessageDigest.getInstance("SHA-256")
    private var verified = false

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) md.update(b.toByte()) else verifyAtEof()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) md.update(b, off, n) else if (n < 0) verifyAtEof()
        return n
    }

    private fun verifyAtEof() {
        if (verified) return
        verified = true
        val actual = Base64.getEncoder().encodeToString(md.digest())
        if (actual != expected.valueBase64) {
            throw ChecksumMismatchException(key, expected.valueBase64, actual)
        }
    }

    override fun close() {
        try {
            delegate.close()
        } catch (e: IOException) {
            throw StorageIoException("closing content stream for '$key' failed", e, retrySafe = true)
        }
    }
}
