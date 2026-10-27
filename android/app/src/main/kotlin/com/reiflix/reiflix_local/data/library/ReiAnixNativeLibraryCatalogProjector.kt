package com.reiflix.reiflix_local.data.library

import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixArtworkRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.artwork.validReiAnixArtworkHttpUrl
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryCatalogRow
import com.reiflix.reiflix_local.ui.mapper.LibraryUiMappers
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import org.json.JSONArray

/** A single canonical SQLite read projected into the existing immutable Compose models. */
internal data class ReiAnixNativeLibraryCatalogSnapshot(
    val animes: List<ReiAnixAnimeUiModel>,
    val continueWatching: List<ReiAnixContinueWatchingUiModel>,
)

internal object ReiAnixNativeLibraryCatalogProjector {
    private val specialTypes = setOf("special", "ova", "oad", "ona", "extra")

    /**
     * Keeps all references from this anime's ordered artwork rows. An old row URL must not
     * outrank the current anime.cover_url metadata, and cover_cache remains a final local
     * fallback even when another non-empty path exists. File validation stays in Coil's
     * asynchronous decode path; projection never performs disk I/O on the caller thread.
     */
    internal fun resolvePosterArtworkCandidates(
        artworkLocalPaths: List<String?>,
        artworkExternalUrls: List<String?>,
        coverCache: String?,
        coverUrl: String?,
    ): Pair<List<String>, List<String>> {
        val local = (artworkLocalPaths + coverCache)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        val external = (listOf(coverUrl) + artworkExternalUrls)
            .mapNotNull(::validReiAnixArtworkHttpUrl)
            .distinct()
        return local to external
    }

    /**
     * Projects poster and backdrop metadata through the same path used by the full
     * catalog. The input records are read from canonical SQLite; this function has no
     * IO, cache, or image-loading side effects.
     */
    internal fun projectArtwork(
        anime: ReiAnixAnimeRecord,
        artworkRows: List<ReiAnixArtworkRecord>,
    ): ReiAnixArtworkUiModel? {
        val backdrop = artworkRows.firstOrNull {
            it.artworkType.equals("backdrop", ignoreCase = true) ||
                it.artworkType.equals("banner", ignoreCase = true)
        }
        val posterRows = artworkRows.filterNot {
            it.artworkType.equals("backdrop", ignoreCase = true) ||
                it.artworkType.equals("banner", ignoreCase = true)
        }
        val (localCandidates, externalCandidates) = resolvePosterArtworkCandidates(
            artworkLocalPaths = posterRows.map { it.localPath },
            artworkExternalUrls = posterRows.map { it.externalUrl },
            coverCache = anime.coverCache,
            coverUrl = anime.coverUrl,
        )
        val backdropLocalPath = backdrop?.localPath?.trim()?.takeIf(String::isNotEmpty)
        val backdropExternalUrl = backdrop?.externalUrl?.trim()?.takeIf(String::isNotEmpty)
        if (
            localCandidates.isEmpty() &&
            externalCandidates.isEmpty() &&
            backdropLocalPath == null &&
            backdropExternalUrl == null
        ) {
            return null
        }
        return ReiAnixArtworkUiModel(
            localPath = localCandidates.firstOrNull(),
            externalUrl = externalCandidates.firstOrNull(),
            backdropLocalPath = backdropLocalPath,
            backdropExternalUrl = backdropExternalUrl,
            localCandidates = localCandidates,
            externalCandidates = externalCandidates,
        )
    }

    /**
     * Replaces only the artwork field for one persisted anime identity. Missing artwork
     * never removes the anime from its collection, and unrelated model instances retain
     * their identity/state so Compose grids and filters remain stable.
     */
    internal fun replaceArtworkForAnime(
        animes: List<ReiAnixAnimeUiModel>,
        animeId: Long,
        artwork: ReiAnixArtworkUiModel?,
    ): List<ReiAnixAnimeUiModel> {
        if (animes.none { it.id == animeId }) return animes
        return animes.map { anime ->
            if (anime.id == animeId) anime.copy(artwork = artwork) else anime
        }
    }

    /**
     * Reconciles a page result with the latest canonical artwork projection.
     * A present canonical item with null artwork intentionally clears an older image;
     * a missing canonical item leaves the page item untouched.
     */
    internal fun mergeLatestArtwork(
        pageAnimes: List<ReiAnixAnimeUiModel>,
        canonicalAnimes: List<ReiAnixAnimeUiModel>,
    ): List<ReiAnixAnimeUiModel> {
        if (pageAnimes.isEmpty() || canonicalAnimes.isEmpty()) return pageAnimes
        val canonicalById = canonicalAnimes.associateBy { it.id }
        return pageAnimes.map { pageAnime ->
            val canonical = canonicalById[pageAnime.id] ?: return@map pageAnime
            if (pageAnime.artwork == canonical.artwork) pageAnime
            else pageAnime.copy(artwork = canonical.artwork)
        }
    }

    fun project(rows: List<ReiAnixLibraryCatalogRow>): ReiAnixNativeLibraryCatalogSnapshot {
        val animes = rows.map(::projectAnime)
        val lastPlayedByEpisodeId = rows.asSequence()
            .flatMap { it.episodes.asSequence() }
            .associate { it.id to (it.lastPlayedAt ?: 0.0) }
        val continueWatching = buildList {
            for (anime in animes) {
                val playable = if (anime.mediaKind == ReiAnixMediaKind.MOVIE) {
                    anime.mediaFiles
                } else {
                    anime.seasons.flatMap { it.episodes }
                }
                for (episode in playable) {
                    if (episode.consumptionState != ReiAnixConsumptionState.IN_PROGRESS ||
                        episode.media.availability != ReiAnixMediaAvailability.AVAILABLE
                    ) continue
                    add(
                        ReiAnixContinueWatchingUiModel(
                            episodeId = episode.id,
                            animeId = anime.id,
                            animeTitle = anime.title,
                            seasonNumber = episode.seasonNumber,
                            number = episode.number,
                            title = episode.title,
                            fileName = episode.fileName,
                            progressSeconds = episode.progressSeconds,
                            durationSeconds = episode.durationSeconds,
                            artwork = episode.artwork,
                        ),
                    )
                }
            }
        }.sortedWith(
            compareByDescending<ReiAnixContinueWatchingUiModel> { lastPlayedByEpisodeId[it.episodeId] ?: 0.0 }
                .thenByDescending { it.episodeId },
        ).take(CONTINUE_WATCHING_LIMIT)

        return ReiAnixNativeLibraryCatalogSnapshot(
            animes = animes,
            continueWatching = continueWatching,
        )
    }

    private fun projectAnime(row: ReiAnixLibraryCatalogRow): ReiAnixAnimeUiModel {
        val anime = row.anime
        val orderedEpisodes = row.episodes.sortedWith(
            compareBy<ReiAnixEpisodeRecord> { it.season }
                .thenBy { it.number ?: -1.0 }
                .thenBy { it.fileName.lowercase() }
                .thenBy { it.path.lowercase() },
        )
        val specialEpisodes = orderedEpisodes.filter { it.episodeType?.lowercase() in specialTypes }
        val movieEpisodes = orderedEpisodes.filter { it.episodeType?.lowercase() == "movie" }
        val regularEpisodes = orderedEpisodes.filter {
            it.episodeType?.lowercase() !in specialTypes && it.episodeType?.lowercase() != "movie"
        }
        val artwork = projectArtwork(anime, row.animeArtwork)
        val primaryLocalArtwork = artwork?.localPath
        val primaryExternalArtwork = artwork?.externalUrl
        val genreNames = if (row.genres.isNotEmpty()) row.genres.map { it.name }
            else parseStringArray(anime.genresJson)
        val genreIds = if (row.genres.isNotEmpty()) row.genres.map { it.id } else emptyList()
        val meta = linkedMapOf<String, Any?>(
            "title" to anime.title,
            "romaji" to anime.romaji,
            "english" to anime.english,
            "native" to anime.nativeTitle,
            "aliases" to parseStringArray(anime.aliasesJson),
            "description" to anime.description,
            "cover_url" to anime.coverUrl,
            "cover_cache" to anime.coverCache,
            "banner_url" to anime.bannerUrl,
            "genres" to genreNames,
            "year" to anime.year,
            "season" to anime.season,
            "status" to anime.status,
            "episodes_count" to anime.episodesCount,
            "duration" to anime.duration,
            "score" to anime.score?.toDouble(),
            "format" to anime.format,
            "studio" to anime.studio,
            "metadata_status" to anime.metadataStatus,
            "anilist_id" to anime.anilistId?.toString(),
            "favorite" to anime.favorite,
            "is_pinned" to anime.isPinned,
            "added_at" to anime.addedAt,
            "media_kind" to anime.mediaKind,
        )
        val seasonGroups = regularEpisodes.groupBy { it.season }.toSortedMap().map { (season, episodes) ->
            mapOf(
                "season" to season,
                "season_name" to "Temporada $season",
                "episodes" to episodes.map { episodeMap(it, anime.id, row) },
            )
        }
        val lastPlayed = row.episodes.mapNotNull { it.lastPlayedAt }.maxOrNull()
        val availableEpisodes = row.episodes.filter(::isAvailable)
        val target = playbackTarget(anime.mediaKind, availableEpisodes)

        val projection = linkedMapOf<String, Any?>(
            "id" to anime.id,
            "title" to anime.title,
            "main_title" to anime.title,
            "lookup_title" to anime.lookupTitle,
            "anilist_id" to anime.anilistId?.toString(),
            "meta" to meta,
            "favorite" to (anime.favorite == true),
            "is_pinned" to (anime.isPinned == true),
            "user_tags" to parseStringArray(anime.userTagsJson),
            "personal_note" to anime.personalNote,
            "genres" to genreNames,
            "genre_ids" to genreIds,
            "seasons" to seasonGroups,
            "specials" to if (specialEpisodes.isEmpty()) emptyList<Any>() else listOf(
                mapOf(
                    "season_name" to "Especiais",
                    "season" to null,
                    "episodes" to specialEpisodes.map { episodeMap(it, anime.id, row) },
                ),
            ),
            "media_files" to movieEpisodes.map { episodeMap(it, anime.id, row) },
            "artwork_available" to (!primaryLocalArtwork.isNullOrBlank() || !anime.coverCache.isNullOrBlank()),
            "artwork_local_path" to primaryLocalArtwork,
            "artwork_external_url" to primaryExternalArtwork,
            "artwork_local_candidates" to artwork?.localCandidates.orEmpty(),
            "artwork_external_candidates" to artwork?.externalCandidates.orEmpty(),
            "artwork_backdrop_local_path" to artwork?.backdropLocalPath,
            "backdrop_external_url" to artwork?.backdropExternalUrl,
            "playback_target_episode_id" to target?.id,
            "score" to anime.score?.toDouble(),
            "year" to anime.year,
            "added_at" to anime.addedAt,
            "last_played_at" to lastPlayed,
            "media_kind" to anime.mediaKind,
            "metadata_status" to anime.metadataStatus,
            "presentation_state" to null,
        )
        return LibraryUiMappers.anime(projection)
    }

    private fun episodeMap(
        episode: ReiAnixEpisodeRecord,
        animeId: Long,
        row: ReiAnixLibraryCatalogRow,
    ): Map<String, Any?> {
        val availability = episode.availabilityState?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (episode.missing == true) "missing" else "available"
        val progress = episode.progress?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val duration = episode.duration?.takeIf { it.isFinite() && it > 0.0 }
        val completed = episode.watched == true ||
            (duration != null && (progress.coerceAtMost(duration) / duration) >= COMPLETION_RATIO)
        val state = when {
            completed && episode.watched == true -> "watched"
            completed -> "completed"
            episode.missing != true && progress > 0.0 -> "in_progress"
            else -> "unwatched"
        }
        val artwork = row.episodeArtwork[episode.id]
        return linkedMapOf(
            "id" to episode.id,
            "anime_id" to animeId,
            "season" to episode.season,
            "number" to episode.number,
            "absolute_number" to episode.absoluteNumber,
            "episode_title" to episode.episodeTitle,
            "title" to episode.fileName,
            "file_name" to episode.fileName,
            "path" to episode.path,
            "uri" to episode.path,
            "progress" to episode.progress,
            "duration" to episode.duration,
            "watched" to (episode.watched == true),
            "consumption_state" to state,
            "missing" to (episode.missing == true),
            "availability_state" to availability,
            "last_played_at" to episode.lastPlayedAt,
            "modified_at" to episode.modifiedAt,
            "file_size" to episode.fileSize,
            "media_identity" to episode.mediaIdentity,
            "mime_type" to episode.mimeType,
            "episode_type" to episode.episodeType,
            "identification_source" to episode.identificationSource,
            "identification_confidence" to episode.identificationConfidence,
            "manual_override" to (episode.manualOverride == true),
            "source_folder" to episode.sourceFolder,
            "relative_path" to episode.relativePath,
            "volume_id" to episode.volumeId,
            "volume_uuid" to episode.volumeUuid,
            "artwork_local_path" to artwork?.localPath,
            "artwork_external_url" to artwork?.externalUrl,
        )
    }

    private fun playbackTarget(
        mediaKind: String?,
        available: List<ReiAnixEpisodeRecord>,
    ): ReiAnixEpisodeRecord? {
        if (available.isEmpty()) return null
        val ordered = available.sortedWith(episodeIdentityComparator)
        if (mediaKind.equals("movie", ignoreCase = true)) {
            return ordered.firstOrNull { it.episodeType.equals("movie", ignoreCase = true) } ?: ordered.firstOrNull()
        }
        val regular = ordered.filter {
            it.episodeType?.lowercase() !in specialTypes && it.episodeType?.lowercase() != "movie"
        }
        if (regular.isEmpty()) {
            return ordered.firstOrNull { it.episodeType?.lowercase() in specialTypes } ?: ordered.firstOrNull()
        }
        val active = regular.filter { episode ->
            val progress = episode.progress?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val duration = episode.duration?.takeIf { it.isFinite() && it > 0.0 }
            val completed = episode.watched == true ||
                (duration != null && progress.coerceAtMost(duration) / duration >= COMPLETION_RATIO)
            episode.missing != true && progress > 0.0 && !completed
        }.maxWithOrNull(compareBy<ReiAnixEpisodeRecord> { it.lastPlayedAt ?: 0.0 }.thenBy { it.id })
        if (active != null) return active

        val completed = regular.filter { episode ->
            val duration = episode.duration?.takeIf { it.isFinite() && it > 0.0 }
            episode.watched == true ||
                (duration != null && (episode.progress ?: 0.0).coerceAtLeast(0.0).coerceAtMost(duration) / duration >= COMPLETION_RATIO)
        }
        if (completed.isNotEmpty()) {
            val furthest = completed.maxWithOrNull(episodeIdentityComparator) ?: return regular.first()
            val next = regular.filter { episodeIdentityComparator.compare(it, furthest) > 0 }
                .minWithOrNull(episodeIdentityComparator)
            if (next != null) return next
        }
        return regular.firstOrNull { episode ->
            val duration = episode.duration?.takeIf { it.isFinite() && it > 0.0 }
            val progress = episode.progress?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            !(episode.watched == true || (duration != null && progress.coerceAtMost(duration) / duration >= COMPLETION_RATIO))
        } ?: regular.firstOrNull()
    }

    private fun isAvailable(episode: ReiAnixEpisodeRecord): Boolean =
        episode.missing != true && (episode.availabilityState.isNullOrBlank() ||
            episode.availabilityState.equals("available", ignoreCase = true))

    private fun parseStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.opt(index)?.toString()?.trim().orEmpty()
                    if (value.isNotEmpty() && value != "null") add(value)
                }
            }
        }.getOrDefault(emptyList())
    }

    private val episodeIdentityComparator = compareBy<ReiAnixEpisodeRecord> { it.season }
        .thenBy { if (it.number != null) 1 else 0 }
        .thenBy { it.number ?: it.absoluteNumber ?: Double.MAX_VALUE }
        .thenBy { it.mediaIdentity.orEmpty() }
        .thenBy { it.fileName.lowercase() }
        .thenBy { it.path.lowercase() }

    private const val COMPLETION_RATIO = 0.90
    private const val CONTINUE_WATCHING_LIMIT = 12
}
