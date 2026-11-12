package com.reianix.reianix_local.data.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniListAccountSyncPolicyTest {
    private val policy = AniListAccountSyncPolicy

    @Test fun onlyConfirmedEightyPercentCheckpointsQualify() {
        assertFalse(policy.reachedCheckpoint(799L, 1000L))
        assertTrue(policy.reachedCheckpoint(800L, 1000L))
        assertTrue(policy.reachedCheckpoint(1000L, 1000L))
        assertFalse(policy.reachedCheckpoint(-1L, 1000L))
        assertFalse(policy.reachedCheckpoint(800L, 0L))
        assertFalse(policy.reachedCheckpoint(800L, -1L))
    }

    @Test fun regularEpisodesRequireReliableNumbering() {
        assertEquals(2, policy.episodeProgress("series", 1, 2.0, null, "regular"))
        assertEquals(13, policy.episodeProgress("series", 2, 1.0, 13.0, "regular"))
        assertNull(policy.episodeProgress("series", 2, 1.0, null, "regular"))
        assertNull(policy.episodeProgress("series", 0, 1.0, null, "regular"))
        assertNull(policy.episodeProgress("series", 1, null, null, "regular"))
        assertNull(policy.episodeProgress("series", 1, 2.5, null, "regular"))
        assertNull(policy.episodeProgress("series", 1, Double.NaN, null, "regular"))
        assertNull(policy.episodeProgress("series", 1, Double.POSITIVE_INFINITY, null, "regular"))
        assertNull(policy.episodeProgress("series", 1, 0.0, null, "regular"))
        assertNull(policy.episodeProgress("series", 1, Int.MAX_VALUE.toDouble() + 1, null, "regular"))
    }

    @Test fun specialsAndNonstandardEntriesNeverSync() {
        for (type in listOf("special", "OVA", "oad", "ONA", "extra")) {
            assertNull(policy.episodeProgress("series", 1, 1.0, null, type))
        }
        assertEquals(1, policy.episodeProgress("movie", 1, null, null, "regular"))
    }

    @Test fun neverSendProgressBackwards() {
        assertFalse(policy.shouldSend(3, 6, 12, "CURRENT"))
        assertFalse(policy.shouldSend(6, 6, 12, "CURRENT"))
        assertTrue(policy.shouldSend(7, 6, 12, "CURRENT"))
        assertEquals("CURRENT", policy.statusFor(7, 12, "PAUSED"))
    }

    @Test fun completionRequiresKnownLastEpisodeAndPreservesExistingStatuses() {
        assertFalse(policy.shouldComplete(12, 0, "CURRENT"))
        assertFalse(policy.shouldComplete(11, 12, "CURRENT"))
        assertTrue(policy.shouldComplete(12, 12, "CURRENT"))
        assertFalse(policy.shouldComplete(12, 12, "COMPLETED"))
        assertFalse(policy.shouldComplete(12, 12, "REPEATING"))
        assertTrue(policy.shouldSend(12, 12, 12, "CURRENT"))
        assertEquals("COMPLETED", policy.statusFor(12, 12, "CURRENT"))
        assertEquals("COMPLETED", policy.statusFor(12, 12, "COMPLETED"))
        assertEquals("REPEATING", policy.statusFor(12, 12, "REPEATING"))
        assertEquals("CURRENT", policy.statusFor(12, 0, "CURRENT"))
    }
}
