package com.reiflix.reiflix_local.ui.model

/**
 * Immutable presentation models for the existing local library projection.
 *
 * These types intentionally contain only data the Compose UI needs. They are
 * not persistence entities and never become a second source of truth.
 */

enum class ReiAnixMediaKind {
    SERIES,
    MOVIE,
    UNKNOWN,
}

enum class ReiAnixConsumptionState {
    UNWATCHED,
    IN_PROGRESS,
    COMPLETED,
    WATCHED,
    UNKNOWN,
}

enum class ReiAnixMediaAvailability {
    AVAILABLE,
    MISSING,
    SCOPE_UNAVAILABLE,
    VOLUME_UNAVAILABLE,
    UNKNOWN,
}

enum class ReiAnixMetadataAvailability {
    AVAILABLE,
    STALE,
    MANUAL,
    AMBIGUOUS,
    UNRESOLVED,
    ERROR,
    UNKNOWN,
}

data class ReiAnixGenreUiModel(
    val id: String?,
    val name: String,
) {
    val stableKey: String
        get() = id?.takeIf { it.isNotBlank() } ?: "name:" + name.trim().lowercase()
}

data class ReiAnixArtworkUiModel(
    val localPath: String?,
    val externalUrl: String?,
    val backdropLocalPath: String? = null,
    val backdropExternalUrl: String? = null,
) {
    val isAvailable: Boolean
        get() = !localPath.isNullOrBlank() || !externalUrl.isNullOrBlank()
}

data class ReiAnixLocalMediaUiModel(
    /** The original local reference exactly as supplied by the source projection. */
    val reference: String?,
    /** Derived only when the original reference is a content URI. */
    val uri: String?,
    /** Derived only when the original reference is a filesystem path. */
    val path: String?,
    /** Persisted/stable media identity from the source, when available. */
    val mediaIdentity: String?,
    /** Exact source availability_state, retained so unknown values are not discarded. */
    val sourceAvailabilityState: String?,
    val availability: ReiAnixMediaAvailability,
)

data class ReiAnixEpisodeUiModel(
    val id: Long,
    val animeId: Long,
    val seasonNumber: Int?,
    val number: Double?,
    val title: String?,
    val fileName: String,
    val media: ReiAnixLocalMediaUiModel,
    val progressSeconds: Double?,
    val durationSeconds: Double?,
    val watched: Boolean?,
    val consumptionState: ReiAnixConsumptionState?,
    val artwork: ReiAnixArtworkUiModel?,
    val lastPlayedAt: Double? = null,
    val modifiedAt: Double? = null,
    val fileSizeBytes: Long? = null,
) {
    /**
     * Episode identity is the persisted SQLite primary key. It is global and
     * remains stable across progress-only snapshot updates.
     */
    val stableKey: String
        get() = "episode:" + id

    val isCompleted: Boolean
        get() = consumptionState == ReiAnixConsumptionState.COMPLETED ||
            consumptionState == ReiAnixConsumptionState.WATCHED

    val isWatched: Boolean
        get() = watched == true || isCompleted

    /** Presentation-only progress fraction for the fixed progress slot in each card. */
    val progressFraction: Float
        get() {
            val duration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 } ?: return 0f
            val progress = (progressSeconds ?: 0.0).coerceAtLeast(0.0)
            return (progress / duration).coerceIn(0.0, 1.0).toFloat()
        }

    val progressPercent: Int?
        get() = durationSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (progressFraction * 100.0).toInt() }

    /**
     * Single Compose presentation label derived only from the canonical
     * persisted consumption state/availability. It never becomes state of its own.
     */
    val playbackActionLabel: String
        get() = when {
            !isPlayable -> "Indisponível"
            isCompleted -> "Reassistir"
            consumptionState == ReiAnixConsumptionState.IN_PROGRESS -> "Continuar"
            else -> "Assistir"
        }

    /**
     * Playback remains owned by the existing Python/Android bridge. This is
     * only a presentation guard for obviously unavailable local media.
     */
    val isPlayable: Boolean
        get() = !media.reference.isNullOrBlank() &&
            media.availability !in setOf(
                ReiAnixMediaAvailability.MISSING,
                ReiAnixMediaAvailability.SCOPE_UNAVAILABLE,
                ReiAnixMediaAvailability.VOLUME_UNAVAILABLE,
            )

    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: fileName
}

data class ReiAnixContinueWatchingUiModel(
    val episodeId: Long,
    val animeId: Long,
    val animeTitle: String,
    val seasonNumber: Int?,
    val number: Double?,
    val title: String?,
    val fileName: String,
    val progressSeconds: Double?,
    val durationSeconds: Double?,
    val artwork: ReiAnixArtworkUiModel?,
) {
    val stableKey: String
        get() = "episode:" + episodeId

    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: fileName
}

data class ReiAnixSeasonUiModel(
    /** Seasons have no independent SQLite ID in the current source projection. */
    val animeId: Long,
    val number: Int?,
    val title: String,
    val episodes: List<ReiAnixEpisodeUiModel>,
) {
    /** Stable UI key derived from existing identity without persisting a new ID. */
    val stableKey: String
        get() {
            val normalizedTitle = title
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
                .takeIf { it.isNotEmpty() }
            val identity = number?.toString()
                ?: normalizedTitle?.let { "title:$it" }
                ?: "special"
            return "anime:" + animeId + ":season:" + identity
        }
}

data class ReiAnixAnimeUiModel(
    val id: Long,
    val title: String,
    val year: Int?,
    val genres: List<ReiAnixGenreUiModel>,
    val favorite: Boolean,
    val mediaKind: ReiAnixMediaKind,
    val artwork: ReiAnixArtworkUiModel?,
    val metadataAvailability: ReiAnixMetadataAvailability,
    val seasons: List<ReiAnixSeasonUiModel>,
    val specials: List<ReiAnixEpisodeUiModel>,
    val mediaFiles: List<ReiAnixEpisodeUiModel>,
    val playbackTargetEpisodeId: Long? = null,
    /** Source score in the library metadata (0..100), when actually stored. */
    val score: Double? = null,
    /** Canonical local-library timestamps used only for existing sort semantics. */
    val addedAt: Double? = null,
    val lastPlayedAt: Double? = null,
    val pinned: Boolean = false,
    // Editorial metadata comes from the existing SQLite/AniList-derived projection.
    // Defaults keep older library screens source-compatible.
    val description: String? = null,
    val romajiTitle: String? = null,
    val englishTitle: String? = null,
    val nativeTitle: String? = null,
    /** Persisted alternate titles used by the canonical local search projection. */
    val aliases: List<String> = emptyList(),
    /** Existing local user tags; presentation only and never a second source of truth. */
    val userTags: List<String> = emptyList(),
    /** Existing local note; searchable only because it is already canonical library data. */
    val personalNote: String? = null,
    val status: String? = null,
    val format: String? = null,
    val durationMinutes: Int? = null,
    val studio: String? = null,
    val seasonLabel: String? = null,
) {
    val stableKey: String
        get() = "anime:" + id

    val contentEpisodes: List<ReiAnixEpisodeUiModel>
        get() = buildList {
            seasons.forEach { season -> addAll(season.episodes) }
            addAll(specials)
            addAll(mediaFiles)
        }

    val availableContentCount: Int
        get() = contentEpisodes.count { it.media.availability == ReiAnixMediaAvailability.AVAILABLE }

    val episodeCountLabel: String
        get() = when (availableContentCount) {
            1 -> "1 episódio"
            else -> "$availableContentCount episódios"
        }

    val isWatching: Boolean
        get() = contentEpisodes.any {
            it.consumptionState == ReiAnixConsumptionState.IN_PROGRESS
        }

    val isCompleted: Boolean
        get() = contentEpisodes.isNotEmpty() &&
            contentEpisodes.all { episode ->
                episode.media.availability != ReiAnixMediaAvailability.MISSING &&
                    episode.media.availability != ReiAnixMediaAvailability.SCOPE_UNAVAILABLE &&
                    episode.media.availability != ReiAnixMediaAvailability.VOLUME_UNAVAILABLE &&
                    episode.isCompleted
            }
}

data class ReiAnixHomeAnimeUiModel(
    val id: Long,
    val title: String,
    val year: Int?,
    val genres: List<ReiAnixGenreUiModel>,
    val favorite: Boolean,
    val mediaKind: ReiAnixMediaKind,
    val artwork: ReiAnixArtworkUiModel?,
    val playbackTargetEpisodeId: Long?,
    val availableContentCount: Int,
    val playbackActionLabel: String = "Assistir",
    val isWatching: Boolean = false,
    val score: Double? = null,
    val addedAt: Double? = null,
    val lastPlayedAt: Double? = null,
    val pinned: Boolean = false,
    val description: String? = null,
    val status: String? = null,
    val format: String? = null,
    val studio: String? = null,
) {
    val stableKey: String
        get() = "anime:" + id
}
