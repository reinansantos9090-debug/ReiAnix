package com.reiflix.reiflix_local.data.tracemoe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

private const val TRACE_MOE_INITIAL_RETRY_DELAY_MILLIS = 500L
private const val TRACE_MOE_MAX_RETRY_DELAY_MILLIS = 5_000L
private const val TRACE_MOE_MAX_RETRY_AFTER_MILLIS = 30_000L

enum class TraceMoeFailureCategory(val retryable: Boolean) {
    INVALID_IMAGE(false),
    INVALID_REQUEST(false),
    QUOTA_OR_CONCURRENCY(false),
    FORBIDDEN(false),
    METHOD_NOT_ALLOWED(false),
    PAYLOAD_TOO_LARGE(false),
    UNSUPPORTED_MEDIA_TYPE(false),
    RATE_LIMITED(true),
    QUEUE_FULL(true),
    SERVER_ERROR(true),
    HTTP_ERROR(false),
    NETWORK_ERROR(true),
    INVALID_RESPONSE(false),
}

class TraceMoeRequestException(
    val category: TraceMoeFailureCategory,
    message: String,
    val httpStatus: Int? = null,
    val retryable: Boolean = category.retryable,
    val retryAfterMillis: Long = 0L,
    cause: Throwable? = null,
) : IOException(message, cause)

data class TraceMoeQuotaSnapshot(
    val quota: Int,
    val quotaUsed: Int,
) {
    val remainingRequests: Int
        get() = (quota - quotaUsed).coerceAtLeast(0)

    val isDepleted: Boolean
        get() = remainingRequests == 0
}

internal fun classifyTraceMoeHttpStatus(statusCode: Int): TraceMoeFailureCategory = when (statusCode) {
    400 -> TraceMoeFailureCategory.INVALID_REQUEST
    402 -> TraceMoeFailureCategory.QUOTA_OR_CONCURRENCY
    403 -> TraceMoeFailureCategory.FORBIDDEN
    405 -> TraceMoeFailureCategory.METHOD_NOT_ALLOWED
    413 -> TraceMoeFailureCategory.PAYLOAD_TOO_LARGE
    415 -> TraceMoeFailureCategory.UNSUPPORTED_MEDIA_TYPE
    429 -> TraceMoeFailureCategory.RATE_LIMITED
    503 -> TraceMoeFailureCategory.QUEUE_FULL
    in 500..599 -> TraceMoeFailureCategory.SERVER_ERROR
    else -> TraceMoeFailureCategory.HTTP_ERROR
}

internal fun traceMoeRetryDelayMillis(retryIndex: Int, retryAfterMillis: Long = 0L): Long {
    require(retryIndex >= 0)
    val backoff = (TRACE_MOE_INITIAL_RETRY_DELAY_MILLIS shl retryIndex.coerceAtMost(4))
        .coerceAtMost(TRACE_MOE_MAX_RETRY_DELAY_MILLIS)
    return maxOf(backoff, retryAfterMillis.coerceIn(0L, TRACE_MOE_MAX_RETRY_AFTER_MILLIS))
}

internal fun traceMoeQuotaEndpoint(searchEndpoint: String): String =
    URI(searchEndpoint).resolve("/me").toASCIIString()

internal fun parseTraceMoeQuotaSnapshot(responseBody: String): TraceMoeQuotaSnapshot {
    val root = try {
        JSONObject(responseBody)
    } catch (error: Exception) {
        throw TraceMoeRequestException(
            TraceMoeFailureCategory.INVALID_RESPONSE,
            "Trace.moe returned malformed quota JSON.",
            cause = error,
        )
    }
    val quota = root.optInt("quota", -1)
    val quotaUsed = root.optInt("quotaUsed", -1)
    if (quota < 0 || quotaUsed < 0) {
        throw TraceMoeRequestException(
            TraceMoeFailureCategory.INVALID_RESPONSE,
            "Trace.moe quota response did not contain valid quota values.",
        )
    }
    return TraceMoeQuotaSnapshot(quota, quotaUsed)
}

/**
 * Search contract shared by the public anime-scene matcher and optional web-title
 * hint source. Only already-generated local episode thumbnails are supplied.
 */
fun interface ReiAnixTraceMoeSearchProvider {
    suspend fun search(imageFile: File): List<TraceMoeSearchMatch>
}

class ReiAnixTraceMoeClient(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val minimumRequestIntervalMillis: Long = DEFAULT_REQUEST_INTERVAL_MILLIS,
    private val searchEndpoint: String = SEARCH_ENDPOINT,
) : ReiAnixTraceMoeSearchProvider {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..60_000)
        require(minimumRequestIntervalMillis >= 0L)
        val endpointUri = runCatching { URI(searchEndpoint) }.getOrNull()
        require(
            endpointUri != null &&
                endpointUri.scheme.equals("https", ignoreCase = true) &&
                !endpointUri.host.isNullOrBlank()
        ) { "The visual-search endpoint must be an absolute HTTPS URL." }
    }

    private val endpointKey = runCatching {
        URI(searchEndpoint).let { "${it.scheme}://${it.rawAuthority}${it.rawPath}" }
    }.getOrDefault(searchEndpoint)
    private val requestGate = REQUEST_GATES.computeIfAbsent(endpointKey) {
        EndpointRequestGate(minimumRequestIntervalMillis)
    }
    private val quotaGate = QUOTA_GATES.computeIfAbsent(endpointKey) { QuotaRequestGate() }

    /**
     * A process-wide, per-endpoint mutex serializes every instance in the app process.
     * The public API and a separately hosted API use different gates, so they remain
     * independent and can still be queried in parallel by the hybrid client.
     */
    override suspend fun search(imageFile: File): List<TraceMoeSearchMatch> =
        requestGate.mutex.withLock {
            val imageBytes = readSearchImage(imageFile)
            var retryIndex = 0
            while (true) {
                val now = System.currentTimeMillis()
                val waitMillis = (
                    requestGate.lastRequestAtMillis + minimumRequestIntervalMillis - now
                ).coerceAtLeast(0L)
                if (waitMillis > 0L) delay(waitMillis)
                requestGate.lastRequestAtMillis = System.currentTimeMillis()

                try {
                    return@withLock withContext(Dispatchers.IO) {
                        requestOnce(imageBytes)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: TraceMoeRequestException) {
                    if (!error.retryable || retryIndex >= MAX_RETRIES) throw error
                    delay(traceMoeRetryDelayMillis(retryIndex, error.retryAfterMillis))
                    retryIndex += 1
                } catch (error: IOException) {
                    if (retryIndex >= MAX_RETRIES) {
                        throw TraceMoeRequestException(
                            TraceMoeFailureCategory.NETWORK_ERROR,
                            error.message ?: "Trace.moe could not be reached.",
                            cause = error,
                        )
                    }
                    delay(traceMoeRetryDelayMillis(retryIndex))
                    retryIndex += 1
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }

    /**
     * Reads Trace.moe's /me quota endpoint. The result is cached briefly because /me
     * is a status query and must not add one request for every episode thumbnail.
     */
    suspend fun getQuota(forceRefresh: Boolean = false): TraceMoeQuotaSnapshot =
        quotaGate.mutex.withLock {
            val now = System.currentTimeMillis()
            val cached = quotaGate.cachedQuota
            if (!forceRefresh && cached != null &&
                now >= quotaGate.cachedAtMillis &&
                now - quotaGate.cachedAtMillis < QUOTA_CACHE_TTL_MILLIS
            ) {
                return@withLock cached
            }
            val fresh = withContext(Dispatchers.IO) { requestQuotaOnce() }
            quotaGate.cachedQuota = fresh
            quotaGate.cachedAtMillis = System.currentTimeMillis()
            fresh
        }

    private suspend fun readSearchImage(imageFile: File): ByteArray = withContext(Dispatchers.IO) {
        if (!imageFile.isFile || imageFile.length() !in 4L..MAX_IMAGE_BYTES) {
            throw TraceMoeRequestException(
                TraceMoeFailureCategory.INVALID_IMAGE,
                "Trace.moe requires a readable episode thumbnail within the image-size limit.",
            )
        }
        val imageBytes = try {
            imageFile.readBytes()
        } catch (error: IOException) {
            throw TraceMoeRequestException(
                TraceMoeFailureCategory.INVALID_IMAGE,
                "The generated episode thumbnail could not be read.",
                cause = error,
            )
        }
        if (imageBytes.size.toLong() > MAX_IMAGE_BYTES || !isJpeg(imageBytes)) {
            throw TraceMoeRequestException(
                TraceMoeFailureCategory.INVALID_IMAGE,
                "Trace.moe input must be a valid JPEG episode thumbnail.",
            )
        }
        imageBytes
    }

    private suspend fun requestOnce(imageBytes: ByteArray): List<TraceMoeSearchMatch> {
        val boundary = "ReiAnixTraceMoe" + UUID.randomUUID().toString().replace("-", "")
        val prefix = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"image\"; filename=\"episode-frame.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val requestBytes = ByteArrayOutputStream(prefix.size + imageBytes.size + suffix.size).apply {
            write(prefix)
            write(imageBytes)
            write(suffix)
        }.toByteArray()

        val connection = URL(searchEndpoint).openConnection() as? HttpURLConnection
            ?: throw IOException("Trace.moe did not provide an HTTP connection.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(requestBytes.size)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) connection.disconnect()
            }
            connection.outputStream.use { output -> output.write(requestBytes) }
            val statusCode = connection.responseCode
            val responseBody = readBoundedResponse(connection, statusCode, MAX_RESPONSE_BYTES)
            if (statusCode !in 200..299) {
                val category = classifyTraceMoeHttpStatus(statusCode)
                throw TraceMoeRequestException(
                    category = category,
                    message = errorMessage(statusCode, responseBody),
                    httpStatus = statusCode,
                    retryAfterMillis = retryAfterMillis(connection),
                )
            }
            return parseMatches(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestQuotaOnce(): TraceMoeQuotaSnapshot {
        val connection = URL(traceMoeQuotaEndpoint(searchEndpoint)).openConnection() as? HttpURLConnection
            ?: throw IOException("Trace.moe did not provide an HTTP connection for /me.")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val statusCode = connection.responseCode
            val responseBody = readBoundedResponse(connection, statusCode, MAX_QUOTA_RESPONSE_BYTES)
            if (statusCode !in 200..299) {
                val category = classifyTraceMoeHttpStatus(statusCode)
                throw TraceMoeRequestException(
                    category = category,
                    message = errorMessage(statusCode, responseBody),
                    httpStatus = statusCode,
                    retryable = false,
                )
            }
            return parseTraceMoeQuotaSnapshot(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedResponse(
        connection: HttpURLConnection,
        statusCode: Int,
        maximumBytes: Int,
    ): String {
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            ?: return ""
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                if (total > maximumBytes) {
                    throw TraceMoeRequestException(
                        TraceMoeFailureCategory.INVALID_RESPONSE,
                        "Trace.moe response exceeded the configured size limit.",
                    )
                }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun retryAfterMillis(connection: HttpURLConnection): Long {
        val seconds = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: return 0L
        return (seconds * 1_000L).coerceIn(0L, TRACE_MOE_MAX_RETRY_AFTER_MILLIS)
    }

    private fun errorMessage(statusCode: Int, responseBody: String): String {
        val detail = runCatching { JSONObject(responseBody).optString("error").take(160) }.getOrNull()
        return detail?.takeIf(String::isNotBlank) ?: "Trace.moe returned HTTP $statusCode."
    }

    private fun parseMatches(responseBody: String): List<TraceMoeSearchMatch> {
        val root = try {
            JSONObject(responseBody)
        } catch (error: Exception) {
            throw TraceMoeRequestException(
                TraceMoeFailureCategory.INVALID_RESPONSE,
                "Trace.moe returned malformed JSON.",
                cause = error,
            )
        }
        val results = root.optJSONArray("result")
            ?: throw TraceMoeRequestException(
                TraceMoeFailureCategory.INVALID_RESPONSE,
                "Trace.moe response did not contain a result array.",
            )
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val rawId = item.opt("anilist")
                // The upstream anilistInfo response includes AniList's adult flag.
                // Skip flagged entries before they can participate in visual consensus.
                if (rawId is JSONObject && rawId.optBoolean("isAdult", true)) continue
                val anilistId = when (rawId) {
                    is Number -> rawId.toInt()
                    is JSONObject -> rawId.optInt("id", 0)
                    else -> 0
                }
                val similarity = item.optDouble("similarity", Double.NaN)
                if (anilistId > 0 && similarity.isFinite() && similarity in 0.0..1.0) {
                    add(TraceMoeSearchMatch(anilistId = anilistId, similarity = similarity))
                }
            }
        }.sortedByDescending(TraceMoeSearchMatch::similarity)
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
            bytes[bytes.lastIndex] == 0xd9.toByte()

    private class EndpointRequestGate(val minimumIntervalMillis: Long) {
        val mutex = Mutex()
        var lastRequestAtMillis: Long = 0L
    }

    private class QuotaRequestGate {
        val mutex = Mutex()
        var cachedQuota: TraceMoeQuotaSnapshot? = null
        var cachedAtMillis: Long = 0L
    }

    companion object {
        const val SEARCH_ENDPOINT = "https://api.trace.moe/search?anilistInfo&cutBorders=1"
        const val DEFAULT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_REQUEST_INTERVAL_MILLIS = 1_200L
        const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_QUOTA_RESPONSE_BYTES = 16 * 1024
        private const val USER_AGENT = "ReiAnix-Android/1.0 (local anime identification)"
        private const val MAX_RETRIES = 2
        private const val QUOTA_CACHE_TTL_MILLIS = 15_000L

        private val REQUEST_GATES = java.util.concurrent.ConcurrentHashMap<String, EndpointRequestGate>()
        private val QUOTA_GATES = java.util.concurrent.ConcurrentHashMap<String, QuotaRequestGate>()


    }
}

data class TraceMoeSearchMatch(
    val anilistId: Int,
    val similarity: Double,
)

/** A local episode image is always tied to its canonical episode row. */
data class TraceMoeEpisodeSample(
    val episodeId: Long,
    val imageFile: File,
)

data class TraceMoeIdentityConsensus(
    val anilistId: Int,
    val averageSimilarity: Double,
    val margin: Double,
    val supportingEpisodes: Int,
)

/**
 * Conservative majority vote: two distinct episode files must independently return
 * the same AniList ID with high similarity. A single screenshot can never auto-link
 * a local anime, even when Trace.moe reports a strong match.
 */
internal fun evaluateTraceMoeIdentityConsensus(
    resultsByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
    minimumSimilarity: Double = MINIMUM_TRACE_MOE_SIMILARITY,
    minimumFrameMargin: Double = MINIMUM_TRACE_MOE_FRAME_MARGIN,
): TraceMoeIdentityConsensus? {
    require(minimumSimilarity in 0.0..1.0)
    require(minimumFrameMargin in 0.0..1.0)
    val confidentWinners = resultsByEpisode.entries
        .asSequence()
        .filter { it.key > 0L }
        .mapNotNull { (_, matches) ->
            val rankedAnime = matches.asSequence()
                .filter { it.anilistId > 0 && it.similarity.isFinite() && it.similarity >= minimumSimilarity }
                .groupBy(TraceMoeSearchMatch::anilistId)
                .values
                .mapNotNull { sameAnimeMatches ->
                    sameAnimeMatches.maxByOrNull(TraceMoeSearchMatch::similarity)
                }
                .sortedByDescending(TraceMoeSearchMatch::similarity)
            val best = rankedAnime.firstOrNull() ?: return@mapNotNull null
            val secondSimilarity = rankedAnime.getOrNull(1)?.similarity ?: 0.0
            best.takeIf { best.similarity - secondSimilarity >= minimumFrameMargin }
        }
        .toList()
    if (confidentWinners.size < MINIMUM_TRACE_MOE_SUPPORTING_EPISODES) return null

    val counts = confidentWinners.groupBy(TraceMoeSearchMatch::anilistId)
    val winningGroup = counts.entries
        .sortedWith(
            compareByDescending<Map.Entry<Int, List<TraceMoeSearchMatch>>> { it.value.size }
                .thenByDescending { entry -> entry.value.map(TraceMoeSearchMatch::similarity).average() },
        )
        .firstOrNull()
        ?: return null
    val competitorCount = counts.entries
        .filter { it.key != winningGroup.key }
        .maxOfOrNull { it.value.size }
        ?: 0
    if (winningGroup.value.size < MINIMUM_TRACE_MOE_SUPPORTING_EPISODES ||
        winningGroup.value.size <= competitorCount
    ) return null

    val average = winningGroup.value.map(TraceMoeSearchMatch::similarity).average()
    val strongestCompetitor = confidentWinners
        .filter { it.anilistId != winningGroup.key }
        .maxOfOrNull(TraceMoeSearchMatch::similarity)
        ?: 0.0
    return TraceMoeIdentityConsensus(
        anilistId = winningGroup.key,
        averageSimilarity = average,
        margin = (average - strongestCompetitor).coerceAtLeast(0.0),
        supportingEpisodes = winningGroup.value.size,
    )
}


/**
 * Progressive identity policy used while episode thumbnails arrive.
 *
 * A clear single-frame result can be accepted immediately. When one frame is not
 * decisive, two different episode files agreeing on the same AniList ID are enough.
 * Only three distinct thumbnail samples are examined; if those remain inconclusive,
 * the strongest returned match is used as the final fallback.
 */
internal fun evaluateTraceMoeProgressiveIdentity(
    resultsByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
    singleFrameMinimumSimilarity: Double = MINIMUM_TRACE_MOE_SIMILARITY,
    supportingFrameMinimumSimilarity: Double = MINIMUM_TRACE_MOE_SUPPORTING_FRAME_SIMILARITY,
    minimumSingleFrameMargin: Double = MINIMUM_TRACE_MOE_FRAME_MARGIN,
): TraceMoeIdentityConsensus? {
    require(singleFrameMinimumSimilarity in 0.0..1.0)
    require(supportingFrameMinimumSimilarity in 0.0..1.0)
    require(minimumSingleFrameMargin in 0.0..1.0)

    val episodeWinners = mutableListOf<TraceMoeSearchMatch>()
    for ((episodeId, matches) in resultsByEpisode) {
        if (episodeId <= 0L) continue
        val rankedAnime = matches.asSequence()
            .filter { it.anilistId > 0 && it.similarity.isFinite() && it.similarity in 0.0..1.0 }
            .groupBy(TraceMoeSearchMatch::anilistId)
            .values
            .mapNotNull { sameAnimeMatches ->
                sameAnimeMatches.maxByOrNull(TraceMoeSearchMatch::similarity)
            }
            .sortedByDescending(TraceMoeSearchMatch::similarity)
        val best = rankedAnime.firstOrNull() ?: continue
        val secondSimilarity = rankedAnime.getOrNull(1)?.similarity ?: 0.0

        if (best.similarity >= singleFrameMinimumSimilarity &&
            best.similarity - secondSimilarity >= minimumSingleFrameMargin
        ) {
            return TraceMoeIdentityConsensus(
                anilistId = best.anilistId,
                averageSimilarity = best.similarity,
                margin = (best.similarity - secondSimilarity).coerceAtLeast(0.0),
                supportingEpisodes = 1,
            )
        }

        if (best.similarity >= supportingFrameMinimumSimilarity) {
            episodeWinners += best
        }
    }

    if (episodeWinners.size < 2) return null
    val counts = episodeWinners.groupBy(TraceMoeSearchMatch::anilistId)
    val winner = counts.entries
        .sortedWith(
            compareByDescending<Map.Entry<Int, List<TraceMoeSearchMatch>>> { it.value.size }
                .thenByDescending { entry -> entry.value.map(TraceMoeSearchMatch::similarity).average() },
        )
        .firstOrNull()
        ?: return null
    val strongestCompetitorCount = counts.entries
        .filter { it.key != winner.key }
        .maxOfOrNull { it.value.size }
        ?: 0
    if (winner.value.size < 2 || winner.value.size <= strongestCompetitorCount) return null

    val average = winner.value.map(TraceMoeSearchMatch::similarity).average()
    val strongestCompetitorSimilarity = episodeWinners
        .filter { it.anilistId != winner.key }
        .maxOfOrNull(TraceMoeSearchMatch::similarity)
        ?: 0.0
    return TraceMoeIdentityConsensus(
        anilistId = winner.key,
        averageSimilarity = average,
        margin = (average - strongestCompetitorSimilarity).coerceAtLeast(0.0),
        supportingEpisodes = winner.value.size,
    )
}

internal fun evaluateTraceMoeBestEffortIdentity(
    resultsByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
): TraceMoeIdentityConsensus? {
    val allMatches = resultsByEpisode.values.asSequence()
        .flatten()
        .filter { it.anilistId > 0 && it.similarity.isFinite() && it.similarity in 0.0..1.0 }
        .toList()
    val best = allMatches.maxWithOrNull(
        compareBy<TraceMoeSearchMatch> { it.similarity }
            .thenByDescending { it.anilistId },
    ) ?: return null
    val strongestDifferentAnime = allMatches.asSequence()
        .filter { it.anilistId != best.anilistId }
        .maxOfOrNull(TraceMoeSearchMatch::similarity)
        ?: 0.0
    val supportingEpisodes = resultsByEpisode.count { (_, matches) ->
        matches.any { it.anilistId == best.anilistId }
    }
    return TraceMoeIdentityConsensus(
        anilistId = best.anilistId,
        averageSimilarity = best.similarity,
        margin = (best.similarity - strongestDifferentAnime).coerceAtLeast(0.0),
        supportingEpisodes = supportingEpisodes,
    )
}

internal fun firstTraceMoeEpisodeMatches(
    matchesByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
): LinkedHashMap<Long, List<TraceMoeSearchMatch>> = LinkedHashMap<Long, List<TraceMoeSearchMatch>>().apply {
    matchesByEpisode.entries.take(MAX_TRACE_MOE_EPISODE_SAMPLES).forEach { (episodeId, matches) ->
        put(episodeId, matches)
    }
}

internal const val MAX_TRACE_MOE_EPISODE_SAMPLES = 3
internal const val MINIMUM_TRACE_MOE_SUPPORTING_FRAME_SIMILARITY = 0.86

const val MINIMUM_TRACE_MOE_SIMILARITY = 0.90
const val MINIMUM_TRACE_MOE_FRAME_MARGIN = 0.02
const val MINIMUM_TRACE_MOE_SUPPORTING_EPISODES = 2
