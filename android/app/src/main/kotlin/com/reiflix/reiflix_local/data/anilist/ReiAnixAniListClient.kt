package com.reiflix.reiflix_local.data.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import com.reiflix.reiflix_local.data.contract.arrayOrNull
import com.reiflix.reiflix_local.data.contract.intOrNull
import com.reiflix.reiflix_local.data.contract.objectOrNull
import com.reiflix.reiflix_local.data.contract.stringOrNull
import com.reiflix.reiflix_local.data.artwork.validReiAnixArtworkHttpUrl
import com.reiflix.reiflix_local.data.artwork.resolveAniListCoverCandidates
import com.reiflix.reiflix_local.data.artwork.ReiAnixAniListCoverCandidate
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Native, typed AniList GraphQL client.
 *
 * This is an API adapter, not a catalog or persistence authority. Local media
 * identities and playback state are deliberately absent from remote DTOs.
 * Callers must merge successful responses into the existing LibraryStore/SQLite
 * metadata contract rather than replacing local records.
 */
class ReiAnixAniListClient(
    private val transport: AniListTransport = HttpUrlConnectionAniListTransport(),
    private val minimumRequestIntervalMs: Long = DEFAULT_MINIMUM_REQUEST_INTERVAL_MS,
) {
    init {
        require(minimumRequestIntervalMs >= 0L) { "Request interval cannot be negative." }
    }

    private val requestMutex = Mutex()
    private var lastRequestAtMs = 0L
    @Volatile private var effectiveMinimumRequestIntervalMs = minimumRequestIntervalMs

    suspend fun searchAnime(term: String, limit: Int = 10): AniListResult<List<AniListAnime>> {
        val query = term.trim()
        if (query.isEmpty()) return AniListResult.Success(emptyList())
        require(limit in 1..50) { "AniList result limit must be between 1 and 50." }
        val gql = """
            query SearchAnime(${'$'}search: String, ${'$'}page: Int, ${'$'}perPage: Int) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(search: ${'$'}search, type: ANIME, sort: SEARCH_MATCH) {
                  $MEDIA_FIELDS
                }
              }
            }
        """.trimIndent()
        return execute(gql, JSONObject()
            .put("search", query)
            .put("page", 1)
            .put("perPage", limit)) { data ->
            val page = data.objectOrNull("Page")
                ?: return@execute AniListResult.Failure(AniListError.InvalidResponse("Missing Page object"))
            val media = page.arrayOrNull("media")
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Missing media array in AniList search response"),
                )
            val results = ArrayList<AniListAnime>(media.length())
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i)
                    ?: return@execute AniListResult.Failure(
                        AniListError.InvalidResponse("Invalid anime item in AniList search response"),
                    )
                val anime = parseAnime(item)
                    ?: return@execute AniListResult.Failure(
                        AniListError.InvalidResponse("Anime item is missing a valid AniList ID"),
                    )
                results += anime
            }
            AniListResult.Success(results)
        }
    }


    /**
     * Searches every supplied title in one GraphQL operation. Each title owns an aliased
     * Page field, so the service does not serialize one HTTP round-trip per alias. The
     * returned map preserves the original normalized query for candidate attribution.
     */
    suspend fun searchAnimeBatch(
        terms: List<String>,
        limit: Int = 10,
    ): AniListResult<Map<String, List<AniListAnime>>> {
        require(limit in 1..50) { "AniList result limit must be between 1 and 50." }
        val queries = terms.asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
        if (queries.isEmpty()) return AniListResult.Success(emptyMap())

        val variableDefinitions = (listOf("\$perPage: Int!") +
            queries.indices.map { "\$term$it: String!" }).joinToString(", ")
        val searchFields = queries.indices.joinToString("\n") { index ->
            "search$index: Page(page: 1, perPage: \$perPage) { " +
                "media(search: \$term$index, type: ANIME, sort: SEARCH_MATCH) { " +
                SEARCH_MEDIA_FIELDS + " } }"
        }
        val gql = """
            query SearchAnimeAliases($variableDefinitions) {
              $searchFields
            }
        """.trimIndent()
        val variables = JSONObject().put("perPage", limit)
        queries.forEachIndexed { index, term -> variables.put("term$index", term) }

        return execute(gql, variables) { data ->
            val byQuery = linkedMapOf<String, List<AniListAnime>>()
            for (index in queries.indices) {
                val page = data.objectOrNull("search$index")
                    ?: return@execute AniListResult.Failure(
                        AniListError.InvalidResponse("Missing AniList search result for alias $index"),
                    )
                val media = page.arrayOrNull("media")
                    ?: return@execute AniListResult.Failure(
                        AniListError.InvalidResponse("Missing media array for AniList alias $index"),
                    )
                val results = ArrayList<AniListAnime>(media.length())
                for (itemIndex in 0 until media.length()) {
                    val item = media.optJSONObject(itemIndex)
                        ?: return@execute AniListResult.Failure(
                            AniListError.InvalidResponse("Invalid anime item in AniList alias $index"),
                        )
                    val anime = parseAnime(item)
                        ?: return@execute AniListResult.Failure(
                            AniListError.InvalidResponse("Anime item is missing a valid AniList ID"),
                        )
                    results += anime
                }
                byQuery[queries[index]] = results
            }
            AniListResult.Success(byQuery)
        }
    }

    suspend fun getAnime(id: Int): AniListResult<AniListAnime?> {
        if (id <= 0) return AniListResult.Failure(AniListError.InvalidRequest("AniList ID must be positive"))
        val gql = """
            query AnimeById(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $MEDIA_FIELDS }
            }
        """.trimIndent()
        return execute(gql, JSONObject().put("id", id)) { data ->
            if (!data.has("Media")) {
                return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Missing Media field in AniList detail response"),
                )
            }
            if (data.isNull("Media")) return@execute AniListResult.Success(null)
            val media = data.objectOrNull("Media")
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Invalid Media object in AniList detail response"),
                )
            val anime = parseAnime(media)
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("AniList detail is missing a valid media ID"),
                )
            AniListResult.Success(anime)
        }
    }

    private suspend fun <T> execute(
        query: String,
        variables: JSONObject,
        decode: (JSONObject) -> AniListResult<T>,
    ): AniListResult<T> {
        val response = try {
            requestMutex.withLock {
                val now = System.currentTimeMillis()
                val wait = (lastRequestAtMs + effectiveMinimumRequestIntervalMs - now).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
                lastRequestAtMs = System.currentTimeMillis()
                // Keep the lock through the HTTP call so concurrent callers cannot
                // start together after sharing the same rate-limit delay.
                withContext(Dispatchers.IO) {
                    transport.post(ENDPOINT, JSONObject()
                        .put("query", query)
                        .put("variables", variables))
                }
                response.rateLimitLimit?.takeIf { it > 0 }?.let { serverLimit ->
                    // AniList may temporarily lower the documented per-minute budget.
                    // Adapt to the response header while retaining the configured floor.
                    val serverInterval = kotlin.math.ceil(60_000.0 / serverLimit * 1.05).toLong()
                    effectiveMinimumRequestIntervalMs = maxOf(minimumRequestIntervalMs, serverInterval)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return AniListResult.Failure(AniListError.Network(error.message ?: "Network request failed"))
        } catch (error: Exception) {
            return AniListResult.Failure(AniListError.InvalidResponse(error.message ?: "Request failed"))
        }

        if (response.statusCode == 429) {
            return AniListResult.Failure(AniListError.RateLimited(response.retryAfterSeconds))
        }
        if (response.statusCode !in 200..299) {
            return AniListResult.Failure(AniListError.Http(response.statusCode))
        }
        val root = try {
            JSONObject(response.body)
        } catch (_: JSONException) {
            return AniListResult.Failure(AniListError.InvalidResponse("Malformed JSON response"))
        }
        val errors = try {
            root.arrayOrNull("errors")
        } catch (_: Exception) {
            return AniListResult.Failure(AniListError.InvalidResponse("Malformed GraphQL errors array"))
        }
        if (errors != null && errors.length() > 0) {
            val messages = try {
                buildList<String> {
                    for (index in 0 until errors.length()) {
                        val errorObject = errors.optJSONObject(index)
                            ?: error("Malformed GraphQL error item at index=$index")
                        val message = errorObject.stringOrNull("message").orEmpty().trim()
                        if (message.isNotBlank()) add(message.take(160))
                    }
                }
            } catch (_: Exception) {
                return AniListResult.Failure(AniListError.InvalidResponse("Malformed GraphQL error payload"))
            }
            return AniListResult.Failure(AniListError.GraphQl(messages.ifEmpty { listOf("GraphQL request failed") }))
        }
        val data = try {
            root.objectOrNull("data")
        } catch (_: Exception) {
            return AniListResult.Failure(AniListError.InvalidResponse("Invalid data object"))
        } ?: return AniListResult.Failure(AniListError.InvalidResponse("Missing data object"))
        return try {
            decode(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AniListResult.Failure(AniListError.InvalidResponse("Unexpected AniList response shape"))
        }
    }

    private fun parseAnime(media: JSONObject): AniListAnime? {
        val id = media.intOrNull("id")?.takeIf { it > 0 } ?: return null
        val title = media.objectOrNull("title")
        val cover = media.objectOrNull("coverImage")
        val studioObject = media.objectOrNull("studios")
        val studios = studioObject?.arrayOrNull("nodes")
        val studioNames = buildList {
            if (studios != null) for (index in 0 until studios.length()) {
                val studio = studios.optJSONObject(index)
                    ?: error("Malformed AniList studio at index=$index")
                studio.stringOrNull("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        val synonyms = media.arrayOrNull("synonyms")
        val genresArray = media.arrayOrNull("genres")
        val aliases = buildList {
            synonyms?.stringValues("synonyms")?.forEach(::add)
            listOfNotNull(
                title?.stringOrNull("romaji"),
                title?.stringOrNull("english"),
                title?.stringOrNull("native"),
            ).mapNotNull { it.nonBlankOrNull() }.forEach(::add)
        }.distinctBy { it.lowercase(Locale.ROOT) }
        val genres = genresArray?.stringValues("genres").orEmpty()

        val episodes = media.nonNegativeIntOrNull("episodes")
        val averageScore = media.intOrNull("averageScore")?.also {
            require(it in 0..100) { "AniList averageScore must be between 0 and 100." }
        }
        val duration = media.nonNegativeIntOrNull("duration")
        val seasonYear = media.intOrNull("seasonYear")?.also {
            require(it >= 0) { "AniList seasonYear must be non-negative." }
        }?.takeIf { it > 0 }

        return AniListAnime(
            id = id,
            titleRomaji = title?.stringOrNull("romaji").nonBlankOrNull(),
            titleEnglish = title?.stringOrNull("english").nonBlankOrNull(),
            titleNative = title?.stringOrNull("native").nonBlankOrNull(),
            synonyms = aliases,
            description = media.stringOrNull("description").nonBlankOrNull(),
            format = media.stringOrNull("format").nonBlankOrNull(),
            episodes = episodes,
            status = media.stringOrNull("status").nonBlankOrNull(),
            averageScore = averageScore,
            durationMinutes = duration,
            season = media.stringOrNull("season").nonBlankOrNull(),
            seasonYear = seasonYear,
            genres = genres,
            coverLargeUrl = validReiAnixArtworkHttpUrl(cover?.stringOrNull("large").nonBlankOrNull()),
            coverExtraLargeUrl = validReiAnixArtworkHttpUrl(cover?.stringOrNull("extraLarge").nonBlankOrNull()),
            bannerUrl = media.stringOrNull("bannerImage").nonBlankOrNull(),
            studios = studioNames,
        )
    }

    private fun JSONObject.nonNegativeIntOrNull(key: String): Int? =
        intOrNull(key)?.also { require(it >= 0) { "AniList field '$key' must be non-negative." } }

    private fun JSONArray.stringValues(field: String): List<String> =
        buildList {
            for (index in 0 until length()) {
                val value = opt(index) as? String
                    ?: error("Malformed AniList field '$field[$index]': expected text.")
                if (value.isNotBlank()) add(value)
            }
        }

    private fun String?.nonBlankOrNull(): String? =
        this?.takeIf { it.isNotBlank() && it != "null" }

    companion object {
        const val ENDPOINT = "https://graphql.anilist.co"
        const val DEFAULT_MINIMUM_REQUEST_INTERVAL_MS = 700L
        private const val MEDIA_FIELDS = """
            id title { romaji english native } synonyms
            description(asHtml: false) format episodes duration status averageScore season seasonYear
            genres coverImage { large extraLarge } bannerImage
            studios(isMain: true) { nodes { name } }
        """
        private const val SEARCH_MEDIA_FIELDS = """
            id title { romaji english native } synonyms
            format episodes status season seasonYear
            coverImage { large extraLarge } bannerImage
        """
    }
}

data class AniListAnime(
    val id: Int,
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String?,
    val synonyms: List<String>,
    val description: String?,
    val format: String?,
    val episodes: Int?,
    val status: String?,
    val averageScore: Int?,
    val durationMinutes: Int?,
    val season: String?,
    val seasonYear: Int?,
    val genres: List<String>,
    val coverLargeUrl: String?,
    val coverExtraLargeUrl: String?,
    val bannerUrl: String?,
    val studios: List<String>,
) {
    /** Secure, de-duplicated variants ordered by preferred image quality. */
    internal val coverCandidates: List<ReiAnixAniListCoverCandidate>
        get() = resolveAniListCoverCandidates(coverExtraLargeUrl, coverLargeUrl)

    val preferredTitle: String
        get() = titleEnglish ?: titleRomaji ?: titleNative ?: synonyms.firstOrNull().orEmpty()
}

sealed interface AniListResult<out T> {
    data class Success<T>(val value: T) : AniListResult<T>
    data class Failure(val error: AniListError) : AniListResult<Nothing>
}

sealed interface AniListError {
    data class Network(val message: String) : AniListError
    data class Http(val statusCode: Int) : AniListError
    data class RateLimited(val retryAfterSeconds: Long?) : AniListError
    data class GraphQl(val messages: List<String>) : AniListError
    data class InvalidRequest(val message: String) : AniListError
    data class InvalidResponse(val message: String) : AniListError
}

data class AniListHttpResponse(
    val statusCode: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
    val rateLimitLimit: Int? = null,
    val rateLimitRemaining: Int? = null,
)

fun interface AniListTransport {
    suspend fun post(endpoint: String, body: JSONObject): AniListHttpResponse
}

/** Blocking platform transport isolated on Dispatchers.IO by the client. */
class HttpUrlConnectionAniListTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) : AniListTransport {
    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
    }

    override suspend fun post(endpoint: String, body: JSONObject): AniListHttpResponse =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val activeConnection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", "ReiAnix-Android")
                }
                connection = activeConnection
                coroutineContext.job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) activeConnection.disconnect()
                }
                activeConnection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = activeConnection.responseCode
                val stream = if (code in 200..299) activeConnection.inputStream else activeConnection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val retryAfter = activeConnection.getHeaderField("Retry-After")?.toLongOrNull()
                val rateLimitLimit = activeConnection.getHeaderField("X-RateLimit-Limit")?.toIntOrNull()
                val rateLimitRemaining = activeConnection.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
                AniListHttpResponse(code, responseBody, retryAfter, rateLimitLimit, rateLimitRemaining)
            } finally {
                connection?.disconnect()
            }
        }
}
