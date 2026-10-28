package com.reiflix.reiflix_local.data.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixTmdbArtworkCachePolicyTest {
    @Test
    fun cacheExpiresAtTheConservativeSixMonthBoundary() {
        val now = 2_000_000.0
        val maxAge = TMDB_ARTWORK_CACHE_MAX_AGE_SECONDS.toDouble()

        assertFalse(shouldExpireTmdbArtworkCache(now - maxAge + 1.0, null, now))
        assertTrue(shouldExpireTmdbArtworkCache(now - maxAge, null, now))
        assertTrue(shouldExpireTmdbArtworkCache(null, now - maxAge - 1.0, now))
        assertFalse(shouldExpireTmdbArtworkCache(null, now - maxAge + 1.0, now))
    }

    @Test
    fun discoveredTimestampTakesPrecedenceOverLaterRetryTimestamp() {
        val now = 2_000_000.0
        val staleDiscovery = now - TMDB_ARTWORK_CACHE_MAX_AGE_SECONDS - 1.0
        val recentRetry = now - 30.0

        assertTrue(shouldExpireTmdbArtworkCache(staleDiscovery, recentRetry, now))
    }
}
