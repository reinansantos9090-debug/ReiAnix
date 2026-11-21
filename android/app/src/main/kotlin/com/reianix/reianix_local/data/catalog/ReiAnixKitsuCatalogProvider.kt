package com.reianix.reianix_local.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import com.reianix.reianix_local.data.anilist.ReiAnixAnimeCharacter
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Public Kitsu JSON:API reader. It never calls OAuth or write endpoints. */
class ReiAnixKitsuCatalogProvider(
    private val transport: ReiAnixCatalogGetTransport = HttpUrlConnectionReiAnixCatalogGetTransport(),
) : ReiAnixAnimeCatalogProvider {
    override val source: ReiAnixCatalogSource = ReiAnixCatalogSource.KITSU

    override suspend fun searchAnime(
        query: String,
        limit: Int,
    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> =
        searchAnimeInternal(query, limit, includeCharacters = false)

    override suspend fun searchAnimeWithCharacters(
        query: String,
        limit: Int,
    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> =
        searchAnimeInternal(query, limit, includeCharacters = true)

    private suspend fun searchAnimeInternal(
        query: String,
        limit: Int,
        includeCharacters: Boolean,
    ): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return ReiAnixCatalogResult.Success(emptyList())
        if (limit !in 1..20) {
            return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("Kitsu result limit must be between 1 and 20."),
            )
        }
        val encoded = encodeQuery(normalized)
        val url = "$BASE_URL/anime?filter%5Btext%5D=$encoded&page%5Blimit%5D=$limit" +
            if (includeCharacters) CHARACTER_INCLUDE else ""
        return execute(url, SEARCH_CACHE_TTL_MILLIS) { root ->
            val data = root.optJSONArray("data")
                ?: throw JSONException("Kitsu search response is missing the JSON:API data array.")
            val validResources = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val resourceId = item.catalogText("id")
                    if (resourceId == null || !resourceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
                        continue
                    }
                    parseAnime(item, root.optJSONArray("included"))?.let(::add)
                }
            }
            if (data.length() > 0 && validResources.isEmpty()) {
                throw JSONException("Kitsu search response contains no valid anime resources.")
            }
            validResources
        }
    }

    override suspend fun getAnime(externalId: String): ReiAnixCatalogResult<ReiAnixCatalogAnime?> =
        getAnimeInternal(externalId, includeCharacters = false)

    override suspend fun getAnimeWithCharacters(
        externalId: String,
    ): ReiAnixCatalogResult<ReiAnixCatalogAnime?> =
        getAnimeInternal(externalId, includeCharacters = true)

    private suspend fun getAnimeInternal(
        externalId: String,
        includeCharacters: Boolean,
    ): ReiAnixCatalogResult<ReiAnixCatalogAnime?> {
        val id = externalId.trim()
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            return ReiAnixCatalogResult.Failure(
                ReiAnixCatalogError.InvalidRequest("Kitsu ID is invalid."),
            )
        }
        val url = "$BASE_URL/anime/$id" +
            if (includeCharacters) CHARACTER_INCLUDE.replaceFirst("&", "?") else ""
        return execute(url, DETAIL_CACHE_TTL_MILLIS) { root ->
            if (!root.has("data") || root.isNull("data")) return@execute null
            val data = root.optJSONObject("data")
                ?: throw JSONException("Kitsu detail response contains an invalid JSON:API data object.")
            parseAnime(data, root.optJSONArray("included"))
                ?: throw JSONException("Kitsu detail resource is missing its ID or canonical title.")
        }
    }

    /**
     * Paged JSON:API relationship for large casts. Used only by the deferred
     * character repair worker, never by poster or normal metadata lookups.
     */
    suspend fun getCharacterPage(
        externalId: String,
        offset: Int,
        limit: Int = 20,
    ): ReiAnixCatalogResult<ReiAnixKitsuCharacterPage> {
        val id = externalId.trim()
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
            offset < 0 || limit !in 1..20
        ) return ReiAnixCatalogResult.Failure(
            ReiAnixCatalogError.InvalidRequest("Invalid Kitsu character page"),
        )
        val url = "$BASE_URL/anime/$id/characters" +
            "?page%5Blimit%5D=$limit&page%5Boffset%5D=$offset" +
            "&include=character" +
            "&fields%5BmediaCharacters%5D=role,character" +
            "&fields%5Bcharacters%5D=name,image"
        return execute(url, DETAIL_CACHE_TTL_MILLIS) { root ->
            val refs = root.optJSONArray("data")
                ?: throw JSONException("Missing Kitsu character page data")
            val relation = JSONObject().put("relationships", JSONObject().put(
                "characters", JSONObject().put("data", refs),
            ))
            val characters = parseIncludedCharacters(relation, root.optJSONArray("included"))
                .orEmpty()
            val next = root.optJSONObject("links")?.optString("next")
                ?.takeIf { it.startsWith(BASE_URL) }
            ReiAnixKitsuCharacterPage(characters, !next.isNullOrBlank())
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

    private fun parseAnime(resource: JSONObject, included: JSONArray?): ReiAnixCatalogAnime? {
        if (resource.catalogText("type")?.equals("anime", ignoreCase = true) != true) return null
        val id = resource.catalogText("id")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: return null
        val attributes = resource.optJSONObject("attributes") ?: return null
        val titles = attributes.optJSONObject("titles")
        val canonical = attributes.catalogText("canonicalTitle")
            ?: titles?.catalogText("en")
            ?: titles?.catalogText("en_jp")
            ?: titles?.catalogText("ja_jp")
            ?: titles?.catalogText("en_us")
            ?: return null

        val alternativeTitles = linkedSetOf<String>().apply {
            titles?.let { objectValue ->
                val keys = objectValue.keys()
                while (keys.hasNext()) {
                    objectValue.catalogText(keys.next())?.let(::add)
                }
            }
            attributes.optJSONArray("abbreviatedTitles")?.addStringValues(this)
            add(canonical)
        }.filterTo(linkedSetOf()) { !it.equals(canonical, ignoreCase = true) }

        val subtype = attributes.catalogText("subtype")
        val format = normalizeFormat(subtype)
        val year = attributes.catalogText("startDate")?.take(4)?.toIntOrNull()
            ?.takeIf { it in 1900..2200 }
        val poster = attributes.optJSONObject("posterImage")?.bestImage()
        val cover = attributes.optJSONObject("coverImage")?.bestImage()
        // Kitsu's REST schema defines coverImage as the banner displayed at the top of a media page.
        val rating = attributes.catalogNumber("averageRating")
            ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            ?.toInt()

        val relationCount = resource.optJSONObject("relationships")
            ?.optJSONObject("characters")?.optJSONArray("data")?.length()
        val embeddedCharacters = parseIncludedCharacters(resource, included)
        return ReiAnixCatalogAnime(
            source = source,
            ids = ReiAnixExternalCatalogIds(kitsuId = id),
            title = canonical,
            alternativeTitles = alternativeTitles,
            synopsis = attributes.catalogText("synopsis"),
            coverUrl = poster?.first,
            coverImageField = "posterImage",
            coverVariant = poster?.second,
            bannerUrl = cover?.first,
            bannerImageField = "coverImage",
            bannerVariant = cover?.second,
            releaseYear = year,
            format = format,
            mediaKind = subtype.toCatalogMediaKind(),
            episodes = attributes.catalogInt("episodeCount"),
            durationMinutes = attributes.catalogInt("episodeLength"),
            status = attributes.catalogText("status"),
            averageScore = rating,
            contentRating = attributes.catalogText("ageRatingGuide")
                ?: attributes.catalogText("ageRating"),
            startDate = attributes.catalogText("startDate"),
            endDate = attributes.catalogText("endDate"),
            characters = embeddedCharacters,
            charactersComplete = relationCount != null &&
                embeddedCharacters != null && embeddedCharacters.size == relationCount,
        )
    }

    /**
     * Kitsu JSON:API includes both relationship rows (mediaCharacters)
     * and character resources in the SAME anime metadata response.
     * Absence of included resources is different from a confirmed empty cast.
     */
    private fun parseIncludedCharacters(
        resource: JSONObject,
        included: JSONArray?,
    ): List<ReiAnixAnimeCharacter>? {
        val refs = resource.optJSONObject("relationships")
            ?.optJSONObject("characters")?.optJSONArray("data") ?: return null
        if (included == null && refs.length() > 0) return null
        val resources = HashMap<String, JSONObject>()
        if (included != null) {
            for (index in 0 until included.length()) {
                val entry = included.optJSONObject(index) ?: continue
                val type = entry.catalogText("type") ?: continue
                val id = entry.catalogText("id") ?: continue
                resources["$type:$id"] = entry
            }
        }
        return buildList {
            for (index in 0 until refs.length()) {
                val ref = refs.optJSONObject(index) ?: continue
                val media = resources["mediaCharacters:${ref.catalogText("id")}"] ?: continue
                val personRef = media.optJSONObject("relationships")
                    ?.optJSONObject("character")?.optJSONObject("data") ?: continue
                val character = resources["characters:${personRef.catalogText("id")}"] ?: continue
                val id = character.catalogText("id")?.toIntOrNull()
                    ?.takeIf { it > 0 } ?: continue
                val attrs = character.optJSONObject("attributes") ?: continue
                val name = attrs.catalogText("name") ?: continue
                val image = attrs.optJSONObject("image")
                    ?.let { picture ->
                        listOf("medium", "small", "large", "original")
                            .firstNotNullOfOrNull { variant ->
                                picture.catalogText(variant)
                                    ?.takeIf(::isValidCatalogArtworkUrl)
                            }
                    }
                val role = when (media.optJSONObject("attributes")?.catalogText("role")?.lowercase(Locale.ROOT)) {
                    "main" -> "Principal"
                    "supporting" -> "Secundário"
                    else -> "Figurante"
                }
                add(ReiAnixAnimeCharacter(id, name, role, image))
            }
        }.distinctBy { it.id }
    }

    private fun JSONArray.addStringValues(target: MutableSet<String>) {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() && it != "null" }?.let(target::add)
        }
    }

    private fun JSONObject.bestImage(): Pair<String, String>? =
        listOf("original", "large", "medium", "small", "tiny")
            .firstNotNullOfOrNull { variant ->
                catalogText(variant)?.takeIf(::isValidCatalogArtworkUrl)?.let { it to variant }
            }

    private fun String?.toCatalogMediaKind(): ReiAnixCatalogMediaKind = when (
        this?.trim()?.lowercase(Locale.ROOT)
    ) {
        "tv", "tv_short" -> ReiAnixCatalogMediaKind.SERIES
        "movie" -> ReiAnixCatalogMediaKind.MOVIE
        "ova" -> ReiAnixCatalogMediaKind.OVA
        "ona" -> ReiAnixCatalogMediaKind.ONA
        "special" -> ReiAnixCatalogMediaKind.SPECIAL
        "music" -> ReiAnixCatalogMediaKind.MUSIC
        else -> ReiAnixCatalogMediaKind.UNKNOWN
    }

    private fun normalizeFormat(value: String?): String? = when (
        value?.trim()?.lowercase(Locale.ROOT)
    ) {
        "tv", "tv_short" -> "TV"
        "movie" -> "MOVIE"
        "ova" -> "OVA"
        "ona" -> "ONA"
        "special" -> "SPECIAL"
        "music" -> "MUSIC"
        else -> value?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    companion object {
        const val BASE_URL = "https://kitsu.io/api/edge"
        const val ACCEPT = "application/vnd.api+json, application/json"
        // Kitsu's characters relationship is mediaCharacters -> character.
        // Include both in the existing request; keep only needed attributes.
        private const val CHARACTER_INCLUDE =
            "&include=characters.character" +
                "&fields%5BmediaCharacters%5D=role,character" +
                "&fields%5Bcharacters%5D=name,image"
        const val SEARCH_CACHE_TTL_MILLIS = 30L * 60L * 1000L
        const val DETAIL_CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L
    }
}

data class ReiAnixKitsuCharacterPage(
    val characters: List<ReiAnixAnimeCharacter>,
    val hasNextPage: Boolean,
)

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
