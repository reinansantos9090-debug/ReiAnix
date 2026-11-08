package com.reiflix.reiflix_local.data.tracemoe

import android.util.Log
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
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

/**
 * Typed failures keep a failed HTTP/network call separate from a successful search
 * that returned zero matches. This distinction is required by the persisted sample
 * progress: transient failures must never become completed empty samples.
 */
enum class TraceMoeFailureKind(val retryable: Boolean) {
    BAD_IMAGE(false),
    CONCURRENCY_LIMIT(true),
    QUOTA_EXHAUSTED(false),
    PAYMENT_REQUIRED_UNCLASSIFIED(false),
    ACCOUNT_STATUS_UNAVAILABLE(false),
    LOCAL_DAILY_BUDGET_EXHAUSTED(false),
    RATE_LIMITED(true),
    NETWORK_UNAVAILABLE(true),
    TIMEOUT(true),
    SERVER_UNAVAILABLE(true),
    INVALID_RESPONSE(true),
    AUTHENTICATION(false),
    FORBIDDEN(false),
    HTTP_ERROR(false),
}

/** Stable HTTP classification, also used by the focused JVM policy tests. */
internal fun classifyTraceMoeHttpFailure(
    statusCode: Int,
    errorMessage: String?,
): TraceMoeFailureKind {
    val message = errorMessage.orEmpty().lowercase()
    return when {
        // HTTP 402 represents either exhausted quota or concurrency in trace.moe.
        // Only the response text distinguishes the documented cases reliably.
        "concurr" in message -> TraceMoeFailureKind.CONCURRENCY_LIMIT
        "quota" in message || "deplet" in message -> TraceMoeFailureKind.QUOTA_EXHAUSTED
        statusCode == 402 -> TraceMoeFailureKind.PAYMENT_REQUIRED_UNCLASSIFIED
        statusCode == 429 -> TraceMoeFailureKind.RATE_LIMITED
        statusCode in setOf(400, 413, 415) -> TraceMoeFailureKind.BAD_IMAGE
        statusCode == 401 -> TraceMoeFailureKind.AUTHENTICATION
        statusCode == 403 -> TraceMoeFailureKind.FORBIDDEN
        statusCode == 408 || statusCode == 425 -> TraceMoeFailureKind.SERVER_UNAVAILABLE
        statusCode in 500..599 -> TraceMoeFailureKind.SERVER_UNAVAILABLE
        "rate limit" in message || "too many requests" in message -> TraceMoeFailureKind.RATE_LIMITED
        "quota" in message -> TraceMoeFailureKind.QUOTA_EXHAUSTED
        else -> TraceMoeFailureKind.HTTP_ERROR
    }
}

class TraceMoeApiException(
    val kind: TraceMoeFailureKind,
    message: String,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
    val quota: Int? = null,
    val quotaUsed: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class TraceMoeAccountStatus(
    val id: String,
    val priority: Int,
    val concurrency: Int,
    val quota: Int,
    val quotaUsed: Int,
) {
    val quotaRemaining: Int
        get() = (quota - quotaUsed).coerceAtLeast(0)
}

/**
 * All Trace.moe clients in this process share one gate. The free API tier has
 * concurrency 1, so a per-instance Mutex is not sufficient when multiple service
 * instances happen to exist.
 */
private object TraceMoeRequestCoordinator {
    val mutex = Mutex()
    var lastRequestAtMillis: Long = 0L

    private data class CachedResult(
        val matches: List<TraceMoeSearchMatch>,
        val expiresAtMillis: Long,
    )

    private val resultCache = LinkedHashMap<String, CachedResult>(16, 0.75f, true)
    private var cachedAccountStatus: TraceMoeAccountStatus? = null
    private var accountStatusFetchedAtMillis: Long = 0L
    private var accountStatusWasFetched: Boolean = false

    fun cachedResult(key: String, nowMillis: Long): List<TraceMoeSearchMatch>? =
        synchronized(this) {
            val entry = resultCache[key] ?: return@synchronized null
            if (entry.expiresAtMillis <= nowMillis) {
                resultCache.remove(key)
                return@synchronized null
            }
            entry.matches
        }

    fun cacheResult(key: String, matches: List<TraceMoeSearchMatch>, nowMillis: Long) {
        synchronized(this) {
            resultCache[key] = CachedResult(
                matches = matches.toList(),
                expiresAtMillis = nowMillis + ReiAnixTraceMoeClient.RESULT_CACHE_TTL_MILLIS,
            )
            while (resultCache.size > ReiAnixTraceMoeClient.MAX_CACHE_ENTRIES) {
                val oldestKey = resultCache.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) iterator.next().key else null
                } ?: break
                resultCache.remove(oldestKey)
            }
        }
    }

    fun freshAccountStatus(nowMillis: Long): Pair<Boolean, TraceMoeAccountStatus?> =
        synchronized(this) {
            val ttl = if (cachedAccountStatus == null) {
                ReiAnixTraceMoeClient.ACCOUNT_ERROR_CACHE_TTL_MILLIS
            } else {
                ReiAnixTraceMoeClient.ACCOUNT_CACHE_TTL_MILLIS
            }
            val fresh = accountStatusWasFetched &&
                nowMillis - accountStatusFetchedAtMillis < ttl
            fresh to cachedAccountStatus
        }

    fun cacheAccountStatus(status: TraceMoeAccountStatus?, nowMillis: Long) {
        synchronized(this) {
            cachedAccountStatus = status
            accountStatusFetchedAtMillis = nowMillis
            accountStatusWasFetched = true
        }
    }

    fun invalidateAccountStatus() {
        synchronized(this) {
            accountStatusWasFetched = false
            cachedAccountStatus = null
            accountStatusFetchedAtMillis = 0L
        }
    }
}

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

    /**
     * Search is serialized globally, cached by the image bytes (not file path), and
     * retried only for transient failures. reserveRequest is called once per actual
     * /search attempt, including retries, but never for a cache hit or /me request.
     */
    suspend fun search(
        imageFile: File,
        reserveRequest: suspend () -> Boolean = { true },
    ): List<TraceMoeSearchMatch> {
        val imageBytes = withContext(Dispatchers.IO) {
            if (!imageFile.isFile || imageFile.length() !in 4L..MAX_IMAGE_BYTES) {
                throw TraceMoeApiException(
                    TraceMoeFailureKind.BAD_IMAGE,
                    "Trace.moe requires a readable episode thumbnail within the image-size limit.",
                )
            }
            val bytes = try {
                imageFile.readBytes()
            } catch (error: IOException) {
                throw TraceMoeApiException(
                    TraceMoeFailureKind.BAD_IMAGE,
                    "The generated episode thumbnail could not be read.",
                    cause = error,
                )
            }
            if (bytes.size.toLong() > MAX_IMAGE_BYTES || !isJpeg(bytes)) {
                throw TraceMoeApiException(
                    TraceMoeFailureKind.BAD_IMAGE,
                    "Trace.moe input must be a valid JPEG episode thumbnail.",
                )
            }
            bytes
        }
        val contentKey = sha256(imageBytes)
        val now = System.currentTimeMillis()
        TraceMoeRequestCoordinator.cachedResult(contentKey, now)?.let { return it }

        return TraceMoeRequestCoordinator.mutex.withLock {
            TraceMoeRequestCoordinator.cachedResult(contentKey, System.currentTimeMillis())?.let {
                return@withLock it
            }

            // /me is a read-only preflight. A zero balance cannot recover by polling:
            // trace.moe counts searches in a rolling 24-hour window. Fail immediately
            // and expose the authoritative usage values instead of waiting eight seconds.
            val account = refreshAccountStatusLocked(forceRefresh = false)
            if (account != null && account.quotaRemaining <= 0) {
                throw quotaExhausted(account)
            }

            var attempt = 0
            while (true) {
                waitForGlobalRequestSlot()
                if (!reserveRequest()) {
                    throw TraceMoeApiException(
                        TraceMoeFailureKind.LOCAL_DAILY_BUDGET_EXHAUSTED,
                        "A caller-local Trace.moe request reservation was rejected.",
                    )
                }
                TraceMoeRequestCoordinator.lastRequestAtMillis = System.currentTimeMillis()

                try {
                    val matches = request(imageBytes)
                    TraceMoeRequestCoordinator.cacheResult(
                        contentKey,
                        matches,
                        System.currentTimeMillis(),
                    )
                    return@withLock matches
                } catch (error: CancellationException) {
                    throw error
                } catch (error: TraceMoeApiException) {
                    when (error.kind) {
                        TraceMoeFailureKind.PAYMENT_REQUIRED_UNCLASSIFIED -> {
                            // A generic HTTP 402 is ambiguous in trace.moe: it can mean
                            // exhausted quota or a concurrency rejection. Ask /me once.
                            // If /me is unavailable, never misreport that as exhausted quota.
                            TraceMoeRequestCoordinator.invalidateAccountStatus()
                            val refreshed = refreshAccountStatusLocked(forceRefresh = true)
                                ?: throw TraceMoeApiException(
                                    TraceMoeFailureKind.ACCOUNT_STATUS_UNAVAILABLE,
                                    "Trace.moe returned HTTP 402, but its /me account status could not be read.",
                                    httpStatus = error.httpStatus,
                                    cause = error,
                                )
                            if (refreshed.quotaRemaining <= 0) {
                                throw quotaExhausted(refreshed, error.httpStatus, error)
                            }
                            // Positive quota plus an unclassified 402 is most likely a
                            // concurrency rejection. Retry once, then stop the session.
                            if (attempt >= MAX_RETRIES) {
                                throw TraceMoeApiException(
                                    TraceMoeFailureKind.CONCURRENCY_LIMIT,
                                    "Trace.moe rejected the search with HTTP 402 while quota remains; concurrency may be busy.",
                                    httpStatus = error.httpStatus,
                                    cause = error,
                                )
                            }
                            attempt++
                            delay(UNCLASSIFIED_402_RETRY_MILLIS)
                            continue
                        }
                        TraceMoeFailureKind.QUOTA_EXHAUSTED -> throw error
                        else -> Unit
                    }
                    if (!error.kind.retryable || attempt >= MAX_RETRIES) throw error

                    val delayMillis = error.retryAfterMillis
                        ?: defaultRetryDelayMillis(error.kind, attempt)
                    attempt++
                    delay(delayMillis.coerceIn(MIN_RETRY_DELAY_MILLIS, MAX_RETRY_DELAY_MILLIS))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            emptyList()
        }
    }

    private fun quotaExhausted(
        status: TraceMoeAccountStatus,
        httpStatus: Int? = null,
        cause: Throwable? = null,
    ) = TraceMoeApiException(
        kind = TraceMoeFailureKind.QUOTA_EXHAUSTED,
        message = "Trace.moe search quota exhausted (${status.quotaUsed}/${status.quota} searches used in the rolling 24-hour window).",
        httpStatus = httpStatus,
        quota = status.quota,
        quotaUsed = status.quotaUsed,
        cause = cause,
    )

    /** Refreshes /me at most once per five minutes; a temporary /me failure is non-fatal. */
    suspend fun getAccountStatus(forceRefresh: Boolean = false): TraceMoeAccountStatus? =
        TraceMoeRequestCoordinator.mutex.withLock {
            refreshAccountStatusLocked(forceRefresh)
        }

    private suspend fun refreshAccountStatusLocked(
        forceRefresh: Boolean,
    ): TraceMoeAccountStatus? {
        val now = System.currentTimeMillis()
        val (isFresh, cached) = TraceMoeRequestCoordinator.freshAccountStatus(now)
        if (!forceRefresh && isFresh) return cached

        // /me is account metadata, not a scene-search request; it shares the
        // global mutex but does not consume or reset the /search interval.
        val status = try {
            requestAccountStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Trace.moe /me lookup failed: ${error.javaClass.simpleName}")
            null
        }
        TraceMoeRequestCoordinator.cacheAccountStatus(status, System.currentTimeMillis())
        return status
    }

    private suspend fun waitForGlobalRequestSlot() {
        val effectiveInterval = maxOf(minimumRequestIntervalMillis, DEFAULT_REQUEST_INTERVAL_MILLIS)
        val waitMillis = (
            TraceMoeRequestCoordinator.lastRequestAtMillis +
                effectiveInterval -
                System.currentTimeMillis()
            ).coerceAtLeast(0L)
        if (waitMillis > 0L) delay(waitMillis)
    }

    private suspend fun requestAccountStatus(): TraceMoeAccountStatus? =
        withContext(Dispatchers.IO) {
            val connection = URL(ACCOUNT_ENDPOINT).openConnection() as? HttpURLConnection ?: return@withContext null
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = minOf(connectTimeoutMillis, ACCOUNT_TIMEOUT_MILLIS)
                connection.readTimeout = minOf(readTimeoutMillis, ACCOUNT_TIMEOUT_MILLIS)
                connection.setRequestProperty("Accept", "application/json")
                val startedAt = System.currentTimeMillis()
                val statusCode = connection.responseCode
                if (statusCode !in 200..299) {
                    Log.w(TAG, "Trace.moe /me HTTP $statusCode (${System.currentTimeMillis() - startedAt} ms)")
                    return@withContext null
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(body)
                val quota = root.optInt("quota", -1)
                val quotaUsed = root.optInt("quotaUsed", -1)
                val concurrency = root.optInt("concurrency", -1)
                if (quota < 0 || quotaUsed < 0 || concurrency < 1) return@withContext null
                TraceMoeAccountStatus(
                    id = root.optString("id", ""),
                    priority = root.optInt("priority", 0),
                    concurrency = concurrency,
                    quota = quota,
                    quotaUsed = quotaUsed,
                ).also {
                    Log.i(TAG, "Trace.moe /me OK (${System.currentTimeMillis() - startedAt} ms): quota=${it.quota} used=${it.quotaUsed} concurrency=${it.concurrency}")
                }
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun request(imageBytes: ByteArray): List<TraceMoeSearchMatch> =
        withContext(Dispatchers.IO) {
            val boundary = "ReiAnixTraceMoe" + UUID.randomUUID().toString().replace("-", "")
            val prefix = (
                "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"image\"; filename=\"episode-frame.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
            val suffix = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)
            val requestBytes = ByteArrayOutputStream(prefix.size + imageBytes.size + suffix.size).apply {
                write(prefix)
                write(imageBytes)
                write(suffix)
            }.toByteArray()

            val connection = URL(SEARCH_ENDPOINT).openConnection() as? HttpURLConnection
                ?: throw TraceMoeApiException(
                    TraceMoeFailureKind.NETWORK_UNAVAILABLE,
                    "Trace.moe did not provide an HTTP connection.",
                )
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(requestBytes.size)
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                coroutineContext[Job]?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) connection.disconnect()
                }
                val requestStartedAt = System.currentTimeMillis()
                Log.i(TAG, "Trace.moe /search upload starting: imageBytes=${imageBytes.size}")
                connection.outputStream.use { output -> output.write(requestBytes) }
                Log.i(TAG, "Trace.moe /search upload completed (${System.currentTimeMillis() - requestStartedAt} ms)")
                val statusCode = connection.responseCode
                Log.i(TAG, "Trace.moe /search HTTP $statusCode (${System.currentTimeMillis() - requestStartedAt} ms)")
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
                            throw TraceMoeApiException(
                                TraceMoeFailureKind.INVALID_RESPONSE,
                                "Trace.moe response exceeded the configured size limit.",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()

                if (statusCode !in 200..299) {
                    val errorMessage = parseErrorMessage(responseBody)
                    val retryAfterMillis = parseRetryAfterMillis(
                        headerValue = connection.getHeaderField("Retry-After"),
                        body = responseBody,
                    )
                    val kind = classifyTraceMoeHttpFailure(statusCode, errorMessage)
                    Log.w(TAG, "Trace.moe /search rejected: kind=$kind status=$statusCode message=${errorMessage.orEmpty().take(160)}")
                    throw TraceMoeApiException(
                        kind = kind,
                        message = errorMessage ?: ("Trace.moe returned HTTP " + statusCode + "."),
                        httpStatus = statusCode,
                        retryAfterMillis = retryAfterMillis,
                    )
                }

                val root = try {
                    JSONObject(responseBody)
                } catch (error: Exception) {
                    throw TraceMoeApiException(
                        TraceMoeFailureKind.INVALID_RESPONSE,
                        "Trace.moe returned malformed JSON.",
                        httpStatus = statusCode,
                        cause = error,
                    )
                }
                val inlineError = parseErrorMessage(root)
                if (inlineError != null) {
                    // Some deployments can return an API-level error in a JSON
                    // body even when the HTTP envelope itself is successful.
                    val kind = classifyTraceMoeHttpFailure(statusCode, inlineError)
                    throw TraceMoeApiException(
                        kind,
                        inlineError,
                        httpStatus = statusCode,
                    )
                }
                val results = root.optJSONArray("result")
                    ?: throw TraceMoeApiException(
                        TraceMoeFailureKind.INVALID_RESPONSE,
                        "Trace.moe response did not contain a result array.",
                        httpStatus = statusCode,
                    )
                parseMatches(results).also { matches ->
                    Log.i(TAG, "Trace.moe /search response parsed: candidates=${matches.size} elapsed=${System.currentTimeMillis() - requestStartedAt} ms")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: TraceMoeApiException) {
                throw error
            } catch (error: SocketTimeoutException) {
                throw TraceMoeApiException(
                    TraceMoeFailureKind.TIMEOUT,
                    "Trace.moe request timed out.",
                    cause = error,
                )
            } catch (error: IOException) {
                throw TraceMoeApiException(
                    TraceMoeFailureKind.NETWORK_UNAVAILABLE,
                    "Could not reach Trace.moe.",
                    cause = error,
                )
            } finally {
                connection.disconnect()
            }
        }

    private fun parseMatches(results: org.json.JSONArray): List<TraceMoeSearchMatch> =
        buildList {
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

    private fun parseErrorMessage(responseBody: String): String? =
        runCatching { parseErrorMessage(JSONObject(responseBody)) }.getOrNull()

    private fun parseErrorMessage(root: JSONObject): String? {
        val error = root.opt("error")
        val message = when (error) {
            is String -> error
            is JSONObject -> error.optString("message").takeIf(String::isNotBlank)
            else -> null
        }
        return message?.takeIf(String::isNotBlank)
            ?: root.optString("message").takeIf(String::isNotBlank)
    }

    private fun parseRetryAfterMillis(headerValue: String?, body: String): Long? {
        val headerSeconds = headerValue?.trim()?.toDoubleOrNull()
        val bodySeconds = runCatching {
            val root = JSONObject(body)
            val retryAfter = root.opt("retry_after_seconds")
                ?: root.opt("retry_after")
            when (retryAfter) {
                is Number -> retryAfter.toDouble()
                is String -> retryAfter.toDoubleOrNull()
                else -> null
            }
        }.getOrNull()
        val seconds = headerSeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?: bodySeconds?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        return (seconds * 1_000.0).toLong().coerceAtLeast(MIN_RETRY_DELAY_MILLIS)
    }

    private fun defaultRetryDelayMillis(
        kind: TraceMoeFailureKind,
        attempt: Int,
    ): Long = when (kind) {
        TraceMoeFailureKind.CONCURRENCY_LIMIT -> 1_500L
        TraceMoeFailureKind.RATE_LIMITED -> 2_500L
        else -> 1_000L
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() &&
            bytes[bytes.lastIndex] == 0xd9.toByte()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    companion object {
        private const val TAG = "ReiAnixTraceMoe"
        // AniList metadata is resolved by the app after identification; requesting it
        // in every Trace.moe response adds work that is not used by this client.
        const val SEARCH_ENDPOINT = "https://api.trace.moe/search?cutBorders=1"
        const val ACCOUNT_ENDPOINT = "https://api.trace.moe/me"
        const val DEFAULT_TIMEOUT_MILLIS = 8_000
        const val DEFAULT_REQUEST_INTERVAL_MILLIS = 1_200L
        const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
        const val RESULT_CACHE_TTL_MILLIS = 60L * 60L * 1_000L
        const val MAX_CACHE_ENTRIES = 200
        const val ACCOUNT_CACHE_TTL_MILLIS = 5L * 60L * 1_000L
        const val ACCOUNT_ERROR_CACHE_TTL_MILLIS = 30L * 1_000L
        const val UNCLASSIFIED_402_RETRY_MILLIS = 1_500L
        const val MAX_RETRIES = 1
        private const val ACCOUNT_TIMEOUT_MILLIS = 3_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_RETRY_DELAY_MILLIS = 800L
        private const val MAX_RETRY_DELAY_MILLIS = 5_000L
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

enum class TraceMoeIdentificationProgressPhase {
    PREPARING_THUMBNAIL,
    SENDING_TO_API,
    WAITING_FOR_API,
    WAITING_FOR_QUOTA,
    RESPONSE_RECEIVED,
    SKIPPING_FAILED_EPISODE,
    VERIFYING_IDENTITY,
    UPDATING_COVER,
}

data class TraceMoeIdentificationProgress(
    val phase: TraceMoeIdentificationProgressPhase,
    val episodeId: Long? = null,
    val thumbnailPath: String? = null,
    val validResponses: Int = 0,
    val message: String,
)

internal fun hasRemainingTraceMoeResponseSlots(
    successfulResponses: Int,
    maximumResponses: Int = MAX_TRACE_MOE_EPISODE_SAMPLES,
): Boolean {
    require(successfulResponses >= 0)
    require(maximumResponses > 0)
    return successfulResponses < maximumResponses
}

/**
 * Do not start a long successful-attempt cooldown after a session ended with an
 * unreplaced recoverable failure. That failure did not count as a valid response.
 */
internal fun shouldCompleteTraceMoeIdentification(
    successfulResponses: Int,
    hadUnreplacedFailure: Boolean,
    maximumResponses: Int = MAX_TRACE_MOE_EPISODE_SAMPLES,
): Boolean {
    require(successfulResponses >= 0)
    require(maximumResponses > 0)
    return successfulResponses >= maximumResponses ||
        (successfulResponses > 0 && !hadUnreplacedFailure)
}

/**
 * Request-specific/transient failures can be replaced by a later episode thumbnail.
 * Account-wide failures must stop the session rather than flooding the API with more
 * images that will fail for the same reason.
 */
internal fun shouldAdvanceToNextTraceMoeEpisodeAfterFailure(
    kind: TraceMoeFailureKind,
): Boolean = kind in setOf(
    TraceMoeFailureKind.BAD_IMAGE,
    TraceMoeFailureKind.RATE_LIMITED,
    TraceMoeFailureKind.NETWORK_UNAVAILABLE,
    TraceMoeFailureKind.TIMEOUT,
    TraceMoeFailureKind.SERVER_UNAVAILABLE,
    TraceMoeFailureKind.INVALID_RESPONSE,
    TraceMoeFailureKind.HTTP_ERROR,
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
 * Progressive identity policy evaluated over the latest three successful episode
 * responses. A clear single-frame result can be accepted immediately. Otherwise,
 * two different episode files must agree on the same AniList ID. The caller keeps
 * querying later thumbnails and advances this rolling window until it is decisive.
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

/**
 * Appends one successful Trace.moe response to the rolling evidence window.
 *
 * The app may inspect any number of local episode thumbnails, but only the most
 * recent three successful responses participate in a single identity decision.
 * Failed requests never call this helper and therefore never evict valid evidence.
 */
internal fun appendTraceMoeEpisodeMatches(
    matchesByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
    episodeId: Long,
    matches: List<TraceMoeSearchMatch>,
): LinkedHashMap<Long, List<TraceMoeSearchMatch>> {
    require(episodeId > 0L) { "Trace.moe evidence requires a positive episode ID." }
    return LinkedHashMap(matchesByEpisode).apply {
        remove(episodeId)
        put(episodeId, matches.toList())
        while (size > MAX_TRACE_MOE_EPISODE_SAMPLES) {
            val oldestEpisodeId = keys.firstOrNull() ?: break
            remove(oldestEpisodeId)
        }
    }
}

/** Restore the latest evidence window, not merely the first three episodes in a library. */
internal fun latestTraceMoeEpisodeMatches(
    matchesByEpisode: Map<Long, List<TraceMoeSearchMatch>>,
): LinkedHashMap<Long, List<TraceMoeSearchMatch>> =
    LinkedHashMap<Long, List<TraceMoeSearchMatch>>().apply {
        matchesByEpisode.entries
            .toList()
            .takeLast(MAX_TRACE_MOE_EPISODE_SAMPLES)
            .forEach { (episodeId, matches) -> put(episodeId, matches) }
    }

internal const val MAX_TRACE_MOE_EPISODE_SAMPLES = 3

const val MINIMUM_TRACE_MOE_SIMILARITY = 0.90
const val MINIMUM_TRACE_MOE_FRAME_MARGIN = 0.02
const val MINIMUM_TRACE_MOE_SUPPORTING_EPISODES = 2
