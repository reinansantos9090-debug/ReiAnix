package com.reiflix.reiflix_local.data.anilist

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Process-wide serial coordinator for the AniList GraphQL endpoint.
 *
 * AniList publishes a burst limiter without documenting its capacity/window.
 * Requests are serialized and spaced with a safety margin. A lower header limit
 * applies immediately; a higher limit requires three consistent observations.
 *
 * A 429 is retried inside this shared critical section so other clients cannot
 * race the cooldown. delay() keeps the wait suspending and coroutine-cancellable.
 */
class AniListGraphqlRequestController(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalOverrideMs: Long? = null,
    private val logger: (String) -> Unit = { Log.i(TAG, it) },
) {
    init {
        require(minimumIntervalOverrideMs == null || minimumIntervalOverrideMs >= 0L) {
            "Request interval override cannot be negative."
        }
    }

    private val mutex = Mutex()
    private var lastRequestFinishedAtMs: Long? = null
    private var blockedUntilMs = 0L
    private var activeLimitPerMinute = CONSERVATIVE_LIMIT_PER_MINUTE
    private var pendingHigherLimit: Int? = null
    private var pendingHigherLimitObservations = 0
    private var remainingRequests: Long? = null
    private var resetAtMs: Long? = null
    private var consecutiveRateLimitResponses = 0

    suspend fun execute(
        endpoint: String,
        request: suspend () -> AniListHttpResponse,
    ): AniListHttpResponse = mutex.withLock {
        while (true) {
            val now = nowMillis()
            if (resetAtMs != null && resetAtMs!! <= now) {
                resetAtMs = null
                remainingRequests = null
            }
            if (remainingRequests == 0L) {
                val knownDeadline = resetAtMs?.takeIf { it > now }
                    ?: blockedUntilMs.takeIf { it > now }
                if (knownDeadline != null) {
                    blockedUntilMs = maxOf(blockedUntilMs, knownDeadline)
                } else {
                    blockedUntilMs = maxOf(blockedUntilMs, safeAdd(now, UNKNOWN_REMAINING_WAIT_MS))
                }
            }

            val intervalMs = requiredIntervalMs(now)
            val nextPacedAt = lastRequestFinishedAtMs?.let { safeAdd(it, intervalMs) }
            val waitUntil = maxOf(blockedUntilMs, nextPacedAt ?: Long.MIN_VALUE)
            val waitMs = (waitUntil - now).coerceAtLeast(0L)
            if (waitMs > 0L) {
                logger(
                    "AniList GraphQL waiting " + waitMs + "ms endpoint=" + endpoint +
                        " limit=" + activeLimitPerMinute + "/min remaining=" + (remainingRequests ?: "unknown"),
                )
                delayMillis(waitMs)
            }

            val readyAt = nowMillis()
            if (resetAtMs?.let { it <= readyAt } == true ||
                (remainingRequests == 0L && resetAtMs == null && readyAt >= blockedUntilMs)
            ) {
                remainingRequests = null
                resetAtMs = null
            }

            val requestIntervalMs = requiredIntervalMs(readyAt)
            logger(
                "AniList GraphQL request start endpoint=" + endpoint +
                    " limit=" + activeLimitPerMinute + "/min remaining=" +
                    (remainingRequests ?: "unknown") + " interval=" + requestIntervalMs + "ms",
            )
            val response = try {
                request()
            } finally {
                lastRequestFinishedAtMs = nowMillis()
            }

            val responseAt = nowMillis()
            val resetDeadline = updateQuotaFromHeaders(response, responseAt)
            if (response.statusCode != HTTP_TOO_MANY_REQUESTS) {
                consecutiveRateLimitResponses = 0
                logger(
                    "AniList GraphQL response endpoint=" + endpoint +
                        " status=" + response.statusCode +
                        " limit=" + activeLimitPerMinute + "/min remaining=" +
                        (remainingRequests ?: "unknown"),
                )
                return@withLock response
            }

            consecutiveRateLimitResponses += 1
            remainingRequests = 0L
            val retryAfterDeadline = retryAfterDeadline(response, responseAt)
            val knownDeadlines = listOfNotNull(retryAfterDeadline, resetDeadline)
                .filter { it > responseAt }
            val headerDeadline = knownDeadlines.maxOrNull()
            val fallbackDelay = fallbackDelayMs(consecutiveRateLimitResponses)
            val resumeAt = headerDeadline ?: safeAdd(responseAt, fallbackDelay)
            blockedUntilMs = maxOf(blockedUntilMs, resumeAt)
            logger(
                "AniList GraphQL HTTP 429 endpoint=" + endpoint +
                    " Retry-After deadline=" + (retryAfterDeadline ?: "unavailable") +
                    " reset=" + (resetDeadline ?: "unavailable") +
                    " resumeAt=" + blockedUntilMs + " fallback=" + fallbackDelay + "ms; retrying automatically",
            )
        }
        error("Unreachable AniList request controller state.")
    }

    private fun updateQuotaFromHeaders(response: AniListHttpResponse, now: Long): Long? {
        val headers = response.headers
        val limit = headers.header("X-RateLimit-Limit")
            ?.toLongOrNull()
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.toInt()
        val remaining = headers.header("X-RateLimit-Remaining")
            ?.toLongOrNull()
            ?.takeIf { it >= 0L && (limit == null || it <= limit.toLong()) }
        val resetDeadline = headers.header("X-RateLimit-Reset")
            ?.toLongOrNull()
            ?.takeIf { it > 0L && it <= Long.MAX_VALUE / 1_000L }
            ?.times(1_000L)
            ?.takeIf { it > now }

        if (limit != null && remaining != null) {
            when {
                limit < activeLimitPerMinute -> {
                    activeLimitPerMinute = limit
                    pendingHigherLimit = null
                    pendingHigherLimitObservations = 0
                }
                limit > activeLimitPerMinute && response.statusCode != HTTP_TOO_MANY_REQUESTS -> {
                    if (pendingHigherLimit == limit) {
                        pendingHigherLimitObservations += 1
                    } else {
                        pendingHigherLimit = limit
                        pendingHigherLimitObservations = 1
                    }
                    if (pendingHigherLimitObservations >= REQUIRED_STABLE_LIMIT_OBSERVATIONS) {
                        activeLimitPerMinute = limit
                        pendingHigherLimit = null
                        pendingHigherLimitObservations = 0
                    }
                }
                limit == activeLimitPerMinute -> {
                    pendingHigherLimit = null
                    pendingHigherLimitObservations = 0
                }
            }
            remainingRequests = remaining
            resetAtMs = resetDeadline
        } else {
            // Incomplete or malformed quota data is never permission to speed up.
            activeLimitPerMinute = CONSERVATIVE_LIMIT_PER_MINUTE
            pendingHigherLimit = null
            pendingHigherLimitObservations = 0
            remainingRequests = null
            resetAtMs = resetDeadline
        }

        if (response.statusCode != HTTP_TOO_MANY_REQUESTS && remainingRequests == 0L) {
            logger("AniList GraphQL quota exhausted; reset=" + (resetAtMs ?: "unknown"))
        }
        return resetDeadline
    }

    private fun requiredIntervalMs(now: Long): Long {
        minimumIntervalOverrideMs?.let { return it }
        val steadyInterval = ceil(
            MILLIS_PER_MINUTE.toDouble() / activeLimitPerMinute.toDouble() * SAFETY_FACTOR,
        ).toLong()

        val remaining = remainingRequests ?: return steadyInterval
        val reset = resetAtMs ?: return steadyInterval
        if (remaining <= 0L || reset <= now) return steadyInterval

        val remainingWindowMs = ceil(
            (reset - now).toDouble() / remaining.toDouble() * SAFETY_FACTOR,
        ).toLong()
        return maxOf(steadyInterval, remainingWindowMs)
    }

    private fun retryAfterDeadline(response: AniListHttpResponse, now: Long): Long? {
        val rawHeader = response.headers.header("Retry-After")
        val parsedHeader = rawHeader?.let { parseRetryAfterValue(it, now) }
        val compatibilityValue = response.retryAfterSeconds
            ?.takeIf { it >= 0L && it <= Long.MAX_VALUE / 1_000L }
            ?.let { safeAdd(now, it * 1_000L) }
        return listOfNotNull(parsedHeader, compatibilityValue).maxOrNull()
    }

    private fun parseRetryAfterValue(value: String, now: Long): Long? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null
        val seconds = normalized.toLongOrNull()
        if (seconds != null) {
            if (seconds < 0L || seconds > Long.MAX_VALUE / 1_000L) return null
            return safeAdd(now, seconds * 1_000L)
        }
        return parseHttpDate(normalized)?.takeIf { it > now }
    }

    private fun parseHttpDate(value: String): Long? {
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (pattern in patterns) {
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("GMT")
            }
            formatter.set2DigitYearStart(Date(nowMillis() - TWO_DIGIT_YEAR_WINDOW_MS))
            val normalizedDate = value.trim().replace(Regex(" {2,}"), " ")
            val position = ParsePosition(0)
            val parsed = formatter.parse(normalizedDate, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return null
    }

    private fun fallbackDelayMs(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 2)
        return minOf(MAX_FALLBACK_BACKOFF_MS, UNKNOWN_REMAINING_WAIT_MS * multiplier)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun Map<String, String>.header(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()

    companion object {
        private const val TAG = "AniListRateLimit"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val CONSERVATIVE_LIMIT_PER_MINUTE = 30
        private const val REQUIRED_STABLE_LIMIT_OBSERVATIONS = 3
        private const val SAFETY_FACTOR = 1.1
        private const val UNKNOWN_REMAINING_WAIT_MS = 60_000L
        private const val MAX_FALLBACK_BACKOFF_MS = 300_000L
        private const val TWO_DIGIT_YEAR_WINDOW_MS = 50L * 365L * 24L * 60L * 60L * 1_000L

        val processShared: AniListGraphqlRequestController by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AniListGraphqlRequestController()
        }
    }
}
