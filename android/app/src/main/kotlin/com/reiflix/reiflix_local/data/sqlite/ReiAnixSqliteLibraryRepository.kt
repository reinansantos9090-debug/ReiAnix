package com.reiflix.reiflix_local.data.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
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
    internal val database: ReiAnixSqliteDatabase,
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
     * Searches the canonical LibraryStore SQLite database and returns persisted anime IDs.
     * SQL applies filters and selected sorting; Kotlin verifies normalized text from one
     * streamed cursor so accent-insensitive matching does not depend on SQLite LOWER().
     */
    suspend fun searchAnimeIds(
        query: String,
        genreKey: String?,
        favoritesOnly: Boolean,
        watchingOnly: Boolean,
        completedOnly: Boolean,
        sortLabel: String?,
        limit: Int,
        offset: Int,
    ): ReiAnixSqliteSearchPage = withContext(Dispatchers.IO) {
        require(limit in 1..199) { "Search page size must be between 1 and 199." }
        require(offset >= 0) { "Search offset cannot be negative." }

        val terms = normalizeNativeSearchText(query).split(' ').filter(String::isNotBlank).distinct()
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (genreKey != null) {
            if (genreKey.startsWith("name:")) {
                val name = genreKey.removePrefix("name:")
                where += "(EXISTS (SELECT 1 FROM anime_genres ag JOIN genres g ON g.id=ag.genre_id WHERE ag.anime_id=a.id AND lower(g.canonical_name)=?) OR a.genres LIKE ?)"
                args += name
                args += "%$name%"
            } else {
                where += "(EXISTS (SELECT 1 FROM anime_genres ag WHERE ag.anime_id=a.id AND ag.genre_id=?) OR a.genres LIKE ?)"
                args += genreKey
                args += "%$genreKey%"
            }
        }
        if (favoritesOnly) where += "COALESCE(a.favorite,0)=1"
        if (watchingOnly) {
            where += """EXISTS (SELECT 1 FROM episodes ew WHERE ew.anime_id=a.id
                AND COALESCE(ew.missing,0)=0 AND COALESCE(ew.watched,0)=0
                AND COALESCE(ew.progress,0)>0
                AND (COALESCE(ew.duration,0)<=0 OR COALESCE(ew.progress,0)<ew.duration*0.9))"""
        }
        if (completedOnly) {
            where += """EXISTS (SELECT 1 FROM episodes ec0 WHERE ec0.anime_id=a.id)
                AND NOT EXISTS (SELECT 1 FROM episodes ec WHERE ec.anime_id=a.id
                    AND (COALESCE(ec.missing,0)=1 OR
                         lower(COALESCE(ec.availability_state,'')) IN ('missing','scope_unavailable','volume_unavailable') OR
                         NOT (COALESCE(ec.watched,0)=1 OR
                              (COALESCE(ec.duration,0)>0 AND COALESCE(ec.progress,0)>=ec.duration*0.9))))"""
        }

        // SQL candidate narrowing uses first-character variants, then the streamed cursor
        // applies the canonical Unicode-normalized token predicate.
        val candidateArgs = mutableListOf<String>()
        for (term in terms) {
            val variants = nativeSearchCharacterVariants(term.first().toString())
            val alternatives = mutableListOf<String>()
            for (variant in variants) {
                alternatives += listOf(
                    "a.title LIKE ? COLLATE NOCASE",
                    "a.lookup_title LIKE ? COLLATE NOCASE",
                    "COALESCE(a.romaji,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.english,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.native,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.aliases,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.description,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.studio,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.genres,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.user_tags,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.personal_note,'') LIKE ? COLLATE NOCASE",
                    "CAST(COALESCE(a.year,'') AS TEXT) LIKE ? COLLATE NOCASE",
                    "COALESCE(a.season,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.status,'') LIKE ? COLLATE NOCASE",
                    "COALESCE(a.format,'') LIKE ? COLLATE NOCASE",
                    "EXISTS (SELECT 1 FROM episodes es WHERE es.anime_id=a.id AND (COALESCE(es.episode_title,'') LIKE ? COLLATE NOCASE OR (COALESCE(es.file_name,'') || ' ' || COALESCE(es.source_folder,'') || ' episode ' || COALESCE(CAST(es.number AS TEXT),'') || ' ep ' || COALESCE(CAST(es.number AS TEXT),'') || ' season ' || CAST(es.season AS TEXT) || ' s' || printf('%02d',es.season) || 'e' || printf('%02d',CAST(COALESCE(es.number,0) AS INTEGER))) LIKE ? COLLATE NOCASE))",
                    "EXISTS (SELECT 1 FROM anime_genres ags JOIN genres gs ON gs.id=ags.genre_id LEFT JOIN genre_aliases gas ON gas.genre_id=gs.id WHERE ags.anime_id=a.id AND (gs.canonical_name LIKE ? COLLATE NOCASE OR COALESCE(gas.alias,'') LIKE ? COLLATE NOCASE))",
                )
                repeat(15) { candidateArgs += "%$variant%" }
                repeat(4) { candidateArgs += "%$variant%" }
            }
            where += "(" + alternatives.joinToString(" OR ") + ")"
        }
        args += candidateArgs

        val sort = when (sortLabel?.trim()) {
            "Nome A-Z" -> "a.title COLLATE NOCASE ASC, a.id ASC"
            "Nome Z-A" -> "a.title COLLATE NOCASE DESC, a.id DESC"
            "Assistidos recentemente" -> "COALESCE((SELECT MAX(e.last_played_at) FROM episodes e WHERE e.anime_id=a.id),0) DESC, a.title COLLATE NOCASE ASC, a.id DESC"
            "Progresso" -> "COALESCE((SELECT AVG(CASE WHEN COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available' AND COALESCE(e.duration,0)>0 THEN MIN(1.0, MAX(COALESCE(e.progress,0),0)/e.duration) ELSE 0 END) FROM episodes e WHERE e.anime_id=a.id),0) DESC, a.title COLLATE NOCASE ASC, a.id DESC"
            "Episódio" -> "CASE WHEN (SELECT e.number FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available' ORDER BY e.season ASC, e.number ASC, e.id ASC LIMIT 1) IS NULL THEN 1 ELSE 0 END ASC, (SELECT e.number FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available' ORDER BY e.season ASC, e.number ASC, e.id ASC LIMIT 1) ASC, a.title COLLATE NOCASE ASC, a.id DESC"
            "Temporada + episódio" -> "CASE WHEN (SELECT MIN(e.season) FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available') IS NULL THEN 1 ELSE 0 END ASC, (SELECT MIN(e.season) FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available') ASC, CASE WHEN (SELECT MIN(e.number) FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available') IS NULL THEN 1 ELSE 0 END ASC, (SELECT MIN(e.number) FROM episodes e WHERE e.anime_id=a.id AND COALESCE(e.missing,0)=0 AND COALESCE(e.availability_state,'available')='available') ASC, a.title COLLATE NOCASE ASC, a.id ASC"
            "Modificação" -> "COALESCE((SELECT MAX(e.modified_at) FROM episodes e WHERE e.anime_id=a.id),0) DESC, a.title COLLATE NOCASE ASC, a.id DESC"
            "Duração" -> "COALESCE((SELECT SUM(COALESCE(e.duration,0)) FROM episodes e WHERE e.anime_id=a.id),0) DESC, a.title COLLATE NOCASE ASC, a.id ASC"
            "Tamanho" -> "COALESCE((SELECT SUM(COALESCE(e.file_size,0)) FROM episodes e WHERE e.anime_id=a.id),0) DESC, a.title COLLATE NOCASE ASC, a.id ASC"
            "Favoritos primeiro" -> "COALESCE(a.favorite,0) DESC, a.title COLLATE NOCASE ASC, a.id ASC"
            "Fixados primeiro" -> "COALESCE(a.is_pinned,0) DESC, a.title COLLATE NOCASE ASC, a.id ASC"
            else -> "a.added_at DESC, a.title COLLATE NOCASE ASC, a.id DESC"
        }

        val sql = """
            SELECT a.id, a.title, a.romaji, a.english, a.native, a.aliases, a.description,
                   a.cover_url, a.cover_cache, a.banner_url, a.genres, a.year, a.season,
                   a.status, a.format, a.studio, a.user_tags, a.personal_note, a.lookup_title,
                   ${if (terms.isEmpty()) "'' AS episode_search_text, '' AS genre_search_text" else """
                   COALESCE((SELECT GROUP_CONCAT(COALESCE(e.episode_title,'') || ' ' || e.file_name ||
                             ' episode ' || COALESCE(CAST(e.number AS TEXT),'') ||
                             ' ep ' || COALESCE(CAST(e.number AS TEXT),''), ' ')
                             FROM episodes e WHERE e.anime_id=a.id), '') AS episode_search_text,
                   COALESCE((SELECT GROUP_CONCAT(g.canonical_name || ' ' || COALESCE(ga.alias,''), ' ')
                             FROM anime_genres ag JOIN genres g ON g.id=ag.genre_id
                             LEFT JOIN genre_aliases ga ON ga.genre_id=g.id WHERE ag.anime_id=a.id), '') AS genre_search_text
                   """.trimIndent()}
            FROM anime a
            ${if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ")}
            ORDER BY $sort
            ${if (terms.isEmpty()) "LIMIT ? OFFSET ?" else ""}
        """.trimIndent()
        val queryArgs = args.toMutableList()
        if (terms.isEmpty()) {
            queryArgs += (limit + 1).toString()
            queryArgs += offset.toString()
        }

        database.read { connection ->
            val matched = mutableListOf<Pair<Long, Int>>()
            val relevanceMode = terms.isNotEmpty() && sortLabel.isNullOrBlank()
            val keepCount = (offset.toLong() + limit + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val queryText = terms.joinToString(" ")
            val relevanceOrder = compareByDescending<Pair<Long, Int>> { it.second }.thenBy { it.first }

            connection.rawQuery(sql, queryArgs.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    if (terms.isNotEmpty()) {
                        val searchable = buildString {
                            for (column in 1..18) {
                                if (!cursor.isNull(column)) append(cursor.getString(column)).append(' ')
                            }
                            append(cursor.getString(19)).append(' ').append(cursor.getString(20))
                        }
                        val normalized = normalizeNativeSearchText(searchable)
                        if (!terms.all { term -> normalized.contains(term) }) continue
                    }

                    val id = cursor.getLong(0)
                    val title = normalizeNativeSearchText(cursor.getString(1))
                    val alternateTitles = listOf(2, 3, 4)
                        .map { column -> normalizeNativeSearchText(cursor.getString(column)) }
                        .filter(String::isNotBlank)
                    val aliases = normalizeNativeSearchText(cursor.getString(5))
                    val studio = normalizeNativeSearchText(cursor.getString(15))
                    val genres = normalizeNativeSearchText(cursor.getString(10) + " " + cursor.getString(20))
                    var score = when {
                        terms.isEmpty() -> 0
                        title == queryText -> 1000
                        title.startsWith(queryText) -> 800
                        title.contains(queryText) -> 600
                        alternateTitles.any { it == queryText } -> 520
                        alternateTitles.any { it.startsWith(queryText) } -> 420
                        alternateTitles.any { it.contains(queryText) } -> 320
                        aliases.contains(queryText) -> 320
                        studio == queryText -> 300
                        else -> 0
                    }
                    score += terms.sumOf { term ->
                        when {
                            title == term -> 350
                            title.contains(term) -> 180
                            alternateTitles.any { it == term } -> 150
                            alternateTitles.any { it.contains(term) } -> 110
                            aliases == term -> 140
                            aliases.contains(term) -> 95
                            genres == term -> 120
                            genres.contains(term) -> 80
                            studio == term -> 50
                            else -> 20
                        }
                    }
                    matched += id to score

                    if (terms.isNotEmpty() && !relevanceMode && matched.size >= keepCount) break
                    if (relevanceMode && matched.size > keepCount) {
                        matched.sortWith(relevanceOrder)
                        matched.removeAt(matched.lastIndex)
                    }
                }
            }
            val ordered = if (relevanceMode) matched.sortedWith(relevanceOrder) else matched
            val page = if (terms.isEmpty()) ordered else ordered.drop(offset).take(limit + 1)
            ReiAnixSqliteSearchPage(page.take(limit).map { it.first }, offset, limit, page.size > limit)
        }
    }

    private fun normalizeNativeSearchText(value: String): String =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
            .replace("\\p{M}+".toRegex(), "")
            .lowercase(java.util.Locale.ROOT)
            .replace("[^\\p{L}\\p{Nd}]+".toRegex(), " ")
            .trim()
            .replace("\\s+".toRegex(), " ")

    private fun nativeSearchCharacterVariants(character: String): Set<String> {
        val base = java.text.Normalizer.normalize(character, java.text.Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
        return when (base.lowercase(java.util.Locale.ROOT)) {
            "a" -> setOf("a", "á", "à", "â", "ã", "ä", "å", "ā")
            "e" -> setOf("e", "é", "è", "ê", "ë", "ē")
            "i" -> setOf("i", "í", "ì", "î", "ï", "ī")
            "o" -> setOf("o", "ó", "ò", "ô", "õ", "ö", "ō")
            "u" -> setOf("u", "ú", "ù", "û", "ü", "ū")
            "c" -> setOf("c", "ç", "ć", "č")
            "n" -> setOf("n", "ñ", "ń")
            else -> setOf(character)
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

    /**
     * Persists a user-authorized SAF root in the existing canonical folders table.
     * This mirrors the legacy identity-aware upsert without creating a parallel store.
     */
    suspend fun upsertSafFolder(
        path: String,
        name: String?,
        authorization: String = "granted",
        accountId: String? = null,
        safAuthority: String? = null,
        safDocumentId: String? = null,
        safVolumeId: String? = null,
        safIdentity: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val reference = path.trim()
        require(reference.isNotEmpty()) { "SAF folder URI cannot be empty." }
        require(reference.startsWith("content://")) { "SAF folder must remain a content URI." }
        val normalizedName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: reference.substringAfterLast('/').ifBlank { reference }

        database.transaction { connection ->
            if (!safIdentity.isNullOrBlank()) {
                val existingPath = connection.rawQuery(
                    "SELECT path FROM folders WHERE saf_identity = ? LIMIT 1",
                    arrayOf(safIdentity),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (!existingPath.isNullOrBlank() && existingPath != reference) {
                    connection.execSQL(
                        """UPDATE folders SET name = ?, kind = 'saf', authorization = ?,
                           account_id = COALESCE(?, account_id),
                           saf_authority = COALESCE(?, saf_authority),
                           saf_document_id = COALESCE(?, saf_document_id),
                           saf_volume_id = COALESCE(?, saf_volume_id),
                           saf_identity = ?, last_error = NULL, last_scan_at = NULL
                           WHERE path = ?""",
                        arrayOf(
                            normalizedName, authorization, accountId, safAuthority,
                            safDocumentId, safVolumeId, safIdentity, existingPath,
                        ),
                    )
                    return@transaction existingPath
                }
            }
            connection.execSQL(
                """INSERT INTO folders(
                       path, name, kind, authorization, account_id, added_at,
                       saf_authority, saf_document_id, saf_volume_id, saf_identity
                   ) VALUES (?, ?, 'saf', ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(path) DO UPDATE SET
                       name = excluded.name,
                       kind = 'saf',
                       authorization = excluded.authorization,
                       account_id = COALESCE(excluded.account_id, folders.account_id),
                       saf_authority = COALESCE(excluded.saf_authority, folders.saf_authority),
                       saf_document_id = COALESCE(excluded.saf_document_id, folders.saf_document_id),
                       saf_volume_id = COALESCE(excluded.saf_volume_id, folders.saf_volume_id),
                       saf_identity = COALESCE(excluded.saf_identity, folders.saf_identity),
                       last_error = NULL""",
                arrayOf(
                    reference, normalizedName, authorization, accountId,
                    System.currentTimeMillis() / 1000.0, safAuthority,
                    safDocumentId, safVolumeId, safIdentity,
                ),
            )
            reference
        }
    }

    suspend fun updateFolderAuthorization(
        path: String,
        authorization: String,
        error: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val reference = path.trim()
        if (reference.isEmpty()) return@withContext false
        database.transaction { connection ->
            val exists = connection.rawQuery(
                "SELECT 1 FROM folders WHERE path = ? LIMIT 1",
                arrayOf(reference),
            ).use { it.moveToFirst() }
            if (!exists) return@transaction false
            connection.execSQL(
                "UPDATE folders SET authorization = ?, last_error = ? WHERE path = ?",
                arrayOf(authorization, error, reference),
            )
            true
        }
    }

    suspend fun removeFolder(path: String): Boolean = withContext(Dispatchers.IO) {
        val reference = path.trim()
        if (reference.isEmpty()) return@withContext false
        database.transaction { connection ->
            connection.delete("folders", "path = ?", arrayOf(reference)) > 0
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

    /**
     * Registers a selected SAF tree in the canonical folders table.
     * Repeated selections update one source; conflicting identities are rejected.
     */
    suspend fun upsertSafFolder(
        reference: String, name: String?, authority: String?, documentId: String?,
        volumeId: String?, identity: String?,
    ): String = withContext(Dispatchers.IO) {
        val normalized = reference.trim()
        require(normalized.isNotEmpty()) { "A SAF tree URI is required." }
        val treeUri = runCatching { Uri.parse(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("The SAF tree URI is invalid.")
        require(treeUri.scheme.equals("content", ignoreCase = true) && DocumentsContract.isTreeUri(treeUri)) {
            "Only valid SAF tree content URIs can be registered."
        }
        val normalizedName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: normalized.substringAfterLast('/').ifBlank { normalized }
        val key = identity?.trim()?.takeIf { it.isNotEmpty() }
        val provider = authority?.trim()?.takeIf { it.isNotEmpty() }
        val document = documentId?.trim()?.takeIf { it.isNotEmpty() }
        val volume = volumeId?.trim()?.takeIf { it.isNotEmpty() }
        database.transaction { connection ->
            fun queryString(sql: String, args: Array<String>): String? =
                connection.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val previousPath = key?.let {
                queryString("SELECT path FROM folders WHERE kind='saf' AND saf_identity=? LIMIT 1", arrayOf(it))
            }
            val targetKind = queryString("SELECT kind FROM folders WHERE path=? LIMIT 1", arrayOf(normalized))
            require(targetKind == null || targetKind.equals("saf", true)) {
                "The selected URI is already registered as a different source type."
            }
            require(!(targetKind != null && previousPath != null && previousPath != normalized)) {
                "The selected URI already exists beside another SAF identity; refusing an ambiguous merge."
            }
            val existingIdentity = queryString(
                "SELECT COALESCE(saf_identity,'') FROM folders WHERE path=? AND kind='saf' LIMIT 1",
                arrayOf(normalized),
            )
            require(existingIdentity.isNullOrBlank() || key == null || existingIdentity == key) {
                "The selected URI is already registered with another SAF identity."
            }
            if (previousPath != null && previousPath != normalized) {
                val changed = connection.update(
                    "folders", ContentValues().apply { put("path", normalized) },
                    "path=? AND kind='saf'", arrayOf(previousPath),
                )
                check(changed == 1) { "The existing SAF source changed during registration." }
                connection.update(
                    "episodes", ContentValues().apply { put("source_folder", normalized) },
                    "source_folder=?", arrayOf(previousPath),
                )
            }
            val values = ContentValues().apply {
                put("name", normalizedName); put("kind", "saf"); put("authorization", "granted")
                putNull("last_error")
                provider?.let { put("saf_authority", it) }
                document?.let { put("saf_document_id", it) }
                volume?.let { put("saf_volume_id", it) }
                key?.let { put("saf_identity", it) }
            }
            if (connection.update("folders", values, "path=?", arrayOf(normalized)) == 0) {
                values.put("path", normalized)
                values.put("added_at", System.currentTimeMillis() / 1000.0)
                connection.insertOrThrow("folders", null, values)
            }
            normalized
        }
    }

    suspend fun updateSafFolderAccess(reference: String, authorization: String, error: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val normalized = reference.trim()
            require(normalized.isNotEmpty()) { "A folder reference is required." }
            val status = authorization.trim().lowercase()
            require(status in setOf("granted", "revoked", "unavailable", "unknown")) {
                "Unsupported SAF authorization state."
            }
            database.transaction { connection ->
                val values = ContentValues().apply {
                    put("authorization", status)
                    if (error.isNullOrBlank()) putNull("last_error") else put("last_error", error)
                }
                connection.update("folders", values, "path=? AND kind='saf'", arrayOf(normalized)) > 0
            }
        }

    /**
     * Re-keys one provider-confirmed SAF document URI without replacing the canonical
     * episode row. This preserves history, playback progress, metadata and artwork.
     */
    suspend fun reconcileSafDocumentIdentityChange(
        oldUri: String,
        newUri: String,
        name: String,
        treeUri: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val oldReference = oldUri.trim()
        val newReference = newUri.trim()
        val newName = name.trim()
        val treeReference = treeUri.trim()
        val old = runCatching { Uri.parse(oldReference) }.getOrNull()
        val new = runCatching { Uri.parse(newReference) }.getOrNull()
        val tree = runCatching { Uri.parse(treeReference) }.getOrNull()
        fun result(status: String, episodeId: Long? = null) = JSONObject()
            .put("status", status)
            .put("episode_id", episodeId ?: JSONObject.NULL)

        if (
            old == null || new == null || tree == null ||
            !old.scheme.equals("content", ignoreCase = true) ||
            !new.scheme.equals("content", ignoreCase = true) ||
            !tree.scheme.equals("content", ignoreCase = true) ||
            old.authority.isNullOrBlank() || new.authority.isNullOrBlank() ||
            !DocumentsContract.isTreeUri(tree) || newName.isBlank()
        ) return@withContext result("rejected")
        if (!old.authority.equals(new.authority, ignoreCase = true)) return@withContext result("rejected")

        val now = System.currentTimeMillis() / 1000.0
        database.transaction { connection ->
            fun isSafObservation(episodeId: Long, uri: String): Boolean =
                connection.rawQuery(
                    "SELECT 1 FROM episode_observations WHERE episode_id=? AND source_kind='saf' AND uri=? LIMIT 1",
                    arrayOf(episodeId.toString(), uri),
                ).use { it.moveToFirst() }

            fun belongsToTree(sourceFolder: String?): Boolean {
                val source = sourceFolder.orEmpty()
                return source == treeReference || source.startsWith("$treeReference/")
            }

            if (oldReference == newReference) {
                val source = connection.rawQuery(
                    "SELECT id,file_name,source_folder FROM episodes WHERE path=? LIMIT 1",
                    arrayOf(newReference),
                ).use { cursor ->
                    if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()) else null
                } ?: return@transaction result("no_match")
                if (!belongsToTree(source.third) && !isSafObservation(source.first, newReference)) {
                    return@transaction result("rejected", source.first)
                }
                if (source.second == newName) return@transaction result("already_reconciled", source.first)
                connection.execSQL(
                    "UPDATE episodes SET file_name=?,missing=0,availability_state='available' WHERE id=? AND path=?",
                    arrayOf(newName, source.first, newReference),
                )
                return@transaction result("reconciled", source.first)
            }

            val source = connection.rawQuery(
                "SELECT id,file_name,source_folder FROM episodes WHERE path=? LIMIT 1",
                arrayOf(oldReference),
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()) else null
            }
            if (source == null) {
                val target = connection.rawQuery(
                    "SELECT id,source_folder FROM episodes WHERE path=? LIMIT 1",
                    arrayOf(newReference),
                ).use { cursor ->
                    if (cursor.moveToFirst()) Pair(cursor.getLong(0), cursor.getString(1).orEmpty()) else null
                }
                return@transaction when {
                    target == null -> result("no_match")
                    belongsToTree(target.second) || isSafObservation(target.first, newReference) ->
                        result("already_reconciled", target.first)
                    else -> result("conflict", target.first)
                }
            }
            val episodeId = source.first
            if (!belongsToTree(source.third) && !isSafObservation(episodeId, oldReference)) {
                return@transaction result("rejected", episodeId)
            }

            val targetId = connection.rawQuery(
                "SELECT id FROM episodes WHERE path=? LIMIT 1",
                arrayOf(newReference),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (targetId != null && targetId != episodeId) return@transaction result("conflict", episodeId)

            val observationConflict = connection.rawQuery(
                "SELECT 1 FROM episode_observations WHERE episode_id=? AND source_kind='saf' AND uri=? AND uri<>? LIMIT 1",
                arrayOf(episodeId.toString(), newReference, oldReference),
            ).use { it.moveToFirst() }
            if (observationConflict) return@transaction result("conflict", episodeId)

            connection.execSQL(
                "UPDATE episodes SET path=?,file_name=?,missing=0,availability_state='available' WHERE id=? AND path=?",
                arrayOf(newReference, newName, episodeId, oldReference),
            )
            connection.execSQL(
                "UPDATE episode_observations SET uri=?,state='available',error=NULL,last_seen=?,last_checked_at=? " +
                    "WHERE episode_id=? AND source_kind='saf' AND uri=?",
                arrayOf(newReference, now, now, episodeId, oldReference),
            )
            result("reconciled", episodeId)
        }
    }

    /**
     * Persists Android's authoritative volume snapshot in the existing preferences
     * table and reconciles episode availability in the same canonical SQLite file.
     * This mirrors the old LibraryStore operation without creating another store.
     */
    suspend fun recordNativeVolumeChange(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val eventAt = sequenceOf(
            payload.optDouble("eventTimestamp", 0.0),
            payload.optDouble("timestamp", 0.0),
            payload.optDouble("observedAt", 0.0),
        ).firstOrNull { it > 0.0 } ?: 0.0
        val now = System.currentTimeMillis() / 1000.0
        database.transaction { connection ->
            fun preference(key: String, fallback: String): String =
                connection.rawQuery(
                    "SELECT value FROM preferences WHERE key=? LIMIT 1",
                    arrayOf(key),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) ?: fallback else fallback }

            val lastEventAt = preference("native_volume_event_at", "0").toDoubleOrNull() ?: 0.0
            val previous = runCatching { JSONObject(preference("native_volume_states", "{}")) }
                .getOrElse { JSONObject() }
            if (eventAt > 0.0 && lastEventAt > 0.0 && eventAt < lastEventAt) {
                return@transaction JSONObject(previous.toString()).put("ignored", true)
            }

            val merged = JSONObject(previous.toString())
            val current = payload.optJSONArray("current") ?: JSONArray()
            val removed = payload.optJSONArray("removed") ?: JSONArray()
            val mountedStates = setOf("mounted", "mounted_ro", "mounted_rofs")

            fun markUnavailable(volumeId: String, reason: String) {
                if (volumeId.isBlank()) return
                val episodeIds = linkedSetOf<Long>()
                connection.rawQuery(
                    "SELECT DISTINCT episode_id FROM episode_observations WHERE volume_id=?",
                    arrayOf(volumeId),
                ).use { cursor -> while (cursor.moveToNext()) episodeIds.add(cursor.getLong(0)) }
                episodeIds.forEach { episodeId ->
                    connection.execSQL(
                        "UPDATE episode_observations SET state='volume_unavailable',error=?,last_checked_at=? " +
                            "WHERE volume_id=? AND episode_id=? AND state != 'missing'",
                        arrayOf(reason, now, volumeId, episodeId),
                    )
                    recomputeEpisodeAvailability(connection, episodeId)
                }
                connection.execSQL(
                    "UPDATE episodes SET missing=1,availability_state='volume_unavailable' " +
                        "WHERE volume_id=? AND availability_state != 'scope_removed' " +
                        "AND id NOT IN (SELECT episode_id FROM episode_observations WHERE volume_id=?)",
                    arrayOf(volumeId, volumeId),
                )
            }

            for (index in 0 until current.length()) {
                val item = current.optJSONObject(index) ?: continue
                val volumeId = item.optString("volumeId").trim()
                if (volumeId.isBlank()) continue
                val state = item.optString("state", "unknown").trim().lowercase().ifBlank { "unknown" }
                val available = if (item.has("available")) item.optBoolean("available", false) else state in mountedStates
                val stateRecord = JSONObject()
                    .put("volumeId", volumeId)
                    .put("uuid", item.optString("uuid"))
                    .put("state", state)
                    .put("available", available)
                    .put("removable", item.optBoolean("removable"))
                    .put("emulated", item.optBoolean("emulated"))
                    .put("primary", item.optBoolean("primary"))
                    .put("directory", item.optString("directory"))
                    .put("description", item.optString("description"))
                    .put("observed_at", now)
                merged.put(volumeId, stateRecord)
                if (!available) markUnavailable(volumeId, state)
            }

            for (index in 0 until removed.length()) {
                val item = removed.optJSONObject(index) ?: continue
                val volumeId = item.optString("volumeId").ifBlank { item.optString("uuid") }.trim()
                if (volumeId.isBlank()) continue
                val stateRecord = merged.optJSONObject(volumeId)?.let { JSONObject(it.toString()) }
                    ?: JSONObject().put("volumeId", volumeId)
                stateRecord.put("volumeId", volumeId)
                    .put("state", "unavailable")
                    .put("available", false)
                    .put("unavailable_at", now)
                    .put("reason", payload.optString("reason", "volume_removed"))
                merged.put(volumeId, stateRecord)
                markUnavailable(volumeId, payload.optString("reason", "volume_removed"))
            }

            connection.execSQL(
                "INSERT INTO preferences(key,value,updated_at) VALUES(?,?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
                arrayOf("native_volume_states", merged.toString(), now),
            )
            if (eventAt > 0.0) {
                connection.execSQL(
                    "INSERT INTO preferences(key,value,updated_at) VALUES(?,?,?) " +
                        "ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at",
                    arrayOf("native_volume_event_at", maxOf(eventAt, lastEventAt).toString(), now),
                )
            }
            JSONObject(merged.toString()).put("ignored", false)
        }
    }

    /**
     * Finalizes native SAF scan bookkeeping in the canonical schema. The scan-run
     * row is operational metadata; catalog writes/reconciliation remain in the
     * preceding indexDiscoveredMedia transaction.
     */
    suspend fun finishNativeScanRun(
        scanId: String,
        requestId: String?,
        sourceKind: String,
        scopeKind: String,
        scopeRef: String,
        nativeGeneration: Long?,
        generationId: String?,
        status: String,
        stats: JSONObject,
        indexReport: JSONObject?,
        startedAtMillis: Long?,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        require(scanId.isNotBlank()) { "A scan ID is required." }
        require(scopeRef.isNotBlank()) { "A scan scope reference is required." }
        val normalizedSource = sourceKind.trim().lowercase().ifBlank { "unknown" }
        val normalizedScopeKind = scopeKind.trim().lowercase().ifBlank { "source" }
        val normalizedStatus = status.trim().uppercase().ifBlank { "FAILED" }
        val finalStatus = when (normalizedStatus) {
            "COMPLETED", "COMPLETE" -> "completed"
            "EMPTY_COMPLETE" -> "empty_complete"
            "PARTIAL" -> "partial"
            "UNAVAILABLE" -> "unavailable"
            "REVOKED" -> "revoked"
            "CANCELLED", "CANCELED" -> "cancelled"
            else -> "error"
        }
        val generationStatus = when (normalizedStatus) {
            "COMPLETED", "COMPLETE" -> "COMPLETED"
            "EMPTY_COMPLETE" -> "EMPTY_COMPLETE"
            "PARTIAL" -> "PARTIAL"
            "UNAVAILABLE", "REVOKED" -> "UNAVAILABLE"
            "CANCELLED", "CANCELED" -> "CANCELLED"
            else -> "FAILED"
        }
        val cancelled = normalizedStatus in setOf("CANCELLED", "CANCELED")
        val startedSeconds = (startedAtMillis?.takeIf { it > 0L } ?: finishedAtMillis) / 1000.0
        val finishedSeconds = finishedAtMillis / 1000.0
        val elapsedMillis = (finishedAtMillis - (startedAtMillis?.takeIf { it > 0L } ?: finishedAtMillis)).coerceAtLeast(0L)
        val errors = linkedSetOf<String>()
        stats.optJSONArray("errors")?.let { raw ->
            for (index in 0 until raw.length()) raw.optString(index).trim().takeIf { it.isNotEmpty() }?.let(errors::add)
        }
        indexReport?.optString("terminalError")?.trim()?.takeIf { it.isNotEmpty() }?.let(errors::add)
        val files = stats.optInt("files", stats.optInt("discovered", 0)).coerceAtLeast(0)
        val videos = stats.optInt("videos", 0).coerceAtLeast(0)
        val inserted = indexReport?.optInt("inserted", stats.optInt("new", 0)) ?: stats.optInt("new", 0)
        val updated = indexReport?.optInt("updated", stats.optInt("updated", 0)) ?: stats.optInt("updated", 0)
        val unchanged = indexReport?.optInt("unchanged", stats.optInt("unchanged", 0)) ?: stats.optInt("unchanged", 0)
        val reconciled = indexReport?.optInt("reconciledMissing", 0) ?: 0
        val ignored = stats.optInt("ignored", 0).coerceAtLeast(0)
        val duplicates = stats.optInt("duplicates", 0).coerceAtLeast(0)
        val unknown = stats.optInt("unknown", 0).coerceAtLeast(0)
        val folderAuthorization = when (normalizedStatus) {
            "COMPLETED", "COMPLETE", "EMPTY_COMPLETE", "PARTIAL" -> "granted"
            "UNAVAILABLE" -> "unavailable"
            "REVOKED" -> "revoked"
            else -> null
        }

        database.transaction { connection ->
            val existing = connection.rawQuery(
                "SELECT id, started_at, errors FROM scan_runs WHERE scan_id = ? ORDER BY id DESC LIMIT 1",
                arrayOf(scanId),
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.getDouble(1), cursor.getString(2).orEmpty()) else null
            }
            val mergedErrors = linkedSetOf<String>()
            existing?.third?.let { raw ->
                runCatching { JSONArray(raw) }.getOrNull()?.let { prior ->
                    for (index in 0 until prior.length()) prior.optString(index).trim().takeIf { it.isNotEmpty() }?.let(mergedErrors::add)
                }
            }
            mergedErrors.addAll(errors)
            val values = ContentValues().apply {
                put("finished_at", finishedSeconds)
                put("status", finalStatus)
                put("folders", 1)
                put("files", files)
                put("videos", videos)
                put("animes", connection.rawQuery("SELECT COUNT(*) FROM anime", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
                put("episodes", connection.rawQuery("SELECT COUNT(*) FROM episodes", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
                put("new_files", inserted.coerceAtLeast(0))
                put("updated_files", updated.coerceAtLeast(0))
                put("unchanged_files", unchanged.coerceAtLeast(0))
                put("ignored_files", ignored)
                put("duplicate_files", duplicates)
                put("unknown_files", unknown)
                put("reconciled_files", reconciled.coerceAtLeast(0))
                put("scan_id", scanId)
                put("request_id", requestId)
                put("source", normalizedSource)
                put("scope", scopeRef)
                put("source_kind", normalizedSource)
                put("scope_kind", normalizedScopeKind)
                put("scope_ref", scopeRef)
                if (nativeGeneration == null) putNull("native_generation") else put("native_generation", nativeGeneration)
                put("generation_id", generationId ?: scanId)
                put("generation_status", generationStatus)
                put("cancelled", if (cancelled) 1 else 0)
                put("discovered", stats.optInt("discovered", files).coerceAtLeast(0))
                put("processed", stats.optInt("processed", files).coerceAtLeast(0))
                put("inserted_files", inserted.coerceAtLeast(0))
                put("removed_files", reconciled.coerceAtLeast(0))
                put("elapsed_ms", elapsedMillis)
                put("errors", JSONArray(mergedErrors.toList()).toString())
            }
            if (existing == null) {
                values.put("started_at", startedSeconds)
                connection.insertOrThrow("scan_runs", null, values)
            } else {
                values.put("started_at", existing.second)
                connection.update("scan_runs", values, "id = ?", arrayOf(existing.first.toString()))
            }
            if (normalizedSource == "saf" && folderAuthorization != null) {
                val folderValues = ContentValues().apply {
                    put("authorization", folderAuthorization)
                    put("last_scan_at", finishedSeconds)
                    val error = mergedErrors.firstOrNull()
                    if (error == null || folderAuthorization == "granted") putNull("last_error") else put("last_error", error)
                }
                connection.update("folders", folderValues, "path = ? AND kind = 'saf'", arrayOf(scopeRef))
            }
            true
        }
    }

    suspend fun removeSafFolder(reference: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = reference.trim()
        require(normalized.isNotEmpty()) { "A folder reference is required." }
        database.transaction { connection ->
            val folderIdentity = connection.rawQuery(
                "SELECT COALESCE(saf_identity,'') FROM folders WHERE path=? AND kind='saf' LIMIT 1",
                arrayOf(normalized),
            ).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else null }
                ?: return@transaction false
            val scopes = setOf(normalized, folderIdentity).filter { it.isNotBlank() }
            val episodes = linkedSetOf<Long>()
            scopes.forEach { scope ->
                connection.rawQuery(
                    "SELECT DISTINCT episode_id FROM episode_observations WHERE source_kind='saf' AND scope_ref=?",
                    arrayOf(scope),
                ).use { c -> while (c.moveToNext()) episodes.add(c.getLong(0)) }
            }
            val now = System.currentTimeMillis() / 1000.0
            scopes.forEach { scope ->
                connection.execSQL(
                    "UPDATE episode_observations SET state='scope_removed', error='source_removed', last_checked_at=? " +
                        "WHERE source_kind='saf' AND scope_ref=?",
                    arrayOf(now, scope),
                )
            }
            episodes.forEach { recomputeEpisodeAvailability(connection, it) }
            connection.execSQL(
                "UPDATE episodes SET missing=1, availability_state='scope_removed' WHERE source_folder=? " +
                    "AND id NOT IN (SELECT episode_id FROM episode_observations)",
                arrayOf(normalized),
            )
            connection.delete("folders", "path=? AND kind='saf'", arrayOf(normalized)) > 0
        }
    }

    private fun recomputeEpisodeAvailability(connection: SQLiteDatabase, episodeId: Long) {
        val latestByLocation = linkedMapOf<String, String>()
        connection.rawQuery(
            "SELECT source_kind,scope_kind,scope_ref,volume_id,state FROM episode_observations " +
                "WHERE episode_id=? ORDER BY last_checked_at DESC,id DESC",
            arrayOf(episodeId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val source = c.getString(0).orEmpty().trim().lowercase().ifBlank { "unknown" }
                val kind = c.getString(1).orEmpty().trim().lowercase().ifBlank { "source" }
                val scope = c.getString(2).orEmpty().trim()
                val volume = c.getString(3).orEmpty().trim()
                val key = if (volume.isNotEmpty()) "$source|volume|$volume" else "$source|$kind|$scope"
                if (!latestByLocation.containsKey(key)) latestByLocation[key] = c.getString(4).orEmpty().trim().lowercase()
            }
        }
        if (latestByLocation.isEmpty()) return
        val states = latestByLocation.values.toSet()
        val (missing, availability) = when {
            "available" in states -> 0 to "available"
            "volume_unavailable" in states -> 1 to "volume_unavailable"
            "scope_unavailable" in states -> 1 to "scope_unavailable"
            "unavailable" in states -> 1 to "unavailable"
            states.all { it == "scope_removed" } -> 1 to "scope_removed"
            else -> 1 to "missing"
        }
        connection.execSQL("UPDATE episodes SET missing=?, availability_state=? WHERE id=?",
            arrayOf(missing, availability, episodeId))
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
    suspend fun indexDiscoveredMedia(
        snapshot: NativeLibraryScanSnapshot,
        onTransactionOutcome: ((
            ReiAnixSqliteTransactionOutcome,
            NativeLibraryIndexReport?,
        ) -> Unit)? = null,
    ): NativeLibraryIndexReport = withContext(Dispatchers.IO) {
            require(snapshot.operationId.isNotBlank()) { "A scan operation ID is required." }
            require(snapshot.scopeRoot.isNotBlank()) { "An explicit scope root is required." }
            var transactionReport: NativeLibraryIndexReport? = null
            val report = database.transaction(
                onOutcome = { outcome ->
                    val committedReport = if (outcome == ReiAnixSqliteTransactionOutcome.COMMITTED) {
                        transactionReport
                    } else {
                        null
                    }
                    runCatching { onTransactionOutcome?.invoke(outcome, committedReport) }
                },
            ) { db ->
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
                        val folder = item.sourceFolder?.takeIf { it.isNotBlank() }
                            ?: snapshot.scopeReference.trim().takeIf { it.isNotEmpty() }
                            ?: snapshot.scopeRoot
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
                        put(
                            "source_folder",
                            item.sourceFolder?.takeIf { it.isNotBlank() }
                                ?: snapshot.scopeReference.trim().takeIf { it.isNotEmpty() }
                                ?: snapshot.scopeRoot,
                        )
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
                    // scopeRoot is scanner evidence (e.g. SAF document ID); scopeReference is the
                    // persisted source key used by LibraryStore (e.g. the granted tree URI).
                    val root = snapshot.scopeReference.trim().ifBlank { snapshot.scopeRoot.trim() }
                        .replace('\\', '/').trimEnd('/')
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
                ).also { transactionReport = it }
            }
            report
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

internal fun Cursor.toAnimeRecord() = ReiAnixAnimeRecord(
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

internal fun Cursor.toEpisodeRecord() = ReiAnixEpisodeRecord(
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

/** A bounded page of persisted anime identities returned by native SQLite search. */
data class ReiAnixSqliteSearchPage(
    val animeIds: List<Long>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)
