package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Read-only AniPub adapter. It uses the documented metadata search/details routes only;
 * streaming/player endpoints are deliberately outside this catalog integration.
 */
class ReiAnixAniPubCatalogProvider(
    private val transport: ReiAnixCatalogGetTransport = HttpUrlConnectionReiAnixCatalogGetTransport(),
) : ReiAnixAnimeCatalogProvider {
    override val source: ReiAnixCatalogSource = ReiAnixCatalogSource.ANIPUB

    override suspend fun searchAnime(
        query: String,
        limit: Int,
    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return ReiAnixCatalogResult.Success(emptyList())
        if (limit !in 1..10) {
            return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("AniPub result limit must be between 1 and 10."),
            )
        }
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val search = requestJson("$BASE_URL/api/search/$encoded")
        if (search is ReiAnixCatalogResult.Failure) return search
        val root = (search as ReiAnixCatalogResult.Success).value
        val entries = when {
            root is JSONObject -> root.optJSONArray("data") ?: root.optJSONArray("results")
            root is JSONArray -> root
            else -> null
        } ?: return ReiAnixCatalogResult.Failure(
            ReiAnixCatalogError.InvalidResponse("AniPub search response is not a documented result array."),
        )

        val candidates = buildList {
            for (index in 0 until entries.length()) {
                val item = entries.optJSONObject(index) ?: continue
                val id = item.optCatalogText("Id") ?: item.optCatalogText("id") ?: continue
                if (id.toLongOrNull()?.let { it > 0L } != true) continue
                add(id)
                if (size >= minOf(limit, MAX_DETAIL_LOOKUPS_PER_SEARCH)) break
            }
        }.distinct()

        if (candidates.isEmpty()) return ReiAnixCatalogResult.Success(emptyList())
        val details = mutableListOf<ReiAnixCatalogAnime>()
        var firstFailure: ReiAnixCatalogError? = null
        for (id in candidates) {
            when (val detail = getAnime(id)) {
                is ReiAnixCatalogResult.Success -> detail.value?.let(details::add)
                is ReiAnixCatalogResult.Failure -> {
                    // A Retry-After from AniPub applies to the source, not just this candidate.
                    // Propagate it so the shared coordinator defers subsequent requests.
                    if (detail.error is ReiAnixCatalogError.RateLimited) return detail
                    if (firstFailure == null) firstFailure = detail.error
                }
            }
        }
        return when {
            details.isNotEmpty() -> ReiAnixCatalogResult.Success(details)
            firstFailure != null -> ReiAnixCatalogResult.Failure(firstFailure)
            else -> ReiAnixCatalogResult.Success(emptyList())
        }
    }

    override suspend fun getAnime(externalId: String): ReiAnixCatalogResult<ReiAnixCatalogAnime?> {
        val id = externalId.trim()
        if (id.toLongOrNull()?.let { it > 0L } != true) {
            return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("AniPub ID must be a positive integer."),
            )
        }
        // Prefer the documented full-details API (local AniPub data + Jikan identity fields).
        // Fall back to the documented /api/info endpoint for metadata-only availability.
        val response = requestJson("$BASE_URL/anime/api/details/$id")
        val selected = when (response) {
            is ReiAnixCatalogResult.Success -> response
            is ReiAnixCatalogResult.Failure -> when (response.error) {
                // A missing full-details record may still exist in the basic metadata API.
                is ReiAnixCatalogError.Http -> {
                    if ((response.error as ReiAnixCatalogError.Http).statusCode == 404) {
                        requestJson("$BASE_URL/api/info/$id")
                    } else {
                        return response
                    }
                }
                // Do not bypass Retry-After, network failures, or server errors with another route.
                else -> return response
            }
        }
        if (selected is ReiAnixCatalogResult.Failure) return selected
        return try {
            val payload = (selected as ReiAnixCatalogResult.Success).value
            val root = payload as? JSONObject
                ?: throw JSONException("AniPub details response must be a JSON object.")
            ReiAnixCatalogResult.Success(parseAnime(root, id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ReiAnixCatalogResult.Failure(reiAnixCatalogErrorFor(error))
        }
    }

    private suspend fun requestJson(url: String): ReiAnixCatalogResult<Any> = try {
        val response = transport.get(url, ACCEPT)
        if (response.statusCode !in 200..299) {
            ReiAnixCatalogResult.Failure(
                reiAnixCatalogHttpError(response.statusCode, response.header("Retry-After")),
            )
        } else {
            val body = response.body.trim()
            if (body.isEmpty()) throw JSONException("AniPub returned an empty response.")
            val parsed: Any = when (body.first()) {
                '{' -> JSONObject(body)
                '[' -> JSONArray(body)
                else -> throw JSONException("AniPub returned an unsupported JSON payload.")
            }
            ReiAnixCatalogResult.Success(parsed)
        }
    } catch (_: TimeoutCancellationException) {
        ReiAnixCatalogResult.Failure(ReiAnixCatalogError.TimedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ReiAnixCatalogResult.Failure(reiAnixCatalogErrorFor(error))
    }

    private fun parseAnime(root: JSONObject, requestedId: String): ReiAnixCatalogAnime? {
        val local = root.optJSONObject("local") ?: root
        val jikanRoot = root.optJSONObject("jikan")
        val jikan = jikanRoot?.optJSONObject("data") ?: jikanRoot
        val title = local.optCatalogText("Name")
            ?: local.optCatalogText("name")
            ?: jikan?.optCatalogText("title")
            ?: throw JSONException("AniPub details response has no anime title.")

        val alternatives = linkedSetOf<String>()
        local.optCatalogTextValues("Synonyms", alternatives)
        jikan?.optCatalogText("title_english")?.let(alternatives::add)
        jikan?.optCatalogText("title_japanese")?.let(alternatives::add)
        jikan?.optJSONArray("title_synonyms")?.addValues(alternatives)
        jikan?.optJSONArray("titles")?.addTitleValues(alternatives)
        alternatives.removeAll { it.equals(title, ignoreCase = true) }

        val malId = jikan?.optCatalogLong("mal_id")?.takeIf { it > 0L }
        val ids = ReiAnixExternalCatalogIds(
            myAnimeListId = malId,
            additionalIds = mapOf("anipub" to requestedId),
        )
        val type = jikan?.optCatalogText("type")
            ?: local.optCatalogText("Type")
            ?: local.optCatalogText("Format")
        val aired = jikan?.optJSONObject("aired")
        val startDate = aired?.optCatalogText("from")
        val year = startDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2200 }
            ?: jikan?.optCatalogInt("year")?.takeIf { it in 1900..2200 }
            ?: firstAiredYear(local.optCatalogText("Aired"))
            ?: firstAiredYear(local.optCatalogText("Premiered"))
        val score = local.optCatalogDouble("MALScore")
            ?.takeIf { it.isFinite() && it in 0.0..10.0 && it > 0.0 }
            ?.let { (it * 10.0).roundToInt().coerceIn(0, 100) }
            ?: jikan?.optCatalogDouble("score")
                ?.takeIf { it.isFinite() && it in 0.0..10.0 && it > 0.0 }
                ?.let { (it * 10.0).roundToInt().coerceIn(0, 100) }

        val poster = local.optCatalogText("ImagePath")?.let(::normalizeAniPubImageUrl)
        val banner = local.optCatalogText("Cover")?.let(::normalizeAniPubImageUrl)
        val episodes = jikan?.optCatalogInt("episodes")?.takeIf { it > 0 }
            ?: local.optCatalogInt("epCount")?.takeIf { it > 0 }
        val duration = parseDurationMinutes(local.optCatalogText("Duration"))
            ?: jikan?.optCatalogText("duration")?.let(::parseDurationMinutes)
        val season = jikan?.optCatalogText("season")?.toAniPubCalendarSeason()
            ?: local.optCatalogText("Premiered")?.substringBefore(' ')?.toAniPubCalendarSeason()

        return ReiAnixCatalogAnime(
            source = source,
            ids = ids,
            title = title,
            alternativeTitles = alternatives,
            synopsis = local.optCatalogText("DescripTion")
                ?: local.optCatalogText("Description")
                ?: jikan?.optCatalogText("synopsis"),
            coverUrl = poster,
            // The AniPub API reference identifies ImagePath as poster and Cover as banner.
            backdropUrl = banner,
            releaseYear = year,
            season = season,
            format = type?.uppercase(Locale.ROOT),
            mediaKind = type.toAniPubMediaKind(),
            episodes = episodes,
            durationMinutes = duration,
            status = local.optCatalogText("Status") ?: jikan?.optCatalogText("status"),
            averageScore = score,
            contentRating = jikan?.optCatalogText("rating"),
            startDate = startDate,
            endDate = aired?.optCatalogText("to"),
        )
    }

    private fun normalizeAniPubImageUrl(rawValue: String): String? {
        val raw = rawValue.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code == 0 }) return null
        val candidate = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("//", ignoreCase = true) -> return null
            else -> "https://anipub.org/" + raw.removePrefix("/")
        }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() || uri.userInfo != null || uri.path.isNullOrBlank()
        ) return null
        return uri.toASCIIString()
    }

    private fun firstAiredYear(value: String?): Int? =
        Regex("\\b(19|20|21|22)\\d{2}\\b").find(value.orEmpty())
            ?.value?.toIntOrNull()?.takeIf { it in 1900..2200 }

    private fun parseDurationMinutes(value: String?): Int? {
        val raw = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        val hours = Regex("(\\d+)\\s*(?:h|hr|hour)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:m|min|minute)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    companion object {
        const val BASE_URL = "https://anipub.org"
        const val ACCEPT = "application/json"
        const val MAX_DETAIL_LOOKUPS_PER_SEARCH = 5
    }
}

private fun JSONObject.optCatalogText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val value = opt(key)) {
        is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
        is Number -> value.toLong().takeIf { it > 0L }?.toString()
        else -> null
    }
}

private fun JSONObject.optCatalogLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else when (val raw = opt(key)) {
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    }

private fun JSONObject.optCatalogInt(key: String): Int? =
    optCatalogLong(key)?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()

private fun JSONObject.optCatalogDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }

private fun JSONObject.optCatalogTextValues(key: String, target: MutableSet<String>) {
    if (!has(key) || isNull(key)) return
    when (val raw = opt(key)) {
        is JSONArray -> raw.addValues(target)
        is String -> raw.split('|', ';').map(String::trim).filter(String::isNotEmpty).forEach(target::add)
    }
}

private fun JSONArray.addValues(target: MutableSet<String>) {
    for (index in 0 until length()) {
        optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }?.let(target::add)
    }
}

private fun JSONArray.addTitleValues(target: MutableSet<String>) {
    for (index in 0 until length()) {
        optJSONObject(index)?.optCatalogText("title")?.let(target::add)
    }
}

private fun String?.toAniPubCalendarSeason(): String? =
    this?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in setOf("WINTER", "SPRING", "SUMMER", "FALL") }

private fun String?.toAniPubMediaKind(): ReiAnixCatalogMediaKind? = when (this?.trim()?.lowercase(Locale.ROOT)) {
    "tv", "tv short", "tv_short", "series" -> ReiAnixCatalogMediaKind.SERIES
    "movie", "film" -> ReiAnixCatalogMediaKind.MOVIE
    "ova" -> ReiAnixCatalogMediaKind.OVA
    "ona" -> ReiAnixCatalogMediaKind.ONA
    "special" -> ReiAnixCatalogMediaKind.SPECIAL
    "music" -> ReiAnixCatalogMediaKind.MUSIC
    else -> null
}
