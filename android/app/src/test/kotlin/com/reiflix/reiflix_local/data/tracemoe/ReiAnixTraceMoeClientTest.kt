package com.reiflix.reiflix_local.data.tracemoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixTraceMoeClientTest {
    @Test
    fun parsesThePublicMeQuotaWithoutRetainingTheReturnedNetworkIdentity() {
        val quota = parseTraceMoeQuotaSnapshot(
            """{"id":"192.0.2.10/32","priority":0,"concurrency":1,"quota":100,"quotaUsed":96}""",
        )

        assertEquals(100, quota.quota)
        assertEquals(96, quota.quotaUsed)
        assertEquals(4, quota.remainingRequests)
        assertFalse(quota.isDepleted)
    }

    @Test
    fun reportsExhaustedQuotaWithoutAllowingNegativeRemainingCount() {
        val quota = parseTraceMoeQuotaSnapshot(
            """{"quota":100,"quotaUsed":120}""",
        )

        assertEquals(0, quota.remainingRequests)
        assertTrue(quota.isDepleted)
    }

    @Test
    fun rejectsMalformedOrIncompleteMeResponses() {
        val malformed = assertThrows(TraceMoeRequestException::class.java) {
            parseTraceMoeQuotaSnapshot("not-json")
        }
        val incomplete = assertThrows(TraceMoeRequestException::class.java) {
            parseTraceMoeQuotaSnapshot("""{"quota":100}""")
        }

        assertEquals(TraceMoeFailureCategory.INVALID_RESPONSE, malformed.category)
        assertEquals(TraceMoeFailureCategory.INVALID_RESPONSE, incomplete.category)
    }

    @Test
    fun classifiesKnownTraceMoeHttpFailures() {
        assertEquals(TraceMoeFailureCategory.INVALID_REQUEST, classifyTraceMoeHttpStatus(400))
        assertEquals(TraceMoeFailureCategory.QUOTA_OR_CONCURRENCY, classifyTraceMoeHttpStatus(402))
        assertEquals(TraceMoeFailureCategory.FORBIDDEN, classifyTraceMoeHttpStatus(403))
        assertEquals(TraceMoeFailureCategory.RATE_LIMITED, classifyTraceMoeHttpStatus(429))
        assertEquals(TraceMoeFailureCategory.QUEUE_FULL, classifyTraceMoeHttpStatus(503))
        assertEquals(TraceMoeFailureCategory.SERVER_ERROR, classifyTraceMoeHttpStatus(500))
    }

    @Test
    fun onlyTemporaryHttpFailuresAreEligibleForRetry() {
        assertFalse(classifyTraceMoeHttpStatus(400).retryable)
        assertFalse(classifyTraceMoeHttpStatus(402).retryable)
        assertFalse(classifyTraceMoeHttpStatus(403).retryable)
        assertTrue(classifyTraceMoeHttpStatus(429).retryable)
        assertTrue(classifyTraceMoeHttpStatus(500).retryable)
        assertTrue(classifyTraceMoeHttpStatus(503).retryable)
        assertTrue(classifyTraceMoeHttpStatus(504).retryable)
    }

    @Test
    fun appliesBoundedBackoffAndHonoursRetryAfter() {
        assertEquals(500L, traceMoeRetryDelayMillis(0))
        assertEquals(1_000L, traceMoeRetryDelayMillis(1))
        assertEquals(3_000L, traceMoeRetryDelayMillis(0, 3_000L))
        assertEquals(30_000L, traceMoeRetryDelayMillis(1, 60_000L))
    }

    @Test
    fun resolvesQuotaStatusAgainstTheSameApiOrigin() {
        assertEquals(
            "https://api.trace.moe/me",
            traceMoeQuotaEndpoint(ReiAnixTraceMoeClient.SEARCH_ENDPOINT),
        )
        assertEquals(
            "https://visual-index.example.net/me",
            traceMoeQuotaEndpoint("https://visual-index.example.net/search?anilistInfo&cutBorders=1"),
        )
    }
}
