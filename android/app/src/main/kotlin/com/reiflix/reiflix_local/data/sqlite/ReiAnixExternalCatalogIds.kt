package com.reiflix.reiflix_local.data.sqlite

import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentity
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentityMatch
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentityMatcher
import com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogMediaKind
import com.reiflix.reiflix_local.data.catalog.ReiAnixExternalCatalogIds
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
            SELECT lookup_title,title,romaji,english,"native",aliases,year,format,media_kind,season,anilist_id
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
