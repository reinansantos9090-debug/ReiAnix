package com.reianix.reianix_local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewPolicyTest {
    @Test fun invalidDurationNeverRequestsFrames() {
        assertEquals(0L, SeekPreviewPolicy.bucketTimeMs(100L, 0L))
        assertEquals(0L, SeekPreviewPolicy.bucketTimeMs(100L, -1L))
    }

    @Test fun seekPositionsAreClampedToLocalVideoBounds() {
        assertEquals(0L, SeekPreviewPolicy.bucketTimeMs(-1_000L, 60_000L))
        assertEquals(50_000L, SeekPreviewPolicy.bucketTimeMs(100_000L, 60_000L))
    }

    @Test fun nearbyThumbPositionsShareTheSameCachedFrame() {
        val duration = 120_000L
        assertEquals(20_000L, SeekPreviewPolicy.bucketTimeMs(25_100L, duration))
        assertEquals(20_000L, SeekPreviewPolicy.bucketTimeMs(29_900L, duration))
        assertEquals(30_000L, SeekPreviewPolicy.bucketTimeMs(31_000L, duration))
    }

    @Test fun longVideosHaveBoundedThirtySecondFrameIntervals() {
        val duration = 3_600_000L
        assertEquals(60_000L, SeekPreviewPolicy.bucketTimeMs(78_000L, duration))
        assertTrue(SeekPreviewPolicy.bucketTimeMs(duration, duration) < duration)
    }
}
