package com.reiflix.reiflix_local.ui.library

import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability

data class ReiAnixLibraryFilters(
    val query: String = "",
    val selectedGenreKey: String? = null,
    val favoritesOnly: Boolean = false,
    val watchingOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val sort: String = ReiAnixLibrarySort.DEFAULT.label,
) {
    val hasAnyFilter: Boolean
        get() = query.isNotBlank() ||
            selectedGenreKey != null ||
            favoritesOnly ||
            watchingOnly ||
            completedOnly
}

enum class ReiAnixLibrarySort(val label: String) {
    RECENT("Mais recentes"),
    RECENTLY_WATCHED("Assistidos recentemente"),
    PROGRESS("Progresso"),
    EPISODE("Episódio"),
    SEASON_EPISODE("Temporada + episódio"),
    MODIFICATION("Modificação"),
    DURATION("Duração"),
    SIZE("Tamanho"),
    FAVORITES_FIRST("Favoritos primeiro"),
    PINNED_FIRST("Fixados primeiro"),
    TITLE_ASC("Nome A-Z"),
    TITLE_DESC("Nome Z-A");

    companion object {
        val DEFAULT = RECENT
        val OPTIONS = entries.toList()
        fun fromLabel(label: String): ReiAnixLibrarySort =
            entries.firstOrNull { it.label == label } ?: DEFAULT
    }
}

object ReiAnixLibraryFilterEngine {
    fun filter(
        animes: List<ReiAnixAnimeUiModel>,
        filters: ReiAnixLibraryFilters,
    ): List<ReiAnixAnimeUiModel> {
        val query = filters.query.trim()
        val filtered = animes.filter { anime ->
            val matchesQuery = query.isBlank() ||
                anime.title.contains(query, ignoreCase = true) ||
                anime.genres.any { it.name.contains(query, ignoreCase = true) }
            val matchesGenre = filters.selectedGenreKey == null ||
                anime.genres.any { it.stableKey == filters.selectedGenreKey }
            val matchesFavorite = !filters.favoritesOnly || anime.favorite
            val matchesWatching = !filters.watchingOnly || anime.isWatching
            val matchesCompleted = !filters.completedOnly || anime.isCompleted
            matchesQuery && matchesGenre && matchesFavorite && matchesWatching && matchesCompleted
        }
        return sort(filtered, filters.sort)
    }

    private data class SortMetrics(
        val anime: ReiAnixAnimeUiModel,
        val addedAt: Double,
        val lastPlayedAt: Double,
        val progress: Double,
        val firstEpisode: Double,
        val minEpisode: Double,
        val firstSeason: Int,
        val maxModifiedAt: Double,
        val totalDuration: Double,
        val totalSize: Long,
    )

    private fun sort(
        animes: List<ReiAnixAnimeUiModel>,
        sortLabel: String,
    ): List<ReiAnixAnimeUiModel> {
        if (animes.size < 2 || sortLabel.isBlank()) return animes

        val decorated = animes.map { anime ->
            val episodes = anime.contentEpisodes
            val available = episodes.filter {
                it.media.availability == ReiAnixMediaAvailability.AVAILABLE
            }
            val episodeForOrdering = available.sortedWith(
                compareBy<ReiAnixEpisodeUiModel>(
                    { it.seasonNumber ?: Int.MAX_VALUE },
                    { it.number ?: Double.MAX_VALUE },
                ).thenBy { it.id },
            ).firstOrNull()
            val firstSeason = available.minOfOrNull { it.seasonNumber ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
            val progress = if (episodes.isEmpty()) {
                0.0
            } else {
                episodes.map { episode ->
                    val duration = episode.durationSeconds
                    if (episode.media.availability == com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.AVAILABLE &&
                        duration != null && duration.isFinite() && duration > 0.0
                    ) {
                        ((episode.progressSeconds ?: 0.0).coerceAtLeast(0.0) / duration).coerceIn(0.0, 1.0)
                    } else {
                        0.0
                    }
                }.average()
            }
            SortMetrics(
                anime = anime,
                addedAt = anime.addedAt ?: 0.0,
                lastPlayedAt = anime.lastPlayedAt ?: 0.0,
                progress = progress,
                firstEpisode = episodeForOrdering?.number ?: Double.MAX_VALUE,
                minEpisode = available.minOfOrNull { it.number ?: Double.MAX_VALUE } ?: Double.MAX_VALUE,
                firstSeason = firstSeason,
                maxModifiedAt = episodes.maxOfOrNull { it.modifiedAt ?: 0.0 } ?: 0.0,
                totalDuration = episodes.sumOf { it.durationSeconds?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 },
                totalSize = episodes.sumOf { it.fileSizeBytes?.coerceAtLeast(0L) ?: 0L },
            )
        }

        val comparator = when (ReiAnixLibrarySort.fromLabel(sortLabel)) {
            ReiAnixLibrarySort.RECENT -> compareByDescending<SortMetrics> { it.addedAt }
            ReiAnixLibrarySort.RECENTLY_WATCHED -> compareByDescending<SortMetrics> { it.lastPlayedAt }
            ReiAnixLibrarySort.PROGRESS -> compareByDescending<SortMetrics> { it.progress }
            ReiAnixLibrarySort.EPISODE -> compareBy<SortMetrics> { it.firstEpisode }
            ReiAnixLibrarySort.SEASON_EPISODE -> compareBy<SortMetrics> { it.firstSeason }
                .thenBy { it.minEpisode }
            ReiAnixLibrarySort.MODIFICATION -> compareByDescending<SortMetrics> { it.maxModifiedAt }
            ReiAnixLibrarySort.DURATION -> compareByDescending<SortMetrics> { it.totalDuration }
            ReiAnixLibrarySort.SIZE -> compareByDescending<SortMetrics> { it.totalSize }
            ReiAnixLibrarySort.FAVORITES_FIRST -> compareByDescending<SortMetrics> { it.anime.favorite }
            ReiAnixLibrarySort.PINNED_FIRST -> compareByDescending<SortMetrics> { it.anime.pinned }
            ReiAnixLibrarySort.TITLE_ASC -> compareBy<SortMetrics> { it.anime.title.trim().lowercase() }
            ReiAnixLibrarySort.TITLE_DESC -> compareByDescending<SortMetrics> { it.anime.title.trim().lowercase() }
        }

        val ordered = when (ReiAnixLibrarySort.fromLabel(sortLabel)) {
            ReiAnixLibrarySort.TITLE_ASC ->
                decorated.sortedWith(comparator.thenBy { it.anime.id })
            ReiAnixLibrarySort.TITLE_DESC ->
                decorated.sortedWith(comparator.thenByDescending { it.anime.id })
            else ->
                decorated.sortedWith(
                    comparator
                        .thenBy { it.anime.title.trim().lowercase() }
                        .thenByDescending { it.anime.id },
                )
        }
        return ordered.map(SortMetrics::anime)
    }
}
