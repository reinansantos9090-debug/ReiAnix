package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.ReiAnixVideoFreezeWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixVideoFreezeWatchdogTest {
    @Test
    fun detectsOnlyWhenAudioProgressesWithoutNewVideoFrames() {
        val watchdog = ReiAnixVideoFreezeWatchdog(timeoutMs = 3_000L, minimumAudioAdvanceMs = 500L)
        watchdog.begin(positionMs = 10_000L, nowMs = 1_000L, frameSerial = 7L)

        assertFalse(watchdog.shouldDetect(4_100L, 10_100L, 7L, true, true))
        assertFalse(watchdog.shouldDetect(4_100L, 11_000L, 7L, false, true))
        assertFalse(watchdog.shouldDetect(4_100L, 11_000L, 8L, true, true))
        assertTrue(watchdog.shouldDetect(4_100L, 11_000L, 7L, true, true))
    }

    @Test
    fun exposesFourEscalatingRecoveryStepsAndCanResolve() {
        val watchdog = ReiAnixVideoFreezeWatchdog(timeoutMs = 10L, minimumAudioAdvanceMs = 1L)
        watchdog.begin(100L, 0L, 0L)
        assertTrue(watchdog.shouldDetect(10L, 102L, 0L, true, true))
        assertEquals(1, watchdog.nextRecoveryStep())
        assertEquals(2, watchdog.nextRecoveryStep())
        assertEquals(3, watchdog.nextRecoveryStep())
        assertEquals(4, watchdog.nextRecoveryStep())
        assertNull(watchdog.nextRecoveryStep())
        watchdog.resolve()
        assertFalse(watchdog.isActive())
    }
}
