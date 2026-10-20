package com.reiflix.reiflix_local.data.library

import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixCatalogFilters
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import kotlin.random.Random

/** Read-only Kotlin counterpart of LibraryService.gacha_pick. */
class ReiAnixGachaService(
    private val repository: ReiAnixSqliteLibraryRepository,
    private val random: Random = Random.Default,
) {
    data class Result(
        val anime: ReiAnixAnimeRecord,
        val episode: ReiAnixEpisodeRecord?,
        val durationLabel: String?,
        val episodeDrawRequested: Boolean,
    )

    /** An empty eligible pool returns null; SQLite errors propagate to the caller. */
    suspend fun draw(
        filters: ReiAnixCatalogFilters = ReiAnixCatalogFilters(),
        excludeAnimeId: Long? = null,
        includeEpisode: Boolean = false,
    ): Result? {
        val anime = repository.randomCatalogAnime(filters, excludeAnimeId, random) ?: return null
        if (!includeEpisode) return Result(anime, null, null, episodeDrawRequested = false)
        val episodes = repository.marathonEpisodes(anime.id)
        val episode = episodes.takeIf { it.isNotEmpty() }?.get(random.nextInt(episodes.size))
        return Result(
            anime = anime,
            episode = episode,
            durationLabel = episode?.let { ReiAnixLibraryDiscovery.formatDuration(it.duration) },
            episodeDrawRequested = true,
        )
    }
}
