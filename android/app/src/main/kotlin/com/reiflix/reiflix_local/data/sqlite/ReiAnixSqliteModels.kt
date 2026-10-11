package com.reiflix.reiflix_local.data.sqlite

import android.database.Cursor

/**
 * Persistence records mirror columns in LibraryStore's existing schema.
 * JSON/text columns remain raw strings so unknown keys and null values survive.
 */
data class ReiAnixAnimeRecord(
    val id: Long,
    val lookupTitle: String,
    val anilistId: Long?,
    val title: String,
    val romaji: String?,
    val english: String?,
    val nativeTitle: String?,
    val aliasesJson: String?,
    val description: String?,
    val descriptionOriginal: String?,
    val coverUrl: String?,
    val coverCache: String?,
    val bannerUrl: String?,
    val genresJson: String?,
    val year: Int?,
    val season: String?,
    val status: String?,
    val episodesCount: Int?,
    val duration: Int?,
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
    val isPinned: Boolean?,
    val personalNote: String?,
    val addedAt: Double,
    val anilistMatchStatus: String?,
    val anilistMatchScore: Double?,
    val anilistMatchMargin: Double?,
    val anilistMatchManual: Boolean?,
)

data class ReiAnixEpisodeRecord(
    val id: Long,
    val animeId: Long,
    val path: String,
    val fileName: String,
    val season: Int,
    val number: Double?,
    val duration: Double?,
    val progress: Double?,
    val watched: Boolean?,
    val mimeType: String?,
    val fileSize: Long?,
    val modifiedAt: Double?,
    val sourceFolder: String?,
    val absoluteNumber: Double?,
    val relativePath: String?,
    val volumeId: String?,
    val volumeUuid: String?,
    val episodeType: String?,
    val episodeTitle: String?,
    val identificationSource: String?,
    val identificationConfidence: String?,
    val manualOverride: Boolean?,
    val missing: Boolean?,
    val lastPlayedAt: Double?,
    val mediaIdentity: String?,
    val availabilityState: String?,
)

data class ReiAnixFolderRecord(
    val path: String,
    val name: String?,
    val kind: String?,
    val authorization: String?,
    val accountId: String?,
    val addedAt: Double?,
    val lastScanAt: Double?,
    val lastError: String?,
    val safAuthority: String?,
    val safDocumentId: String?,
    val safVolumeId: String?,
    val safIdentity: String?,
)

data class ReiAnixPreferenceRecord(
    val key: String,
    val value: String,
    val updatedAt: Double,
)

data class ReiAnixArtworkRecord(
    val id: Long,
    val entityType: String,
    val entityId: String,
    val artworkType: String,
    val source: String,
    val sourceRef: String?,
    val localPath: String?,
    val externalUrl: String?,
    val manual: Boolean?,
    val priority: Int?,
    val status: String?,
    val discoveredAt: Double?,
    val updatedAt: Double?,
    val lastAttemptAt: Double?,
    val failureCount: Int?,
    val artworkKey: String?,
    val variant: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val checksum: String?,
    val contentType: String?,
    val lastAccess: Double?,
    val nextRetryAt: Double?,
    val httpStatus: Int?,
)

/** Values written by the native player use the same units as episodes.progress/duration. */
data class ReiAnixPlaybackProgressUpdate(
    val episodeId: Long,
    val progress: Double,
    val duration: Double? = null,
    val watched: Boolean? = null,
    val playedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
) {
    init {
        require(episodeId > 0L) { "episodeId must be a positive persisted ID." }
        require(progress.isFinite() && progress >= 0.0) {
            "progress must be finite and non-negative."
        }
        require(duration == null || (duration.isFinite() && duration >= 0.0)) {
            "duration must be null or finite and non-negative."
        }
        require(playedAtEpochSeconds.isFinite() && playedAtEpochSeconds >= 0.0) {
            "playedAtEpochSeconds must be finite and non-negative."
        }
    }
}

internal fun Cursor.requiredString(column: String): String =
    getString(getColumnIndexOrThrow(column))
        ?: throw IllegalStateException("Required SQLite text column is null: " + column)

internal fun Cursor.requiredLong(column: String): Long {
    val index = getColumnIndexOrThrow(column)
    check(!isNull(index)) { "Required SQLite integer column is null: " + column }
    return getLong(index)
}

internal fun Cursor.requiredDouble(column: String): Double {
    val index = getColumnIndexOrThrow(column)
    check(!isNull(index)) { "Required SQLite numeric column is null: " + column }
    return getDouble(index)
}

internal fun Cursor.optionalString(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

internal fun Cursor.optionalLong(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

internal fun Cursor.optionalInt(column: String): Int? = optionalLong(column)?.toInt()

internal fun Cursor.optionalDouble(column: String): Double? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getDouble(index)
}

internal fun Cursor.optionalBoolean(column: String): Boolean? =
    optionalLong(column)?.let { it != 0L }
