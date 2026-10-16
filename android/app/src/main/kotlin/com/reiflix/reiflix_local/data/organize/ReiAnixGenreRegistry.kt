package com.reiflix.reiflix_local.data.organize

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.UUID

/**
 * Native owner of genre identity/aliases/associations in the existing LibraryStore
 * schema v29. It never creates or migrates tables and never opens a second database.
 *
 * initialize() is explicit (not called by constructors or Application startup) so
 * legacy association backfill runs only when a feature requests it.
 */
class ReiAnixGenreRegistry(private val database: ReiAnixSqliteDatabase) {
    data class Genre(
        val id: String,
        val canonicalName: String,
        val normalizedName: String,
        val source: String,
        val aliases: List<String>,
        val isSystem: Boolean,
        val isCustom: Boolean,
        val animeCount: Int = 0,
    )

    data class ClassificationReport(
        val scanned: Int,
        val classifiedAnime: Int,
        val assignedGenreCount: Int,
        val skippedAuthoritativeMetadata: Int,
        val withoutRuleMatch: Int,
        val changedAnimeIds: List<Long>,
    )

    private data class ClassificationCandidate(
        val id: Long,
        val title: String,
        val genres: String?,
        val source: String?,
        val aniListId: Long?,
        val manualFields: String?,
    )

    /**
     * Seeds the seven legacy system identities and aliases and backfills existing
     * JSON genre columns into anime_genres without removing associations by source.
     * The operation is idempotent and fully transactional.
     */
    suspend fun initialize(): Int = withContext(Dispatchers.IO) {
        database.transaction { connection ->
            seedSystemAliases(connection)
            val legacy = connection.rawQuery(
                "SELECT id, genres FROM anime WHERE genres IS NOT NULL AND TRIM(genres) != '' ORDER BY id",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(0) to cursor.getString(1))
                    }
                }
            }
            var associations = 0
            legacy.forEach { (animeId, raw) ->
                val names = parseReiAnixGenreNames(raw)
                if (names.isNotEmpty()) {
                    associations += syncAnimeInTransaction(
                        connection = connection,
                        animeId = animeId,
                        names = names,
                        source = "local",
                        replaceSource = false,
                    ).size
                }
            }
            associations
        }
    }

    suspend fun register(
        name: String,
        source: String = "local",
        isSystem: Boolean = false,
        isCustom: Boolean = false,
    ): Genre = withContext(Dispatchers.IO) {
        database.transaction { connection ->
            seedSystemAliases(connection)
            registerInTransaction(connection, name, source, isSystem, isCustom)
        }
    }

    suspend fun resolve(name: String): Genre? = withContext(Dispatchers.IO) {
        val normalized = normalizeReiAnixGenre(name)
        if (normalized.isBlank()) return@withContext null
        database.read { connection -> resolveInTransaction(connection, normalized) }
    }

    suspend fun addAlias(
        genreId: String,
        alias: String,
        source: String = "system",
    ): Genre? = withContext(Dispatchers.IO) {
        database.transaction { connection ->
            seedSystemAliases(connection)
            addAliasInTransaction(connection, genreId, alias, source)
        }
    }

    suspend fun syncAnime(
        animeId: Long,
        names: List<String>,
        source: String = "local",
        replaceSource: Boolean = true,
    ): List<Genre> = withContext(Dispatchers.IO) {
        require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
        database.transaction { connection ->
            seedSystemAliases(connection)
            syncAnimeInTransaction(connection, animeId, names, source, replaceSource)
        }
    }

    suspend fun attach(
        animeId: Long,
        genreId: String,
        source: String = "user",
    ): Boolean = withContext(Dispatchers.IO) {
        require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
        database.transaction { connection ->
            val animeExists = connection.rawQuery(
                "SELECT 1 FROM anime WHERE id=? LIMIT 1",
                arrayOf(animeId.toString()),
            ).use { it.moveToFirst() }
            require(animeExists) { "Anime não encontrado no catálogo local." }
            val genreExists = connection.rawQuery(
                "SELECT 1 FROM genres WHERE id=? LIMIT 1",
                arrayOf(genreId),
            ).use(Cursor::moveToFirst)
            require(genreExists) { "Gênero não encontrado." }
            val now = nowSeconds()
            connection.execSQL(
                "INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) VALUES(?,?,?,?,?)",
                arrayOf(animeId, genreId, source.normalizedSource(), now, now),
            )
            true
        }
    }

    suspend fun detach(
        animeId: Long,
        genreId: String,
        source: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
        database.transaction { connection ->
            if (source == null) {
                connection.execSQL(
                    "DELETE FROM anime_genres WHERE anime_id=? AND genre_id=?",
                    arrayOf(animeId, genreId),
                )
            } else {
                connection.execSQL(
                    "DELETE FROM anime_genres WHERE anime_id=? AND genre_id=? AND source=?",
                    arrayOf(animeId, genreId, source.normalizedSource()),
                )
            }
            true
        }
    }

    suspend fun forAnime(animeId: Long): List<Genre> = withContext(Dispatchers.IO) {
        require(animeId > 0L) { "Anime ID must be a positive persisted ID." }
        database.read { connection ->
            connection.rawQuery(
                "SELECT DISTINCT g.* FROM genres g JOIN anime_genres ag ON ag.genre_id=g.id " +
                    "WHERE ag.anime_id=? ORDER BY g.normalized_name",
                arrayOf(animeId.toString()),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toGenre(connection)) } }
        }
    }

    suspend fun listAll(): List<Genre> = withContext(Dispatchers.IO) {
        database.read { connection ->
            connection.rawQuery(
                "SELECT g.*, COUNT(DISTINCT ag.anime_id) AS anime_count " +
                    "FROM genres g LEFT JOIN anime_genres ag ON ag.genre_id=g.id " +
                    "GROUP BY g.id ORDER BY g.normalized_name",
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toGenre(connection)) } }
        }
    }

    /**
     * Explicit user-triggered classification. Only records with an empty genre
     * JSON and no genre associations are considered. AniList-associated records,
     * manually-owned genre metadata, and records with existing labels are never
     * overwritten. Unknown titles remain unknown instead of receiving placeholders.
     */
    suspend fun classifyMissingLocalGenres(limit: Int = DEFAULT_CLASSIFICATION_LIMIT): ClassificationReport =
        withContext(Dispatchers.IO) {
            require(limit in 1..MAX_CLASSIFICATION_LIMIT) {
                "Classification limit must be between 1 and $MAX_CLASSIFICATION_LIMIT."
            }
            database.transaction { connection ->
                seedSystemAliases(connection)
                val candidates = connection.rawQuery(
                    """
                    SELECT a.id, COALESCE(NULLIF(TRIM(a.title),''), a.lookup_title), a.genres,
                           a.metadata_source, a.anilist_id, a.metadata_manual_fields
                    FROM anime a
                    WHERE (a.genres IS NULL OR TRIM(a.genres)='' OR TRIM(a.genres)='[]')
                      AND NOT EXISTS (SELECT 1 FROM anime_genres ag WHERE ag.anime_id=a.id)
                    ORDER BY a.id ASC
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(limit.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                ClassificationCandidate(
                                    id = cursor.getLong(0),
                                    title = cursor.getString(1).orEmpty(),
                                    genres = if (cursor.isNull(2)) null else cursor.getString(2),
                                    source = if (cursor.isNull(3)) null else cursor.getString(3),
                                    aniListId = if (cursor.isNull(4)) null else cursor.getLong(4),
                                    manualFields = if (cursor.isNull(5)) null else cursor.getString(5),
                                ),
                            )
                        }
                    }
                }

                var classified = 0
                var assigned = 0
                var skipped = 0
                var unmatched = 0
                val changedIds = mutableListOf<Long>()
                candidates.forEach { candidate ->
                    if (
                        candidate.aniListId != null ||
                        candidate.source.normalizedSource() in AUTHORITATIVE_SOURCES ||
                        manualFieldsOwnGenres(candidate.manualFields)
                    ) {
                        skipped += 1
                        return@forEach
                    }
                    val inferred = ReiAnixGenreClassifier.classify(candidate.title)
                    if (inferred.isEmpty()) {
                        unmatched += 1
                        return@forEach
                    }
                    val encoded = JSONArray(inferred).toString()
                    connection.execSQL(
                        "UPDATE anime SET genres=? WHERE id=? " +
                            "AND (genres IS NULL OR TRIM(genres)='' OR TRIM(genres)='[]')",
                        arrayOf(encoded, candidate.id),
                    )
                    val wrote = connection.rawQuery(
                        "SELECT genres FROM anime WHERE id=?",
                        arrayOf(candidate.id.toString()),
                    ).use { cursor ->
                        cursor.moveToFirst() && cursor.getString(0) == encoded
                    }
                    if (wrote) {
                        val synced = syncAnimeInTransaction(
                            connection,
                            candidate.id,
                            inferred,
                            source = "local",
                            replaceSource = true,
                        )
                        classified += 1
                        assigned += synced.size
                        changedIds += candidate.id
                    }
                }
                ClassificationReport(
                    scanned = candidates.size,
                    classifiedAnime = classified,
                    assignedGenreCount = assigned,
                    skippedAuthoritativeMetadata = skipped,
                    withoutRuleMatch = unmatched,
                    changedAnimeIds = changedIds,
                )
            }
        }

    private fun seedSystemAliases(connection: SQLiteDatabase) {
        SYSTEM_ALIASES.forEach { (canonical, aliases) ->
            val genre = registerInTransaction(connection, canonical, "system", true, false)
            aliases.forEach { alias ->
                addAliasInTransaction(connection, genre.id, alias, "system")
            }
        }
    }

    private fun registerInTransaction(
        connection: SQLiteDatabase,
        rawName: String,
        source: String,
        isSystem: Boolean,
        isCustom: Boolean,
    ): Genre {
        val name = rawName.trim().split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" ")
        val normalized = normalizeReiAnixGenre(name)
        require(normalized.isNotBlank()) { "Gênero vazio não pode ser registrado." }
        resolveInTransaction(connection, normalized)?.let { return it }
        val now = nowSeconds()
        val id = UUID.randomUUID().toString()
        connection.execSQL(
            "INSERT INTO genres(id,canonical_name,normalized_name,source,is_system,is_custom,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(id, name, normalized, source.normalizedSource(), if (isSystem) 1 else 0, if (isCustom) 1 else 0, now, now),
        )
        return genreById(connection, id) ?: error("Gênero registrado, mas não foi possível relê-lo.")
    }

    private fun resolveInTransaction(connection: SQLiteDatabase, normalized: String): Genre? =
        connection.rawQuery(
            "SELECT g.* FROM genres g WHERE g.normalized_name=? " +
                "UNION SELECT g.* FROM genres g JOIN genre_aliases a ON a.genre_id=g.id " +
                "WHERE a.normalized_alias=? LIMIT 1",
            arrayOf(normalized, normalized),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toGenre(connection) else null }

    private fun addAliasInTransaction(
        connection: SQLiteDatabase,
        genreId: String,
        rawAlias: String,
        source: String,
    ): Genre? {
        val alias = rawAlias.trim().split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" ")
        val normalized = normalizeReiAnixGenre(alias)
        require(normalized.isNotBlank()) { "Alias de gênero vazio." }
        val target = genreById(connection, genreId) ?: return null
        val currentOwner = connection.rawQuery(
            "SELECT genre_id FROM genre_aliases WHERE normalized_alias=? LIMIT 1",
            arrayOf(normalized),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (currentOwner != null && currentOwner != genreId) {
            return genreById(connection, currentOwner)
        }
        val canonicalOwner = connection.rawQuery(
            "SELECT id FROM genres WHERE normalized_name=? LIMIT 1",
            arrayOf(normalized),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (canonicalOwner != null && canonicalOwner != genreId) {
            return genreById(connection, canonicalOwner)
        }
        val now = nowSeconds()
        connection.execSQL(
            "INSERT INTO genre_aliases(genre_id,alias,normalized_alias,source,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?) ON CONFLICT(normalized_alias) DO UPDATE SET " +
                "alias=excluded.alias,updated_at=excluded.updated_at",
            arrayOf(genreId, alias, normalized, source.normalizedSource(), now, now),
        )
        return genreById(connection, target.id)
    }

    private fun syncAnimeInTransaction(
        connection: SQLiteDatabase,
        animeId: Long,
        names: List<String>,
        source: String,
        replaceSource: Boolean,
    ): List<Genre> {
        val exists = connection.rawQuery(
            "SELECT 1 FROM anime WHERE id=? LIMIT 1",
            arrayOf(animeId.toString()),
        ).use(Cursor::moveToFirst)
        require(exists) { "Anime não encontrado no catálogo local." }
        val normalizedSource = source.normalizedSource()
        if (replaceSource) {
            connection.execSQL(
                "DELETE FROM anime_genres WHERE anime_id=? AND source=?",
                arrayOf(animeId, normalizedSource),
            )
        }
        val distinctNames = names.asSequence()
            .map { it.trim().split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" ") }
            .filter(String::isNotBlank)
            .distinctBy(::normalizeReiAnixGenre)
            .toList()
        val result = mutableListOf<Genre>()
        distinctNames.forEach { name ->
            val genre = resolveInTransaction(connection, normalizeReiAnixGenre(name))
                ?: registerInTransaction(connection, name, normalizedSource, false, false)
            val now = nowSeconds()
            connection.execSQL(
                "INSERT OR IGNORE INTO anime_genres(anime_id,genre_id,source,created_at,updated_at) VALUES(?,?,?,?,?)",
                arrayOf(animeId, genre.id, normalizedSource, now, now),
            )
            connection.execSQL(
                "UPDATE anime_genres SET updated_at=? WHERE anime_id=? AND genre_id=? AND source=?",
                arrayOf(now, animeId, genre.id, normalizedSource),
            )
            result += genreById(connection, genre.id) ?: genre
        }
        return result
    }

    private fun genreById(connection: SQLiteDatabase, id: String): Genre? =
        connection.rawQuery(
            "SELECT g.*, (SELECT COUNT(DISTINCT anime_id) FROM anime_genres ag WHERE ag.genre_id=g.id) AS anime_count " +
                "FROM genres g WHERE g.id=? LIMIT 1",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toGenre(connection) else null }

    private fun Cursor.toGenre(connection: SQLiteDatabase): Genre {
        val id = getString(getColumnIndexOrThrow("id"))
        val aliases = connection.rawQuery(
            "SELECT alias FROM genre_aliases WHERE genre_id=? ORDER BY normalized_alias",
            arrayOf(id),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val countColumn = getColumnIndex("anime_count")
        val count = if (countColumn >= 0 && !isNull(countColumn)) getInt(countColumn) else 0
        return Genre(
            id = id,
            canonicalName = getString(getColumnIndexOrThrow("canonical_name")),
            normalizedName = getString(getColumnIndexOrThrow("normalized_name")),
            source = getString(getColumnIndexOrThrow("source")),
            aliases = aliases,
            isSystem = getInt(getColumnIndexOrThrow("is_system")) != 0,
            isCustom = getInt(getColumnIndexOrThrow("is_custom")) != 0,
            animeCount = count,
        )
    }

    private fun manualFieldsOwnGenres(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return false
        return try {
            val json = JSONTokener(value).nextValue()
            when (json) {
                is JSONObject -> json.has("genres")
                is JSONArray -> (0 until json.length()).any {
                    json.optString(it).trim().equals("genres", ignoreCase = true)
                }
                else -> false
            }
        } catch (_: Exception) {
            value.contains("genres", ignoreCase = true)
        }
    }

    private fun String?.normalizedSource(): String =
        this.orEmpty().trim().lowercase(Locale.ROOT).ifBlank { "local" }

    private fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    companion object {
        const val DEFAULT_CLASSIFICATION_LIMIT = 10_000
        private const val MAX_CLASSIFICATION_LIMIT = 100_000
        private val AUTHORITATIVE_SOURCES = setOf("anilist", "manual")
        private val SYSTEM_ALIASES = linkedMapOf(
            "Action" to listOf("Ação"),
            "Adventure" to listOf("Aventura"),
            "Fantasy" to listOf("Fantasia"),
            "Romance" to emptyList(),
            "Comedy" to listOf("Comédia"),
            "Horror" to listOf("Terror"),
            "Sports" to listOf("Esporte"),
        )
    }
}

/** Mirrors GenreRegistry._names without introducing a schema or coercing valid non-array JSON. */
internal fun parseReiAnixGenreNames(raw: String?): List<String> {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return emptyList()
    return try {
        when (val parsed = JSONTokener(value).nextValue()) {
            is JSONArray -> buildList {
                for (index in 0 until parsed.length()) {
                    parsed.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
            is String -> if (value.startsWith('"')) emptyList() else listOf(value)
            else -> emptyList()
        }
    } catch (_: Exception) {
        listOf(value)
    }
}
