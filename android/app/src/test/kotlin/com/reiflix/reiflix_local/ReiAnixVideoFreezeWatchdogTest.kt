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
        fun watchdog(): ReiAnixVideoFreezeWatchdog =
            ReiAnixVideoFreezeWatchdog(timeoutMs = 3_000L, minimumAudioAdvanceMs = 500L)
                .also { it.begin(positionMs = 10_000L, nowMs = 1_000L, frameSerial = 7L) }

        assertFalse(watchdog().shouldDetect(4_100L, 10_100L, 7L, true, true))
        assertFalse(watchdog().shouldDetect(4_100L, 11_000L, 7L, false, true))
        assertFalse(watchdog().shouldDetect(4_100L, 11_000L, 8L, true, true))
        assertTrue(watchdog().shouldDetect(4_100L, 11_000L, 7L, true, true))
    }

    @Test
    fun pausedSeekKeepsWatchdogActiveUntilPlaybackIsReanchored() {
        val watchdog = ReiAnixVideoFreezeWatchdog(timeoutMs = 3_000L, minimumAudioAdvanceMs = 500L)
        watchdog.begin(positionMs = 10_000L, nowMs = 1_000L, frameSerial = 7L)

        // A seek while paused must not consume the pending watchdog identity.
        assertFalse(watchdog.shouldDetect(5_000L, 10_000L, 7L, false, true))
        assertTrue(watchdog.isActive())

        // The Activity re-anchors the window when playback actually resumes.
        watchdog.begin(positionMs = 10_000L, nowMs = 5_000L, frameSerial = 7L)
        assertFalse(watchdog.shouldDetect(7_900L, 10_400L, 7L, true, true))
        assertTrue(watchdog.shouldDetect(8_100L, 10_700L, 7L, true, true))
    }

    @Test
    fun failedRecoveryAttemptAdvancesToTheNextStep() {
        val watchdog = ReiAnixVideoFreezeWatchdog(timeoutMs = 10L, minimumAudioAdvanceMs = 1L)
        watchdog.begin(100L, 0L, 0L)
        assertTrue(watchdog.shouldDetect(10L, 102L, 0L, true, true))
        assertEquals(1, watchdog.nextRecoveryStep(10L, 102L, 0L))

        watchdog.markRecoveryAttemptFailed()
        assertEquals(2, watchdog.nextRecoveryStep(20L, 105L, 0L))
        assertEquals(2, watchdog.currentStep())
    }

    @Test
    fun exposesFourEscalatingRecoveryStepsAndCanResolve() {
        val watchdog = ReiAnixVideoFreezeWatchdog(timeoutMs = 10L, minimumAudioAdvanceMs = 1L)
        watchdog.begin(100L, 0L, 0L)
        assertTrue(watchdog.shouldDetect(10L, 102L, 0L, true, true))

        assertEquals(1, watchdog.nextRecoveryStep(10L, 102L, 0L))
        watchdog.markRecoveryAttemptFailed()
        assertEquals(2, watchdog.nextRecoveryStep(20L, 103L, 0L))
        watchdog.markRecoveryAttemptFailed()
        assertEquals(3, watchdog.nextRecoveryStep(30L, 104L, 0L))
        watchdog.markRecoveryAttemptFailed()
        assertEquals(4, watchdog.nextRecoveryStep(40L, 105L, 0L))
        watchdog.markRecoveryAttemptFailed()
        assertNull(watchdog.nextRecoveryStep(50L, 106L, 0L))

        watchdog.resolve()
        assertFalse(watchdog.isActive())
    }
}
