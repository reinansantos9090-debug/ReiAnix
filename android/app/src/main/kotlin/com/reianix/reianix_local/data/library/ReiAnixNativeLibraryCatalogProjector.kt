package com.reianix.reianix_local.data.library

import com.reianix.reianix_local.data.media.LocalCatalogLookup
import com.reianix.reianix_local.data.media.LocalCatalogLookupPolicy
import com.reianix.reianix_local.data.sqlite.localCatalogLookup

import com.reianix.reianix_local.data.artwork.ReiAnixAdultArtworkPolicy
import com.reianix.reianix_local.data.organize.ReiAnixGenreNames
import com.reianix.reianix_local.data.organize.parseReiAnixGenreNames
import com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel

import com.reianix.reianix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reianix.reianix_local.data.sqlite.ReiAnixLibraryCatalogRow
import com.reianix.reianix_local.ui.mapper.LibraryUiMappers
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reianix.reianix_local.ui.model.ReiAnixConsumptionState
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability
import org.json.JSONArray
import java.io.File
import android.net.Uri
import com.reianix.reianix_local.ui.artwork.localArtworkSourceRevision

/** A single canonical SQLite read projected into the existing immutable Compose models. */
internal data class ReiAnixNativeLibraryCatalogSnapshot(
    val animes: List<ReiAnixAnimeUiModel>,
    val continueWatching: List<ReiAnixContinueWatchingUiModel>,
)

internal object ReiAnixNativeLibraryCatalogProjector {
    // Catalog worker only: stale pointers must not hide another saved poster.
    private fun hasReadableArtworkFile(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("content://") || path.startsWith("android.resource://")) return true
        return runCatching {
            val file = if (path.startsWith("file://")) {
                Uri.parse(path).path?.let(::File) ?: return@runCatching false
            } else File(path)
            if (!file.isFile || !file.canRead() || file.length() <= 0L) return@runCatching false
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)
    }

    private val specialTypes = setOf("special", "ova", "oad", "ona", "extra")

    fun project(
        rows: List<ReiAnixLibraryCatalogRow>,
        unchangedAnimes: Map<Long, ReiAnixAnimeUiModel> = emptyMap(),
    ): ReiAnixNativeLibraryCatalogSnapshot {
        val animes = rows.map { unchangedAnimes[it.anime.id] ?: projectAnime(it) }
        val lastPlayedByEpisodeId = rows.asSequence()
            .flatMap { it.episodes.asSequence() }
            .associate { it.id to (it.lastPlayedAt ?: 0.0) }
        val continueWatching = buildList {
            for (anime in animes) {
                val playable = if (anime.mediaKind == ReiAnixMediaKind.VIDEO) {
                    anime.contentEpisodes
                } else if (anime.mediaKind == ReiAnixMediaKind.MOVIE) {
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
        // Reuse the episodes already loaded by this background projection. Existing
        // automatic remote artwork on a personal capture is hidden, not deleted.
        val personalVideo = LocalCatalogLookupPolicy.needsEvidence(anime.lookupTitle, anime.mediaKind) &&
            anime.localCatalogLookup(row.episodes.sortedBy { it.id }.take(8).map { it.fileName }) == LocalCatalogLookup.PERSONAL
        // Presentation-only category: retain persisted episode IDs, file paths and
        // playback bookkeeping; do not migrate or re-index the library.
        val displayKind = if (personalVideo) "video" else anime.mediaKind
        val specialEpisodes = if (personalVideo) emptyList() else orderedEpisodes.filter { it.episodeType?.lowercase() in specialTypes }
        val movieEpisodes = if (personalVideo) orderedEpisodes else orderedEpisodes.filter { it.episodeType?.lowercase() == "movie" }
        val regularEpisodes = if (personalVideo) emptyList() else orderedEpisodes.filter {
            it.episodeType?.lowercase() !in specialTypes && it.episodeType?.lowercase() != "movie"
        }
        val personalManualFields = if (personalVideo) parseStringArray(anime.metadataManualFieldsJson).toSet() else emptySet()
        fun visibleField(field: String): Boolean = !personalVideo || field in personalManualFields
        val legacyGenres = parseReiAnixGenreNames(anime.genresJson)
        val visibleLegacyGenres = legacyGenres.takeIf { visibleField("genres") }.orEmpty()
        val preservedGenres = ReiAnixGenreNames.preserveMetadataGenres(
            row.genres.takeIf { visibleField("genres") }.orEmpty().map { ReiAnixGenreUiModel(it.id, it.name) },
            visibleLegacyGenres,
        )
        val genreNames = preservedGenres.map { it.name }
        val adultArtwork = ReiAnixAdultArtworkPolicy.blocked(genreNames) ||
            ReiAnixAdultArtworkPolicy.blocked(legacyGenres) || ReiAnixAdultArtworkPolicy.blockedJson(anime.genresJson)
        val animeArtwork = when {
            adultArtwork -> emptyList()
            !personalVideo -> row.animeArtwork
            else -> row.animeArtwork.filter {
                it.manual == true || it.source.equals("manual", true) ||
                    (it.externalUrl.isNullOrBlank() && !it.source.equals("anilist", true) && !it.source.equals("kitsu", true))
            }
        }
        val localVideoPoster = if (personalVideo && !adultArtwork) orderedEpisodes.firstNotNullOfOrNull {
            row.episodeArtwork[it.id]?.localPath?.takeIf(::hasReadableArtworkFile)
        } else null
        val showCoverCache = !adultArtwork && visibleField("cover_cache")
        val showCoverUrl = !adultArtwork && visibleField("cover_url")
        val backdrop = animeArtwork.firstOrNull {
            it.artworkType.equals("backdrop", ignoreCase = true) ||
                it.artworkType.equals("banner", ignoreCase = true)
        }
        val posterCandidates = animeArtwork.filterNot {
            it.artworkType.equals("backdrop", ignoreCase = true) ||
                it.artworkType.equals("banner", ignoreCase = true)
        }
        // A valid saved poster remains stable when background metadata changes
        // priorities or URLs. Explicit manual artwork still takes precedence.
        val manualPoster = posterCandidates.firstOrNull {
            (it.manual == true || it.source.equals("manual", true)) &&
                it.localPath?.let(::hasReadableArtworkFile) == true
        }
        val primaryLocalArtwork = manualPoster?.localPath
            ?: anime.coverCache?.takeIf { showCoverCache }?.takeIf(::hasReadableArtworkFile)
            ?: posterCandidates.mapNotNull { it.localPath }.firstOrNull(::hasReadableArtworkFile)
            ?: localVideoPoster
        val selectedPoster = posterCandidates.firstOrNull { it.localPath == primaryLocalArtwork && primaryLocalArtwork != null }
        val primaryExternalArtwork = selectedPoster?.externalUrl
            ?: anime.coverUrl?.takeIf { showCoverUrl }?.takeIf(String::isNotBlank)
            ?: posterCandidates.firstOrNull { !it.externalUrl.isNullOrBlank() }?.externalUrl
        val meta = linkedMapOf<String, Any?>(
            "title" to anime.title,
            "romaji" to anime.romaji.takeIf { visibleField("romaji") },
            "english" to anime.english.takeIf { visibleField("english") },
            "native" to anime.nativeTitle.takeIf { visibleField("native") },
            "aliases" to parseStringArray(anime.aliasesJson).takeIf { visibleField("aliases") }.orEmpty(),
            "description" to anime.description.takeIf { !adultArtwork && visibleField("description") },
            "cover_url" to anime.coverUrl.takeIf { showCoverUrl },
            "cover_cache" to anime.coverCache.takeIf { showCoverCache },
            "banner_url" to anime.bannerUrl.takeIf { !adultArtwork && visibleField("banner_url") },
            "genres" to genreNames,
            "year" to anime.year.takeIf { visibleField("year") },
            "season" to anime.season.takeIf { visibleField("season") },
            "status" to anime.status.takeIf { visibleField("status") },
            "episodes_count" to anime.episodesCount.takeIf { visibleField("episodes_count") },
            "duration" to anime.duration.takeIf { visibleField("duration") },
            "score" to anime.score?.takeIf { visibleField("score") }?.toDouble(),
            "format" to anime.format.takeIf { visibleField("format") },
            "studio" to anime.studio.takeIf { visibleField("studio") },
            "metadata_status" to anime.metadataStatus,
            "anilist_id" to anime.anilistId?.toString(),
            "favorite" to anime.favorite,
            "is_pinned" to anime.isPinned,
            "added_at" to anime.addedAt,
            "media_kind" to displayKind,
        )
        val seasonGroups = regularEpisodes.groupBy { it.season }.toSortedMap().map { (season, episodes) ->
            mapOf(
                "season" to season,
                "season_name" to "Temporada $season",
                "episodes" to episodes.map { episodeMap(it, anime.id, row, adultArtwork) },
            )
        }
        val lastPlayed = row.episodes.mapNotNull { it.lastPlayedAt }.maxOrNull()
        val availableEpisodes = row.episodes.filter(::isAvailable)
        val target = playbackTarget(if (personalVideo) "movie" else anime.mediaKind, availableEpisodes)

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
            "seasons" to seasonGroups,
            "specials" to if (specialEpisodes.isEmpty()) emptyList<Any>() else listOf(
                mapOf(
                    "season_name" to "Especiais",
                    "season" to null,
                    "episodes" to specialEpisodes.map { episodeMap(it, anime.id, row, adultArtwork) },
                ),
            ),
            "media_files" to movieEpisodes.map {
                val mapped = episodeMap(it, anime.id, row, adultArtwork)
                if (personalVideo) mapped + mapOf("episode_type" to "video", "number" to null, "season" to null) else mapped
            },
            // File metadata is captured with the background catalog projection,
            // never during Compose composition. Preserve same-path regeneration keys.
            "artwork_source_revisions" to (animeArtwork.mapNotNull { it.localPath } +
                listOfNotNull(anime.coverCache.takeIf { showCoverCache }, localVideoPoster)).distinct().associateWith(::localArtworkSourceRevision),
            "artwork_available" to (!adultArtwork && (!primaryLocalArtwork.isNullOrBlank() || (showCoverCache && !anime.coverCache.isNullOrBlank()))),
            "artwork_local_path" to primaryLocalArtwork,
            "artwork_external_url" to primaryExternalArtwork,
            "artwork_backdrop_local_path" to (backdrop?.localPath ?: localVideoPoster),
            "backdrop_external_url" to backdrop?.externalUrl,
            "playback_target_episode_id" to target?.id,
            "score" to anime.score?.takeIf { visibleField("score") }?.toDouble(),
            "year" to anime.year.takeIf { visibleField("year") },
            "added_at" to anime.addedAt,
            "last_played_at" to lastPlayed,
            "media_kind" to displayKind,
            "metadata_status" to anime.metadataStatus,
            "presentation_state" to null,
        )
        // Do not zip a shortened ID list against mixed legacy/registered names.
        return LibraryUiMappers.anime(projection).copy(genres = preservedGenres)
    }

    private fun episodeMap(
        episode: ReiAnixEpisodeRecord,
        animeId: Long,
        row: ReiAnixLibraryCatalogRow,
        adultArtwork: Boolean,
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
        val artwork = row.episodeArtwork[episode.id].takeUnless { adultArtwork }
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
            "artwork_source_revisions" to listOfNotNull(artwork?.localPath)
                .associateWith(::localArtworkSourceRevision),
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
