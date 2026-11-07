package com.reiflix.reiflix_local.data.tracemoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceMoeFailurePolicyTest {
    @Test
    fun concurrencyLimitIsRetryableAndDistinctFromQuotaExhaustion() {
        val kind = classifyTraceMoeHttpFailure(402, "Concurrency limit exceeded")

        assertEquals(TraceMoeFailureKind.CONCURRENCY_LIMIT, kind)
        assertTrue(kind.retryable)
    }


    @Test
    fun concurrencyMessageIsRetryableEvenInSuccessfulHttpEnvelope() {
        val kind = classifyTraceMoeHttpFailure(200, "Too many concurrent searches")

        assertEquals(TraceMoeFailureKind.CONCURRENCY_LIMIT, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun textualRateLimitIsRetryableEvenWithoutHttp429() {
        val kind = classifyTraceMoeHttpFailure(200, "Rate limit exceeded")

        assertEquals(TraceMoeFailureKind.RATE_LIMITED, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun exhaustedSearchQuotaIsNotRetried() {
        val kind = classifyTraceMoeHttpFailure(402, "Search quota exceeded")

        assertEquals(TraceMoeFailureKind.QUOTA_EXHAUSTED, kind)
        assertFalse(kind.retryable)
    }

    @Test
    fun textualQuotaExhaustionIsNotRetriedEvenWithoutHttp402() {
        val kind = classifyTraceMoeHttpFailure(200, "Search quota exceeded")

        assertEquals(TraceMoeFailureKind.QUOTA_EXHAUSTED, kind)
        assertFalse(kind.retryable)
    }

    @Test
    fun rateLimitIsRetryable() {
        val kind = classifyTraceMoeHttpFailure(429, "Too many requests")

        assertEquals(TraceMoeFailureKind.RATE_LIMITED, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun serverFailuresAreRetryable() {
        val kind = classifyTraceMoeHttpFailure(503, "Service unavailable")

        assertEquals(TraceMoeFailureKind.SERVER_UNAVAILABLE, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun invalidImagesAreNotRetried() {
        val kind = classifyTraceMoeHttpFailure(400, "Invalid image")

        assertEquals(TraceMoeFailureKind.BAD_IMAGE, kind)
        assertFalse(kind.retryable)
    }

    @Test
    fun authenticationFailuresAreNotRetried() {
        val kind = classifyTraceMoeHttpFailure(401, "Invalid API key")

        assertEquals(TraceMoeFailureKind.AUTHENTICATION, kind)
        assertFalse(kind.retryable)
    }
}
