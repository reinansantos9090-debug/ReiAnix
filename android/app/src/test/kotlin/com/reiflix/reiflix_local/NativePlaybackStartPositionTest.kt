package com.reiflix.reiflix_local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackStartPositionTest {
    @Test
    fun normalInProgressEpisodeKeepsExplicitResumePosition() {
        val start = resolveNativePlaybackStartPosition(
            progressSeconds = 420.0,
            durationSeconds = 1_200.0,
            watched = false,
            resumeEnabled = true,
        )
        assertEquals(420.0, start.positionSeconds, 0.0)
        assertEquals(420_000L, start.positionMsOverride)
        assertFalse(start.progressInvalid)
    }

    @Test
    fun watchedFlagDefersPositiveProgressUntilActualFileDurationIsKnown() {
        val start = resolveNativePlaybackStartPosition(
            progressSeconds = 420.0,
            durationSeconds = 1_200.0,
            watched = true,
            resumeEnabled = true,
        )
        assertEquals(420.0, start.positionSeconds, 0.0)
        assertNull(start.positionMsOverride)
        assertFalse(start.progressInvalid)
    }

    @Test
    fun progressBeyondStaleStoredDurationIsNotRejectedBeforeFileValidation() {
        val start = resolveNativePlaybackStartPosition(
            progressSeconds = 1_500.0,
            durationSeconds = 1_200.0,
            watched = false,
            resumeEnabled = true,
        )
        assertEquals(1_500.0, start.positionSeconds, 0.0)
        assertNull(start.positionMsOverride)
        assertFalse(start.progressInvalid)
    }

    @Test
    fun resumeDisabledStillStartsAtZeroExplicitly() {
        val start = resolveNativePlaybackStartPosition(
            progressSeconds = 420.0,
            durationSeconds = 1_200.0,
            watched = true,
            resumeEnabled = false,
        )
        assertEquals(0.0, start.positionSeconds, 0.0)
        assertEquals(0L, start.positionMsOverride)
        assertFalse(start.progressInvalid)
    }

    @Test
    fun invalidNumericProgressIsRejected() {
        val nan = resolveNativePlaybackStartPosition(
            progressSeconds = Double.NaN,
            durationSeconds = 1_200.0,
            watched = false,
            resumeEnabled = true,
        )
        assertEquals(0.0, nan.positionSeconds, 0.0)
        assertEquals(0L, nan.positionMsOverride)
        assertTrue(nan.progressInvalid)

        val negative = resolveNativePlaybackStartPosition(
            progressSeconds = -1.0,
            durationSeconds = null,
            watched = false,
            resumeEnabled = true,
        )
        assertEquals(0L, negative.positionMsOverride)
        assertTrue(negative.progressInvalid)
    }

    @Test
    fun zeroProgressStillAllowsNativeSqliteLookup() {
        val start = resolveNativePlaybackStartPosition(
            progressSeconds = 0.0,
            durationSeconds = 1_200.0,
            watched = false,
            resumeEnabled = true,
        )
        assertNull(start.positionMsOverride)
        assertFalse(start.progressInvalid)
    }
}
