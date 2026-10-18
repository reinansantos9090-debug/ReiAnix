package com.reiflix.reiflix_local.player

import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import java.util.Locale

private const val REIANIX_PLAYER_ORDER_SENTINEL = 1_000_000.0
private val REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES =
    setOf("movie", "special", "ova", "oad", "ona", "extra")

/**
 * Mirrors LibraryStore._episode_order_key. The same key is used by Compose
 * launch validation and by the native player's Next/Previous resolver.
 */
internal data class ReiAnixNativePlayerEpisodeOrderKey(
    val season: Double,
    val orderGroup: Int,
    val primaryNumber: Double,
    val secondaryNumber: Double,
    val mediaIdentity: String,
    val fileName: String,
    val path: String,
) : Comparable<ReiAnixNativePlayerEpisodeOrderKey> {
    override fun compareTo(other: ReiAnixNativePlayerEpisodeOrderKey): Int {
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

private fun nativePlayerOrderText(value: String): String =
    value.lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")

internal object ReiAnixNativePlayerEpisodeOrder {
    fun key(episode: ReiAnixEpisodeRecord): ReiAnixNativePlayerEpisodeOrderKey {
        fun numeric(value: Double?): Double =
            value?.takeIf { it.isFinite() } ?: REIANIX_PLAYER_ORDER_SENTINEL

        val number = numeric(episode.number)
        val absolute = numeric(episode.absoluteNumber)
        val hasRelativeNumber = number < REIANIX_PLAYER_ORDER_SENTINEL
        return ReiAnixNativePlayerEpisodeOrderKey(
            season = episode.season.toDouble(),
            orderGroup = if (hasRelativeNumber) 1 else 0,
            primaryNumber = if (hasRelativeNumber) number else absolute,
            secondaryNumber = if (hasRelativeNumber) absolute else 0.0,
            mediaIdentity = episode.mediaIdentity.orEmpty(),
            fileName = nativePlayerOrderText(episode.fileName),
            path = nativePlayerOrderText(episode.path),
        )
    }

    fun isRegularEpisode(episode: ReiAnixEpisodeRecord): Boolean =
        episode.episodeType.orEmpty().lowercase(Locale.ROOT) !in
            REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES

    /** Mirrors the canonical SQLite predicate used by LibraryStore.player_navigation(). */
    fun isAvailableRegularCandidate(episode: ReiAnixEpisodeRecord): Boolean =
        episode.missing == false &&
            (episode.availabilityState == null || episode.availabilityState == "available") &&
            episode.episodeType != null &&
            episode.episodeType !in REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES
}

data class ReiAnixNativePlayerNavigationSnapshot(
    val current: ReiAnixEpisodeRecord,
    val next: ReiAnixEpisodeRecord?,
    val previous: ReiAnixEpisodeRecord?,
    val nextCanNext: Boolean,
    val nextCanPrevious: Boolean,
    val previousCanNext: Boolean,
    val previousCanPrevious: Boolean,
)

/**
 * Resolves adjacency from the canonical SQLite repository. This does not create
 * another catalog and keeps the same eligibility/order contract as LibraryStore.
 */
class ReiAnixNativePlayerNavigationService(
    private val library: ReiAnixSqliteLibraryRepository,
) {
    suspend fun resolve(episodeId: Long): ReiAnixNativePlayerNavigationSnapshot {
        require(episodeId > 0L) { "Episode ID must be a positive persisted ID." }
        val rows = library.playerNavigationRows(episodeId)
        val current = rows.current
            ?: throw NoSuchElementException("Episode ID does not exist: " + episodeId)

        if (!rows.animeExists ||
            !ReiAnixNativePlayerEpisodeOrder.isRegularEpisode(current)
        ) {
            return ReiAnixNativePlayerNavigationSnapshot(
                current = current,
                next = null,
                previous = null,
                nextCanNext = false,
                nextCanPrevious = false,
                previousCanNext = false,
                previousCanPrevious = false,
            )
        }

        val available = rows.availableCandidates
            .filter(ReiAnixNativePlayerEpisodeOrder::isAvailableRegularCandidate)
        val currentKey = ReiAnixNativePlayerEpisodeOrder.key(current)
        val next = available.asSequence()
            .filter { ReiAnixNativePlayerEpisodeOrder.key(it) > currentKey }
            .minByOrNull(ReiAnixNativePlayerEpisodeOrder::key)
        val previous = available.asSequence()
            .filter { ReiAnixNativePlayerEpisodeOrder.key(it) < currentKey }
            .maxByOrNull(ReiAnixNativePlayerEpisodeOrder::key)
        val nextKey = next?.let(ReiAnixNativePlayerEpisodeOrder::key)
        val previousKey = previous?.let(ReiAnixNativePlayerEpisodeOrder::key)

        return ReiAnixNativePlayerNavigationSnapshot(
            current = current,
            next = next,
            previous = previous,
            nextCanNext = nextKey != null &&
                available.any { ReiAnixNativePlayerEpisodeOrder.key(it) > nextKey },
            // Preserve the existing boundary contract: the current episode is
            // the predecessor of a resolved Next target.
            nextCanPrevious = next != null,
            previousCanNext = previous != null,
            previousCanPrevious = previousKey != null &&
                available.any { ReiAnixNativePlayerEpisodeOrder.key(it) < previousKey },
        )
    }
}
