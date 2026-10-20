package com.reiflix.reiflix_local.data.media

/** Domain identifiers mirror existing persisted identifiers; they create no new database keys. */
@JvmInline
value class AnimeRecordId(val value: Long) {
    init { require(value > 0L) { "Anime record ID must be positive." } }
}

@JvmInline
value class EpisodeRecordId(val value: Long) {
    init { require(value > 0L) { "Episode record ID must be positive." } }
}

@JvmInline
value class AniListCatalogId(val value: Long) {
    init { require(value > 0L) { "AniList catalog ID must be positive." } }
}

/** Construct this only when the source supplies stable location evidence. */
@JvmInline
value class CanonicalMediaIdentity(val value: String) {
    init { require(value.isNotBlank()) { "Canonical media identity cannot be blank." } }
}

/**
 * Catalogue anime and local episode records are separate concepts.
 * Raw JSON columns are retained so legacy and user-owned data survives projection.
 */
data class Anime(
    val id: AnimeRecordId,
    val lookupKey: String,
    val aniListId: AniListCatalogId?,
    val title: String,
    val romajiTitle: String?,
    val englishTitle: String?,
    val nativeTitle: String?,
    val aliasesJson: String?,
    val description: String?,
    val originalDescription: String?,
    val coverUrl: String?,
    val coverCacheReference: String?,
    val bannerUrl: String?,
    val genresJson: String?,
    val year: Int?,
    val season: String?,
    val status: String?,
    val episodeCount: Int?,
    val durationMinutes: Int?,
    val score: Int?,
    val format: String?,
    val studio: String?,
    val metadataUpdatedAt: Double?,
    val metadataFetchedAt: Double?,
    val metadataSource: String?,
    val metadataConfidence: String?,
    val metadataStatus: String?,
    val metadataManualFieldsJson: String?,
    val favorite: Boolean?,
    val userTagsJson: String?,
    val mediaKind: String?,
    val pinned: Boolean?,
    val personalNote: String?,
    val addedAtEpochSeconds: Double,
    val aniListMatchStatus: String?,
    val aniListMatchScore: Double?,
    val aniListMatchMargin: Double?,
    val aniListMatchManual: Boolean?,
)

/**
 * A row in the current SQLite episodes table carries episode metadata and its
 * current local-file reference. This does not claim there is a separate media-file PK.
 */
data class Episode(
    val id: EpisodeRecordId,
    val animeId: AnimeRecordId,
    val seasonNumber: Int,
    val number: Double?,
    val durationSeconds: Double?,
    val progressSeconds: Double?,
    val watched: Boolean?,
    val media: LocalMediaReference,
    val absoluteNumber: Double?,
    val episodeType: String?,
    val title: String?,
    val identificationSource: String?,
    val identificationConfidence: String?,
    val manualOverride: Boolean?,
    val missing: Boolean?,
    val lastPlayedAtEpochSeconds: Double?,
    val availabilityState: String?,
) {
    /** Never infer logical episode identity from a title or file name alone. */
    val logicalIdentity: LogicalEpisodeIdentity?
        get() {
            val episodeNumber = number?.takeIf { it.isFinite() && it > 0.0 }
            val absolute = absoluteNumber?.takeIf { it.isFinite() && it > 0.0 }
            if (episodeNumber == null && absolute == null) return null
            return LogicalEpisodeIdentity(
                animeId = animeId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                absoluteNumber = absolute,
                episodeType = episodeType?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(),
            )
        }
}

data class LogicalEpisodeIdentity(
    val animeId: AnimeRecordId,
    val seasonNumber: Int,
    val episodeNumber: Double?,
    val absoluteNumber: Double?,
    val episodeType: String?,
)

/**
 * Represents a location, not an immutable identity. storedLocation preserves
 * the exact legacy value; SAF/content URIs may be revoked and are not stable IDs.
 */
data class LocalMediaReference(
    val episodeRecordId: EpisodeRecordId?,
    val storedLocation: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAtEpochSeconds: Double?,
    val sourceFolder: String?,
    val relativePath: String?,
    val volumeId: String?,
    val volumeUuid: String?,
    val identity: CanonicalMediaIdentity?,
    val availabilityState: String?,
) {
    val uri: String?
        get() = storedLocation.takeIf(MediaReferenceSyntax::hasUriScheme)

    val filesystemPath: String?
        get() = storedLocation.takeUnless(MediaReferenceSyntax::hasUriScheme)
}

object MediaReferenceSyntax {
    private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    fun hasUriScheme(value: String): Boolean = uriScheme.containsMatchIn(value.trim())
}

data class ArtworkReference(
    val id: Long,
    val entityType: String,
    val entityId: String,
    val artworkType: String,
    val source: String,
    val sourceReference: String?,
    val localPath: String?,
    val externalUrl: String?,
    val manual: Boolean?,
    val priority: Int?,
    val status: String?,
    val discoveredAtEpochSeconds: Double?,
    val updatedAtEpochSeconds: Double?,
    val lastAttemptAtEpochSeconds: Double?,
    val failureCount: Int?,
    val artworkKey: String?,
    val variant: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val checksum: String?,
    val contentType: String?,
    val lastAccessEpochSeconds: Double?,
    val nextRetryAtEpochSeconds: Double?,
    val httpStatus: Int?,
)

data class PlaybackProgress(
    val episodeId: EpisodeRecordId,
    val positionSeconds: Double?,
    val durationSeconds: Double?,
    val watched: Boolean?,
    val lastPlayedAtEpochSeconds: Double?,
)
