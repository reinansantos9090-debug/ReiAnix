package com.reiflix.reiflix_local.ui.mapper

import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import org.json.JSONArray

/**
 * Explicit adapters from the existing Python/SQLite catalog projection to the
 * immutable Compose presentation models. No persistence or business rules live here.
 */
object LibraryUiMappers {

    fun anime(source: Map<String, Any?>): ReiAnixAnimeUiModel {
        val id = source.requiredLong("id")
        val metadata = source.mapValue("meta")
        val title = source.stringOrNull("main_title")
            ?: metadata.stringOrNull("title")
            ?: source.stringOrNull("title")
            ?: source.requiredString("lookup_title")

        return ReiAnixAnimeUiModel(
            id = id,
            title = title,
            year = metadata.intOrNull("year") ?: source.intOrNull("year"),
            genres = genres(source),
            favorite = source.booleanOrNull("favorite") ?: metadata.booleanOrNull("favorite") ?: false,
            mediaKind = mediaKind(
                source.stringOrNull("media_kind") ?: metadata.stringOrNull("media_kind"),
            ),
            artwork = artwork(source, metadata),
            metadataAvailability = metadataAvailability(
                metadata.stringOrNull("metadata_status")
                    ?: source.stringOrNull("metadata_status"),
            ),
            seasons = source.listOfMaps("seasons").map { season(it, id) },
            specials = source.listOfMaps("specials")
                .flatMap { it.listOfMaps("episodes") }
                .map { episode(it, id) },
            mediaFiles = source.listOfMaps("media_files").map { episode(it, id) },
            playbackTargetEpisodeId = source.longOrNull("playback_target_episode_id"),
            score = metadata.doubleOrNull("score") ?: source.doubleOrNull("score"),
            addedAt = metadata.doubleOrNull("added_at") ?: source.doubleOrNull("added_at"),
            lastPlayedAt = source.doubleOrNull("last_played_at"),
            pinned = source.booleanOrNull("is_pinned") ?: metadata.booleanOrNull("is_pinned") ?: false,
            // description is the canonical presentation value persisted by
            // LibraryService. A missing value means the pt-BR synopsis is not ready.
            description = metadata.stringOrNull("description"),
            romajiTitle = metadata.stringOrNull("romaji"),
            englishTitle = metadata.stringOrNull("english"),
            nativeTitle = metadata.stringOrNull("native"),
            aliases = metadata.stringList("aliases"),
            userTags = source.stringList("user_tags"),
            personalNote = source.stringOrNull("personal_note"),
            status = metadata.stringOrNull("status"),
            format = metadata.stringOrNull("format"),
            durationMinutes = metadata.intOrNull("duration"),
            studio = metadata.stringOrNull("studio"),
            seasonLabel = metadata.stringOrNull("season"),
        )
    }

    fun continueWatching(source: Map<String, Any?>): ReiAnixContinueWatchingUiModel {
        val episodeId = source.longOrNull("episode_id") ?: source.requiredLong("id")
        return ReiAnixContinueWatchingUiModel(
            episodeId = episodeId,
            animeId = source.requiredLong("anime_id"),
            animeTitle = source.requiredString("anime_title"),
            seasonNumber = source.intOrNull("season"),
            number = source.doubleOrNull("number"),
            title = source.stringOrNull("episode_title"),
            fileName = source.requiredString("file_name", fallback = "title"),
            progressSeconds = source.doubleOrNull("progress"),
            durationSeconds = source.doubleOrNull("duration"),
            artwork = artwork(source),
        )
    }

    fun season(source: Map<String, Any?>, animeId: Long): ReiAnixSeasonUiModel =
        ReiAnixSeasonUiModel(
            animeId = animeId,
            number = source.intOrNull("season"),
            title = source.stringOrNull("season_name") ?: "Temporada",
            episodes = source.listOfMaps("episodes").map { episode(it, animeId) },
        )

    fun episode(
        source: Map<String, Any?>,
        animeIdOverride: Long? = null,
    ): ReiAnixEpisodeUiModel {
        val animeId = animeIdOverride ?: source.requiredLong("anime_id")
        return ReiAnixEpisodeUiModel(
            id = source.requiredLong("id"),
            animeId = animeId,
            seasonNumber = source.intOrNull("season"),
            number = source.doubleOrNull("number"),
            title = source.stringOrNull("episode_title"),
            fileName = source.requiredString("file_name", fallback = "title"),
            media = localMedia(source),
            progressSeconds = source.doubleOrNull("progress"),
            durationSeconds = source.doubleOrNull("duration"),
            watched = source.booleanOrNull("watched"),
            consumptionState = consumptionState(source.stringOrNull("consumption_state")),
            artwork = artwork(source),
            lastPlayedAt = source.doubleOrNull("last_played_at"),
            modifiedAt = source.doubleOrNull("modified_at"),
            fileSizeBytes = source.longOrNull("file_size"),
        )
    }

    private fun genres(source: Map<String, Any?>): List<ReiAnixGenreUiModel> {
        val names = source.stringList("genres")
        val ids = source.stringList("genre_ids")
        return names.mapIndexed { index, name ->
            ReiAnixGenreUiModel(
                id = ids.getOrNull(index),
                name = name,
            )
        }
    }

    private fun artwork(vararg sources: Map<String, Any?>): ReiAnixArtworkUiModel? {
        fun firstNonBlank(keys: List<String>): String? =
            sources.asSequence()
                .flatMap { map -> keys.asSequence().mapNotNull { map.stringOrNull(it) } }
                .firstOrNull { it.isNotBlank() }

        val posterLocalPath = firstNonBlank(
            listOf("artwork_local_path", "cover_cache", "cover", "poster_path", "local_path"),
        )
        val posterExternalUrl = firstNonBlank(
            listOf("artwork_external_url", "cover_url", "external_url"),
        )
        val backdropLocalPath = firstNonBlank(
            listOf("backdrop_local_path", "artwork_backdrop_local_path"),
        )
        val backdropExternalUrl = firstNonBlank(
            listOf("backdrop_external_url", "banner_url"),
        )
        return if (
            posterLocalPath == null &&
            posterExternalUrl == null &&
            backdropLocalPath == null &&
            backdropExternalUrl == null
        ) {
            null
        } else {
            ReiAnixArtworkUiModel(
                localPath = posterLocalPath,
                externalUrl = posterExternalUrl,
                backdropLocalPath = backdropLocalPath,
                backdropExternalUrl = backdropExternalUrl,
            )
        }
    }

    private fun localMedia(source: Map<String, Any?>): ReiAnixLocalMediaUiModel {
        val reference = source.stringOrNull("path") ?: source.stringOrNull("uri")
        val isContentUri = reference?.startsWith("content://", ignoreCase = true) == true
        val sourceAvailabilityState = source.stringOrNull("availability_state")
        val missing = source.booleanOrNull("missing") == true
        val availability = when {
            missing -> ReiAnixMediaAvailability.MISSING
            else -> when (sourceAvailabilityState?.lowercase()) {
                "available" -> ReiAnixMediaAvailability.AVAILABLE
                "missing" -> ReiAnixMediaAvailability.MISSING
                "scope_unavailable" -> ReiAnixMediaAvailability.SCOPE_UNAVAILABLE
                "volume_unavailable" -> ReiAnixMediaAvailability.VOLUME_UNAVAILABLE
                null, "" -> ReiAnixMediaAvailability.UNKNOWN
                else -> ReiAnixMediaAvailability.UNKNOWN
            }
        }
        return ReiAnixLocalMediaUiModel(
            reference = reference,
            uri = reference?.takeIf { isContentUri },
            path = reference?.takeUnless { isContentUri },
            mediaIdentity = source.stringOrNull("media_identity"),
            sourceAvailabilityState = sourceAvailabilityState,
            availability = availability,
        )
    }

    private fun mediaKind(value: String?): ReiAnixMediaKind = when (value?.lowercase()) {
        "series", "anime" -> ReiAnixMediaKind.SERIES
        "movie", "film" -> ReiAnixMediaKind.MOVIE
        else -> ReiAnixMediaKind.UNKNOWN
    }

    private fun consumptionState(value: String?): ReiAnixConsumptionState? = when (value?.lowercase()) {
        "unwatched" -> ReiAnixConsumptionState.UNWATCHED
        "in_progress" -> ReiAnixConsumptionState.IN_PROGRESS
        "completed" -> ReiAnixConsumptionState.COMPLETED
        "watched" -> ReiAnixConsumptionState.WATCHED
        null, "" -> null
        else -> ReiAnixConsumptionState.UNKNOWN
    }

    private fun metadataAvailability(value: String?): ReiAnixMetadataAvailability = when (value?.lowercase()) {
        "available" -> ReiAnixMetadataAvailability.AVAILABLE
        "stale" -> ReiAnixMetadataAvailability.STALE
        "manual" -> ReiAnixMetadataAvailability.MANUAL
        "ambiguous" -> ReiAnixMetadataAvailability.AMBIGUOUS
        "unresolved", null, "" -> ReiAnixMetadataAvailability.UNRESOLVED
        "error" -> ReiAnixMetadataAvailability.ERROR
        else -> ReiAnixMetadataAvailability.UNKNOWN
    }

    private fun Map<String, Any?>.requiredString(
        key: String,
        fallback: String? = null,
    ): String =
        stringOrNull(key)?.takeIf { it.isNotBlank() }
            ?: fallback?.let { stringOrNull(it)?.takeIf(String::isNotBlank) }
            ?: error("Missing required library field: $key")

    private fun Map<String, Any?>.requiredLong(key: String): Long =
        longOrNull(key) ?: error("Missing required library field: $key")

    private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> =
        (this[key] as? Map<*, *>)?.entries
            ?.associate { (k, v) -> k.toString() to v }
            ?: emptyMap()

    private fun Map<String, Any?>.stringOrNull(key: String): String? =
        when (val value = this[key]) {
            null -> null
            is String -> value
            else -> value.toString()
        }?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.longOrNull(key: String): Long? = when (val value = this[key]) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun Map<String, Any?>.intOrNull(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun Map<String, Any?>.doubleOrNull(key: String): Double? = when (val value = this[key]) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun Map<String, Any?>.booleanOrNull(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
        else -> null
    }

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        when (val value = this[key]) {
            is Iterable<*> ->
                value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is Array<*> ->
                value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> {
                val raw = value.trim()
                if (raw.isEmpty()) {
                    emptyList()
                } else {
                    runCatching {
                        JSONArray(raw).let { array ->
                            (0 until array.length())
                                .mapNotNull { index ->
                                    array.optString(index).trim().takeIf(String::isNotEmpty)
                                }
                        }
                    }.getOrElse {
                        listOf(raw)
                    }
                }
            }
            else -> emptyList()
        }

    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> =
        when (val value = this[key]) {
            is Iterable<*> ->
                value.mapNotNull { item ->
                    (item as? Map<*, *>)?.entries
                        ?.associate { (k, v) -> k.toString() to v }
                }
            is Array<*> ->
                value.mapNotNull { item ->
                    (item as? Map<*, *>)?.entries
                        ?.associate { (k, v) -> k.toString() to v }
                }
            else -> emptyList()
        }
}
