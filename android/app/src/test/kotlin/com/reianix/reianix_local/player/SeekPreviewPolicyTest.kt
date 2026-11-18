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

    @Test fun progressivePreviewsStartAtMiddleThenQuartersThenEighths() {
        val positions = SeekPreviewPolicy.progressivePositions(160_000L)
        assertEquals(15, positions.size)
        assertEquals(
            listOf(80_000L, 40_000L, 120_000L, 20_000L, 60_000L, 100_000L, 140_000L),
            positions.take(7),
        )
        assertEquals(15, positions.toSet().size)
    }

    @Test fun progressiveLocationsNeverExceedVideoDuration() {
        for (duration in listOf(0L, 1L, 4_000L, 60_000L, 3_600_000L)) {
            val positions = SeekPreviewPolicy.progressivePositions(duration)
            assertTrue(positions.all { it >= 0 && it < duration })
            if (duration <= 0) assertTrue(positions.isEmpty())
        }
    }

    @Test fun focusedSamplingHasHigherResolutionAndClampedEdges() {
        val duration = 1_440_000L
        // At 24 minutes, 1/240 of the duration is 6 seconds.
        assertEquals(120_000L, SeekPreviewPolicy.focusBucketTimeMs(125_999L, duration))
        assertEquals(126_000L, SeekPreviewPolicy.focusBucketTimeMs(126_001L, duration))
        assertEquals(0L, SeekPreviewPolicy.focusBucketTimeMs(-500L, duration))
        assertTrue(SeekPreviewPolicy.focusBucketTimeMs(duration, duration) < duration)
    }

    @Test fun twoSecondMinimumSamplingForShortMedia() {
        assertEquals(2_000L, SeekPreviewPolicy.focusBucketTimeMs(3_000L, 70_000L))
        assertEquals(4_000L, SeekPreviewPolicy.focusBucketTimeMs(4_999L, 70_000L))
        assertEquals(0L, SeekPreviewPolicy.focusBucketTimeMs(7_000L, -1L))
    }
}
