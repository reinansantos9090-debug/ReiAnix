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
 * Read-only AniPub adapter. It uses the documented search and public info routes only;
 * streaming/player routes and embedded third-party catalog data are deliberately excluded.
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
        // Use AniPub's documented metadata endpoint directly. The richer details endpoint
        // embeds an optional Jikan payload and must not make AniPub depend on the retired API.
        return when (val response = requestJson("$BASE_URL/api/info/$id")) {
            is ReiAnixCatalogResult.Failure -> response
            is ReiAnixCatalogResult.Success -> try {
                val root = response.value as? JSONObject
                    ?: throw JSONException("AniPub info response must be a JSON object.")
                ReiAnixCatalogResult.Success(parseAnime(root, id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ReiAnixCatalogResult.Failure(reiAnixCatalogErrorFor(error))
            }
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
        val responseId = root.optCatalogText("_id") ?: requestedId
        if (responseId != requestedId) {
            throw JSONException("AniPub info response ID does not match the requested record.")
        }
        val title = root.optCatalogText("Name")
            ?: root.optCatalogText("name")
            ?: throw JSONException("AniPub info response has no anime title.")

        val alternatives = linkedSetOf<String>()
        root.optCatalogTextValues("Synonyms", alternatives)
        alternatives.removeAll { it.equals(title, ignoreCase = true) }

        val ids = ReiAnixExternalCatalogIds(
            additionalIds = mapOf("anipub" to requestedId),
        )
        val type = root.optCatalogText("Type") ?: root.optCatalogText("Format")
        val aired = root.optCatalogText("Aired")
        val year = firstAiredYear(aired)
            ?: firstAiredYear(root.optCatalogText("Premiered"))
        val score = root.optCatalogDouble("MALScore")
            ?.takeIf { it.isFinite() && it in 0.0..10.0 && it > 0.0 }
            ?.let { (it * 10.0).roundToInt().coerceIn(0, 100) }
        val poster = root.optCatalogText("ImagePath")?.let(::normalizeAniPubImageUrl)
        val banner = root.optCatalogText("Cover")?.let(::normalizeAniPubImageUrl)

        return ReiAnixCatalogAnime(
            source = source,
            ids = ids,
            title = title,
            alternativeTitles = alternatives,
            synopsis = root.optCatalogText("DescripTion")
                ?: root.optCatalogText("Description"),
            coverUrl = poster,
            coverImageField = "ImagePath",
            bannerUrl = banner,
            bannerImageField = "Cover",
            bannerVariant = "Cover",
            releaseYear = year,
            season = root.optCatalogText("Premiered")?.substringBefore(' ')?.toAniPubCalendarSeason(),
            format = type?.uppercase(Locale.ROOT),
            mediaKind = type.toAniPubMediaKind(),
            episodes = root.optCatalogInt("epCount")?.takeIf { it > 0 },
            durationMinutes = parseDurationMinutes(root.optCatalogText("Duration")),
            status = root.optCatalogText("Status"),
            averageScore = score,
            contentRating = root.optCatalogText("Rating") ?: root.optCatalogText("AgeRating"),
        )
    }

    private fun normalizeAniPubImageUrl(rawValue: String): String? {
        val raw = rawValue.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it.code == 0 }) return null
        val candidate = when {
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("//", ignoreCase = true) -> return null
            else -> {
                val relative = raw.removePrefix("/")
                val relativeUri = runCatching { URI(relative) }.getOrNull() ?: return null
                if (relativeUri.isAbsolute || relativeUri.rawAuthority != null ||
                    relativeUri.path.orEmpty().split('/').any { it == "." || it == ".." }
                ) return null
                "https://anipub.org/$relative"
            }
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
