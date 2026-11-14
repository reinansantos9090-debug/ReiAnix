package com.reiflix.reiflix_local.data.sqlite

import java.util.Locale

private const val REIANIX_PLAYER_ORDER_SENTINEL = 1_000_000.0

private val REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES =
    setOf("movie", "special", "ova", "oad", "ona", "extra")

/**
 * Canonical adjacent-episode result matching LibraryStore.player_navigation().
 *
 * Each neighbor carries the edge flags that must be passed to the destination
 * player so Next/Previous remain correct after more than one transition.
 */
internal data class ReiAnixPlayerEpisodeNavigationSnapshot(
    val current: ReiAnixEpisodeRecord,
    val next: ReiAnixEpisodeRecord?,
    val previous: ReiAnixEpisodeRecord?,
    val nextCanNext: Boolean,
    val nextCanPrevious: Boolean,
    val previousCanNext: Boolean,
    val previousCanPrevious: Boolean,
)

/**
 * Pure Kotlin equivalent of LibraryStore._episode_order_key / player_navigation.
 *
 * The repository supplies rows from the existing episodes table; this helper
 * applies the same total-order fields and determines the neighboring available
 * regular episodes without creating another catalog or media identity.
 */
internal object ReiAnixPlayerEpisodeNavigator {
    private data class EpisodeOrderKey(
        val season: Double,
        val orderGroup: Int,
        val primaryNumber: Double,
        val secondaryNumber: Double,
        val mediaIdentity: String,
        val fileName: String,
        val path: String,
    ) : Comparable<EpisodeOrderKey> {
        override fun compareTo(other: EpisodeOrderKey): Int {
            var result = season.compareTo(other.season)
            if (result != 0) return result
            result = orderGroup.compareTo(other.orderGroup)
            if (result != 0) return result
            result = primaryNumber.compareTo(other.primaryNumber)
            if (result != 0) return result
            if (orderGroup == 1) {
                result = secondaryNumber.compareTo(other.secondaryNumber)
                if (result != 0) return result
            }
            result = mediaIdentity.compareTo(other.mediaIdentity)
            if (result != 0) return result
            result = fileName.compareTo(other.fileName)
            if (result != 0) return result
            return path.compareTo(other.path)
        }
    }

    fun resolve(
        current: ReiAnixEpisodeRecord,
        episodes: List<ReiAnixEpisodeRecord>,
    ): ReiAnixPlayerEpisodeNavigationSnapshot {
        val currentKey = current.orderKey()
        val orderedAvailable = episodes
            .asSequence()
            .filter(::isPlayableRegularEpisode)
            .map { it to it.orderKey() }
            .sortedWith { left, right -> left.second.compareTo(right.second) }
            .toList()

        // Keep the same current-row policy as the existing Compose player order
        // helper. SQL candidate selection below remains stricter, excluding NULL
        // episode_type values just as "episode_type NOT IN (...)" does in SQLite.
        val currentIsRegular = current.episodeType.orEmpty()
            .lowercase(Locale.ROOT) !in REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES
        val nextEntry = if (currentIsRegular) {
            orderedAvailable.firstOrNull { it.second > currentKey }
        } else null
        val previousEntry = if (currentIsRegular) {
            orderedAvailable.lastOrNull { it.second < currentKey }
        } else null

        val nextKey = nextEntry?.second
        val previousKey = previousEntry?.second
        val nextCanNext = nextKey != null && orderedAvailable.any { it.second > nextKey }
        val previousCanPrevious = previousKey != null && orderedAvailable.any { it.second < previousKey }

        return ReiAnixPlayerEpisodeNavigationSnapshot(
            current = current,
            next = nextEntry?.first,
            previous = previousEntry?.first,
            nextCanNext = nextCanNext,
            nextCanPrevious = nextEntry != null,
            previousCanNext = previousEntry != null,
            previousCanPrevious = previousCanPrevious,
        )
    }

    private fun isPlayableRegularEpisode(episode: ReiAnixEpisodeRecord): Boolean =
        episode.missing == false &&
            (episode.availabilityState == null || episode.availabilityState == "available") &&
            episode.episodeType != null &&
            episode.episodeType !in REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES

    private fun ReiAnixEpisodeRecord.orderKey(): EpisodeOrderKey {
        fun numeric(value: Double?): Double =
            value?.takeIf { it.isFinite() } ?: REIANIX_PLAYER_ORDER_SENTINEL

        val number = numeric(number)
        val absolute = numeric(absoluteNumber)
        val hasRelativeNumber = number < REIANIX_PLAYER_ORDER_SENTINEL
        return EpisodeOrderKey(
            season = season.toDouble(),
            orderGroup = if (hasRelativeNumber) 1 else 0,
            primaryNumber = if (hasRelativeNumber) number else absolute,
            secondaryNumber = if (hasRelativeNumber) absolute else 0.0,
            mediaIdentity = mediaIdentity.orEmpty(),
            fileName = caseFold(fileName),
            path = caseFold(path),
        )
    }

    // Python's str.casefold() is slightly stronger than lowercasing. Match the
    // non-ASCII folds already used by the native Compose ordering contract.
    private fun caseFold(value: String): String =
        value.lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")
}
