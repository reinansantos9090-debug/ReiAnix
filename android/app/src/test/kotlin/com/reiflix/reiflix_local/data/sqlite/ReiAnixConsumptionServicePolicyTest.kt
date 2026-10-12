package com.reiflix.reiflix_local.data.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixConsumptionServicePolicyTest {
    @Test fun progressDistinguishesNeverPlayedFromSavedZero() {
        val neverPlayed = ReiAnixConsumptionService.Progress(1L, null, 120_000L, false, null)
        val savedZero = ReiAnixConsumptionService.Progress(1L, 0L, 120_000L, false, 10.0)
        assertFalse(neverPlayed.hasSavedPosition); assertTrue(savedZero.hasSavedPosition)
        assertEquals(null, neverPlayed.positionMs); assertEquals(0L, savedZero.positionMs)
    }
    @Test fun watchedAndInProgressStatesAreMutuallyExclusive() {
        val active = ReiAnixConsumptionService.Progress(2L, 20_000L, 100_000L, false, 12.0)
        val completed = ReiAnixConsumptionService.Progress(2L, 95_000L, 100_000L, true, 13.0)
        assertTrue(active.isInProgress); assertFalse(active.watched)
        assertFalse(completed.isInProgress); assertTrue(completed.watched)
    }
    @Test fun media3ContractUsesMilliseconds() {
        val progress = ReiAnixConsumptionService.Progress(3L, 12_345L, 60_000L, false, 14.0)
        assertEquals(12_345L, progress.positionMs); assertEquals(60_000L, progress.durationMs)
    }
}