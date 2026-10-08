package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.PlayerStartupMetrics
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStartupMetricsTest {
    @Test
    fun startupStagesAreMeasuredIndependently() {
        val metrics = PlayerStartupMetrics(
            episodeTapAtMs = 1_000L,
            preflightStartedAtMs = 1_020L,
            preflightCompletedAtMs = 1_050L,
            prepareDispatchedAtMs = 1_080L,
            playerReadyAtMs = 1_200L,
            firstFrameRenderedAtMs = 1_250L,
        )

        assertEquals(20L, metrics.tapToPreflightStartMs)
        assertEquals(30L, metrics.preflightDurationMs)
        assertEquals(30L, metrics.prepareDispatchGapMs)
        assertEquals(120L, metrics.prepareToReadyMs)
        assertEquals(50L, metrics.readyToFirstFrameMs)
        assertEquals(80L, metrics.tapToPrepareMs)
        assertEquals(200L, metrics.tapToReadyMs)
        assertEquals(250L, metrics.tapToFirstFrameMs)
    }

    @Test
    fun missingOrReversedTimestampsNeverProduceNegativeLatency() {
        val metrics = PlayerStartupMetrics(
            episodeTapAtMs = 2_000L,
            preflightStartedAtMs = 1_900L,
            preflightCompletedAtMs = 1_800L,
            prepareDispatchedAtMs = 1_700L,
            playerReadyAtMs = 1_600L,
            firstFrameRenderedAtMs = 1_500L,
        )

        assertEquals(0L, metrics.tapToPreflightStartMs)
        assertEquals(0L, metrics.preflightDurationMs)
        assertEquals(0L, metrics.prepareDispatchGapMs)
        assertEquals(0L, metrics.prepareToReadyMs)
        assertEquals(0L, metrics.readyToFirstFrameMs)
        assertEquals(0L, metrics.tapToFirstFrameMs)
    }
}
