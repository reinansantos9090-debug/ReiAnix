package com.reianix.reianix_local.ui.home

import com.reianix.reianix_local.ui.model.ReiAnixHomeAnimeUiModel
import java.util.Locale

/**
 * Ranks unstarted titles already in the local library from genres the user
 * watches, has played, or has favorited. This policy is deterministic and
 * performs no catalog requests or persistence.
 */
internal object ReiAnixLocalRecommendationPolicy {
    fun recommend(
        animes: List<ReiAnixHomeAnimeUiModel>,
        limit: Int = DEFAULT_LIMIT,
    ): List<ReiAnixHomeAnimeUiModel> {
        if (limit <= 0) return emptyList()

        val unique = animes.distinctBy { it.id }
        val genreAffinity = mutableMapOf<String, Int>()
        unique.asSequence()
            .filter { it.favorite || it.isWatching || it.lastPlayedAt != null }
            .forEach { anime ->
                val weight = when {
                    anime.isWatching -> WATCHING_WEIGHT
                    anime.favorite -> FAVORITE_WEIGHT
                    else -> PLAYED_WEIGHT
                }
                anime.genres.asSequence()
                    .mapNotNull { genre ->
                        genre.name.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
                    }
                    .distinct()
                    .forEach { genre ->
                        genreAffinity[genre] = (genreAffinity[genre] ?: 0) + weight
                    }
            }

        val candidates = unique.filter { anime ->
            anime.availableContentCount > 0 &&
                !anime.isWatching &&
                anime.playbackActionLabel == START_WATCHING_LABEL
        }
        if (candidates.isEmpty()) return emptyList()

        val scored = candidates.map { anime ->
            val score = anime.genres.asSequence()
                .mapNotNull { genre ->
                    genre.name.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty)
                }
                .distinct()
                .sumOf { genre -> genreAffinity[genre] ?: 0 }
            anime to score
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<ReiAnixHomeAnimeUiModel, Int>> { it.second }
                    .thenByDescending { it.first.addedAt ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.first.title.trim().lowercase(Locale.ROOT) }
                    .thenBy { it.first.id },
            )
            .take(limit)
            .map { it.first }
    }

    private const val DEFAULT_LIMIT = 8
    private const val WATCHING_WEIGHT = 3
    private const val FAVORITE_WEIGHT = 2
    private const val PLAYED_WEIGHT = 1
    private const val START_WATCHING_LABEL = "Assistir"
}
