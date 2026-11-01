package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.roundToInt

/** Public Tenrai v1 GET-only reader; MyAnimeList IDs remain the stable external identity key. */
class ReiAnixTenraiCatalogProvider(
    private val transport: ReiAnixCatalogGetTransport = HttpUrlConnectionReiAnixCatalogGetTransport(),
) : ReiAnixAnimeCatalogProvider {
    override val source: ReiAnixCatalogSource = ReiAnixCatalogSource.TENRAI

    override suspend fun searchAnime(
        query: String,
        limit: Int,
    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return ReiAnixCatalogResult.Success(emptyList())
        if (limit !in 1..25) {
            return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("Tenrai result limit must be between 1 and 25."),
            )
        }
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return execute("$BASE_URL/anime?q=$encoded&limit=$limit&sfw=true", SEARCH_CACHE_TTL_MILLIS) { root ->
            val data = root.optJSONArray("data")
                ?: throw JSONException("Tenrai search response is missing the data array.")
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index)
                        ?: throw JSONException("Tenrai search contains an invalid anime entry.")
                    add(
                        parseAnime(item)
                            ?: throw JSONException("Tenrai search entry is missing its MAL ID or title."),
                    )
                }
            }
        }
    }

    override suspend fun getAnime(externalId: String): ReiAnixCatalogResult<ReiAnixCatalogAnime?> {
        val id = externalId.trim().toLongOrNull()?.takeIf { it > 0L }
            ?: return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("MyAnimeList ID must be a positive integer."),
            )
        return execute("$BASE_URL/anime/$id", DETAIL_CACHE_TTL_MILLIS) { root ->
            if (!root.has("data") || root.isNull("data")) return@execute null
            val data = root.optJSONObject("data")
                ?: throw JSONException("Tenrai detail response contains an invalid data object.")
            parseAnime(data)
        }
    }

    private suspend fun <T> execute(
        url: String,
        fallbackTtlMillis: Long,
        decode: (JSONObject) -> T,
    ): ReiAnixCatalogResult<T> = try {
        val response = transport.get(url, ACCEPT)
        if (response.statusCode !in 200..299) {
            ReiAnixCatalogResult.Failure(
                reiAnixCatalogHttpError(response.statusCode, response.header("Retry-After")),
            )
        } else {
            val now = System.currentTimeMillis()
            val root = JSONObject(response.body)
            ReiAnixCatalogResult.Success(
                value = decode(root),
                fetchedAtMillis = now,
                expiresAtMillis = response.cacheExpiryMillis(now, fallbackTtlMillis),
            )
        }
    } catch (_: TimeoutCancellationException) {
        ReiAnixCatalogResult.Failure(ReiAnixCatalogError.TimedOut)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ReiAnixCatalogResult.Failure(reiAnixCatalogErrorFor(error))
    }

    private fun parseAnime(item: JSONObject): ReiAnixCatalogAnime? {
        val malId = item.optLong("mal_id", 0L).takeIf { it > 0L } ?: return null
        val titles = item.optJSONArray("titles")
        val mainTitle = item.catalogText("title")
            ?: item.catalogText("title_english")
            ?: item.catalogText("title_japanese")
            ?: titles?.firstTitle()
            ?: return null
        val alternativeTitles = linkedSetOf<String>().apply {
            item.catalogText("title_english")?.let(::add)
            item.catalogText("title_japanese")?.let(::add)
            item.optJSONArray("title_synonyms")?.addStringValues(this)
            titles?.addTitleValues(this)
            add(mainTitle)
        }.filterTo(linkedSetOf()) { !it.equals(mainTitle, ignoreCase = true) }

        val rawType = item.catalogText("type")
        val aired = item.optJSONObject("aired")
        val startDate = aired?.catalogText("from")
        val endDate = aired?.catalogText("to")
        val year = startDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2200 }
        val images = item.optJSONObject("images")
        val webp = images?.optJSONObject("webp")
        val jpg = images?.optJSONObject("jpg")
        val poster = listOfNotNull(
            webp?.catalogText("large_image_url")?.takeIf(::isValidCatalogArtworkUrl)?.let { "images.webp.large_image_url" to it },
            jpg?.catalogText("large_image_url")?.takeIf(::isValidCatalogArtworkUrl)?.let { "images.jpg.large_image_url" to it },
            webp?.catalogText("image_url")?.takeIf(::isValidCatalogArtworkUrl)?.let { "images.webp.image_url" to it },
            jpg?.catalogText("image_url")?.takeIf(::isValidCatalogArtworkUrl)?.let { "images.jpg.image_url" to it },
        ).firstOrNull()
        val score = item.catalogNumber("score")
            ?.takeIf { it.isFinite() && it in 0.0..10.0 && it > 0.0 }
            ?.let { (it * 10.0).roundToInt().coerceIn(0, 100) }
        val duration = item.catalogText("duration")?.let { raw ->
            val hours = Regex("(\\d+)\\s*(?:hr|hour)", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val minutes = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE)
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            (hours * 60 + minutes).takeIf { it > 0 }
        }

        return ReiAnixCatalogAnime(
            source = source,
            ids = ReiAnixExternalCatalogIds(myAnimeListId = malId),
            title = mainTitle,
            alternativeTitles = alternativeTitles,
            synopsis = item.catalogText("synopsis"),
            coverUrl = poster?.second,
            coverImageField = poster?.first ?: "images",
            coverVariant = poster?.first?.substringAfterLast('.'),
            releaseYear = year,
            format = rawType?.uppercase(Locale.ROOT),
            mediaKind = rawType.toCatalogMediaKind(),
            episodes = item.catalogInt("episodes"),
            durationMinutes = duration,
            status = item.catalogText("status"),
            averageScore = score,
            contentRating = item.catalogText("rating"),
            startDate = startDate,
            endDate = endDate,
        )
    }

    private fun JSONArray.firstTitle(): String? {
        for (index in 0 until length()) {
            optJSONObject(index)?.catalogText("title")?.let { return it }
        }
        return null
    }

    private fun JSONArray.addTitleValues(target: MutableSet<String>) {
        for (index in 0 until length()) {
            optJSONObject(index)?.catalogText("title")?.let(target::add)
        }
    }

    private fun JSONArray.addStringValues(target: MutableSet<String>) {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }?.let(target::add)
        }
    }

    private fun String?.toCatalogMediaKind(): ReiAnixCatalogMediaKind = when (
        this?.trim()?.lowercase(Locale.ROOT)
    ) {
        "tv", "tv short" -> ReiAnixCatalogMediaKind.SERIES
        "movie" -> ReiAnixCatalogMediaKind.MOVIE
        "ova" -> ReiAnixCatalogMediaKind.OVA
        "ona" -> ReiAnixCatalogMediaKind.ONA
        "special" -> ReiAnixCatalogMediaKind.SPECIAL
        "music" -> ReiAnixCatalogMediaKind.MUSIC
        else -> ReiAnixCatalogMediaKind.UNKNOWN
    }

    companion object {
        const val BASE_URL = "https://api.tenrai.org/v1"
        const val ACCEPT = "application/json"
        const val SEARCH_CACHE_TTL_MILLIS = 30L * 60L * 1000L
        const val DETAIL_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    }
}

private fun JSONObject.catalogText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

private fun JSONObject.catalogNumber(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

private fun JSONObject.catalogInt(key: String): Int? =
    catalogNumber(key)?.takeIf { it.isFinite() && it >= 0.0 && it <= Int.MAX_VALUE }?.toInt()
