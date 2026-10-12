package com.reiflix.reiflix_local.data.sqlite

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val hasSavedPosition: Boolean get() = positionMs != null && lastPlayedAtEpochSeconds != null
        val isInProgress: Boolean get() = (positionMs ?: 0L) > 0L && !watched
    }

    suspend fun getProgress(episodeId: Long): Progress? {
        require(episodeId > 0L) { "episodeId must be a positive persisted ID." }
        return library.getEpisode(episodeId)?.toConsumptionProgress()
    }

    /** The persisted episode ID is authoritative; title/path fallback matching is forbidden. */
    suspend fun saveProgress(
        episodeId: Long, positionMs: Long, durationMs: Long? = null,
        playedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
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
        val completed = durationSeconds != null && durationSeconds > 0.0 &&
            boundedPosition / durationSeconds >= COMPLETION_RATIO
        return library.updatePlaybackProgress(
            ReiAnixPlaybackProgressUpdate(
                episodeId = episodeId, progress = boundedPosition, duration = durationSeconds,
                watched = if (completed) true else existing.watched,
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

    suspend fun continueWatching(limit: Int = 36): List<Progress> {
        require(limit in 1..200) { "Page size must be between 1 and 200." }
        return withContext(Dispatchers.IO) {
            library.listRecentlyPlayed(limit = 200).asSequence()
                .filter { it.missing != true && it.watched != true }
                .filter { (it.progress ?: 0.0) > 0.0 }
                .take(limit).map { it.toConsumptionProgress() }.toList()
        }
    }

    suspend fun watchedRecently(limit: Int = 36): List<Progress> {
        require(limit in 1..200) { "Page size must be between 1 and 200." }
        return withContext(Dispatchers.IO) {
            library.listRecentlyPlayed(limit = 200).asSequence()
                .filter { it.watched == true && it.missing != true }
                .take(limit).map { it.toConsumptionProgress() }.toList()
        }
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