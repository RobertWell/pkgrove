package com.pkgrove.pkgrovekit.duckdb.s3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * HEL-236: the SigV4 signer pinned against AWS's PUBLISHED signing example
 * (the "GET /test.txt from examplebucket" vector from the S3 SigV4 docs) —
 * proving the canonical-request + HMAC chain byte-for-byte, not just shape.
 */
class AwsSigV4Test {

    @Test
    fun `matches the AWS published GET object example signature`() {
        val header = AwsSigV4.authorizationHeader(
            method = "GET",
            canonicalUri = "/test.txt",
            canonicalQuery = "",
            headers = mapOf(
                "host" to "examplebucket.s3.amazonaws.com",
                "range" to "bytes=0-9",
                "x-amz-content-sha256" to AwsSigV4.EMPTY_PAYLOAD_SHA256,
                "x-amz-date" to "20130524T000000Z",
            ),
            payloadSha256 = AwsSigV4.EMPTY_PAYLOAD_SHA256,
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            amzDate = "20130524T000000Z",
        )
        assertEquals(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            header,
        )
    }

    @Test
    fun `path encoding keeps slashes and unreserved, percent-encodes the rest uppercase`() {
        assertEquals("a/b/c.parquet", AwsSigV4.encodePath("a/b/c.parquet"))
        assertEquals("a%2Bb/c%3Dd", AwsSigV4.encodePath("a+b/c=d"))
        assertEquals("%E6%A8%99%E7%B1%A4", AwsSigV4.encodePath("標籤"))
        assertTrue("~" == AwsSigV4.encodePath("~"))
    }
}
