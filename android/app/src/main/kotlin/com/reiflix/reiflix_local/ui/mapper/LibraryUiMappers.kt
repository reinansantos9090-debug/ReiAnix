package com.reiflix.reiflix_local.ui.mapper

import com.reiflix.reiflix_local.data.contract.ReiAnixContractValues
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
import com.reiflix.reiflix_local.ui.model.ReiAnixPresentationState
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import org.json.JSONArray
import org.json.JSONException

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
            presentationState = presentationState(
                value = source.stringOrNull("presentation_state")
                    ?: metadata.stringOrNull("presentation_state"),
                artworkAvailable = source.booleanOrNull("artwork_available"),
                metadataAvailable = source.stringOrNull("anilist_id") != null
                    || metadata.stringOrNull("anilist_id") != null
                    || metadata.stringOrNull("metadata_status") != null
                    || source.stringOrNull("metadata_status") != null,
                hasLocalArtwork = artwork(source, metadata)?.isAvailable == true,
                hasCoverUrl = metadata.stringOrNull("cover_url") != null
                    || source.stringOrNull("cover_url") != null,
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
            announcedEpisodeCount = metadata.intOrNull("episodes_count")
                ?: source.intOrNull("episodes_count"),
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
            episodeType = source.stringOrNull("episode_type"),
            manualOverride = source.booleanOrNull("manual_override") ?: false,
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

        fun allNonBlank(keys: List<String>): List<String> =
            sources.asSequence()
                .flatMap { map -> keys.asSequence().mapNotNull { map.stringOrNull(it) } }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()

        fun allCandidateListValues(key: String): List<String> =
            sources.asSequence()
                .flatMap { map -> map.stringList(key).asSequence() }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .toList()

        val hasExplicitPosterLocalCandidates = sources.any { it.containsKey("artwork_local_candidates") }
        val posterLocalCandidates = if (hasExplicitPosterLocalCandidates) {
            // The native projector has already checked current-URL association and ownership.
            // Do not reintroduce a stale cover_cache pointer from nested metadata here.
            (
                allCandidateListValues("artwork_local_candidates") +
                    allNonBlank(listOf("artwork_local_path"))
                ).distinct()
        } else {
            allNonBlank(listOf("artwork_local_path", "cover_cache", "cover", "poster_path", "local_path"))
                .distinct()
        }
        val posterExternalCandidates = (
            allCandidateListValues("artwork_external_candidates") +
                allNonBlank(listOf("artwork_external_url", "cover_url", "external_url"))
            ).distinct()
        val posterLocalPath = if (hasExplicitPosterLocalCandidates) {
            posterLocalCandidates.firstOrNull()
        } else {
            posterLocalCandidates.firstOrNull() ?: firstNonBlank(
                listOf("artwork_local_path", "cover_cache", "cover", "poster_path", "local_path"),
            )
        }
        val hasExplicitPosterExternalCandidates = sources.any { it.containsKey("artwork_external_candidates") }
        val posterExternalUrl = if (hasExplicitPosterExternalCandidates) {
            posterExternalCandidates.firstOrNull()
        } else {
            posterExternalCandidates.firstOrNull() ?: firstNonBlank(
                listOf("artwork_external_url", "cover_url", "external_url"),
            )
        }
        val backdropLocalPath = firstNonBlank(
            listOf("backdrop_local_path", "artwork_backdrop_local_path"),
        )
        val backdropExternalUrl = firstNonBlank(
            listOf("backdrop_external_url", "banner_url"),
        )
        return if (
            posterLocalCandidates.isEmpty() &&
            posterExternalCandidates.isEmpty() &&
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
                localCandidates = posterLocalCandidates,
                externalCandidates = posterExternalCandidates,
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

    private fun presentationState(
        value: String?,
        artworkAvailable: Boolean?,
        metadataAvailable: Boolean,
        hasLocalArtwork: Boolean,
        hasCoverUrl: Boolean,
    ): ReiAnixPresentationState = when (value?.lowercase()) {
        "metadata_missing" -> ReiAnixPresentationState.METADATA_MISSING
        "metadata_ready_artwork_pending", "pending", "not_requested", "queued", "downloading", "retry_wait" ->
            ReiAnixPresentationState.METADATA_READY_ARTWORK_PENDING
        "ready" -> ReiAnixPresentationState.READY
        "artwork_failed", "failed", "invalid" -> ReiAnixPresentationState.ARTWORK_FAILED
        null, "" -> when {
            !metadataAvailable -> ReiAnixPresentationState.METADATA_MISSING
            artworkAvailable == true || (hasLocalArtwork && !hasCoverUrl) -> ReiAnixPresentationState.READY
            hasCoverUrl -> ReiAnixPresentationState.METADATA_READY_ARTWORK_PENDING
            else -> ReiAnixPresentationState.READY
        }
        else -> ReiAnixPresentationState.METADATA_READY_ARTWORK_PENDING
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

    private fun Map<String, Any?>.requiredLong(key: String): Long {
        val value = longOrNull(key) ?: error("Missing required library field: $key")
        require(value > 0L) { "Invalid library identifier '$key': IDs must be positive." }
        return value
    }

    private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> =
        when (val value = this[key]) {
            null -> emptyMap()
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
            else -> error("Invalid library field '$key': expected an object.")
        }

    private fun Map<String, Any?>.stringOrNull(key: String): String? =
        when (val value = this[key]) {
            null -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }
            else -> error("Invalid library field '$key': expected text.")
        }

    private fun Map<String, Any?>.longOrNull(key: String): Long? =
        ReiAnixContractValues.longOrNull(this[key], key)

    private fun Map<String, Any?>.intOrNull(key: String): Int? =
        ReiAnixContractValues.intOrNull(this[key], key)

    private fun Map<String, Any?>.doubleOrNull(key: String): Double? =
        ReiAnixContractValues.doubleOrNull(this[key], key)

    private fun Map<String, Any?>.booleanOrNull(key: String): Boolean? =
        ReiAnixContractValues.booleanOrNull(this[key], key)

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        when (val value = this[key]) {
            is Iterable<*> ->
                value.mapIndexed { index, item ->
                    val text = item as? String
                        ?: error("Invalid library field '$key[$index]': expected text.")
                    text.trim().also {
                        require(it.isNotEmpty()) { "Invalid library field '$key[$index]': text must not be blank." }
                    }
                }
            is Array<*> ->
                value.mapIndexed { index, item ->
                    val text = item as? String
                        ?: error("Invalid library field '$key[$index]': expected text.")
                    text.trim().also {
                        require(it.isNotEmpty()) { "Invalid library field '$key[$index]': text must not be blank." }
                    }
                }
            is String -> {
                val raw = value.trim()
                if (raw.isEmpty()) {
                    emptyList()
                } else {
                    val array = try {
                        JSONArray(raw)
                    } catch (error: JSONException) {
                        if (raw.startsWith("[")) {
                            throw IllegalArgumentException("Malformed JSON list in library field '$key'.", error)
                        }
                        null
                    }
                    if (array == null) {
                        // Preserve the legacy single-string representation; valid JSON
                        // arrays, however, must pass element validation without fallback.
                        listOf(raw)
                    } else {
                        (0 until array.length()).map { index ->
                            val item = array.opt(index)
                            val text = item as? String
                                ?: error("Invalid library field '$key[$index]': expected text.")
                            text.trim().also {
                                require(it.isNotEmpty()) {
                                    "Invalid library field '$key[$index]': text must not be blank."
                                }
                            }
                        }
                    }
                }
            }
            null -> emptyList()
            else -> error("Invalid library field '$key': expected a list or legacy string.")
        }

    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> {
        fun convert(items: Iterable<*>): List<Map<String, Any?>> =
            items.mapIndexed { index, item ->
                val map = item as? Map<*, *>
                    ?: error("Invalid library field '$key[$index]': expected an object.")
                map.entries.associate { (k, v) -> k.toString() to v }
            }

        return when (val value = this[key]) {
            null -> emptyList()
            is Iterable<*> -> convert(value)
            is Array<*> -> convert(value.asIterable())
            else -> error("Invalid library field '$key': expected an array.")
        }
    }
}
