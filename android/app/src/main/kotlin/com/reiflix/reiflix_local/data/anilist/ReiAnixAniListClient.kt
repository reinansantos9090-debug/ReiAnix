package com.reiflix.reiflix_local.data.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import com.reiflix.reiflix_local.data.contract.arrayOrNull
import com.reiflix.reiflix_local.data.contract.intOrNull
import com.reiflix.reiflix_local.data.contract.objectOrNull
import com.reiflix.reiflix_local.data.contract.stringOrNull
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
    private val minimumRequestIntervalMs: Long? = null,
    requestController: AniListGraphqlRequestController? = null,
) {
    init {
        require(minimumRequestIntervalMs == null || minimumRequestIntervalMs >= 0L) {
            "Request interval cannot be negative."
        }
    }

    private val effectiveRequestController = requestController
        ?: minimumRequestIntervalMs?.let { interval ->
            AniListGraphqlRequestController(minimumIntervalOverrideMs = interval)
        }
        ?: AniListGraphqlRequestController.processShared

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
        return execute(
            gql,
            JSONObject()
                .put("search", query)
                .put("page", 1)
                .put("perPage", limit),
            allowPartialGraphQlErrors = true,
        ) { data, errorPaths ->
            val page = data.objectOrNull("Page")
                ?: return@execute AniListResult.Failure(AniListError.InvalidResponse("Missing Page object"))
            val media = page.arrayOrNull("media")
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Missing media array in AniList search response"),
                )
            var incomplete = errorPaths.isNotEmpty()
            val results = ArrayList<AniListAnime>(media.length())
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index)
                val anime = item?.let { runCatching { parseAnime(it) }.getOrNull() }
                if (anime == null) {
                    incomplete = true
                    continue
                }
                results += anime
            }
            AniListResult.Success(results.distinctBy { it.id }, incomplete = incomplete)
        }
    }

    suspend fun getAnime(id: Int): AniListResult<AniListAnime?> {
        if (id <= 0) return AniListResult.Failure(AniListError.InvalidRequest("AniList ID must be positive"))
        val gql = """
            query AnimeById(${'$'}id: Int) {
              Media(id: ${'$'}id, type: ANIME) { $MEDIA_FIELDS }
            }
        """.trimIndent()
        return execute(gql, JSONObject().put("id", id), allowPartialGraphQlErrors = true) { data, errorPaths ->
            if (!data.has("Media")) {
                return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Missing Media field in AniList detail response"),
                )
            }
            if (data.isNull("Media")) {
                return@execute AniListResult.Success(null, incomplete = errorPaths.isNotEmpty())
            }
            val media = data.objectOrNull("Media")
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Invalid Media object in AniList detail response"),
                )
            val anime = runCatching { parseAnime(media) }.getOrNull()
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("AniList detail is missing a valid media ID"),
                )
            AniListResult.Success(anime, incomplete = errorPaths.isNotEmpty())
        }
    }


    /**
     * Fetches up to 15 known AniList IDs with one Page.media(id_in: ...) operation.
     * Valid media are retained when GraphQL returns partial data; the incomplete flag
     * prevents callers from treating omitted IDs as definitive misses.
     */
    suspend fun getAnimeBatch(ids: List<Int>): AniListResult<AniListBatchResult<List<AniListAnime>>> {
        val requestedIds = ids.asSequence().filter { it > 0 }.distinct().toList()
        if (requestedIds.size > MAX_BATCH_SIZE) {
            return AniListResult.Failure(
                AniListError.InvalidRequest("AniList ID batch cannot exceed $MAX_BATCH_SIZE unique IDs."),
            )
        }
        if (requestedIds.isEmpty()) {
            return AniListResult.Success(AniListBatchResult(emptyList()))
        }

        val gql = """
            query AnimeBatchByIds(${'$'}ids: [Int!]!, ${'$'}page: Int!, ${'$'}perPage: Int!) {
              Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                media(id_in: ${'$'}ids, type: ANIME, sort: ID) {
                  $MEDIA_FIELDS
                }
              }
            }
        """.trimIndent()
        val variables = JSONObject()
            .put("ids", JSONArray(requestedIds))
            .put("page", 1)
            .put("perPage", requestedIds.size)

        return execute(gql, variables, allowPartialGraphQlErrors = true) { data, errorPaths ->
            val page = data.objectOrNull("Page")
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Missing Page object in AniList ID batch response"),
                )
            val media = page.arrayOrNull("media")
                ?: return@execute AniListResult.Failure(
                    AniListError.InvalidResponse("Missing media array in AniList ID batch response"),
                )
            var complete = errorPaths.isEmpty()
            val results = ArrayList<AniListAnime>(media.length())
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index)
                val anime = item?.let(::parseAnime)
                if (anime == null) {
                    complete = false
                    continue
                }
                if (anime.id in requestedIds) results += anime
            }
            AniListResult.Success(
                AniListBatchResult(
                    items = results.distinctBy { it.id },
                    incompleteKeys = if (complete) emptySet() else setOf(ID_BATCH_KEY),
                ),
            )
        }
    }

    /**
     * Queries up to 15 different title searches through distinct GraphQL aliases.
     * Every title is supplied as a GraphQL variable, never interpolated into query text.
     */
    suspend fun searchAnimeBatch(
        terms: List<String>,
        perTermLimit: Int = 10,
    ): AniListResult<AniListBatchResult<Map<String, List<AniListAnime>>>> {
        require(perTermLimit in 1..50) { "AniList result limit must be between 1 and 50." }
        val normalizedTerms = terms.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..120 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
        if (normalizedTerms.size > MAX_BATCH_SIZE) {
            return AniListResult.Failure(
                AniListError.InvalidRequest("AniList title batch cannot exceed $MAX_BATCH_SIZE unique terms."),
            )
        }
        if (normalizedTerms.isEmpty()) {
            return AniListResult.Success(AniListBatchResult(emptyMap()))
        }

        val variableDefinitions = buildList {
            normalizedTerms.indices.forEach { index -> add("${'$'}term$index: String!") }
            add("${'$'}perPage: Int!")
        }.joinToString(", ")
        val aliasedSearches = normalizedTerms.indices.joinToString("\n") { index ->
            """
              term$index: Page(page: 1, perPage: ${'$'}perPage) {
                media(search: ${'$'}term$index, type: ANIME, sort: SEARCH_MATCH) {
                  ...AniListBatchMediaFields
                }
              }
            """.trimIndent()
        }
        val gql = """
            query SearchAnimeBatch($variableDefinitions) {
              $aliasedSearches
            }
            fragment AniListBatchMediaFields on Media {
              $MEDIA_FIELDS
            }
        """.trimIndent()
        val variables = JSONObject().put("perPage", perTermLimit)
        normalizedTerms.forEachIndexed { index, term ->
            variables.put("term$index", term)
        }

        return execute(gql, variables, allowPartialGraphQlErrors = true) { data, errorPaths ->
            val results = linkedMapOf<String, List<AniListAnime>>()
            val incompleteTerms = linkedSetOf<String>()
            if ("*" in errorPaths) incompleteTerms += normalizedTerms
            normalizedTerms.forEachIndexed { index, term ->
                val alias = "term$index"
                val page = data.objectOrNull(alias)
                val media = page?.arrayOrNull("media")
                if (page == null || media == null || alias in errorPaths) {
                    incompleteTerms += term
                } else {
                    val parsed = ArrayList<AniListAnime>(media.length())
                    for (mediaIndex in 0 until media.length()) {
                        val item = media.optJSONObject(mediaIndex)
                        val anime = item?.let(::parseAnime)
                        if (anime == null) incompleteTerms += term else parsed += anime
                    }
                    results[term] = parsed.distinctBy { it.id }
                }
            }
            AniListResult.Success(
                AniListBatchResult(
                    items = results,
                    incompleteKeys = incompleteTerms,
                ),
            )
        }
    }

    private suspend fun <T> execute(
        query: String,
        variables: JSONObject,
        allowPartialGraphQlErrors: Boolean = false,
        decode: (JSONObject, Set<String>) -> AniListResult<T>,
    ): AniListResult<T> {
        val response = try {
            effectiveRequestController.execute(ENDPOINT) {
                withContext(Dispatchers.IO) {
                    transport.post(ENDPOINT, JSONObject()
                        .put("query", query)
                        .put("variables", variables))
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
        val hasGraphQlErrors = errors != null && errors.length() > 0
        if (hasGraphQlErrors && !allowPartialGraphQlErrors) {
            val messages = try {
                graphQlErrorMessages(errors)
            } catch (_: Exception) {
                return AniListResult.Failure(AniListError.InvalidResponse("Malformed GraphQL error payload"))
            }
            return AniListResult.Failure(AniListError.GraphQl(messages))
        }
        val errorPaths: Set<String> = if (hasGraphQlErrors) {
            try {
                buildSet<String> {
                    for (index in 0 until errors!!.length()) {
                        val errorObject = errors.optJSONObject(index)
                            ?: error("Malformed GraphQL error item at index=$index")
                        val path = errorObject.arrayOrNull("path")
                        val alias = path?.opt(0) as? String
                        add(alias?.takeIf { it.isNotBlank() } ?: "*")
                    }
                }
            } catch (_: Exception) {
                return AniListResult.Failure(AniListError.InvalidResponse("Malformed GraphQL error payload"))
            }
        } else {
            emptySet<String>()
        }
        val data = try {
            root.objectOrNull("data")
        } catch (_: Exception) {
            return AniListResult.Failure(AniListError.InvalidResponse("Invalid data object"))
        } ?: return if (hasGraphQlErrors) {
            val messages = try {
                graphQlErrorMessages(errors)
            } catch (_: Exception) {
                return AniListResult.Failure(AniListError.InvalidResponse("Malformed GraphQL error payload"))
            }
            AniListResult.Failure(AniListError.GraphQl(messages))
        } else {
            AniListResult.Failure(AniListError.InvalidResponse("Missing data object"))
        }
        return try {
            decode(data, errorPaths)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AniListResult.Failure(AniListError.InvalidResponse("Unexpected AniList response shape"))
        }
    }

    private fun graphQlErrorMessages(errors: JSONArray?): List<String> =
        buildList {
            if (errors == null) return@buildList
            for (index in 0 until errors.length()) {
                val errorObject = errors.optJSONObject(index)
                    ?: throw JSONException("Malformed GraphQL error item at index=$index")
                val message = errorObject.stringOrNull("message").orEmpty().trim()
                if (message.isNotBlank()) add(message.take(160))
            }
        }.ifEmpty { listOf("GraphQL request failed") }

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
            myAnimeListId = media.intOrNull("idMal")?.takeIf { it > 0 }?.toLong(),
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
            coverLargeUrl = cover?.stringOrNull("large").nonBlankOrNull(),
            coverExtraLargeUrl = cover?.stringOrNull("extraLarge").nonBlankOrNull(),
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
        const val DEFAULT_MINIMUM_REQUEST_INTERVAL_MS = 2_200L
        const val MAX_BATCH_SIZE = 15
        private const val ID_BATCH_KEY = "ids"
        private const val MEDIA_FIELDS = """
            id idMal title { romaji english native } synonyms
            description(asHtml: false) format episodes duration status averageScore season seasonYear
            genres coverImage { large extraLarge } bannerImage
            studios(isMain: true) { nodes { name } }
        """
    }
}

data class AniListBatchResult<T>(
    val items: T,
    val incompleteKeys: Set<String> = emptySet(),
) {
    val isComplete: Boolean get() = incompleteKeys.isEmpty()
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
    val myAnimeListId: Long? = null,
) {
    val preferredTitle: String
        get() = titleEnglish ?: titleRomaji ?: titleNative ?: synonyms.firstOrNull().orEmpty()
}

sealed interface AniListResult<out T> {
    /** Incomplete results retain valid data but must not be used as a definitive catalog candidate set. */
    data class Success<T>(val value: T, val incomplete: Boolean = false) : AniListResult<T>
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
    val headers: Map<String, String> = emptyMap(),
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
                val responseHeaders = activeConnection.headerFields.entries.mapNotNull { (name, values) ->
                    if (name.isNullOrBlank() || values.isNullOrEmpty()) null
                    else name.lowercase(Locale.ROOT) to values.first()
                }.toMap()
                val retryAfter = responseHeaders["retry-after"]?.toLongOrNull()
                AniListHttpResponse(code, responseBody, retryAfter, responseHeaders)
            } finally {
                connection?.disconnect()
            }
        }
}
