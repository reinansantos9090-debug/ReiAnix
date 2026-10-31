package com.reiflix.reiflix_local.data.library

import android.graphics.BitmapFactory
import android.os.Build
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixArtworkRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryCatalogRow
import com.reiflix.reiflix_local.ui.mapper.LibraryUiMappers
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import org.json.JSONArray
import java.io.File

/** A single canonical SQLite read projected into the existing immutable Compose models. */
internal data class ReiAnixNativeLibraryCatalogSnapshot(
    val animes: List<ReiAnixAnimeUiModel>,
    val continueWatching: List<ReiAnixContinueWatchingUiModel>,
)

internal object ReiAnixNativeLibraryCatalogProjector {
    private val specialTypes = setOf("special", "ova", "oad", "ona", "extra")

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
        val animeArtwork = row.animeArtwork.map(::withoutInvalidLocalArtwork)
        val coverCache = usableLocalArtworkPath(anime.coverCache)
        val backdrop = animeArtwork.firstOrNull {
            it.artworkType.equals("backdrop", ignoreCase = true) ||
                it.artworkType.equals("banner", ignoreCase = true)
        }
        val posterCandidates = animeArtwork.filterNot {
            it.artworkType.equals("backdrop", ignoreCase = true) ||
                it.artworkType.equals("banner", ignoreCase = true)
        }
        val primaryLocalArtworkRecord = posterCandidates.firstOrNull { !it.localPath.isNullOrBlank() }
        val primaryLocalArtwork = primaryLocalArtworkRecord?.localPath ?: coverCache
        val primaryExternalArtwork = posterCandidates.firstOrNull { !it.externalUrl.isNullOrBlank() }?.externalUrl
            ?: anime.coverUrl
        val primaryArtworkRevision = primaryLocalArtworkRecord?.let(::artworkRevision)
        val backdropArtworkRevision = backdrop?.let(::artworkRevision)
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
            "cover_cache" to coverCache,
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
            "artwork_available" to !primaryLocalArtwork.isNullOrBlank(),
            "artwork_local_path" to primaryLocalArtwork,
            "artwork_external_url" to primaryExternalArtwork,
            "artwork_revision" to primaryArtworkRevision,
            "artwork_backdrop_local_path" to backdrop?.localPath,
            "backdrop_external_url" to backdrop?.externalUrl,
            "artwork_backdrop_revision" to backdropArtworkRevision,
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
            "artwork_revision" to artwork?.let(::artworkRevision),
        )
    }

    /**
     * The SQLite row is authoritative for artwork identity, but a stale/corrupt local file
     * must never be projected as a renderable image. URI-backed sources remain delegated
     * to the existing Coil resolver; filesystem paths are decoded before they reach Compose.
     */
    private fun withoutInvalidLocalArtwork(record: ReiAnixArtworkRecord): ReiAnixArtworkRecord {
        val path = record.localPath ?: return record
        val validPath = usableLocalArtworkPath(path)
        return if (validPath == path) record else record.copy(localPath = validPath)
    }

    private fun usableLocalArtworkPath(rawPath: String?): String? {
        val path = rawPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val scheme = path.substringBefore(':', missingDelimiterValue = "")
        if (scheme.equals("content", true) ||
            scheme.equals("android.resource", true) ||
            scheme.equals("file", true) ||
            scheme.equals("http", true) ||
            scheme.equals("https", true)
        ) return path

        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        val extension = file.extension.lowercase()
        if (extension == "avif" && Build.VERSION.SDK_INT < 31) {
            val header = ByteArray(12)
            val count = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
            return path.takeIf {
                count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > 1024) {
            sampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        bitmap.recycle()
        return path
    }

    private fun artworkRevision(record: ReiAnixArtworkRecord): String? {
        record.checksum?.trim()?.takeIf { it.isNotEmpty() }?.let { return "sha256:$it" }
        return buildList {
            record.artworkKey?.trim()?.takeIf { it.isNotEmpty() }?.let { add("key:$it") }
            record.byteSize?.let { add("bytes:$it") }
            record.updatedAt?.let { add("updated:$it") }
            record.localPath?.trim()?.takeIf { it.isNotEmpty() }?.let { add("path:$it") }
        }.takeIf { it.isNotEmpty() }?.joinToString("|")
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
