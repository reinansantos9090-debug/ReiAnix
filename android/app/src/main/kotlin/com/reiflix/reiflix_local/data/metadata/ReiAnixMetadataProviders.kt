package com.reiflix.reiflix_local.data.metadata

import com.reiflix.reiflix_local.data.anilist.AniListAnime
import com.reiflix.reiflix_local.data.anilist.AniListError
import com.reiflix.reiflix_local.data.anilist.AniListResult
import com.reiflix.reiflix_local.data.anilist.ReiAnixAniListClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Input to all metadata/artwork providers. Local catalog IDs are deliberately not provider IDs. */
data class ReiAnixMetadataQuery(
    val title: String,
    val alternateTitles: List<String> = emptyList(),
    val year: Int? = null,
    val mediaKind: String? = null,
) {
    val searchTitles: List<String>
        get() = (listOf(title) + alternateTitles)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(::normalizeReiAnixMetadataTitle)
            .take(MAX_TITLE_VARIANTS)
}

data class ReiAnixMetadataCandidate(
    val providerId: String,
    val externalId: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val mediaKind: String,
    val posterUrl: String?,
    val provenance: String,
    val matchedQueryTitle: String,
    val confidence: Double,
    val alternateTitles: List<String> = emptyList(),
)

data class ReiAnixMetadataProviderFailure(
    val providerId: String,
    val category: String,
)

data class ReiAnixMetadataResolution(
    val candidates: List<ReiAnixMetadataCandidate>,
    val failures: List<ReiAnixMetadataProviderFailure>,
    val skippedProviders: List<String>,
) {
    val hasCandidates: Boolean get() = candidates.isNotEmpty()
}

interface ReiAnixMetadataProvider {
    val providerId: String
    val priority: Int
    val isConfigured: Boolean get() = true
    suspend fun search(query: ReiAnixMetadataQuery): List<ReiAnixMetadataCandidate>
}

/**
 * Runs independent providers concurrently with bounded fan-out. A provider failure or timeout
 * is reported as partial failure and never discards successful candidates from another source.
 */
class ReiAnixMetadataProviderRegistry(
    providers: List<ReiAnixMetadataProvider>,
    private val maxConcurrentProviders: Int = 2,
    private val providerTimeoutMillis: Long = 12_000L,
) {
    private val providers = providers.distinctBy { it.providerId }
        .sortedWith(compareByDescending<ReiAnixMetadataProvider> { it.priority }.thenBy { it.providerId })
    private val permits = Semaphore(maxConcurrentProviders.coerceAtLeast(1))

    suspend fun search(query: ReiAnixMetadataQuery): ReiAnixMetadataResolution = coroutineScope {
        val configured = providers.filter { it.isConfigured }
        val skipped = providers.filterNot { it.isConfigured }.map { it.providerId }
        val outcomes = configured.map { provider ->
            async {
                try {
                    permits.withPermit {
                        try {
                            ProviderOutcome(
                                provider.providerId,
                                withTimeout(providerTimeoutMillis) { provider.search(query) },
                                null,
                            )
                        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                            ProviderOutcome(
                                providerId = provider.providerId,
                                candidates = emptyList(),
                                failure = ReiAnixMetadataProviderFailure(provider.providerId, "timeout"),
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ProviderOutcome(
                        providerId = provider.providerId,
                        candidates = emptyList(),
                        failure = ReiAnixMetadataProviderFailure(
                            provider.providerId,
                            safeFailureCategory(error),
                        ),
                    )
                }
            }
        }.awaitAll()

        val candidates = outcomes.flatMap { it.candidates }
            .mapNotNull { candidate -> rankReiAnixMetadataCandidate(query, candidate) }
            .distinctBy { it.providerId + ":" + it.externalId }
            .sortedWith(
                compareByDescending<ReiAnixMetadataCandidate> { it.confidence }
                    .thenByDescending { candidate ->
                        providers.firstOrNull { provider -> provider.providerId == candidate.providerId }?.priority ?: 0
                    }
                    .thenBy { it.externalId },
            )
        ReiAnixMetadataResolution(
            candidates = candidates,
            failures = outcomes.mapNotNull { it.failure },
            skippedProviders = skipped,
        )
    }

    private data class ProviderOutcome(
        val providerId: String,
        val candidates: List<ReiAnixMetadataCandidate>,
        val failure: ReiAnixMetadataProviderFailure?,
    )

    private fun safeFailureCategory(error: Exception): String = when {
        error is JSONException -> "invalid_json"
        error is IOException && error.message == "ANILIST_RATE_LIMITED" -> "rate_limited"
        error is IOException && error.message?.startsWith("ANILIST_HTTP_") == true -> "http_error"
        error is IOException && error.message == "ANILIST_PROVIDER_FAILURE" -> "provider_error"
        error is IOException && error.message == "TMDB_INVALID_JSON" -> "invalid_json"
        error is IOException && error.message == "TMDB_HTTP_401" -> "auth_rejected"
        error is IOException && error.message == "TMDB_HTTP_403" -> "permission_rejected"
        error is IOException && error.message == "TMDB_HTTP_429" -> "rate_limited"
        error is IOException && error.message?.startsWith("TMDB_HTTP_") == true -> "http_error"
        error is IOException -> "network_or_http"
        else -> "provider_error"
    }
}

private const val MAX_TITLE_VARIANTS = 4

/** Accent/punctuation-insensitive title comparison used only for candidate validation. */
internal fun normalizeReiAnixMetadataTitle(value: String): String {
    val folded = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
    return folded.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

/**
 * Conservatively rejects homonyms and season/sequel mismatches: a candidate must have an exact
 * normalized title/original-title match, and a known year may differ by at most one year.
 */
internal fun rankReiAnixMetadataCandidate(
    query: ReiAnixMetadataQuery,
    candidate: ReiAnixMetadataCandidate,
): ReiAnixMetadataCandidate? {
    if (candidate.externalId.isBlank() || candidate.posterUrl.isNullOrBlank()) return null
    if (!query.mediaKind.isNullOrBlank()) {
        val requestedIsMovie = query.mediaKind.equals("movie", ignoreCase = true)
        val candidateIsMovie = candidate.mediaKind.equals("movie", ignoreCase = true)
        if (requestedIsMovie != candidateIsMovie) return null
    }
    val resultTitles = (listOf(candidate.title, candidate.originalTitle.orEmpty()) + candidate.alternateTitles)
        .map(::normalizeReiAnixMetadataTitle)
        .filter(String::isNotEmpty)
        .toSet()
    val matchedTitle = query.searchTitles.firstOrNull {
        normalizeReiAnixMetadataTitle(it) in resultTitles
    } ?: return null

    val requestedYear = query.year
    val resultYear = candidate.year
    if (requestedYear != null && resultYear != null && kotlin.math.abs(requestedYear - resultYear) > 1) {
        return null
    }
    val confidence = when {
        requestedYear != null && resultYear == requestedYear -> 1.0
        requestedYear != null && resultYear != null -> 0.84
        else -> 0.92
    }
    return candidate.copy(matchedQueryTitle = matchedTitle, confidence = confidence)
}

/** TMDB image URL construction per the official base URL + size + poster_path contract. */
internal fun tmdbPosterImageUrl(posterPath: String?): String? {
    val path = posterPath?.trim().orEmpty()
    if (!path.startsWith("/") || path.startsWith("//") || path.any { it.isWhitespace() || it.code == 0 }) {
        return null
    }
    if ('?' in path || '#' in path || path.split('/').any { it == "." || it == ".." }) return null
    if (!Regex("/[A-Za-z0-9._/-]+").matches(path)) return null
    return "https://image.tmdb.org/t/p/w500" + path
}

/**
 * Official TMDB REST search provider. The read token is supplied by build configuration and is
 * never appended to URLs or diagnostics. No token means this provider is skipped cleanly.
 */
class ReiAnixTmdbMetadataProvider(
    readAccessToken: String,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val language: String = "pt-BR",
) : ReiAnixMetadataProvider {
    private val token = readAccessToken.trim()
    override val providerId: String = "tmdb"
    override val priority: Int = 375
    override val isConfigured: Boolean get() = token.isNotEmpty()

    override suspend fun search(query: ReiAnixMetadataQuery): List<ReiAnixMetadataCandidate> {
        if (!isConfigured || query.searchTitles.isEmpty()) return emptyList()
        val kind = if (query.mediaKind.equals("movie", ignoreCase = true)) "movie" else "tv"
        return withContext(Dispatchers.IO) {
            val byId = LinkedHashMap<String, ReiAnixMetadataCandidate>()
            var successfulRequests = 0
            var lastFailure: Exception? = null
            for (title in query.searchTitles) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    val response = requestSearch(kind, title, query.year)
                    successfulRequests++
                    val results = response.optJSONArray("results") ?: continue
                    for (index in 0 until minOf(results.length(), MAX_RESULTS_PER_QUERY)) {
                        val item = results.optJSONObject(index) ?: continue
                        if (item.optBoolean("adult", false)) continue
                        val externalId = item.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: continue
                        val resultTitle = if (kind == "movie") {
                            item.optString("title").takeUnless { it == "null" }.orEmpty()
                        } else {
                            item.optString("name").takeUnless { it == "null" }.orEmpty()
                        }
                        val originalTitle = if (kind == "movie") {
                            item.optString("original_title").takeUnless { it == "null" }
                        } else {
                            item.optString("original_name").takeUnless { it == "null" }
                        }
                        val poster = tmdbPosterImageUrl(
                            item.optString("poster_path").takeUnless { it == "null" || it.isBlank() },
                        ) ?: continue
                        val date = if (kind == "movie") {
                            item.optString("release_date")
                        } else {
                            item.optString("first_air_date")
                        }
                        val candidate = ReiAnixMetadataCandidate(
                            providerId = providerId,
                            externalId = externalId,
                            title = resultTitle,
                            originalTitle = originalTitle,
                            year = date.take(4).toIntOrNull(),
                            mediaKind = kind,
                            posterUrl = poster,
                            provenance = "TMDB " + kind.uppercase(Locale.ROOT) + " search; poster_path",
                            matchedQueryTitle = title,
                            confidence = 0.0,
                        )
                        val ranked = rankReiAnixMetadataCandidate(query, candidate) ?: continue
                        val key = providerId + ":" + externalId
                        val prior = byId[key]
                        if (prior == null || ranked.confidence > prior.confidence) byId[key] = ranked
                    }
                    if (byId.isNotEmpty()) break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastFailure = error
                    if (successfulRequests == 0) break
                }
            }
            if (successfulRequests == 0 && lastFailure != null) throw lastFailure
            byId.values.sortedByDescending { it.confidence }.take(MAX_CANDIDATES)
        }
    }

    private suspend fun requestSearch(kind: String, title: String, year: Int?): JSONObject {
        val query = "query=" + URLEncoder.encode(title, Charsets.UTF_8.name()) +
            "&include_adult=false&language=" + URLEncoder.encode(language, Charsets.UTF_8.name()) +
            "&page=1" + (year?.let {
                (if (kind == "movie") "&year=" else "&first_air_date_year=") + it
            } ?: "")
        val url = URL("https://api.themoviedb.org/3/search/" + kind + "?" + query)
        var attempt = 0
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val connection = connectionFactory(url)
            val requestJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            requestJob?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer " + token)
                connection.connect()
                val status = connection.responseCode
                if ((status == 429 || status in 500..599) && attempt < MAX_HTTP_RETRIES) {
                    val retrySeconds = connection.getHeaderField("Retry-After")
                        ?.toLongOrNull()
                        ?.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
                        ?: (attempt + 1L)
                    attempt++
                    delay(retrySeconds * 1_000L)
                    continue
                }
                if (status !in 200..299) throw IOException("TMDB_HTTP_" + status)
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_RESPONSE_BYTES) throw IOException("TMDB_RESPONSE_TOO_LARGE")
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                return try {
                    JSONObject(body)
                } catch (_: JSONException) {
                    throw IOException("TMDB_INVALID_JSON")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_RESULTS_PER_QUERY = 20
        const val MAX_CANDIDATES = 20
        const val MAX_HTTP_RETRIES = 2
        const val MAX_RETRY_AFTER_SECONDS = 3L
    }
}


/**
 * Adapter for the existing, unauthenticated AniList GraphQL client. It participates in the
 * same query/result contract while leaving metadata writes and the existing AniList ID untouched.
 */
class ReiAnixAniListMetadataProvider(
    private val client: ReiAnixAniListClient,
) : ReiAnixMetadataProvider {
    override val providerId: String = "anilist"
    override val priority: Int = 400

    override suspend fun search(query: ReiAnixMetadataQuery): List<ReiAnixMetadataCandidate> {
        val ranked = LinkedHashMap<String, ReiAnixMetadataCandidate>()
        var lastFailure: Exception? = null
        for (title in query.searchTitles) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            when (val result = client.searchAnime(title, limit = 12)) {
                is AniListResult.Success -> {
                    for (anime in result.value) {
                        val candidate = anime.toMetadataCandidate(title) ?: continue
                        val valid = rankReiAnixMetadataCandidate(query, candidate) ?: continue
                        val prior = ranked[valid.externalId]
                        if (prior == null || valid.confidence > prior.confidence) {
                            ranked[valid.externalId] = valid
                        }
                    }
                    if (ranked.isNotEmpty()) break
                }
                is AniListResult.Failure -> {
                    lastFailure = when (val error = result.error) {
                        is AniListError.RateLimited -> IOException("ANILIST_RATE_LIMITED")
                        is AniListError.Http -> IOException("ANILIST_HTTP_" + error.statusCode)
                        is AniListError.Network -> IOException("ANILIST_NETWORK")
                        else -> IOException("ANILIST_PROVIDER_FAILURE")
                    }
                    break
                }
            }
        }
        if (ranked.isEmpty() && lastFailure != null) throw lastFailure
        return ranked.values.sortedByDescending { it.confidence }.take(MAX_CANDIDATES)
    }

    private fun AniListAnime.toMetadataCandidate(matchedTitle: String): ReiAnixMetadataCandidate? {
        val poster = coverExtraLargeUrl ?: coverLargeUrl ?: return null
        if (id <= 0) return null
        val bestTitle = preferredTitle.trim().takeIf(String::isNotEmpty) ?: return null
        val alternatives = (listOfNotNull(titleEnglish, titleRomaji, titleNative) + synonyms)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val original = listOfNotNull(titleNative, titleRomaji).firstOrNull { it.isNotBlank() }
        return ReiAnixMetadataCandidate(
            providerId = "anilist",
            externalId = id.toString(),
            title = bestTitle,
            originalTitle = original,
            year = seasonYear,
            mediaKind = if (format.equals("MOVIE", ignoreCase = true)) "movie" else "tv",
            posterUrl = poster,
            provenance = "AniList GraphQL Media search; coverImage",
            matchedQueryTitle = matchedTitle,
            confidence = 0.0,
            alternateTitles = alternatives,
        )
    }
}
