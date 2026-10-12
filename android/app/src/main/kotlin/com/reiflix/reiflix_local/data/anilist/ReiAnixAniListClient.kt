package com.reiflix.reiflix_local.data.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
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

    suspend fun searchAnime(term: String, limit: Int = 10): AniListResult<List<AniListAnime>> {
        val query = term.trim()
        if (query.isEmpty()) return AniListResult.Success(emptyList())
        require(limit in 1..50) { "AniList result limit must be between 1 and 50." }
        val gql = """
            query SearchAnime($search: String, $page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                media(search: $search, type: ANIME, sort: SEARCH_MATCH) {
                  $MEDIA_FIELDS
                }
              }
            }
        """.trimIndent()
        return execute(gql, JSONObject()
            .put("search", query)
            .put("page", 1)
            .put("perPage", limit)) { data ->
            val page = data.optJSONObject("Page")
                ?: return@execute AniListResult.Failure(AniListError.InvalidResponse("Missing Page object"))
            val media = page.optJSONArray("media") ?: JSONArray()
            AniListResult.Success(buildList {
                for (i in 0 until media.length()) {
                    val item = media.optJSONObject(i) ?: continue
                    parseAnime(item)?.let(::add)
                }
            })
        }
    }

    suspend fun getAnime(id: Int): AniListResult<AniListAnime?> {
        if (id <= 0) return AniListResult.Failure(AniListError.InvalidRequest("AniList ID must be positive"))
        val gql = """
            query AnimeById($id: Int) {
              Media(id: $id, type: ANIME) { $MEDIA_FIELDS }
            }
        """.trimIndent()
        return execute(gql, JSONObject().put("id", id)) { data ->
            val media = data.optJSONObject("Media")
            AniListResult.Success(media?.let(::parseAnime))
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
                val wait = (lastRequestAtMs + minimumRequestIntervalMs - now).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
                lastRequestAtMs = System.currentTimeMillis()
            }
            withContext(Dispatchers.IO) {
                transport.post(ENDPOINT, JSONObject()
                    .put("query", query)
                    .put("variables", variables))
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
        val errors = root.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val messages = buildList {
                for (index in 0 until errors.length()) {
                    val message = errors.optJSONObject(index)?.optString("message").orEmpty()
                    if (message.isNotBlank()) add(message.take(160))
                }
            }
            return AniListResult.Failure(AniListError.GraphQl(messages.ifEmpty { listOf("GraphQL request failed") }))
        }
        val data = root.optJSONObject("data")
            ?: return AniListResult.Failure(AniListError.InvalidResponse("Missing data object"))
        return try {
            decode(data)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AniListResult.Failure(AniListError.InvalidResponse("Unexpected AniList response shape"))
        }
    }

    private fun parseAnime(media: JSONObject): AniListAnime? {
        val id = media.optInt("id", 0).takeIf { it > 0 } ?: return null
        val title = media.optJSONObject("title")
        val cover = media.optJSONObject("coverImage")
        val studios = media.optJSONObject("studios")?.optJSONArray("nodes")
        val studioNames = buildList {
            if (studios != null) for (index in 0 until studios.length()) {
                studios.optJSONObject(index)?.optString("name")
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val synonyms = media.optJSONArray("synonyms")
        val aliases = buildList {
            if (synonyms != null) for (index in 0 until synonyms.length()) {
                synonyms.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
            listOfNotNull(title?.optString("romaji"), title?.optString("english"), title?.optString("native"))
                .mapNotNull { it.nonBlankOrNull() }.forEach(::add)
        }.distinctBy { it.lowercase(Locale.ROOT) }
        val genresArray = media.optJSONArray("genres")
        val genres = buildList {
            if (genresArray != null) for (index in 0 until genresArray.length()) {
                genresArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return AniListAnime(
            id = id,
            titleRomaji = title?.optString("romaji").nonBlankOrNull(),
            titleEnglish = title?.optString("english").nonBlankOrNull(),
            titleNative = title?.optString("native").nonBlankOrNull(),
            synonyms = aliases,
            description = media.optString("description").nonBlankOrNull(),
            format = media.optString("format").nonBlankOrNull(),
            episodes = media.optInt("episodes", -1).takeIf { it >= 0 },
            status = media.optString("status").nonBlankOrNull(),
            averageScore = media.optInt("averageScore", -1).takeIf { it >= 0 },
            durationMinutes = media.optInt("duration", -1).takeIf { it >= 0 },
            season = media.optString("season").nonBlankOrNull(),
            seasonYear = media.optInt("seasonYear", -1).takeIf { it > 0 },
            genres = genres,
            coverLargeUrl = cover?.optString("large").nonBlankOrNull(),
            coverExtraLargeUrl = cover?.optString("extraLarge").nonBlankOrNull(),
            bannerUrl = media.optString("bannerImage").nonBlankOrNull(),
            studios = studioNames,
        )
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
                AniListHttpResponse(code, responseBody, retryAfter)
            } finally {
                connection?.disconnect()
            }
        }
}
