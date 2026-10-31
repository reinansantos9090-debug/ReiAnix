package com.reiflix.reiflix_local.data.sqlite

import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentity
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentityMatch
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentityMatcher
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogMediaKind
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalCatalogIds
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogAnime
import android.content.ContentValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/** Namespaced in the existing v29 preferences table; no DDL or second database is introduced. */
internal object ReiAnixExternalCatalogIdsCodec {
    const val PREFERENCE_PREFIX = "__reianix.external_catalog_ids.v1."

    fun preferenceKey(animeId: Long): String {
        require(animeId > 0L)
        return PREFERENCE_PREFIX + animeId
    }

    fun isExternalPreferenceKey(key: String): Boolean =
        key.startsWith(PREFERENCE_PREFIX) &&
            key.removePrefix(PREFERENCE_PREFIX).matches(Regex("[1-9][0-9]{0,18}"))

    fun isValidPreferenceEntry(key: String, value: String): Boolean {
        if (!isExternalPreferenceKey(key)) return false
        return runCatching { decode(value).hasAnyId }.getOrDefault(false)
    }

    fun encode(ids: ReiAnixExternalCatalogIds): String {
        val additional = JSONObject()
        ids.additionalIds.toSortedMap().forEach { (catalog, id) -> additional.put(catalog, id) }
        return JSONObject()
            .put("aniListId", ids.aniListId ?: JSONObject.NULL)
            .put("myAnimeListId", ids.myAnimeListId ?: JSONObject.NULL)
            .put("kitsuId", ids.kitsuId ?: JSONObject.NULL)
            .put("aniDbId", ids.aniDbId ?: JSONObject.NULL)
            .put("additionalIds", additional)
            .toString()
    }

    @Throws(JSONException::class)
    fun decode(raw: String): ReiAnixExternalCatalogIds {
        val json = JSONObject(raw)
        val additionalJson = json.optJSONObject("additionalIds") ?: JSONObject()
        val additional = buildMap {
            val keys = additionalJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = additionalJson.optString(key).trim()
                require(value.isNotEmpty() && value.length <= 128) { "Invalid additional catalog ID." }
                put(key, value)
            }
        }
        return ReiAnixExternalCatalogIds(
            aniListId = json.optPositiveLong("aniListId"),
            myAnimeListId = json.optPositiveLong("myAnimeListId"),
            kitsuId = json.optNullableString("kitsuId"),
            aniDbId = json.optPositiveLong("aniDbId"),
            additionalIds = additional,
        )
    }

    private fun JSONObject.optPositiveLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0L } else null

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)
}

/** Read external IDs for one existing anime; the canonical AniList column takes precedence. */
suspend fun ReiAnixSqliteLibraryRepository.getExternalCatalogIds(
    animeId: Long,
): ReiAnixExternalCatalogIds? = withContext(Dispatchers.IO) {
    require(animeId > 0L)
    database.read { db ->
        val anilistId = db.rawQuery(
            "SELECT anilist_id FROM anime WHERE id=? LIMIT 1",
            arrayOf(animeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Local anime ID does not exist.")
            if (cursor.isNull(0)) null else cursor.getLong(0).takeIf { it > 0L }
        }
        val stored = db.rawQuery(
            "SELECT value FROM preferences WHERE key=? LIMIT 1",
            arrayOf(ReiAnixExternalCatalogIdsCodec.preferenceKey(animeId)),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val decoded = stored?.let {
            runCatching { ReiAnixExternalCatalogIdsCodec.decode(it) }
                .getOrElse { error -> throw IllegalStateException("Stored external catalog IDs are invalid.", error) }
        } ?: ReiAnixExternalCatalogIds()
        check(anilistId == null || decoded.aniListId == null || anilistId == decoded.aniListId) {
            "Stored AniList ID conflicts with the canonical local association."
        }
        decoded.copy(aniListId = anilistId ?: decoded.aniListId).takeIf { it.hasAnyId }
    }
}

/**
 * Persist IDs only from a verified exact catalog match. The local anime row and library counts
 * are never inserted or rewritten. Conflicting previously-verified IDs fail closed.
 */
suspend fun ReiAnixSqliteLibraryRepository.saveExternalCatalogIds(
    match: ReiAnixCatalogIdentityMatch,
): ReiAnixExternalCatalogIds = withContext(Dispatchers.IO) {
    require(match.externalIds.idFor(match.source).isNullOrBlank().not()) {
        "A verified match must include an identifier from its originating catalog."
    }
    database.transaction { db ->
        val localIdentityAndAniListId = db.rawQuery(
            """
            SELECT lookup_title,title,romaji,english,"native",aliases,year,format,media_kind,season,anilist_id,episodes_count
            FROM anime WHERE id=? LIMIT 1
            """.trimIndent(),
            arrayOf(match.localAnimeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Local anime ID does not exist.")
            val titles = linkedSetOf<String>()
            for (index in 0..4) cursor.getString(index)?.takeIf(String::isNotBlank)?.let(titles::add)
            cursor.getString(5)?.let { raw ->
                runCatching {
                    val aliases = JSONArray(raw)
                    for (index in 0 until aliases.length()) {
                        aliases.optString(index).takeIf(String::isNotBlank)?.let(titles::add)
                    }
                }
            }
            val format = cursor.getString(7)
            val localIdentity = ReiAnixCatalogIdentity(
                titles = titles,
                releaseYear = if (cursor.isNull(6)) null else cursor.getInt(6),
                format = format,
                mediaKind = parseLocalMediaKind(cursor.getString(8), format),
                season = cursor.getString(9),
                episodes = if (cursor.isNull(11)) null else cursor.getInt(11).takeIf { it >= 0 },
            )
            localIdentity to if (cursor.isNull(10)) null else cursor.getLong(10).takeIf { it > 0L }
        }

        check(
            ReiAnixCatalogIdentityMatcher.fingerprint(localIdentityAndAniListId.first) ==
                match.localIdentityFingerprint,
        ) { "Local anime identity changed after the external match was verified." }

        val preferenceKey = ReiAnixExternalCatalogIdsCodec.preferenceKey(match.localAnimeId)
        val stored = db.rawQuery(
            "SELECT value FROM preferences WHERE key=? LIMIT 1",
            arrayOf(preferenceKey),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val previous = stored?.let {
            runCatching { ReiAnixExternalCatalogIdsCodec.decode(it) }
                .getOrElse { error -> throw IllegalStateException("Stored external catalog IDs are invalid.", error) }
        } ?: ReiAnixExternalCatalogIds()

        val localAniListId = localIdentityAndAniListId.second
        require(localAniListId == null || match.externalIds.aniListId == null ||
            localAniListId == match.externalIds.aniListId
        ) { "The external match conflicts with the canonical local AniList association." }
        require(localAniListId == null || previous.aniListId == null ||
            localAniListId == previous.aniListId
        ) { "A stored cross-catalog AniList ID conflicts with the canonical local association." }

        val merged = previous.mergeVerified(match.externalIds).let { ids ->
            ids.copy(aniListId = localAniListId ?: ids.aniListId)
        }
        db.execSQL(
            """
            INSERT INTO preferences(key,value,updated_at) VALUES(?,?,?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated_at=excluded.updated_at
            """.trimIndent(),
            arrayOf(
                preferenceKey,
                ReiAnixExternalCatalogIdsCodec.encode(merged),
                System.currentTimeMillis() / 1000.0,
            ),
        )
        merged
    }
}


/** Builds the same strict identity snapshot used again inside saveExternalCatalogIds(). */
internal fun ReiAnixAnimeRecord.toExternalCatalogIdentity(): ReiAnixCatalogIdentity {
    val titles = linkedSetOf<String>().apply {
        listOf(lookupTitle, title, romaji, english, nativeTitle)
            .filterNotNull()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(::add)
        aliasesJson?.let { raw ->
            runCatching {
                val aliases = JSONArray(raw)
                for (index in 0 until aliases.length()) {
                    aliases.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
                }
            }
        }
    }
    return ReiAnixCatalogIdentity(
        titles = titles.ifEmpty { setOf(title) },
        releaseYear = year,
        format = format,
        mediaKind = parseLocalMediaKind(mediaKind, format),
        season = season,
        episodes = episodesCount?.takeIf { it >= 0 },
    )
}

/** Read one bounded v29 anime row without loading the entire library for enrichment. */
suspend fun ReiAnixSqliteLibraryRepository.listAnimeAfterExternalCatalogCursor(
    afterAnimeId: Long,
    limit: Int = 12,
): List<ReiAnixAnimeRecord> = withContext(Dispatchers.IO) {
    require(afterAnimeId >= 0L)
    require(limit in 1..50)
    database.read { db ->
        db.rawQuery(
            "SELECT * FROM anime WHERE id>? ORDER BY id ASC LIMIT ?",
            arrayOf(afterAnimeId.toString(), limit.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toAnimeRecord()) }
        }
    }
}

private fun externalCatalogAttemptPreferenceKey(animeId: Long, source: String): String {
    require(animeId > 0L)
    require(source == "kitsu" || source == "jikan")
    return "__reianix.external_catalog_attempt.v1.$animeId.$source"
}

/** Existing preferences table is reused; no DDL or schema migration is needed. */
suspend fun ReiAnixSqliteLibraryRepository.externalCatalogAttemptAt(
    animeId: Long,
    source: String,
): Double? = withContext(Dispatchers.IO) {
    val key = externalCatalogAttemptPreferenceKey(animeId, source)
    database.read { db ->
        db.rawQuery("SELECT value FROM preferences WHERE key=? LIMIT 1", arrayOf(key)).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        }
    }
}

suspend fun ReiAnixSqliteLibraryRepository.markExternalCatalogAttempt(
    animeId: Long,
    source: String,
    attemptedAtSeconds: Double = System.currentTimeMillis() / 1_000.0,
) = withContext(Dispatchers.IO) {
    require(attemptedAtSeconds.isFinite() && attemptedAtSeconds >= 0.0)
    val key = externalCatalogAttemptPreferenceKey(animeId, source)
    database.transaction { db ->
        db.insertWithOnConflict(
            "preferences",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", attemptedAtSeconds.toString())
                put("updated_at", System.currentTimeMillis() / 1_000.0)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

/** Persistent round-robin cursor prevents startup from faning out over the complete library. */
suspend fun ReiAnixSqliteLibraryRepository.externalCatalogCursor(): Long = withContext(Dispatchers.IO) {
    database.read { db ->
        db.rawQuery(
            "SELECT value FROM preferences WHERE key=? LIMIT 1",
            arrayOf(EXTERNAL_CATALOG_CURSOR_KEY),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L else 0L
        }
    }
}

suspend fun ReiAnixSqliteLibraryRepository.setExternalCatalogCursor(animeId: Long) = withContext(Dispatchers.IO) {
    require(animeId >= 0L)
    database.transaction { db ->
        db.insertWithOnConflict(
            "preferences",
            null,
            ContentValues().apply {
                put("key", EXTERNAL_CATALOG_CURSOR_KEY)
                put("value", animeId.toString())
                put("updated_at", System.currentTimeMillis() / 1_000.0)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

/**
 * Fill only missing metadata fields from a verified supplemental provider.
 * AniList-populated data, manual fields and every non-empty editorial value remain untouched.
 */
suspend fun ReiAnixSqliteLibraryRepository.mergeExternalCatalogMetadata(
    match: ReiAnixCatalogIdentityMatch,
    remote: ReiAnixCatalogAnime,
): ReiAnixAnimeRecord = withContext(Dispatchers.IO) {
    require(remote.source == match.source)
    require(remote.ids.idFor(remote.source).isNullOrBlank().not())
    database.transaction { db ->
        val current = db.rawQuery(
            "SELECT * FROM anime WHERE id=? LIMIT 1",
            arrayOf(match.localAnimeId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw NoSuchElementException("Local anime ID does not exist.")
            cursor.toAnimeRecord()
        }
        check(
            ReiAnixCatalogIdentityMatcher.fingerprint(current.toExternalCatalogIdentity()) ==
                match.localIdentityFingerprint,
        ) { "Local anime identity changed after the external match was verified." }

        val manualFields = try {
            val parsed = JSONArray(current.metadataManualFieldsJson ?: "[]")
            buildSet {
                for (index in 0 until parsed.length()) {
                    val value = parsed.opt(index)
                    if (value !is String) throw IllegalStateException("Manual metadata ownership is malformed.")
                    add(value)
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("Manual metadata ownership is unreadable; refusing supplemental metadata.", error)
        }
        val values = ContentValues()
        fun isMissing(value: String?): Boolean = value.isNullOrBlank()
        fun fillText(field: String, existing: String?, incoming: String?) {
            if (field !in manualFields && isMissing(existing) && !incoming.isNullOrBlank()) {
                values.put(field, incoming.trim())
            }
        }
        fun fillInt(field: String, existing: Int?, incoming: Int?) {
            if (field !in manualFields && existing == null && incoming != null) values.put(field, incoming)
        }

        fillText("description", current.description, remote.synopsis?.takeIf { it.trim().length >= 8 })
        fillText("description_original", current.descriptionOriginal, remote.synopsis?.takeIf { it.trim().length >= 8 })
        fillText("cover_url", current.coverUrl, remote.coverUrl)
        fillText("banner_url", current.bannerUrl, remote.backdropUrl)
        fillText("status", current.status, remote.status)
        fillText("format", current.format, remote.format)
        fillInt("year", current.year, remote.releaseYear)
        fillInt("episodes_count", current.episodesCount, remote.episodes?.takeIf { it > 0 })
        fillInt("duration", current.duration, remote.durationMinutes?.takeIf { it > 0 })
        fillInt("score", current.score, remote.averageScore)

        if ("aliases" !in manualFields) {
            val aliases = linkedSetOf<String>()
            current.aliasesJson?.let { raw ->
                runCatching {
                    val json = JSONArray(raw)
                    for (index in 0 until json.length()) json.optString(index).trim()
                        .takeIf(String::isNotEmpty)?.let(aliases::add)
                }
            }
            val incoming = remote.alternativeTitles + remote.title
            val known = aliases.map(ReiAnixCatalogIdentityMatcher::normalizeCatalogTitle).toMutableSet()
            incoming.forEach { alias ->
                val normalized = ReiAnixCatalogIdentityMatcher.normalizeCatalogTitle(alias)
                if (normalized.isNotEmpty() && known.add(normalized)) aliases += alias.trim()
            }
            if (aliases.isNotEmpty() && aliases.size != current.aliasesJson?.let {
                runCatching { JSONArray(it).length() }.getOrDefault(0)
            }) {
                values.put("aliases", JSONArray(aliases.toList()).toString())
            }
        }

        fillText("metadata_source", current.metadataSource, remote.source.key)
        fillText("metadata_confidence", current.metadataConfidence, "verified_exact_identity")
        fillText("metadata_status", current.metadataStatus, "supplemental")
        if (current.metadataFetchedAt == null) {
            values.put("metadata_fetched_at", System.currentTimeMillis() / 1_000.0)
        }

        if (values.size() == 0) return@transaction current
        values.put("metadata_updated_at", System.currentTimeMillis() / 1_000.0)
        db.update("anime", values, "id=?", arrayOf(match.localAnimeId.toString()))
        db.rawQuery(
            "SELECT * FROM anime WHERE id=? LIMIT 1",
            arrayOf(match.localAnimeId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toAnimeRecord()
            else throw NoSuchElementException("Anime disappeared after supplemental metadata update.")
        }
    }
}

private const val EXTERNAL_CATALOG_CURSOR_KEY = "__reianix.external_catalog_cursor.v1"

private fun parseLocalMediaKind(rawKind: String?, format: String?): ReiAnixCatalogMediaKind? {
    val normalized = (rawKind ?: format).orEmpty().trim().lowercase(Locale.ROOT)
    return when (normalized) {
        "movie", "film" -> ReiAnixCatalogMediaKind.MOVIE
        "tv", "tv series", "series", "anime", "tv_short", "tv short" -> ReiAnixCatalogMediaKind.SERIES
        "ova" -> ReiAnixCatalogMediaKind.OVA
        "ona" -> ReiAnixCatalogMediaKind.ONA
        "special" -> ReiAnixCatalogMediaKind.SPECIAL
        "music" -> ReiAnixCatalogMediaKind.MUSIC
        else -> null
    }
}
