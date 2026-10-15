package com.reiflix.reiflix_local.data.sqlite

import android.content.Context

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
        val isInProgress: Boolean get() = (positionMs ?: 0L) > 0L && !watched
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
            boundedPosition / durationSeconds >= COMPLETION_RATIO
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
        queryRecentlyPlayed(limit) { it.missing != true && it.watched != true && (it.progress ?: 0.0) > 0.0 }

    suspend fun watchedRecently(limit: Int = 36): List<Progress> =
        queryRecentlyPlayed(limit) { it.watched == true && it.missing != true }

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
        return Progress(
            episodeId = id, positionMs = positionSeconds?.let { (it * 1_000.0).toLong() },
            durationMs = durationSeconds?.let { (it * 1_000.0).toLong() }, watched = watched == true,
            lastPlayedAtEpochSeconds = lastPlayedAt?.takeIf { it.isFinite() && it >= 0.0 },
        )
    }

    private companion object {
        // Existing release certification defines completion as 90 percent.
        const val COMPLETION_RATIO = 0.90
    }
}