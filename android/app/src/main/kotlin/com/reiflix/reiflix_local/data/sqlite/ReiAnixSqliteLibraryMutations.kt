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

/** Idempotent favorite mutation for callers that already know the target state. */
suspend fun ReiAnixSqliteLibraryRepository.setAnimeFavorite(
    animeId: Long,
    favorite: Boolean,
): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
    require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
    database.transaction { connection ->
        connection.requireAnime(animeId)
        val values = ContentValues().apply { put("favorite", if (favorite) 1 else 0) }
        connection.update("anime", values, "id = ?", arrayOf(animeId.toString()))
        connection.requireAnime(animeId)
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
        if (tag.isNotEmpty() && seen.add(tag.lowercase(Locale.ROOT))) {
            normalized += tag.take(40)
        }
    }
    val serialized = JSONArray().also { array -> normalized.forEach(array::put) }.toString()
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
        val fieldsToRemove = fields?.let { manualFields.intersect(it) } ?: manualFields.toSet()
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
    JSONArray().also { array -> sorted().forEach(array::put) }.toString()
