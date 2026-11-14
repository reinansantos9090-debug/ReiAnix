package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val REIANIX_CONSUMPTION_COMPLETION_RATIO = 0.90

/**
 * Native consumption contract over the canonical LibraryStore SQLite database.
 * Progress/duration are exposed in milliseconds to match Media3. Persisted columns
 * remain seconds for backwards compatibility; this service creates no parallel DB.
 */
class ReiAnixConsumptionService(private val library: ReiAnixSqliteLibraryRepository) {
    constructor(context: Context) : this(ReiAnixSqliteLibraryRepository(context))

    data class Progress(
        val episodeId: Long, val positionMs: Long?, val durationMs: Long?,
        val watched: Boolean, val lastPlayedAtEpochSeconds: Double?,
    ) {
        // Legacy rows may retain a valid progress value without last_played_at.
        val hasSavedPosition: Boolean get() = positionMs != null
        val isCompleted: Boolean
            get() {
                if (watched) return true
                val duration = durationMs?.takeIf { it > 0L } ?: return false
                val position = positionMs?.takeIf { it >= 0L } ?: return false
                return position.toDouble() / duration.toDouble() >= REIANIX_CONSUMPTION_COMPLETION_RATIO
            }
        val isInProgress: Boolean get() = (positionMs ?: 0L) > 0L && !isCompleted
    }

    suspend fun getProgress(episodeId: Long): Progress? {
        require(episodeId > 0L) { "episodeId must be a positive persisted ID." }
        return library.getEpisode(episodeId)?.toConsumptionProgress()
    }

    /**
     * Native equivalent of LibraryStore.set_watched. Unlike markWatched(), this
     * operation intentionally resets progress to zero when the item is marked
     * unwatched, matching the existing library action contract.
     */
    suspend fun setWatched(
        episodeId: Long,
        watched: Boolean,
        playedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
    ): Progress {
        require(episodeId > 0L) { "episodeId must be a positive persisted ID." }
        return library.setEpisodeWatchedState(
            episodeId = episodeId,
            watched = watched,
            playedAtEpochSeconds = playedAtEpochSeconds,
        ).toConsumptionProgress()
    }

    /** The persisted episode ID is authoritative; title/path fallback matching is forbidden. */
    suspend fun saveProgress(
        episodeId: Long, positionMs: Long, durationMs: Long? = null,
        playedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
        completed: Boolean = false,
    ): Progress {
        require(episodeId > 0L) { "episodeId must be a positive persisted ID." }
        require(positionMs >= 0L) { "positionMs cannot be negative." }
        require(durationMs == null || durationMs > 0L) { "durationMs must be positive when known." }
        require(playedAtEpochSeconds.isFinite() && playedAtEpochSeconds >= 0.0) {
            "playedAtEpochSeconds must be finite and non-negative."
        }
        val existing = library.getEpisode(episodeId)
            ?: throw NoSuchElementException("Episode ID does not exist: $episodeId")
        val durationSeconds = durationMs?.toDouble()?.div(1_000.0)
            ?: existing.duration?.takeIf { it.isFinite() && it > 0.0 }
        val positionSeconds = positionMs.toDouble() / 1_000.0
        val boundedPosition = durationSeconds?.let(positionSeconds::coerceAtMost) ?: positionSeconds
        val reachedCompletionThreshold = durationSeconds != null && durationSeconds > 0.0 &&
            boundedPosition / durationSeconds >= REIANIX_CONSUMPTION_COMPLETION_RATIO
        val finalPosition = if (completed && durationSeconds != null) {
            durationSeconds
        } else boundedPosition
        return library.updatePlaybackProgress(
            ReiAnixPlaybackProgressUpdate(
                episodeId = episodeId, progress = finalPosition, duration = durationSeconds,
                watched = if (completed || reachedCompletionThreshold) true else existing.watched,
                playedAtEpochSeconds = playedAtEpochSeconds,
            ),
        ).toConsumptionProgress()
    }

    suspend fun markWatched(episodeId: Long, watched: Boolean): Progress {
        require(episodeId > 0L) { "episodeId must be a positive persisted ID." }
        val existing = library.getEpisode(episodeId)
            ?: throw NoSuchElementException("Episode ID does not exist: $episodeId")
        val duration = existing.duration?.takeIf { it.isFinite() && it > 0.0 }
        val currentPosition = existing.progress?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val position = if (watched && duration != null) duration else currentPosition
        return library.updatePlaybackProgress(
            ReiAnixPlaybackProgressUpdate(
                episodeId = episodeId, progress = position, duration = duration, watched = watched,
                playedAtEpochSeconds = System.currentTimeMillis() / 1000.0,
            ),
        ).toConsumptionProgress()
    }

    suspend fun continueWatching(limit: Int = 36): List<Progress> =
        queryRecentlyPlayed(limit) { it.missing != true && it.toConsumptionProgress().isInProgress }

    suspend fun watchedRecently(limit: Int = 36): List<Progress> =
        queryRecentlyPlayed(limit) { it.missing != true && it.toConsumptionProgress().isCompleted }

    /** Recent playback history is projected from the canonical episodes table. */
    suspend fun playbackHistory(limit: Int = 36): List<Progress> =
        queryRecentlyPlayed(limit) { true }

    /** Filters across pages so a large watched/missing prefix cannot hide eligible items. */
    private suspend fun queryRecentlyPlayed(
        limit: Int,
        include: (ReiAnixEpisodeRecord) -> Boolean,
    ): List<Progress> {
        require(limit in 1..200) { "Page size must be between 1 and 200." }
        val result = ArrayList<Progress>(limit)
        var offset = 0
        while (result.size < limit) {
            val page = library.listRecentlyPlayed(limit = 200, offset = offset)
            if (page.isEmpty()) break
            page.asSequence().filter(include).take(limit - result.size)
                .map { it.toConsumptionProgress() }.forEach(result::add)
            if (page.size < 200) break
            offset += page.size
        }
        return result
    }

    private fun ReiAnixEpisodeRecord.toConsumptionProgress(): Progress {
        val durationSeconds = duration?.takeIf { it.isFinite() && it > 0.0 }
        val positionSeconds = progress?.takeIf { it.isFinite() && it >= 0.0 }
        val boundedPositionSeconds = positionSeconds?.let { position ->
            durationSeconds?.let(position::coerceAtMost) ?: position
        }
        return Progress(
            episodeId = id,
            positionMs = boundedPositionSeconds?.let(::secondsToMilliseconds),
            durationMs = durationSeconds?.let(::secondsToMilliseconds),
            watched = watched == true,
            lastPlayedAtEpochSeconds = lastPlayedAt?.takeIf { it.isFinite() && it >= 0.0 },
        )
    }

    private fun secondsToMilliseconds(seconds: Double): Long? {
        val milliseconds = seconds * 1_000.0
        return milliseconds
            .takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
    }

    data class PlaybackChange(
        val episodeId: Long,
        val eventType: String,
        val playerSessionId: String,
        val playerGeneration: Long,
        val transitionGeneration: Long,
        val eventCreatedAtEpochMs: Long,
    )

    companion object {
        private val mutablePlaybackChanges =
            MutableSharedFlow<PlaybackChange>(replay = 1, extraBufferCapacity = 32)
        val playbackChanges: SharedFlow<PlaybackChange> = mutablePlaybackChanges.asSharedFlow()

        /**
         * Notify Compose after a committed checkpoint. Periodic player progress emits
         * only at the existing 15-second persistence boundary, never on each UI tick.
         */
        internal fun publishCommittedProgress(
            episodeId: Long,
            eventType: String,
            playerSessionId: String,
            playerGeneration: Long,
            transitionGeneration: Long,
            eventCreatedAtEpochMs: Long,
        ) {
            if (episodeId <= 0L || eventType !in UI_REFRESH_EVENT_TYPES) return
            mutablePlaybackChanges.tryEmit(
                PlaybackChange(
                    episodeId = episodeId,
                    eventType = eventType,
                    playerSessionId = playerSessionId,
                    playerGeneration = playerGeneration,
                    transitionGeneration = transitionGeneration,
                    eventCreatedAtEpochMs = eventCreatedAtEpochMs,
                ),
            )
        }

        private val UI_REFRESH_EVENT_TYPES = setOf(
            "player_progress",
            "player_paused",
            "player_completed",
            "player_exited",
        )
    }
}