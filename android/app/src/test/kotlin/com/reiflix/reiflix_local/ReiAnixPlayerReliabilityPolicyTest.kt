package com.reiflix.reiflix_local

import androidx.media3.common.C
import com.reiflix.reiflix_local.player.ReiAnixPlayerSeekPolicy
import com.reiflix.reiflix_local.player.ReiAnixProgressCheckpointGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixPlayerReliabilityPolicyTest {
    @Test
    fun unknownPlayerDurationFallsBackToDatabaseOnlyForDisplay() {
        val fallback = ReiAnixPlayerSeekPolicy.displayDuration(C.TIME_UNSET, 1_200_000L)
        assertEquals(1_200_000L, fallback.durationMs)
        assertTrue(fallback.fromDatabase)
        assertFalse(ReiAnixPlayerSeekPolicy.canSeek(false, true, false))

        val unknown = ReiAnixPlayerSeekPolicy.displayDuration(0L, null)
        assertEquals(0L, unknown.durationMs)
        assertFalse(unknown.known)
    }

    @Test
    fun seekRequiresBothSeekabilityAndMedia3SeekCommand() {
        assertTrue(ReiAnixPlayerSeekPolicy.canSeek(true, true, false))
        assertFalse(ReiAnixPlayerSeekPolicy.canSeek(false, true, false))
        assertFalse(ReiAnixPlayerSeekPolicy.canSeek(true, false, false))
        assertFalse(ReiAnixPlayerSeekPolicy.canSeek(true, true, true))
    }

    @Test
    fun relativeSeekWorksWhenDurationIsUnknownAndClampsWhenKnown() {
        assertEquals(
            12_000L,
            ReiAnixPlayerSeekPolicy.relativeTarget(10_000L, 2_000L, null),
        )
        assertEquals(
            0L,
            ReiAnixPlayerSeekPolicy.relativeTarget(1_000L, -5_000L, null),
        )
        assertEquals(
            9_999L,
            ReiAnixPlayerSeekPolicy.relativeTarget(9_000L, 2_000L, 9_999L),
        )
    }

    @Test
    fun failedCheckpointCanBeQueuedAgainAtTheSamePosition() {
        val gate = ReiAnixProgressCheckpointGate(minimumDeltaMs = 250L)
        assertTrue(gate.shouldQueue(8_000L, force = false, eventType = "player_progress"))
        gate.markQueued(8_000L)
        assertEquals(-1L, gate.lastPersistedPositionMs)

        gate.markFailed(8_000L)
        assertEquals(-1L, gate.lastPersistedPositionMs)
        assertTrue(gate.shouldQueue(8_000L, force = false, eventType = "player_progress"))

        gate.markQueued(8_000L)
        gate.markPersisted(8_000L)
        assertEquals(8_000L, gate.lastPersistedPositionMs)
        assertFalse(gate.shouldQueue(8_100L, force = false, eventType = "player_progress"))
    }

    @Test
    fun forcedBackwardSeekIsNotSuppressedByPreviousCheckpoint() {
        val gate = ReiAnixProgressCheckpointGate()
        gate.markQueued(40_000L)
        gate.markPersisted(40_000L)
        assertTrue(gate.shouldQueue(12_000L, force = true, eventType = "player_progress"))
    }
}
