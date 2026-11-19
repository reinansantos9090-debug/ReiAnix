package com.reianix.reianix_local.ui.library

import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability

data class ReiAnixLibraryFilters(
    val query: String = "",
    val selectedGenreKey: String? = null,
    val favoritesOnly: Boolean = false,
    val watchingOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val sort: String = ReiAnixLibrarySort.DEFAULT.label,
    val watchState: String = "ALL",
    val labelFilter: String = "ALL",
) {
    val hasAnyFilter: Boolean
        get() = query.isNotBlank() ||
            selectedGenreKey != null ||
            favoritesOnly ||
            watchingOnly ||
            completedOnly ||
            watchState != "ALL" || labelFilter != "ALL"

    val hasPersonalFilters: Boolean
        get() = watchState != "ALL" || labelFilter != "ALL"

    companion object {
        val WATCH_STATES = listOf(
            "ALL" to "Todos",
            *com.reianix.reianix_local.data.personal.ReiAnixWatchStatus.entries.map { it.name to it.label }.toTypedArray(),
            "NONE" to "Sem estado",
        )
        val LABEL_FILTERS = listOf(
            "ALL" to "Todos",
            "WITH_NOTE" to "Com notas pessoais",
            "WITHOUT_NOTE" to "Sem notas pessoais",
            "WITH_TAGS" to "Com tags",
            "WITHOUT_TAGS" to "Sem tags",
        )
    }
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
    /**
     * Reuses the exact Library filter semantics for incremental reconciliation.
     * This is intentionally item-scoped; callers do not need to filter/sort the
     * entire canonical catalog just to decide whether one newly discovered anime
     * belongs in the already-loaded window.
     */
    fun matches(
        anime: ReiAnixAnimeUiModel,
        filters: ReiAnixLibraryFilters,
    ): Boolean {
        val query = filters.query.trim()
        val matchesQuery = query.isBlank() ||
            anime.title.contains(query, ignoreCase = true) ||
            anime.genres.any { it.name.contains(query, ignoreCase = true) }
        val matchesGenre = filters.selectedGenreKey == null ||
            anime.genres.any { it.stableKey == filters.selectedGenreKey }
        val matchesFavorite = !filters.favoritesOnly || anime.favorite
        val matchesWatching = !filters.watchingOnly || anime.isWatching
        val matchesCompleted = !filters.completedOnly || anime.isCompleted
        return matchesQuery && matchesGenre && matchesFavorite && matchesWatching && matchesCompleted
    }

    fun filter(
        animes: List<ReiAnixAnimeUiModel>,
        filters: ReiAnixLibraryFilters,
    ): List<ReiAnixAnimeUiModel> =
        sort(
            animes.filter { anime -> matches(anime, filters) },
            filters.sort,
        )

    fun sortForLibrary(
        animes: List<ReiAnixAnimeUiModel>,
        sortLabel: String,
    ): List<ReiAnixAnimeUiModel> = sort(animes, sortLabel)

    private data class SortMetrics(
        val anime: ReiAnixAnimeUiModel,
        val normalizedTitle: String,
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

        val selectedSort = ReiAnixLibrarySort.fromLabel(sortLabel)
        // Most library views use RECENT or TITLE ordering. Neither needs to
        // inspect episodes: calculating every metric for every anime made even
        // a simple title sort traverse the entire episode catalog.
        val decorated = animes.map { anime ->
            val episodes = when (selectedSort) {
                ReiAnixLibrarySort.PROGRESS,
                ReiAnixLibrarySort.EPISODE,
                ReiAnixLibrarySort.SEASON_EPISODE,
                ReiAnixLibrarySort.MODIFICATION,
                ReiAnixLibrarySort.DURATION,
                ReiAnixLibrarySort.SIZE -> anime.contentEpisodes
                else -> emptyList()
            }
            val available = if (
                selectedSort == ReiAnixLibrarySort.EPISODE ||
                selectedSort == ReiAnixLibrarySort.SEASON_EPISODE
            ) {
                episodes.filter { it.media.availability == ReiAnixMediaAvailability.AVAILABLE }
            } else {
                emptyList()
            }
            val episodeForOrdering = if (selectedSort == ReiAnixLibrarySort.EPISODE) {
                // The first element of a sorted list is the minimum; avoid
                // allocating/sorting a whole episode list to find one item.
                available.minWithOrNull(
                    compareBy<ReiAnixEpisodeUiModel>(
                        { it.seasonNumber ?: Int.MAX_VALUE },
                        { it.number ?: Double.MAX_VALUE },
                    ).thenBy { it.id },
                )
            } else {
                null
            }
            val progress = if (selectedSort == ReiAnixLibrarySort.PROGRESS && episodes.isNotEmpty()) {
                var totalProgress = 0.0
                for (episode in episodes) {
                    val duration = episode.durationSeconds
                    if (episode.media.availability == ReiAnixMediaAvailability.AVAILABLE &&
                        duration != null && duration.isFinite() && duration > 0.0
                    ) {
                        totalProgress += ((episode.progressSeconds ?: 0.0).coerceAtLeast(0.0) / duration)
                            .coerceIn(0.0, 1.0)
                    }
                }
                totalProgress / episodes.size
            } else {
                0.0
            }
            SortMetrics(
                anime = anime,
                normalizedTitle = anime.title.trim().lowercase(),
                addedAt = anime.addedAt ?: 0.0,
                lastPlayedAt = anime.lastPlayedAt ?: 0.0,
                progress = progress,
                firstEpisode = episodeForOrdering?.number ?: Double.MAX_VALUE,
                minEpisode = if (selectedSort == ReiAnixLibrarySort.SEASON_EPISODE) {
                    available.minOfOrNull { it.number ?: Double.MAX_VALUE } ?: Double.MAX_VALUE
                } else {
                    Double.MAX_VALUE
                },
                firstSeason = if (selectedSort == ReiAnixLibrarySort.SEASON_EPISODE) {
                    available.minOfOrNull { it.seasonNumber ?: Int.MAX_VALUE } ?: Int.MAX_VALUE
                } else {
                    Int.MAX_VALUE
                },
                maxModifiedAt = if (selectedSort == ReiAnixLibrarySort.MODIFICATION) {
                    episodes.maxOfOrNull { it.modifiedAt ?: 0.0 } ?: 0.0
                } else {
                    0.0
                },
                totalDuration = if (selectedSort == ReiAnixLibrarySort.DURATION) {
                    episodes.sumOf { it.durationSeconds?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }
                } else {
                    0.0
                },
                totalSize = if (selectedSort == ReiAnixLibrarySort.SIZE) {
                    episodes.sumOf { it.fileSizeBytes?.coerceAtLeast(0L) ?: 0L }
                } else {
                    0L
                },
            )
        }

        val comparator = when (selectedSort) {
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
            ReiAnixLibrarySort.TITLE_ASC -> compareBy<SortMetrics> { it.normalizedTitle }
            ReiAnixLibrarySort.TITLE_DESC -> compareByDescending<SortMetrics> { it.normalizedTitle }
        }

        val ordered = when (selectedSort) {
            ReiAnixLibrarySort.TITLE_ASC ->
                decorated.sortedWith(comparator.thenBy { it.anime.id })
            ReiAnixLibrarySort.TITLE_DESC ->
                decorated.sortedWith(comparator.thenByDescending { it.anime.id })
            else ->
                decorated.sortedWith(
                    comparator
                        .thenBy { it.normalizedTitle }
                        .thenByDescending { it.anime.id },
                )
        }
        return ordered.map(SortMetrics::anime)
    }
}
