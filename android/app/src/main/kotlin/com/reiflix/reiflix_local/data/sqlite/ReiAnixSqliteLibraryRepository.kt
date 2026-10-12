package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import com.reiflix.reiflix_local.data.media.MediaTitleNormalizer
import org.json.JSONException
import kotlinx.coroutines.CancellationException
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
     * Updates supplied catalog metadata for an existing anime in one transaction.
     * Fields in metadata_manual_fields are protected. Null patch members are no-ops.
     * Source/confidence/status bookkeeping and explicit manual corrections remain
     * owned by LibraryStore until their merge rules are migrated with parity tests.
     */
    suspend fun updateAnimeMetadata(
        patch: ReiAnixAnimeMetadataPatch,
    ): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
        database.transaction { connection ->
            val current = connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(patch.animeId.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw NoSuchElementException(
                        "Cannot update anime metadata: anime ID does not exist.",
                    )
                }
                val rawManualFields = cursor.optionalString("metadata_manual_fields") ?: "[]"
                val manualFields: Set<String> = try {
                    val parsed = JSONArray(rawManualFields)
                    buildSet<String> {
                        for (index in 0 until parsed.length()) {
                            val field = parsed.get(index)
                            if (field !is String) {
                                throw IllegalStateException(
                                    "Stored manual metadata ownership contains a non-string field.",
                                )
                            }
                            add(field as String)
                        }
                    }
                } catch (error: JSONException) {
                    throw IllegalStateException(
                        "Stored manual metadata ownership is unreadable; refusing to overwrite metadata.",
                        error,
                    )
                }
                cursor.toAnimeRecord() to manualFields
            }

            val currentRecord = current.first
            val manualFields = current.second
            val values = ContentValues()

            patch.title?.takeIf { it.isNotBlank() }?.let {
                if ("title" !in manualFields) values.put("title", it.trim())
            }
            patch.romaji?.takeIf { it.isNotBlank() }?.let { if ("romaji" !in manualFields) values.put("romaji", it) }
            patch.english?.takeIf { it.isNotBlank() }?.let { if ("english" !in manualFields) values.put("english", it) }
            patch.nativeTitle?.takeIf { it.isNotBlank() }?.let { if ("native" !in manualFields) values.put("native", it) }
            patch.aliasesJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }?.let { if ("aliases" !in manualFields) values.put("aliases", it) }
            patch.description?.takeIf { it.isNotBlank() }?.let { if ("description" !in manualFields) values.put("description", it) }
            patch.coverUrl?.takeIf { it.isNotBlank() }?.let { if ("cover_url" !in manualFields) values.put("cover_url", it) }
            patch.coverCache?.takeIf { it.isNotBlank() }?.let { if ("cover_cache" !in manualFields) values.put("cover_cache", it) }
            patch.bannerUrl?.takeIf { it.isNotBlank() }?.let { if ("banner_url" !in manualFields) values.put("banner_url", it) }
            patch.genresJson?.takeIf { it.isNotBlank() && it.trim() != "[]" }?.let { if ("genres" !in manualFields) values.put("genres", it) }
            patch.year?.let { if ("year" !in manualFields) values.put("year", it) }
            patch.season?.takeIf { it.isNotBlank() }?.let { if ("season" !in manualFields) values.put("season", it) }
            patch.status?.takeIf { it.isNotBlank() }?.let { if ("status" !in manualFields) values.put("status", it) }
            patch.episodesCount?.let { if ("episodes_count" !in manualFields) values.put("episodes_count", it) }
            patch.duration?.let { if ("duration" !in manualFields) values.put("duration", it) }
            patch.score?.let { if ("score" !in manualFields) values.put("score", it) }
            patch.format?.takeIf { it.isNotBlank() }?.let { if ("format" !in manualFields) values.put("format", it) }
            patch.studio?.takeIf { it.isNotBlank() }?.let { if ("studio" !in manualFields) values.put("studio", it) }
            patch.metadataFetchedAt?.let { values.put("metadata_fetched_at", it) }

            // If every requested editorial field is manually protected, preserve
            // the record and its timestamps exactly.
            if (values.size() == 0) {
                return@transaction currentRecord
            }

            values.put("metadata_updated_at", System.currentTimeMillis() / 1000.0)
            connection.update(
                "anime",
                values,
                "id = ?",
                arrayOf(patch.animeId.toString()),
            )
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

    /**
     * Persists an AniList association on the existing anime row. A manual link is
     * authoritative; automatic matching cannot replace it or silently change an
     * existing different AniList ID. Local primary keys and playback tables are untouched.
     */
    suspend fun persistAniListAssociation(
        animeId: Long,
        anilistId: Long,
        manual: Boolean,
        score: Double? = null,
        margin: Double? = null,
    ): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
        require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
        require(anilistId > 0L) { "AniList ID must be positive." }
        require(score == null || (score.isFinite() && score in 0.0..1.0))
        require(margin == null || (margin.isFinite() && margin in 0.0..1.0))
        database.transaction { connection ->
            val current = connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(animeId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toAnimeRecord()
                else throw NoSuchElementException("Anime ID does not exist.")
            }
            if (!manual && current.anilistMatchManual == true) return@transaction current
            if (!manual && current.anilistId != null && current.anilistId != anilistId) {
                return@transaction current
            }
            if (!manual && (score == null || score < 0.88 || margin == null || margin < 0.12)) {
                return@transaction current
            }
            val status = if (manual) "manual" else "matched"
            if (current.anilistId == anilistId &&
                current.anilistMatchManual == manual &&
                current.anilistMatchStatus == status &&
                current.anilistMatchScore == score &&
                current.anilistMatchMargin == margin
            ) return@transaction current

            val values = ContentValues().apply {
                put("anilist_id", anilistId)
                put("anilist_match_manual", if (manual) 1 else 0)
                put("anilist_match_status", status)
                if (score != null) put("anilist_match_score", score)
                if (margin != null) put("anilist_match_margin", margin)
                put("metadata_source", "anilist")
                put("metadata_status", "available")
                put("metadata_confidence", if (manual) "high" else "high")
                put("metadata_updated_at", System.currentTimeMillis() / 1000.0)
            }
            connection.update("anime", values, "id = ?", arrayOf(animeId.toString()))
            connection.rawQuery(
                "SELECT * FROM anime WHERE id = ? LIMIT 1",
                arrayOf(animeId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toAnimeRecord()
                else throw NoSuchElementException("Anime disappeared while saving AniList association.")
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

    suspend fun listRecentlyPlayed(limit: Int = 36, offset: Int = 0): List<ReiAnixEpisodeRecord> =
        withContext(Dispatchers.IO) {
            require(limit in 1..200) { "Page size must be between 1 and 200." }
            require(offset >= 0) { "Offset cannot be negative." }
            database.read { connection ->
                connection.rawQuery(
                    "SELECT * FROM episodes WHERE last_played_at IS NOT NULL " +
                        "ORDER BY last_played_at DESC, id DESC LIMIT ? OFFSET ?",
                    arrayOf(limit.toString(), offset.toString()),
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
        require(update.episodeId > 0L) { "Episode ID must be a positive persisted ID." }
        require(update.progress.isFinite() && update.progress >= 0.0) {
            "Progress must be finite and non-negative."
        }
        require(update.duration == null || (update.duration.isFinite() && update.duration > 0.0)) {
            "Duration must be finite and positive when known."
        }
        require(update.playedAtEpochSeconds.isFinite() && update.playedAtEpochSeconds >= 0.0) {
            "Playback timestamp must be finite and non-negative."
        }
        database.transaction { connection ->
            val current = queryEpisode(connection, update.episodeId)
                ?: throw NoSuchElementException(
                    "Cannot save playback progress: episode ID does not exist.",
                )

            // A delayed callback from an older player session must not overwrite
            // a newer durable position or its watched state.
            if ((current.lastPlayedAt ?: 0.0) >= update.playedAtEpochSeconds) {
                return@transaction current
            }

            val currentDuration = current.duration?.takeIf { it.isFinite() && it > 0.0 }
            val incomingDuration = update.duration?.takeIf { it.isFinite() && it > 0.0 }
            val duration = incomingDuration ?: currentDuration
            val boundedProgress = if (duration != null) update.progress.coerceAtMost(duration)
                else update.progress
            val values = ContentValues().apply {
                put("progress", boundedProgress)
                if (incomingDuration != null) put("duration", incomingDuration)
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
                ?: throw IllegalStateException(
                    "Episode disappeared after saving playback progress.",
                )
        }
    }

    /**
     * Applies discovery facts to the canonical episodes table. Only verified,
     * in-scope items are touched; destructive missing-state reconciliation is
     * gated by the complete scan receipt and limited to source_folder descendants.
     */
    suspend fun indexDiscoveredMedia(snapshot: NativeLibraryScanSnapshot): NativeLibraryIndexReport =
        withContext(Dispatchers.IO) {
            require(snapshot.operationId.isNotBlank()) { "A scan operation ID is required." }
            require(snapshot.scopeRoot.isNotBlank()) { "An explicit scope root is required." }
            database.transaction { db ->
                var inserted = 0
                var updated = 0
                var unchanged = 0
                var ambiguous = 0
                var rejected = 0
                var invalidScopeEvidence = false
                val seenLocations = linkedSetOf<String>()
                val seenIdentities = linkedSetOf<String>()
                val inputIdentities = hashSetOf<String>()

                for (item in snapshot.items) {
                    db.execSQL("SAVEPOINT native_index_item")
                    try {
                    if (!item.scopeVerified && !(item.scopeLocation ?: item.location).isWithinScope(snapshot.scopeRoot)) {
                        rejected++
                        invalidScopeEvidence = true
                        continue
                    }
                    val stable = item.stableIdentity
                    if (stable == null || !inputIdentities.add(stable)) {
                        ambiguous++
                        continue
                    }
                    seenLocations += item.location
                    seenIdentities += stable
                    val byIdentity = item.mediaIdentity?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { queryEpisodesByColumn(db, "media_identity", it) }.orEmpty()
                    val byPath = queryEpisodesByColumn(db, "path", item.location)
                    if (byIdentity.size > 1 || byPath.size > 1) {
                        ambiguous++
                        continue
                    }
                    val identityRow = byIdentity.singleOrNull()
                    val pathRow = byPath.singleOrNull()
                    if (identityRow != null && pathRow != null && identityRow.id != pathRow.id) {
                        ambiguous++
                        continue
                    }
                    val current = identityRow ?: pathRow
                    if (current != null) {
                        val patch = ContentValues()
                        if (current.path != item.location) patch.put("path", item.location)
                        if (current.fileName != item.fileName) patch.put("file_name", item.fileName)
                        item.mimeType?.takeIf { it.isNotBlank() }?.let { if (current.mimeType != it) patch.put("mime_type", it) }
                        item.sizeBytes?.let { if (current.fileSize != it) patch.put("file_size", it) }
                        item.modifiedAtEpochSeconds?.let { if (current.modifiedAt != it) patch.put("modified_at", it) }
                        val folder = item.sourceFolder?.takeIf { it.isNotBlank() } ?: snapshot.scopeRoot
                        if (current.sourceFolder != folder) patch.put("source_folder", folder)
                        item.relativePath?.takeIf { it.isNotBlank() }?.let { if (current.relativePath != it) patch.put("relative_path", it) }
                        item.volumeId?.takeIf { it.isNotBlank() }?.let { if (current.volumeId != it) patch.put("volume_id", it) }
                        item.volumeUuid?.takeIf { it.isNotBlank() }?.let { if (current.volumeUuid != it) patch.put("volume_uuid", it) }
                        item.mediaIdentity?.takeIf { it.isNotBlank() }?.let { if (current.mediaIdentity != it) patch.put("media_identity", it) }
                        if (current.manualOverride != true) {
                            item.episodeType?.takeIf { it.isNotBlank() }?.let { if (current.episodeType != it) patch.put("episode_type", it) }
                            item.episodeTitle?.takeIf { it.isNotBlank() }?.let { if (current.episodeTitle != it) patch.put("episode_title", it) }
                            item.identificationSource?.takeIf { it.isNotBlank() }?.let { if (current.identificationSource != it) patch.put("identification_source", it) }
                            item.identificationConfidence?.takeIf { it.isNotBlank() }?.let { if (current.identificationConfidence != it) patch.put("identification_confidence", it) }
                        }
                        if (current.missing == true) patch.put("missing", 0)
                        if (current.availabilityState != "available") patch.put("availability_state", "available")
                        if (patch.size() == 0) unchanged++ else {
                            db.update("episodes", patch, "id = ?", arrayOf(current.id.toString()))
                            updated++
                        }
                        recordEpisodeObservation(db, current.id, snapshot, item)
                        continue
                    }

                    val title = item.normalizedAnimeTitle()
                    if (!item.hasConfidentEpisodeIdentity || title.isBlank()) {
                        ambiguous++
                        continue
                    }
                    val animeIds = db.rawQuery("SELECT id, title, lookup_title FROM anime", null).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val candidateTitle = MediaTitleNormalizer.normalizeTitle(cursor.getString(1))
                                val lookup = MediaTitleNormalizer.normalizeTitle(cursor.getString(2))
                                if (title == candidateTitle || title == lookup) add(cursor.getLong(0))
                            }
                        }
                    }
                    if (animeIds.size > 1) {
                        ambiguous++
                        continue
                    }
                    val animeId = if (animeIds.size == 1) {
                        animeIds.single()
                    } else {
                        // A confidently parsed new local title can become a local-only
                        // anime row. It is not auto-associated with any remote catalog ID.
                        val existingLookup = db.rawQuery(
                            "SELECT id FROM anime WHERE lookup_title = ? LIMIT 1",
                            arrayOf(title),
                        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
                        if (existingLookup != null) {
                            ambiguous++
                            continue
                        }
                        val localAnime = ContentValues().apply {
                            put("lookup_title", title)
                            put("title", item.animeTitle!!.trim())
                            put("added_at", System.currentTimeMillis() / 1000.0)
                            put("metadata_source", "local_index")
                            put("media_kind", "series")
                        }
                        db.insertOrThrow("anime", null, localAnime)
                    }
                    val season = item.season ?: 0
                    val logicalCount = when {
                        item.episodeNumber != null -> db.rawQuery(
                            "SELECT id FROM episodes WHERE anime_id = ? AND season = ? AND number = ?",
                            arrayOf(animeId.toString(), season.toString(), item.episodeNumber.toString()),
                        ).use { it.count }
                        item.absoluteNumber != null -> db.rawQuery(
                            "SELECT id FROM episodes WHERE anime_id = ? AND absolute_number = ?",
                            arrayOf(animeId.toString(), item.absoluteNumber.toString()),
                        ).use { it.count }
                        else -> 0
                    }
                    // A second release for an existing logical episode is a conflict,
                    // not permission to replace its path or playback history.
                    if (logicalCount > 0) {
                        ambiguous++
                        continue
                    }
                    val values = ContentValues().apply {
                        put("anime_id", animeId)
                        put("path", item.location)
                        put("file_name", item.fileName)
                        put("season", season)
                        item.episodeNumber?.let { put("number", it) }
                        item.mimeType?.takeIf { it.isNotBlank() }?.let { put("mime_type", it) }
                        item.sizeBytes?.let { put("file_size", it) }
                        item.modifiedAtEpochSeconds?.let { put("modified_at", it) }
                        put("source_folder", item.sourceFolder?.takeIf { it.isNotBlank() } ?: snapshot.scopeRoot)
                        putNull("duration")
                        item.absoluteNumber?.let { put("absolute_number", it) }
                        item.relativePath?.takeIf { it.isNotBlank() }?.let { put("relative_path", it) }
                        item.volumeId?.takeIf { it.isNotBlank() }?.let { put("volume_id", it) }
                        item.volumeUuid?.takeIf { it.isNotBlank() }?.let { put("volume_uuid", it) }
                        item.episodeType?.takeIf { it.isNotBlank() }?.let { put("episode_type", it) }
                        item.episodeTitle?.takeIf { it.isNotBlank() }?.let { put("episode_title", it) }
                        item.identificationSource?.takeIf { it.isNotBlank() }?.let { put("identification_source", it) }
                        item.identificationConfidence?.takeIf { it.isNotBlank() }?.let { put("identification_confidence", it) }
                        put("manual_override", 0)
                        put("missing", 0)
                        item.mediaIdentity?.takeIf { it.isNotBlank() }?.let { put("media_identity", it) }
                        put("availability_state", "available")
                    }
                    val insertedEpisodeId = db.insertOrThrow("episodes", null, values)
                    recordEpisodeObservation(db, insertedEpisodeId, snapshot, item)
                    inserted++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (itemError: Exception) {
                        // Roll back only the bad item. Other verified observations in this
                        // scan can still be committed, but ambiguity blocks reconciliation.
                        db.execSQL("ROLLBACK TO native_index_item")
                        ambiguous++
                    } finally {
                        db.execSQL("RELEASE native_index_item")
                    }
                }

                val reconcile = snapshot.mayReconcile && rejected == 0 && !invalidScopeEvidence && ambiguous == 0
                var missing = 0
                if (reconcile) {
                    val root = snapshot.scopeRoot.trim().replace('\\', '/').trimEnd('/')
                    val prefix = "$root/"
                    val scopedRows = db.rawQuery(
                        "SELECT * FROM episodes WHERE source_folder = ? OR substr(source_folder, 1, ?) = ?",
                        arrayOf(root, prefix.length.toString(), prefix),
                    ).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(cursor.toEpisodeRecord()) }
                    }
                    for (episode in scopedRows) {
                        if (episode.path in seenLocations ||
                            (!episode.mediaIdentity.isNullOrBlank() && episode.mediaIdentity in seenIdentities)
                        ) continue
                        if (episode.missing == true && episode.availabilityState == "missing") continue
                        db.update(
                            "episodes",
                            ContentValues().apply { put("missing", 1); put("availability_state", "missing") },
                            "id = ?",
                            arrayOf(episode.id.toString()),
                        )
                        db.execSQL(
                            "UPDATE episode_observations SET state = ?, last_checked_at = ? " +
                                "WHERE episode_id = ? AND source_kind = ? AND scope_kind = ? AND scope_ref = ? AND state = ?",
                            arrayOf("missing", System.currentTimeMillis() / 1000.0, episode.id, snapshot.source, "root", snapshot.scopeReference, "available"),
                        )
                        missing++
                    }
                }
                NativeLibraryIndexReport(
                    operationId = snapshot.operationId,
                    inserted = inserted,
                    updated = updated,
                    unchanged = unchanged,
                    ambiguous = ambiguous,
                    rejectedOutOfScope = rejected,
                    reconciledMissing = missing,
                    reconciliationAuthorized = reconcile,
                )
            }
        }

    private fun recordEpisodeObservation(
        db: SQLiteDatabase,
        episodeId: Long,
        snapshot: NativeLibraryScanSnapshot,
        item: NativeDiscoveredMedia,
    ) {
        val now = System.currentTimeMillis() / 1000.0
        // Keep old URIs as superseded observations when the same stable media
        // identity is found at a new location; never REPLACE/delete history rows.
        db.execSQL(
            "UPDATE episode_observations SET state = ?, last_checked_at = ? " +
                "WHERE episode_id = ? AND source_kind = ? AND scope_kind = ? AND scope_ref = ? AND uri != ? AND state = ?",
            arrayOf("superseded", now, episodeId, snapshot.source, "root", snapshot.scopeReference, item.location, "available"),
        )
        val identity = item.stableIdentity.orEmpty()
        val fingerprint = listOf(identity, item.fileName, item.sizeBytes?.toString().orEmpty(),
            item.modifiedAtEpochSeconds?.toString().orEmpty()).joinToString("|")
        val values = ContentValues().apply {
            put("episode_id", episodeId)
            put("source_kind", snapshot.source)
            put("scope_kind", "root")
            put("scope_ref", snapshot.scopeReference)
            put("uri", item.location)
            item.volumeId?.takeIf { it.isNotBlank() }?.let { put("volume_id", it) }
            snapshot.scanGeneration?.let { put("native_generation", it) }
            put("fingerprint", fingerprint)
            put("first_seen", now)
            put("last_seen", now)
            put("last_checked_at", now)
            put("state", "available")
        }
        db.insertWithOnConflict(
            "episode_observations",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        val updateValues = ContentValues().apply {
            item.volumeId?.takeIf { it.isNotBlank() }?.let { put("volume_id", it) }
            snapshot.scanGeneration?.let { put("native_generation", it) }
            put("fingerprint", fingerprint)
            put("last_seen", now)
            put("last_checked_at", now)
            put("state", "available")
        }
        db.update(
            "episode_observations",
            updateValues,
            "episode_id = ? AND source_kind = ? AND scope_kind = ? AND scope_ref = ? AND uri = ?",
            arrayOf(episodeId.toString(), snapshot.source, "root", snapshot.scopeReference, item.location),
        )
    }

    private fun queryEpisodesByColumn(
        db: SQLiteDatabase,
        column: String,
        value: String,
    ): List<ReiAnixEpisodeRecord> {
        require(column == "media_identity" || column == "path")
        return db.rawQuery("SELECT * FROM episodes WHERE $column = ?", arrayOf(value)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toEpisodeRecord()) }
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
