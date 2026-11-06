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
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

/**
 * Screenshot-only client for Trace.moe. The caller supplies a generated local episode
 * thumbnail; the source video is never uploaded.
 */
class ReiAnixTraceMoeClient(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val minimumRequestIntervalMillis: Long = DEFAULT_REQUEST_INTERVAL_MILLIS,
) {
    init {
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..60_000)
        require(minimumRequestIntervalMillis >= 0L)
    }

    private val requestMutex = Mutex()
    private var lastRequestAtMillis = 0L

    suspend fun search(imageFile: File): List<TraceMoeSearchMatch> = requestMutex.withLock {
        val now = System.currentTimeMillis()
        val waitMillis = (lastRequestAtMillis + minimumRequestIntervalMillis - now).coerceAtLeast(0L)
        if (waitMillis > 0L) delay(waitMillis)
        lastRequestAtMillis = System.currentTimeMillis()
        request(imageFile)
    }

    private suspend fun request(imageFile: File): List<TraceMoeSearchMatch> =
        withContext(Dispatchers.IO) {
            if (!imageFile.isFile || imageFile.length() !in 4L..MAX_IMAGE_BYTES) {
                throw IOException("Trace.moe requires a readable episode thumbnail within the image-size limit.")
            }
            val imageBytes = imageFile.readBytes()
            if (imageBytes.size.toLong() > MAX_IMAGE_BYTES || !isJpeg(imageBytes)) {
                throw IOException("Trace.moe input must be a valid JPEG episode thumbnail.")
            }

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

            val connection = URL(SEARCH_ENDPOINT).openConnection() as? HttpURLConnection
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
                val responseStream = if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseBody = responseStream?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_RESPONSE_BYTES) {
                            throw IOException("Trace.moe response exceeded the configured size limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
                if (statusCode == 429) {
                    throw IOException("Trace.moe rate limit reached; the local episode identification was postponed.")
                }
                if (statusCode !in 200..299) {
                    throw IOException("Trace.moe returned HTTP $statusCode.")
                }
                parseMatches(responseBody)
            } finally {
                connection.disconnect()
            }
        }

    private fun parseMatches(responseBody: String): List<TraceMoeSearchMatch> {
        val root = try {
            JSONObject(responseBody)
        } catch (error: Exception) {
            throw IOException("Trace.moe returned malformed JSON.", error)
        }
        val results = root.optJSONArray("result")
            ?: throw IOException("Trace.moe response did not contain a result array.")
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val rawId = item.opt("anilist")
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

    companion object {
        const val SEARCH_ENDPOINT = "https://api.trace.moe/search?anilistInfo&cutBorders=1"
        const val DEFAULT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_REQUEST_INTERVAL_MILLIS = 1_200L
        const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val USER_AGENT = "ReiAnix-Android/1.0 (local anime identification)"
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
    supportingFrameMinimumSimilarity: Double = 0.86,
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

const val MINIMUM_TRACE_MOE_SIMILARITY = 0.90
const val MINIMUM_TRACE_MOE_FRAME_MARGIN = 0.02
const val MINIMUM_TRACE_MOE_SUPPORTING_EPISODES = 2
