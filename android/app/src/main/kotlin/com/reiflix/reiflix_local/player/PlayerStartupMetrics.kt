package com.reiflix.reiflix_local.player

/**
 * Pure startup timing model for the existing Media3 player pipeline.
 *
 * This class is observational only. It does not create a player, persist state,
 * or introduce a parallel playback architecture.
 */
data class PlayerStartupMetrics(
    val episodeTapAtMs: Long,
    val preflightStartedAtMs: Long,
    val preflightCompletedAtMs: Long,
    val prepareDispatchedAtMs: Long,
    val playerReadyAtMs: Long,
    val firstFrameRenderedAtMs: Long,
) {
    val tapToPreflightStartMs: Long get() = delta(episodeTapAtMs, preflightStartedAtMs)
    val preflightDurationMs: Long get() = delta(preflightStartedAtMs, preflightCompletedAtMs)
    val prepareDispatchGapMs: Long get() = delta(preflightCompletedAtMs, prepareDispatchedAtMs)
    val prepareToReadyMs: Long get() = delta(prepareDispatchedAtMs, playerReadyAtMs)
    val readyToFirstFrameMs: Long get() = delta(playerReadyAtMs, firstFrameRenderedAtMs)
    val tapToPrepareMs: Long get() = delta(episodeTapAtMs, prepareDispatchedAtMs)
    val tapToReadyMs: Long get() = delta(episodeTapAtMs, playerReadyAtMs)
    val tapToFirstFrameMs: Long get() = delta(episodeTapAtMs, firstFrameRenderedAtMs)

    private fun delta(startMs: Long, endMs: Long): Long {
        if (startMs <= 0L || endMs <= 0L) return 0L
        return (endMs - startMs).coerceAtLeast(0L)
    }
}
