package com.reiflix.reiflix_local.player

import kotlin.math.abs

/**
 * Tracks queued and confirmed SQLite checkpoints separately. A failed asynchronous
 * write never advances the durable position, and the same position can be retried.
 */
internal class ReiAnixProgressCheckpointGate(
    private val minimumDeltaMs: Long = 250L,
) {
    @Volatile
    var lastPersistedPositionMs: Long = -1L
        private set

    @Volatile
    var lastQueuedPositionMs: Long = -1L
        private set

    @Synchronized
    fun shouldQueue(positionMs: Long, force: Boolean, eventType: String): Boolean {
        val position = positionMs.coerceAtLeast(0L)
        val baseline = lastQueuedPositionMs.takeIf { it >= 0L } ?: lastPersistedPositionMs
        if (!force && baseline >= 0L && abs(position - baseline) < minimumDeltaMs) return false
        if (force && position == baseline &&
            eventType != "player_completed" && eventType != "player_exited"
        ) {
            return false
        }
        return true
    }

    @Synchronized
    fun markQueued(positionMs: Long) {
        lastQueuedPositionMs = positionMs.coerceAtLeast(0L)
    }

    @Synchronized
    fun markPersisted(positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        lastPersistedPositionMs = position
        if (lastQueuedPositionMs < 0L || lastQueuedPositionMs == position) {
            lastQueuedPositionMs = position
        }
    }

    @Synchronized
    fun markFailed(positionMs: Long) {
        val failedPosition = positionMs.coerceAtLeast(0L)
        if (lastQueuedPositionMs == failedPosition) {
            lastQueuedPositionMs = lastPersistedPositionMs
        }
    }

    @Synchronized
    fun reset() {
        lastPersistedPositionMs = -1L
        lastQueuedPositionMs = -1L
    }
}
