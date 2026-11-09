package com.reiflix.reiflix_local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackResumePolicyTest {
    @Test
    fun keepsPositiveProgressWhenActualFileDurationIsUnknown() {
        val decision = NativePlaybackResumePolicy.decide(
            progressMs = 1_500_000L,
            actualDurationMs = null,
            watched = false,
            progressInvalid = false,
        )

        assertTrue(decision.shouldResume)
        assertEquals("actual_duration_unknown_ignore_stored_duration", decision.reason)
    }

    @Test
    fun doesNotTreatOldStoredDurationRatioAsCompletion() {
        // 98% of an old database duration can still be far below the end of a
        // replacement file. The policy deliberately receives no authority from it.
        val decision = NativePlaybackResumePolicy.decide(
            progressMs = 1_176_000L,
            actualDurationMs = null,
            watched = false,
            progressInvalid = false,
        )

        assertTrue(decision.shouldResume)
    }

    @Test
    fun revalidatesWatchedFlagAgainstTheActualReplacementFile() {
        val decision = NativePlaybackResumePolicy.decide(
            progressMs = 420_000L,
            actualDurationMs = 3_600_000L,
            watched = true,
            progressInvalid = false,
        )

        assertTrue(decision.shouldResume)
        assertEquals("valid_position_against_actual_file", decision.reason)
    }

    @Test
    fun retainsWatchedFlagOnlyWhenActualDurationCannotBeChecked() {
        val decision = NativePlaybackResumePolicy.decide(
            progressMs = 420_000L,
            actualDurationMs = null,
            watched = true,
            progressInvalid = false,
        )

        assertFalse(decision.shouldResume)
        assertEquals("persisted_watched_flag_kept_actual_duration_unknown", decision.reason)
    }

    @Test
    fun suppressesPositionAtCompletionThresholdOfActualFile() {
        val decision = NativePlaybackResumePolicy.decide(
            progressMs = 900_000L,
            actualDurationMs = 1_000_000L,
            watched = false,
            progressInvalid = false,
        )

        assertFalse(decision.shouldResume)
        assertEquals("actual_duration_completion_threshold", decision.reason)
    }

    @Test
    fun rejectsInvalidOrEmptyProgress() {
        assertFalse(
            NativePlaybackResumePolicy.decide(
                progressMs = 420_000L,
                actualDurationMs = 1_000_000L,
                watched = false,
                progressInvalid = true,
            ).shouldResume,
        )
        assertFalse(
            NativePlaybackResumePolicy.decide(
                progressMs = 0L,
                actualDurationMs = 1_000_000L,
                watched = false,
                progressInvalid = false,
            ).shouldResume,
        )
    }
}
