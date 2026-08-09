package com.pkgrove.pkgrovekit.storage.s3

import com.pkgrove.pkgrovekit.storage.ContentSource
import com.pkgrove.pkgrovekit.storage.ObjectKey
import com.pkgrove.pkgrovekit.storage.PreconditionFailedException
import com.pkgrove.pkgrovekit.storage.PutOptions
import com.pkgrove.pkgrovekit.storage.WriteCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * OPT-IN Amazon S3 cloud smoke test (HEL-236 "officially tested" reference
 * target). NEVER runs in normal CI or pull requests: it activates only when
 * `PKGROVEKIT_S3_SMOKE_BUCKET` (+ standard AWS credentials via the default
 * chain, e.g. protected CI secrets or a release engineer's role) is present.
 * See docs/storage.md § "Amazon S3 smoke test" for the run recipe. The bucket
 * is expected to exist and to be dedicated to this test; keys are UUID-scoped
 * and deleted afterwards.
 */
@EnabledIfEnvironmentVariable(named = "PKGROVEKIT_S3_SMOKE_BUCKET", matches = ".+")
class AmazonS3SmokeIT {

    @Test
    fun `amazon s3 basic round trip and conditional create`() {
        val bucket = System.getenv("PKGROVEKIT_S3_SMOKE_BUCKET")
        val region = System.getenv("PKGROVEKIT_S3_SMOKE_REGION") ?: "us-east-1"
        val prefix = "pkgrovekit-smoke/${UUID.randomUUID()}"
        S3ObjectStore.open(S3StorageConfig(region = region), bucket, prefix).use { store ->
            val key = ObjectKey("smoke.txt")
            try {
                store.put(key, ContentSource.of("smoke"), PutOptions(condition = WriteCondition.IfAbsent))
                store.get(key).use {
                    assertEquals("smoke", it.stream().readBytes().toString(Charsets.UTF_8))
                }
                assertThrows(PreconditionFailedException::class.java) {
                    store.put(key, ContentSource.of("again"), PutOptions(condition = WriteCondition.IfAbsent))
                }
            } finally {
                store.deletePrefix("") // remove everything under the UUID prefix
            }
        }
    }
}
