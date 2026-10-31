package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * Read-only AniMap v1 crosswalk adapter. It only looks up IDs already known locally;
 * it never searches by title and never uses AniMap's placeholder pictures as artwork.
 */
class ReiAnixAniMapCatalogIdResolver(
    private val transport: ReiAnixCatalogGetTransport = HttpUrlConnectionReiAnixCatalogGetTransport(),
) : ReiAnixCatalogIdResolver {
    override val source: ReiAnixCatalogSource = ReiAnixCatalogSource.ANIMAP

    override suspend fun resolveIds(
        ids: ReiAnixExternalCatalogIds,
    ): ReiAnixCatalogResult<ReiAnixCatalogIdentifierMapping?> {
        val candidates = lookupCandidates(ids)
        if (candidates.isEmpty()) return ReiAnixCatalogResult.Success(null)

        return try {
            for (candidate in candidates) {
                val response = transport.get(
                    "$BASE_URL/api/v1/map/${candidate.service}/${candidate.id}",
                    ACCEPT,
                )
                if (response.statusCode == 404) continue
                if (response.statusCode !in 200..299) {
                    return ReiAnixCatalogResult.Failure(
                        reiAnixCatalogHttpError(
                            response.statusCode,
                            response.header("Retry-After"),
                            System.currentTimeMillis(),
                        ),
                    )
                }

                val parsed = parseMapping(response.body)
                val mergedIds = try {
                    ids.mergeVerified(parsed.ids)
                } catch (conflict: IllegalArgumentException) {
                    return ReiAnixCatalogResult.Failure(
                        ReiAnixCatalogError.InvalidResponse(
                            conflict.message ?: "AniMap returned identifiers conflicting with local verified IDs.",
                        ),
                    )
                }
                val fetchedAt = System.currentTimeMillis()
                return ReiAnixCatalogResult.Success(
                    value = parsed.copy(ids = mergedIds),
                    fetchedAtMillis = fetchedAt,
                    expiresAtMillis = response.cacheExpiryMillis(fetchedAt, SUCCESS_CACHE_TTL_MILLIS),
                )
            }
            ReiAnixCatalogResult.Success(null)
        } catch (_: TimeoutCancellationException) {
            ReiAnixCatalogResult.Failure(ReiAnixCatalogError.TimedOut)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ReiAnixCatalogResult.Failure(reiAnixCatalogErrorFor(error))
        }
    }

    private fun lookupCandidates(ids: ReiAnixExternalCatalogIds): List<LookupCandidate> = buildList {
        ids.aniListId?.toAniMapServiceId()?.let { add(LookupCandidate("anilist", it)) }
        ids.myAnimeListId?.toAniMapServiceId()?.let { add(LookupCandidate("mal", it)) }
        ids.aniDbId?.toAniMapServiceId()?.let { add(LookupCandidate("anidb", it)) }
        ids.kitsuId?.toLongOrNull()?.toAniMapServiceId()?.let {
            add(LookupCandidate("kitsu", it))
        }
        ids.additionalIds["ann"]?.toLongOrNull()?.toAniMapServiceId()?.let {
            add(LookupCandidate("ann", it))
        }
    }.distinctBy { it.service to it.id }

    private fun parseMapping(raw: String): ReiAnixCatalogIdentifierMapping {
        val root = JSONObject(raw)
        val entry = root.optJSONObject("data")
            ?: root.optJSONArray("data")?.optJSONObject(0)
            ?: root
        val title = entry.optCatalogText("title")
            ?: throw JSONException("AniMap response is missing its title.")
        val sources = entry.optJSONArray("sources")
            ?: throw JSONException("AniMap response is missing its sources array.")
        val mappedIds = parseSourceIds(sources)
        if (!mappedIds.hasAnyId) {
            throw JSONException("AniMap response contains no supported catalog identifiers.")
        }
        val synonyms = entry.optJSONArray("synonyms").toStringSet()
        val season = entry.optJSONObject("animeSeason")
        val year = season?.optNullableInt("year")?.takeIf { it in 1900..2200 }
        val rawType = entry.optCatalogText("type")
        return ReiAnixCatalogIdentifierMapping(
            ids = mappedIds,
            title = title,
            alternativeTitles = synonyms,
            releaseYear = year,
            format = rawType?.uppercase(),
            mediaKind = rawType.toCatalogMediaKind(),
            season = season?.optCatalogText("season")?.toKnownCalendarSeason(),
            episodes = entry.optNullableInt("episodes")?.takeIf { it > 0 },
        )
    }

    private fun parseSourceIds(sources: JSONArray): ReiAnixExternalCatalogIds {
        var aniListId: Long? = null
        var malId: Long? = null
        var kitsuId: String? = null
        var aniDbId: Long? = null
        val additional = linkedMapOf<String, String>()

        for (index in 0 until sources.length()) {
            val raw = sources.optString(index).trim()
            if (raw.isEmpty()) continue
            val uri = runCatching { URI(raw) }.getOrNull() ?: continue
            val host = uri.host?.lowercase()?.removePrefix("www.") ?: continue
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            when {
                host == "anilist.co" && segments.size >= 2 && segments[0] == "anime" ->
                    segments[1].toLongOrNull()?.takeIf { it > 0L }?.let { aniListId = mergeLong("AniList", aniListId, it) }
                host == "myanimelist.net" && segments.size >= 2 && segments[0] == "anime" ->
                    segments[1].toLongOrNull()?.takeIf { it > 0L }?.let { malId = mergeLong("MyAnimeList", malId, it) }
                (host == "kitsu.app" || host == "kitsu.io") && segments.size >= 2 && segments[0] == "anime" ->
                    segments[1].takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }?.let { kitsuId = mergeString("Kitsu", kitsuId, it) }
                host == "anidb.net" && segments.size >= 2 && segments[0] == "anime" ->
                    segments[1].toLongOrNull()?.takeIf { it > 0L }?.let { aniDbId = mergeLong("AniDB", aniDbId, it) }
                host == "animenewsnetwork.com" && uri.path.orEmpty().contains("anime.php") -> {
                    val annId = uri.rawQuery.orEmpty().split('&')
                        .firstOrNull { it.substringBefore('=') == "id" }
                        ?.substringAfter('=')?.toLongOrNull()?.takeIf { it > 0L }
                    annId?.let { additional["ann"] = mergeString("ANN", additional["ann"], it.toString()) }
                }
            }
        }
        return ReiAnixExternalCatalogIds(
            aniListId = aniListId,
            myAnimeListId = malId,
            kitsuId = kitsuId,
            aniDbId = aniDbId,
            additionalIds = additional,
        )
    }

    private fun mergeLong(label: String, current: Long?, incoming: Long): Long {
        if (current != null && current != incoming) {
            throw JSONException("AniMap returned conflicting $label IDs.")
        }
        return current ?: incoming
    }

    private fun mergeString(label: String, current: String?, incoming: String): String {
        if (current != null && current != incoming) {
            throw JSONException("AniMap returned conflicting $label IDs.")
        }
        return current ?: incoming
    }

    private data class LookupCandidate(val service: String, val id: String)

    companion object {
        const val BASE_URL = "https://animap.id"
        const val ACCEPT = "application/json"
        const val SUCCESS_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

private fun Long.toAniMapServiceId(): String? =
    takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toString()

private fun JSONObject.optCatalogText(key: String): String? =
    if (!has(key) || isNull(key)) null else opt(key).let { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }

private fun JSONObject.optNullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else opt(key).let { value ->
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

private fun JSONArray?.toStringSet(): Set<String> {
    if (this == null) return emptySet()
    return buildSet {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }?.let(::add)
        }
    }
}

private fun String?.toKnownCalendarSeason(): String? =
    this?.trim()?.uppercase()?.takeIf { it in setOf("WINTER", "SPRING", "SUMMER", "FALL") }

private fun String?.toCatalogMediaKind(): ReiAnixCatalogMediaKind? = when (this?.trim()?.uppercase()) {
    "TV", "TV_SHORT", "TV SHORT" -> ReiAnixCatalogMediaKind.SERIES
    "MOVIE" -> ReiAnixCatalogMediaKind.MOVIE
    "OVA" -> ReiAnixCatalogMediaKind.OVA
    "ONA" -> ReiAnixCatalogMediaKind.ONA
    "SPECIAL" -> ReiAnixCatalogMediaKind.SPECIAL
    "MUSIC" -> ReiAnixCatalogMediaKind.MUSIC
    else -> null
}
