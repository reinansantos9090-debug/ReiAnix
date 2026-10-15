package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Domain mutations and lookup operations on the existing canonical LibraryStore.
 *
 * These are extensions of the existing repository, not a second repository or
 * persistence owner. Every mutation uses the same ReiAnixSqliteDatabase instance
 * supplied to ReiAnixSqliteLibraryRepository and keeps the schema at v29.
 */
suspend fun ReiAnixSqliteLibraryRepository.getAnimeByLookupTitle(
    lookupTitle: String,
): ReiAnixAnimeRecord? = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    database.read { connection ->
        connection.rawQuery(
            "SELECT * FROM anime WHERE lookup_title = ? LIMIT 1",
            arrayOf(lookup),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toAnimeRecord() else null
        }
    }
}

/**
 * Resolves a stored media location exactly. Plain filesystem paths and file://
 * URIs are alternate representations of the same local file; SAF/MediaStore and
 * other content URIs are never rewritten or guessed.
 *
 * If legacy rows contain conflicting matches for alternate path spellings, the
 * method refuses to choose an arbitrary episode.
 */
suspend fun ReiAnixSqliteLibraryRepository.findEpisodeByPathOrFileUri(
    pathOrUri: String,
): ReiAnixEpisodeRecord? = withContext(Dispatchers.IO) {
    val raw = pathOrUri.trim()
    require(raw.isNotEmpty()) { "pathOrUri cannot be blank." }
    val candidates = linkedSetOf(raw)
    val parsed = Uri.parse(raw)
    if (parsed.scheme.equals("file", ignoreCase = true)) {
        parsed.path?.takeIf(String::isNotBlank)?.let(candidates::add)
    } else if (parsed.scheme.isNullOrBlank() && File(raw).isAbsolute) {
        candidates += Uri.fromFile(File(raw)).toString()
    }

    database.read { connection ->
        val matches = candidates.mapNotNull { candidate ->
            connection.rawQuery(
                "SELECT * FROM episodes WHERE path = ? LIMIT 1",
                arrayOf(candidate),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.toEpisodeRecord() else null
            }
        }.distinctBy(ReiAnixEpisodeRecord::id)
        check(matches.size <= 1) {
            "Conflicting episode rows match the supplied local path; refusing an ambiguous lookup."
        }
        matches.singleOrNull()
    }
}

suspend fun ReiAnixSqliteLibraryRepository.findEpisodesByMediaIdentity(
    mediaIdentity: String,
): List<ReiAnixEpisodeRecord> = withContext(Dispatchers.IO) {
    val identity = mediaIdentity.trim()
    require(identity.isNotEmpty()) { "mediaIdentity cannot be blank." }
    database.read { connection ->
        connection.rawQuery(
            "SELECT * FROM episodes WHERE media_identity = ? ORDER BY id ASC",
            arrayOf(identity),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toEpisodeRecord())
            }
        }
    }
}

/**
 * Mirrors LibraryStore.resolve_local_anime_owner. Strong persisted identities win;
 * the local lookup title is consulted only as the final fallback. An ambiguous
 * identity is not arbitrarily assigned to the first anime row.
 */
suspend fun ReiAnixSqliteLibraryRepository.resolveLocalAnimeOwner(
    animeId: Long? = null,
    mediaIdentity: String? = null,
    path: String? = null,
    sourceFolder: String? = null,
    relativePath: String? = null,
    volumeId: String? = null,
    lookupTitle: String? = null,
): Long? = withContext(Dispatchers.IO) {
    val identity = mediaIdentity.orEmpty().trim()
    val normalizedPath = path.orEmpty().trim()
    val source = sourceFolder.orEmpty().trim()
    val relative = relativePath.orEmpty().trim().replace('\\', '/').trim('/')
    val volume = volumeId.orEmpty().trim()
    val lookup = lookupTitle.orEmpty().trim()
    database.read { connection ->
        if (animeId != null && animeId > 0L) {
            connection.rawQuery(
                "SELECT id FROM anime WHERE id = ? LIMIT 1",
                arrayOf(animeId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) return@read cursor.getLong(0)
            }
        }
        fun uniqueOwner(sql: String, args: Array<String>): Long? =
            connection.rawQuery(sql, args).use { cursor ->
                val ids = linkedSetOf<Long>()
                while (cursor.moveToNext()) if (!cursor.isNull(0)) ids += cursor.getLong(0)
                ids.singleOrNull()
            }

        if (identity.isNotEmpty()) {
            uniqueOwner(
                "SELECT DISTINCT anime_id FROM episodes WHERE media_identity = ? ORDER BY anime_id",
                arrayOf(identity),
            )?.let { return@read it }
        }
        if (normalizedPath.isNotEmpty()) {
            uniqueOwner(
                "SELECT DISTINCT anime_id FROM episodes WHERE path = ? ORDER BY anime_id",
                arrayOf(normalizedPath),
            )?.let { return@read it }
        }
        if (source.isNotEmpty() && relative.isNotEmpty()) {
            val sql = if (volume.isNotEmpty()) {
                "SELECT DISTINCT anime_id FROM episodes WHERE source_folder = ? AND relative_path = ? AND volume_id = ? ORDER BY anime_id"
            } else {
                "SELECT DISTINCT anime_id FROM episodes WHERE source_folder = ? AND relative_path = ? ORDER BY anime_id"
            }
            val args = if (volume.isNotEmpty()) arrayOf(source, relative, volume) else arrayOf(source, relative)
            uniqueOwner(sql, args)?.let { return@read it }
        }
        if (lookup.isNotEmpty()) {
            connection.rawQuery(
                "SELECT id FROM anime WHERE lookup_title = ? LIMIT 1",
                arrayOf(lookup),
            ).use { cursor ->
                if (cursor.moveToFirst()) return@read cursor.getLong(0)
            }
        }
        null
    }
}

/** Mirrors LibraryStore.toggle_pinned while keeping the read and write atomic. */
suspend fun ReiAnixSqliteLibraryRepository.toggleAnimePinned(
    animeId: Long,
): Boolean = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
    database.transaction { connection ->
        val current = connection.rawQuery(
            "SELECT is_pinned FROM anime WHERE id = ? LIMIT 1",
            arrayOf(animeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw NoSuchElementException("Anime local não encontrado.")
            }
            cursor.getInt(0) != 0
        }
        val next = !current
        val values = ContentValues().apply { put("is_pinned", if (next) 1 else 0) }
        connection.update("anime", values, "id = ?", arrayOf(animeId.toString()))
        next
    }
}

/** Mirrors LibraryStore.set_personal_note without changing catalog metadata. */
suspend fun ReiAnixSqliteLibraryRepository.setAnimePersonalNote(
    animeId: Long,
    note: String?,
): String? = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
    val normalized = note.orEmpty().trim().ifEmpty { null }
    require(normalized == null || normalized.length <= 2_000) {
        "A nota pessoal pode ter no máximo 2000 caracteres."
    }
    database.transaction { connection ->
        connection.requireAnime(animeId)
        val values = ContentValues().apply {
            if (normalized == null) putNull("personal_note") else put("personal_note", normalized)
        }
        connection.update("anime", values, "id = ?", arrayOf(animeId.toString()))
        normalized
    }
}

/** Mirrors LibraryStore.set_user_tags; tags stay in the existing anime.user_tags field. */
suspend fun ReiAnixSqliteLibraryRepository.setAnimeUserTags(
    animeId: Long,
    tags: Iterable<String?>,
): List<String> = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
    val normalized = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    tags.forEach { raw ->
        val tag = raw.orEmpty().trim().split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .joinToString(" ")
        val boundedTag = tag.take(40)
        if (boundedTag.isNotEmpty() && seen.add(boundedTag.lowercase(Locale.ROOT))) {
            normalized += boundedTag
        }
    }
    val serialized = JSONArray().also { array -> normalized.forEach { value -> array.put(value) } }.toString()
    database.transaction { connection ->
        connection.requireAnime(animeId)
        val values = ContentValues().apply { put("user_tags", serialized) }
        connection.update("anime", values, "id = ?", arrayOf(animeId.toString()))
        normalized.toList()
    }
}

/**
 * Persists only explicitly supplied user-owned editorial fields and marks those
 * fields manual so later automatic metadata patches cannot overwrite them.
 * The allowlist matches LibraryStore.set_manual_metadata; local identity and
 * personal state are never touched.
 */
suspend fun ReiAnixSqliteLibraryRepository.setManualMetadataByLookupTitle(
    lookupTitle: String,
    patch: ReiAnixAnimeMetadataPatch,
): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    val supplied = linkedMapOf<String, Any>()
    patch.title?.let { supplied["title"] = it }
    patch.romaji?.let { supplied["romaji"] = it }
    patch.english?.let { supplied["english"] = it }
    patch.nativeTitle?.let { supplied["native"] = it }
    patch.aliasesJson?.let { supplied["aliases"] = it }
    patch.description?.let { supplied["description"] = it }
    patch.genresJson?.let { supplied["genres"] = it }
    patch.year?.let { supplied["year"] = it }
    patch.season?.let { supplied["season"] = it }
    patch.status?.let { supplied["status"] = it }
    patch.episodesCount?.let { supplied["episodes_count"] = it }
    patch.duration?.let { supplied["duration"] = it }
    patch.score?.let { supplied["score"] = it }
    patch.format?.let { supplied["format"] = it }
    patch.studio?.let { supplied["studio"] = it }
    require(supplied.isNotEmpty()) {
        "Nenhum campo de metadata manual válido foi informado."
    }

    database.transaction { connection ->
        val current = connection.rawQuery(
            "SELECT id, metadata_manual_fields FROM anime WHERE lookup_title = ? LIMIT 1",
            arrayOf(lookup),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Obra local não encontrada.")
            cursor.getLong(cursor.getColumnIndexOrThrow("id")) to
                cursor.getString(cursor.getColumnIndexOrThrow("metadata_manual_fields"))
        }
        check(current.first == patch.animeId) {
            "The supplied anime ID does not match the persisted lookup title."
        }
        val manualFields = current.second.readJsonStringSet("metadata_manual_fields")
        manualFields += supplied.keys
        val values = ContentValues().apply {
            supplied.forEach { (column, value) ->
                when (value) {
                    is String -> put(column, value)
                    is Int -> put(column, value)
                    is Long -> put(column, value)
                    is Double -> put(column, value)
                    else -> throw IllegalArgumentException("Unsupported manual metadata type for $column.")
                }
            }
            put("metadata_manual_fields", manualFields.toSortedJsonArray())
            put("metadata_updated_at", System.currentTimeMillis() / 1_000.0)
            put("metadata_source", "manual")
            put("metadata_confidence", "high")
            put("metadata_status", "manual")
        }
        connection.update("anime", values, "id = ?", arrayOf(current.first.toString()))
        connection.requireAnime(current.first)
    }
}

/** Removes manual ownership markers without clearing the user's persisted values. */
suspend fun ReiAnixSqliteLibraryRepository.clearManualMetadataByLookupTitle(
    lookupTitle: String,
    fields: Set<String>? = null,
): Boolean = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    database.transaction { connection ->
        val row = connection.rawQuery(
            "SELECT id, anilist_id, metadata_manual_fields FROM anime WHERE lookup_title = ? LIMIT 1",
            arrayOf(lookup),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Obra local não encontrada.")
            Triple(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                if (cursor.isNull(cursor.getColumnIndexOrThrow("anilist_id"))) null
                    else cursor.getLong(cursor.getColumnIndexOrThrow("anilist_id")),
                cursor.getString(cursor.getColumnIndexOrThrow("metadata_manual_fields")),
            )
        }
        val manualFields = row.third.readJsonStringSet("metadata_manual_fields")
        val fieldsToRemove =
            if (fields.isNullOrEmpty()) manualFields.toSet() else manualFields.intersect(fields)
        manualFields.removeAll(fieldsToRemove)
        val hasAniList = row.second != null
        val values = ContentValues().apply {
            put("metadata_manual_fields", manualFields.toSortedJsonArray())
            put("metadata_status", if (hasAniList) "available" else "unresolved")
            put("metadata_source", if (hasAniList) "anilist" else "local")
        }
        connection.update("anime", values, "id = ?", arrayOf(row.first.toString()))
        true
    }
}

data class ReiAnixAniListMatchRecord(
    val animeId: Long,
    val lookupTitle: String,
    val anilistId: Long?,
    val status: String?,
    val score: Double?,
    val margin: Double?,
    val manual: Boolean,
    val metadataStatus: String?,
)

data class ReiAnixPendingMatchRecord(
    val lookupTitle: String,
    val displayTitle: String,
    /** Original JSON array is retained instead of lossy candidate normalization. */
    val candidatesJson: String,
)

/** Read the persisted mapping row without deriving an identity from the display title. */
suspend fun ReiAnixSqliteLibraryRepository.getAniListMatchByLookupTitle(
    lookupTitle: String,
): ReiAnixAniListMatchRecord? = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    database.read { connection ->
        connection.rawQuery(
            """
            SELECT id, lookup_title, anilist_id, anilist_match_status,
                   anilist_match_score, anilist_match_margin,
                   anilist_match_manual, metadata_status
            FROM anime WHERE lookup_title = ? LIMIT 1
            """.trimIndent(),
            arrayOf(lookup),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@read null
            ReiAnixAniListMatchRecord(
                animeId = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                lookupTitle = cursor.getString(cursor.getColumnIndexOrThrow("lookup_title")),
                anilistId = cursor.optionalLong("anilist_id"),
                status = cursor.optionalString("anilist_match_status"),
                score = cursor.optionalDouble("anilist_match_score"),
                margin = cursor.optionalDouble("anilist_match_margin"),
                manual = cursor.optionalBoolean("anilist_match_manual") == true,
                metadataStatus = cursor.optionalString("metadata_status"),
            )
        }
    }
}

/**
 * Mirrors LibraryStore.set_anilist_match atomically across the anime row and
 * associations table. Null ID explicitly removes the mapping.
 */
suspend fun ReiAnixSqliteLibraryRepository.setAniListMatchByLookupTitle(
    lookupTitle: String,
    anilistId: Long?,
    status: String = "matched",
    score: Double? = null,
    margin: Double? = null,
    manual: Boolean = false,
): Boolean = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    require(anilistId == null || anilistId > 0L) { "AniList ID must be positive when supplied." }
    require(score == null || (score.isFinite() && score in 0.0..1.0)) {
        "score must be finite and between zero and one."
    }
    require(margin == null || (margin.isFinite() && margin in 0.0..1.0)) {
        "margin must be finite and between zero and one."
    }
    val normalizedStatus = status.trim().ifEmpty { "unmatched" }
    database.transaction { connection ->
        val animeId = connection.rawQuery(
            "SELECT id FROM anime WHERE lookup_title = ? LIMIT 1",
            arrayOf(lookup),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Obra local não encontrada.")
            cursor.getLong(0)
        }
        val values = ContentValues().apply {
            if (anilistId == null) putNull("anilist_id") else put("anilist_id", anilistId)
            put("anilist_match_status", normalizedStatus)
            if (score == null) putNull("anilist_match_score") else put("anilist_match_score", score)
            if (margin == null) putNull("anilist_match_margin") else put("anilist_match_margin", margin)
            put("anilist_match_manual", if (manual) 1 else 0)
        }
        connection.update("anime", values, "id = ?", arrayOf(animeId.toString()))
        if (anilistId == null) {
            connection.delete("associations", "lookup_title = ?", arrayOf(lookup))
        } else {
            connection.execSQL(
                "INSERT OR REPLACE INTO associations(lookup_title, anilist_id) VALUES(?, ?)",
                arrayOf(lookup, anilistId),
            )
        }
        true
    }
}

/** Clears only AniList match/status bookkeeping; editorial and personal fields survive. */
suspend fun ReiAnixSqliteLibraryRepository.clearAniListMatchByLookupTitle(
    lookupTitle: String,
): Boolean = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    database.transaction { connection ->
        val row = connection.rawQuery(
            "SELECT id FROM anime WHERE lookup_title = ? LIMIT 1",
            arrayOf(lookup),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Obra local não encontrada.")
            cursor.getLong(0)
        }
        val values = ContentValues().apply {
            putNull("anilist_id")
            put("anilist_match_status", "unmatched")
            putNull("anilist_match_score")
            putNull("anilist_match_margin")
            put("anilist_match_manual", 0)
            put("metadata_status", "unresolved")
            put("metadata_source", "local")
            putNull("metadata_updated_at")
            putNull("metadata_fetched_at")
        }
        connection.update("anime", values, "id = ?", arrayOf(row.toString()))
        connection.delete("associations", "lookup_title = ?", arrayOf(lookup))
        connection.delete("pending_matches", "lookup_title = ?", arrayOf(lookup))
        true
    }
}

/** Store a validated JSON array in the existing pending_matches table. */
suspend fun ReiAnixSqliteLibraryRepository.setPendingAniListMatch(
    lookupTitle: String,
    displayTitle: String,
    candidatesJson: String,
): Unit = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    val display = displayTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    require(display.isNotEmpty()) { "displayTitle cannot be blank." }
    val candidates = JSONArray(candidatesJson)
    database.transaction { connection ->
        connection.execSQL(
            "INSERT OR REPLACE INTO pending_matches(lookup_title, display_title, candidates) VALUES(?, ?, ?)",
            arrayOf(lookup, display, candidates.toString()),
        )
    }
}

/** Reads pending match records in stable display-title order; malformed JSON is an error. */
suspend fun ReiAnixSqliteLibraryRepository.listPendingAniListMatches(): List<ReiAnixPendingMatchRecord> =
    withContext(Dispatchers.IO) {
        database.read { connection ->
            connection.rawQuery(
                "SELECT lookup_title, display_title, candidates FROM pending_matches ORDER BY display_title COLLATE NOCASE ASC, lookup_title ASC",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val candidates = cursor.getString(cursor.getColumnIndexOrThrow("candidates"))
                        // Validate, but retain the stored payload to avoid lossy normalization.
                        JSONArray(candidates)
                        add(
                            ReiAnixPendingMatchRecord(
                                lookupTitle = cursor.getString(cursor.getColumnIndexOrThrow("lookup_title")),
                                displayTitle = cursor.getString(cursor.getColumnIndexOrThrow("display_title")),
                                candidatesJson = candidates,
                            ),
                        )
                    }
                }
            }
        }
    }

/**
 * Resolves the user's selected AniList candidate in the canonical catalog.
 *
 * A resolved pending candidate is a manual match, matching LibraryService.resolve_match:
 * anime and associations receive the same ID plus manual status/score/flag, and the
 * pending row is removed only after both catalog writes succeed. Remote editorial
 * metadata is deliberately not marked available here because this SQLite operation
 * does not fetch that payload.
 */
suspend fun ReiAnixSqliteLibraryRepository.resolvePendingAniListMatch(
    lookupTitle: String,
    anilistId: Long,
): Unit = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    require(anilistId > 0L) { "AniList ID must be positive." }

    database.transaction { connection ->
        // Resolve by the canonical local key only; never create or guess a local anime.
        val animeId = connection.rawQuery(
            "SELECT id FROM anime WHERE lookup_title = ? LIMIT 1",
            arrayOf(lookup),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                throw NoSuchElementException("Obra local não encontrada; a pendência foi preservada.")
            }
            cursor.getLong(cursor.getColumnIndexOrThrow("id"))
        }

        val matchValues = ContentValues().apply {
            put("anilist_id", anilistId)
            put("anilist_match_status", "manual")
            put("anilist_match_score", 1.0)
            put("anilist_match_margin", 1.0)
            put("anilist_match_manual", 1)
        }
        check(
            connection.update(
                "anime",
                matchValues,
                "id = ?",
                arrayOf(animeId.toString()),
            ) == 1,
        ) { "Não foi possível atualizar a associação AniList da obra local." }

        connection.execSQL(
            "INSERT OR REPLACE INTO associations(lookup_title, anilist_id) VALUES(?, ?)",
            arrayOf(lookup, anilistId),
        )

        // Deletion is intentionally last: any earlier failure rolls back all writes.
        connection.delete("pending_matches", "lookup_title = ?", arrayOf(lookup))
    }
}

/** The same metadata-status mutation contract as LibraryStore.set_metadata_status. */
suspend fun ReiAnixSqliteLibraryRepository.setMetadataStatusByLookupTitle(
    lookupTitle: String,
    status: String,
    confidence: String? = null,
): Boolean = withContext(Dispatchers.IO) {
    val lookup = lookupTitle.trim()
    require(lookup.isNotEmpty()) { "lookupTitle cannot be blank." }
    database.transaction { connection ->
        val values = ContentValues().apply {
            put("metadata_status", status)
            confidence?.let { put("metadata_confidence", it) }
        }
        connection.update("anime", values, "lookup_title = ?", arrayOf(lookup)) > 0
    }
}

/** Expires metadata/cache freshness without deleting catalog rows or AniList associations. */
suspend fun ReiAnixSqliteLibraryRepository.clearAniListMetadataCache(): Int =
    withContext(Dispatchers.IO) {
        database.transaction { connection ->
            val values = ContentValues().apply {
                putNull("metadata_updated_at")
                put("cover_cache", "")
            }
            connection.update("anime", values, null, null)
        }
    }

/** Matches LibraryStore.set_watched: completion and progress are one transaction. */
suspend fun ReiAnixSqliteLibraryRepository.setEpisodeWatchedState(
    episodeId: Long,
    watched: Boolean,
    playedAtEpochSeconds: Double = System.currentTimeMillis() / 1_000.0,
): ReiAnixEpisodeRecord = withContext(Dispatchers.IO) {
    require(episodeId > 0L) { "Episode ID must be a positive persisted ID." }
    require(playedAtEpochSeconds.isFinite() && playedAtEpochSeconds >= 0.0) {
        "playedAtEpochSeconds must be finite and non-negative."
    }
    database.transaction { connection ->
        val existing = connection.rawQuery(
            "SELECT * FROM episodes WHERE id = ? LIMIT 1",
            arrayOf(episodeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Episode ID does not exist: $episodeId")
            cursor.toEpisodeRecord()
        }
        // Do not let a delayed UI mutation move the durable consumption clock
        // backwards over a newer player checkpoint.
        if (existing.lastPlayedAt != null && existing.lastPlayedAt > playedAtEpochSeconds) {
            return@transaction existing
        }
        val duration = existing.duration?.takeIf { it.isFinite() } ?: 0.0
        val progress = if (watched && duration > 0.0) duration else 0.0
        val values = ContentValues().apply {
            put("watched", if (watched) 1 else 0)
            put("progress", progress)
            put("last_played_at", playedAtEpochSeconds)
        }
        connection.update("episodes", values, "id = ?", arrayOf(episodeId.toString()))
        connection.rawQuery(
            "SELECT * FROM episodes WHERE id = ? LIMIT 1",
            arrayOf(episodeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("Episode vanished after watched-state update.")
            cursor.toEpisodeRecord()
        }
    }
}

private fun SQLiteDatabase.requireAnime(animeId: Long): ReiAnixAnimeRecord =
    rawQuery(
        "SELECT * FROM anime WHERE id = ? LIMIT 1",
        arrayOf(animeId.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toAnimeRecord()
        else throw NoSuchElementException("Anime local não encontrado.")
    }

private fun String.readJsonStringSet(field: String): MutableSet<String> = try {
    val array = JSONArray(this)
    buildSet {
        for (index in 0 until array.length()) {
            val value = array.get(index)
            check(value is String) { "Persisted $field contains a non-string item." }
            add(value as String)
        }
    }.toMutableSet()
} catch (error: JSONException) {
    throw IllegalStateException("Persisted $field is invalid; refusing a destructive metadata update.", error)
}

private fun Set<String>.toSortedJsonArray(): String =
    JSONArray().also { array -> sorted().forEach { value -> array.put(value) } }.toString()
