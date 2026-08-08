package com.pkgrove.pkgrovekit.duckdb.s3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HEL-236: session configuration — statement shape, escaping, no leakage. */
class S3SessionTest {

    @Test
    fun `configuration loads httpfs and registers the secret`() {
        val session = S3Session(
            endpoint = "minio.local:9000",
            accessKeyId = "ak",
            secretAccessKey = "sk",
            region = "us-east-1",
        )
        val sql = session.configurationSql(installExtension = true)
        assertEquals("INSTALL httpfs", sql[0])
        assertEquals("LOAD httpfs", sql[1])
        assertEquals(
            "CREATE OR REPLACE SECRET pkgrovekit_s3 (TYPE S3, KEY_ID 'ak', " +
                "SECRET 'sk', REGION 'us-east-1', ENDPOINT 'minio.local:9000', " +
                "URL_STYLE 'path', USE_SSL false)",
            sql[2],
        )
    }

    @Test
    fun `install can be skipped for air-gapped hosts`() {
        val session = S3Session("e:1", "a", "s")
        val sql = session.configurationSql(installExtension = false)
        assertEquals("LOAD httpfs", sql[0])
        assertEquals(2, sql.size)
    }

    @Test
    fun `single quotes in credentials are escaped, not literal-breaking`() {
        val session = S3Session("e:1", "a'k", "s'k")
        val secret = session.configurationSql(true)[2]
        assertTrue("KEY_ID 'a''k'" in secret)
        assertTrue("SECRET 's''k'" in secret)
    }

    @Test
    fun `endpoint with a scheme is refused - SSL is the explicit flag`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3Session("http://minio:9000", "a", "s")
        }
    }

    @Test
    fun `secret name must be a plain identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3Session("e:1", "a", "s", secretName = "bad name; DROP")
        }
    }

    @Test
    fun `toString never leaks credentials`() {
        val s = S3Session("e:1", "SECRET_ACCESS", "SECRET_KEY").toString()
        assertFalse("SECRET_ACCESS" in s)
        assertFalse("SECRET_KEY" in s)
    }
}
