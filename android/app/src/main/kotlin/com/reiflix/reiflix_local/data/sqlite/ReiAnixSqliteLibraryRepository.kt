package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native repository against the existing canonical library.sqlite3.
 *
 * This is not a second catalog. Callers that migrate a mutation here must stop
 * publishing that same mutation through the legacy Python mailbox.
 */
class ReiAnixSqliteLibraryRepository(
    private val database: ReiAnixSqliteDatabase,
) {
    constructor(context: Context) : this(ReiAnixSqliteDatabase(context))

    suspend fun inspectSchema(): ReiAnixSqliteSchemaSnapshot = withContext(Dispatchers.IO) {
        database.inspectSchema()
    }

    suspend fun quickCheck(): List<String> = withContext(Dispatchers.IO) {
        database.quickCheck()
    }

    suspend fun getAnime(id: Long): ReiAnixAnimeRecord? = withContext(Dispatchers.IO) {
        require(id > 0L) { "Anime ID must be a positive persisted ID." }
        database.read { connection ->
            connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(id.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toAnimeRecord() else null
            }
        }
    }

    suspend fun listAnime(limit: Int = 36, offset: Int = 0): List<ReiAnixAnimeRecord> =
        withContext(Dispatchers.IO) {
            require(limit in 1..200) { "Page size must be between 1 and 200." }
            require(offset >= 0) { "Offset cannot be negative." }
            database.read { connection ->
                connection.rawQuery(
                    "SELECT * FROM anime " +
                        "ORDER BY added_at DESC, title COLLATE NOCASE ASC, id DESC LIMIT ? OFFSET ?",
                    arrayOf(limit.toString(), offset.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toAnimeRecord())
                    }
                }
            }
        }


    /**
     * Updates supplied metadata for an existing anime in one transaction.
     * Null patch members are no-ops. Stable IDs and user-owned fields remain untouched.
     */
    suspend fun updateAnimeMetadata(
        patch: ReiAnixAnimeMetadataPatch,
    ): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
        database.transaction { connection ->
            val values = ContentValues()
            patch.title?.let { values.put("title", it.trim()) }
            patch.anilistId?.let { values.put("anilist_id", it) }
            patch.romaji?.let { values.put("romaji", it) }
            patch.english?.let { values.put("english", it) }
            patch.nativeTitle?.let { values.put("native", it) }
            patch.aliasesJson?.let { values.put("aliases", it) }
            patch.description?.let { values.put("description", it) }
            patch.coverUrl?.let { values.put("cover_url", it) }
            patch.coverCache?.let { values.put("cover_cache", it) }
            patch.bannerUrl?.let { values.put("banner_url", it) }
            patch.genresJson?.let { values.put("genres", it) }
            patch.year?.let { values.put("year", it) }
            patch.season?.let { values.put("season", it) }
            patch.status?.let { values.put("status", it) }
            patch.episodesCount?.let { values.put("episodes_count", it) }
            patch.duration?.let { values.put("duration", it) }
            patch.score?.let { values.put("score", it) }
            patch.format?.let { values.put("format", it) }
            patch.studio?.let { values.put("studio", it) }
            patch.metadataFetchedAt?.let { values.put("metadata_fetched_at", it) }
            patch.metadataSource?.let { values.put("metadata_source", it) }
            patch.metadataConfidence?.let { values.put("metadata_confidence", it) }
            patch.metadataStatus?.let { values.put("metadata_status", it) }
            values.put("metadata_updated_at", System.currentTimeMillis() / 1000.0)

            val updated = connection.update(
                "anime",
                values,
                "id = ?",
                arrayOf(patch.animeId.toString()),
            )
            if (updated == 0) {
                throw NoSuchElementException(
                    "Cannot update anime metadata: anime ID does not exist.",
                )
            }
            connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(patch.animeId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toAnimeRecord()
                else throw NoSuchElementException(
                    "Anime disappeared while updating persisted metadata.",
                )
            }
        }
    }

    suspend fun getEpisode(id: Long): ReiAnixEpisodeRecord? = withContext(Dispatchers.IO) {
        require(id > 0L) { "Episode ID must be a positive persisted ID." }
        database.read { connection ->
            queryEpisode(connection, id)
        }
    }

    suspend fun listEpisodes(animeId: Long): List<ReiAnixEpisodeRecord> =
        withContext(Dispatchers.IO) {
            require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
            database.read { connection ->
                connection.rawQuery(
                    "SELECT * FROM episodes WHERE anime_id = ? " +
                        "ORDER BY season ASC, number ASC, id ASC",
                    arrayOf(animeId.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toEpisodeRecord())
                    }
                }
            }
        }

    suspend fun listRecentlyPlayed(limit: Int = 36): List<ReiAnixEpisodeRecord> =
        withContext(Dispatchers.IO) {
            require(limit in 1..200) { "Page size must be between 1 and 200." }
            database.read { connection ->
                connection.rawQuery(
                    "SELECT * FROM episodes WHERE last_played_at IS NOT NULL " +
                        "ORDER BY last_played_at DESC, id DESC LIMIT ?",
                    arrayOf(limit.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toEpisodeRecord())
                    }
                }
            }
        }

    suspend fun listFolders(): List<ReiAnixFolderRecord> = withContext(Dispatchers.IO) {
        database.read { connection ->
            connection.rawQuery(
                "SELECT * FROM folders ORDER BY added_at DESC, path COLLATE NOCASE ASC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.toFolderRecord())
                }
            }
        }
    }

    suspend fun artworkForAnime(animeId: Long): List<ReiAnixArtworkRecord> =
        withContext(Dispatchers.IO) {
            require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
            database.read { connection ->
                connection.rawQuery(
                    "SELECT * FROM artwork WHERE entity_type = ? AND entity_id = ? " +
                        "ORDER BY priority DESC, updated_at DESC, id DESC",
                    arrayOf("anime", animeId.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.toArtworkRecord())
                    }
                }
            }
        }

    /**
     * Atomically updates only the supplied playback fields on the existing episode.
     * Missing rows and SQLite errors are propagated; they are never treated as success.
     */
    suspend fun updatePlaybackProgress(
        update: ReiAnixPlaybackProgressUpdate,
    ): ReiAnixEpisodeRecord = withContext(Dispatchers.IO) {
        database.transaction { connection ->
            val values = ContentValues().apply {
                put("progress", update.progress)
                if (update.duration != null) put("duration", update.duration)
                if (update.watched != null) put("watched", if (update.watched) 1 else 0)
                put("last_played_at", update.playedAtEpochSeconds)
            }
            connection.update(
                "episodes",
                values,
                "id = ?",
                arrayOf(update.episodeId.toString()),
            )
            queryEpisode(connection, update.episodeId)
                ?: throw NoSuchElementException(
                    "Cannot save playback progress: episode ID does not exist.",
                )
        }
    }

    private fun queryEpisode(
        connection: SQLiteDatabase,
        episodeId: Long,
    ): ReiAnixEpisodeRecord? =
        connection.rawQuery(
            "SELECT * FROM episodes WHERE id = ? LIMIT 1",
            arrayOf(episodeId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toEpisodeRecord() else null
        }
}

private fun Cursor.toAnimeRecord() = ReiAnixAnimeRecord(
    id = requiredLong("id"),
    lookupTitle = requiredString("lookup_title"),
    anilistId = optionalLong("anilist_id"),
    title = requiredString("title"),
    romaji = optionalString("romaji"),
    english = optionalString("english"),
    nativeTitle = optionalString("native"),
    aliasesJson = optionalString("aliases"),
    description = optionalString("description"),
    descriptionOriginal = optionalString("description_original"),
    coverUrl = optionalString("cover_url"),
    coverCache = optionalString("cover_cache"),
    bannerUrl = optionalString("banner_url"),
    genresJson = optionalString("genres"),
    year = optionalInt("year"),
    season = optionalString("season"),
    status = optionalString("status"),
    episodesCount = optionalInt("episodes_count"),
    duration = optionalInt("duration"),
    score = optionalInt("score"),
    format = optionalString("format"),
    studio = optionalString("studio"),
    metadataUpdatedAt = optionalDouble("metadata_updated_at"),
    metadataFetchedAt = optionalDouble("metadata_fetched_at"),
    metadataSource = optionalString("metadata_source"),
    metadataConfidence = optionalString("metadata_confidence"),
    metadataStatus = optionalString("metadata_status"),
    metadataManualFieldsJson = optionalString("metadata_manual_fields"),
    favorite = optionalBoolean("favorite"),
    userTagsJson = optionalString("user_tags"),
    mediaKind = optionalString("media_kind"),
    isPinned = optionalBoolean("is_pinned"),
    personalNote = optionalString("personal_note"),
    addedAt = requiredDouble("added_at"),
    anilistMatchStatus = optionalString("anilist_match_status"),
    anilistMatchScore = optionalDouble("anilist_match_score"),
    anilistMatchMargin = optionalDouble("anilist_match_margin"),
    anilistMatchManual = optionalBoolean("anilist_match_manual"),
)

private fun Cursor.toEpisodeRecord() = ReiAnixEpisodeRecord(
    id = requiredLong("id"),
    animeId = requiredLong("anime_id"),
    path = requiredString("path"),
    fileName = requiredString("file_name"),
    season = requiredLong("season").toInt(),
    number = optionalDouble("number"),
    duration = optionalDouble("duration"),
    progress = optionalDouble("progress"),
    watched = optionalBoolean("watched"),
    mimeType = optionalString("mime_type"),
    fileSize = optionalLong("file_size"),
    modifiedAt = optionalDouble("modified_at"),
    sourceFolder = optionalString("source_folder"),
    absoluteNumber = optionalDouble("absolute_number"),
    relativePath = optionalString("relative_path"),
    volumeId = optionalString("volume_id"),
    volumeUuid = optionalString("volume_uuid"),
    episodeType = optionalString("episode_type"),
    episodeTitle = optionalString("episode_title"),
    identificationSource = optionalString("identification_source"),
    identificationConfidence = optionalString("identification_confidence"),
    manualOverride = optionalBoolean("manual_override"),
    missing = optionalBoolean("missing"),
    lastPlayedAt = optionalDouble("last_played_at"),
    mediaIdentity = optionalString("media_identity"),
    availabilityState = optionalString("availability_state"),
)

private fun Cursor.toFolderRecord() = ReiAnixFolderRecord(
    path = requiredString("path"),
    name = optionalString("name"),
    kind = optionalString("kind"),
    authorization = optionalString("authorization"),
    accountId = optionalString("account_id"),
    addedAt = optionalDouble("added_at"),
    lastScanAt = optionalDouble("last_scan_at"),
    lastError = optionalString("last_error"),
    safAuthority = optionalString("saf_authority"),
    safDocumentId = optionalString("saf_document_id"),
    safVolumeId = optionalString("saf_volume_id"),
    safIdentity = optionalString("saf_identity"),
)

private fun Cursor.toArtworkRecord() = ReiAnixArtworkRecord(
    id = requiredLong("id"),
    entityType = requiredString("entity_type"),
    entityId = requiredString("entity_id"),
    artworkType = requiredString("artwork_type"),
    source = requiredString("source"),
    sourceRef = optionalString("source_ref"),
    localPath = optionalString("local_path"),
    externalUrl = optionalString("external_url"),
    manual = optionalBoolean("manual"),
    priority = optionalInt("priority"),
    status = optionalString("status"),
    discoveredAt = optionalDouble("discovered_at"),
    updatedAt = optionalDouble("updated_at"),
    lastAttemptAt = optionalDouble("last_attempt_at"),
    failureCount = optionalInt("failure_count"),
    artworkKey = optionalString("artwork_key"),
    variant = optionalString("variant"),
    byteSize = optionalLong("byte_size"),
    width = optionalInt("width"),
    height = optionalInt("height"),
    checksum = optionalString("checksum"),
    contentType = optionalString("content_type"),
    lastAccess = optionalDouble("last_access"),
    nextRetryAt = optionalDouble("next_retry_at"),
    httpStatus = optionalInt("http_status"),
)
