package com.reiflix.reiflix_local.player

/**
 * Decides whether persisted local playback progress should be restored.
 *
 * The database duration and watched flag may be stale after a local file is
 * replaced. Only the independently resolved duration of the current file can
 * validate completion. When that duration is unavailable, a finite positive
 * position is retained unless the canonical row is explicitly marked watched.
 */
internal data class NativePlaybackResumeDecision(
    val shouldResume: Boolean,
    val reason: String,
)

internal object NativePlaybackResumePolicy {
    private const val COMPLETION_RATIO = 0.90

    fun decide(
        progressMs: Long?,
        actualDurationMs: Long?,
        watched: Boolean,
        progressInvalid: Boolean,
    ): NativePlaybackResumeDecision {
        if (progressInvalid) {
            return NativePlaybackResumeDecision(false, "invalid_numeric_progress")
        }

        val position = progressMs?.takeIf { it > 0L }
            ?: return NativePlaybackResumeDecision(false, "no_saved_position")

        val actualDuration = actualDurationMs?.takeIf { it > 0L }
        if (actualDuration != null) {
            val actualRatio = position.toDouble() / actualDuration.toDouble()
            if (!actualRatio.isFinite() || actualRatio >= COMPLETION_RATIO) {
                return NativePlaybackResumeDecision(
                    false,
                    "actual_duration_completion_threshold",
                )
            }

            // The real file is authoritative. A stale watched flag or stored
            // duration must not hide valid progress in a longer replacement file.
            return NativePlaybackResumeDecision(true, "valid_position_against_actual_file")
        }

        if (watched) {
            return NativePlaybackResumeDecision(
                false,
                "persisted_watched_flag_kept_actual_duration_unknown",
            )
        }

        // No current-file duration is available. Do not reject progress because
        // it exceeds the old database duration or appears complete against it.
        return NativePlaybackResumeDecision(
            true,
            "actual_duration_unknown_ignore_stored_duration",
        )
    }
}
