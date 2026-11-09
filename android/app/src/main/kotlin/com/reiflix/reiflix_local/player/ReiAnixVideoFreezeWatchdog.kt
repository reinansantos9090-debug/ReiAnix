package com.reiflix.reiflix_local.player

/**
 * Pure state machine for detecting a silent video-render stall after seeking.
 * It only reports a freeze when playback advances but no new video-processing
 * batch has been observed for the configured interval.
 */
internal class ReiAnixVideoFreezeWatchdog(
    private val timeoutMs: Long = 3_500L,
    private val minimumAudioAdvanceMs: Long = 650L,
    private val maximumRecoverySteps: Int = 4,
) {
    data class Snapshot(
        val seekPositionMs: Long,
        val startedAtMs: Long,
        val initialFrameSerial: Long,
        val recoveryStep: Int,
        val detected: Boolean,
    )

    private var active: Snapshot? = null

    @Synchronized
    fun begin(positionMs: Long, nowMs: Long, frameSerial: Long) {
        active = Snapshot(
            seekPositionMs = positionMs.coerceAtLeast(0L),
            startedAtMs = nowMs,
            initialFrameSerial = frameSerial,
            recoveryStep = 0,
            detected = false,
        )
    }

    @Synchronized
    fun shouldDetect(
        nowMs: Long,
        currentPositionMs: Long,
        frameSerial: Long,
        isPlaying: Boolean,
        isVideoTrackSelected: Boolean,
    ): Boolean {
        val snapshot = active ?: return false
        if (snapshot.detected || !isPlaying || !isVideoTrackSelected) return false
        if (frameSerial > snapshot.initialFrameSerial) {
            active = null
            return false
        }
        val elapsed = nowMs - snapshot.startedAtMs
        val advanced = currentPositionMs - snapshot.seekPositionMs >= minimumAudioAdvanceMs
        if (elapsed < timeoutMs || !advanced) return false
        active = snapshot.copy(detected = true)
        return true
    }

    @Synchronized
    fun nextRecoveryStep(
        nowMs: Long,
        currentPositionMs: Long,
        frameSerial: Long,
    ): Int? {
        val snapshot = active ?: return null
        if (!snapshot.detected || snapshot.recoveryStep >= maximumRecoverySteps) return null
        val next = snapshot.recoveryStep + 1
        active = snapshot.copy(
            seekPositionMs = currentPositionMs.coerceAtLeast(0L),
            startedAtMs = nowMs,
            initialFrameSerial = frameSerial,
            recoveryStep = next,
            detected = false,
        )
        return next
    }

    @Synchronized
    fun isActive(): Boolean = active != null

    @Synchronized
    fun currentStep(): Int = active?.recoveryStep ?: 0

    @Synchronized
    fun resolve() {
        active = null
    }

    @Synchronized
    fun reset() {
        active = null
    }
}
