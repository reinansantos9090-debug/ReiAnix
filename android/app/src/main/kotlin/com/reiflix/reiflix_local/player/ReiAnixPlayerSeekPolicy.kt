package com.reiflix.reiflix_local.player

import androidx.media3.common.C

/** Pure policy for distinguishing a display duration from a seekable duration. */
internal object ReiAnixPlayerSeekPolicy {
    data class DisplayDuration(
        val durationMs: Long,
        val fromDatabase: Boolean,
        val known: Boolean,
    )

    fun validDuration(durationMs: Long): Long? =
        durationMs.takeIf { it != C.TIME_UNSET && it > 0L }

    fun displayDuration(
        playerDurationMs: Long,
        savedDurationMs: Long?,
    ): DisplayDuration {
        validDuration(playerDurationMs)?.let {
            return DisplayDuration(it, fromDatabase = false, known = true)
        }
        validDuration(savedDurationMs ?: C.TIME_UNSET)?.let {
            return DisplayDuration(it, fromDatabase = true, known = true)
        }
        return DisplayDuration(0L, fromDatabase = false, known = false)
    }

    /**
     * A successor transition blocks interaction only until the new item has
     * reached READY in the current player generation. First-frame rendering
     * commits navigation visually, but must not be required for controls.
     */
    fun transitionBlocksCommands(
        transitionPending: Boolean,
        transitionReadyForCurrentGeneration: Boolean,
    ): Boolean = transitionPending && !transitionReadyForCurrentGeneration

    fun canSeek(
        isCurrentMediaItemSeekable: Boolean,
        seekCommandAvailable: Boolean,
        transitionPending: Boolean,
    ): Boolean =
        isCurrentMediaItemSeekable && seekCommandAvailable && !transitionPending

    /**
     * Relative seek does not invent an upper bound when duration is unknown.
     * The real seekability/command checks must be performed by the caller.
     */
    fun relativeTarget(
        currentPositionMs: Long,
        deltaMs: Long,
        knownDurationMs: Long?,
    ): Long {
        val current = currentPositionMs.coerceAtLeast(0L)
        val sum = when {
            deltaMs > 0L && current > Long.MAX_VALUE - deltaMs -> Long.MAX_VALUE
            deltaMs < 0L && current < Long.MIN_VALUE - deltaMs -> Long.MIN_VALUE
            else -> current + deltaMs
        }
        val nonNegative = sum.coerceAtLeast(0L)
        val duration = knownDurationMs?.takeIf { it > 0L && it != C.TIME_UNSET }
        return duration?.let { nonNegative.coerceAtMost(it) } ?: nonNegative
    }
}
