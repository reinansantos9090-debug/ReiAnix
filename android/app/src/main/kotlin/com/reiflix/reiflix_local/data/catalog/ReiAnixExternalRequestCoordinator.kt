package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Shared bounded request executor for external anime catalogs.
 *
 * Requests with the same source/key share one Deferred. The timeout includes time waiting for a
 * permit. Work executes on the supplied IO dispatcher, and cancelling one caller's await never
 * cancels the shared lookup. Only explicit owner shutdown cancels registered work.
 */
class ReiAnixExternalRequestCoordinator(
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class RequestKey(val source: ReiAnixCatalogSource, val operationKey: String)

    init {
        require(maxConcurrentRequests in 1..MAX_ALLOWED_CONCURRENT_REQUESTS) {
            "Concurrent request limit must be between 1 and $MAX_ALLOWED_CONCURRENT_REQUESTS."
        }
        require(requestTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "Request timeout must be finite and between $MIN_TIMEOUT_MILLIS and $MAX_TIMEOUT_MILLIS ms."
        }
    }

    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(ownerJob + dispatcher)
    private val permits = Semaphore(maxConcurrentRequests)
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<RequestKey, Deferred<Any?>>()
    private val jikanRateMutex = Mutex()
    private val jikanRequestTimes = ArrayDeque<Long>()
    private var lastJikanRequestAtMillis = Long.MIN_VALUE
    private val sourcePauseMutex = Mutex()
    private val sourceBlockedUntilMillis = mutableMapOf<ReiAnixCatalogSource, Long>()

    suspend fun <T> execute(
        source: ReiAnixCatalogSource,
        operationKey: String,
        operation: suspend () -> T,
    ): T {
        check(ownerJob.isActive) { "External catalog request coordinator is closed." }
        val normalizedKey = operationKey.trim()
        require(normalizedKey.isNotEmpty() && normalizedKey.length <= MAX_OPERATION_KEY_LENGTH) {
            "Operation key must contain between 1 and $MAX_OPERATION_KEY_LENGTH characters."
        }
        val key = RequestKey(source, normalizedKey)
        val deferred = mutex.withLock {
            check(ownerJob.isActive) { "External catalog request coordinator is closed." }
            inFlight[key] ?: run {
                lateinit var created: Deferred<Any?>
                created = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        withTimeout(requestTimeoutMillis) {
                            permits.withPermit {
                                awaitSourcePause(source)
                                if (source == ReiAnixCatalogSource.JIKAN) awaitJikanRateLimit()
                                val result = operation()
                                val rateLimit = (result as? ReiAnixCatalogResult.Failure)
                                    ?.error as? ReiAnixCatalogError.RateLimited
                                if (rateLimit != null) {
                                    deferSource(source, rateLimit.retryAfterMillis ?: DEFAULT_RETRY_AFTER_MILLIS)
                                }
                                result
                            }
                        }
                    } finally {
                        // Timeout/owner cancellation must not leave a completed Deferred in the
                        // dedupe map. NonCancellable protects this tiny in-memory cleanup only.
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (inFlight[key] === created) inFlight.remove(key)
                            }
                        }
                    }
                }
                inFlight[key] = created
                created
            }
        }
        deferred.start()
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }

    /** Apply Retry-After to later requests from the same source without retry loops. */
    private suspend fun deferSource(source: ReiAnixCatalogSource, retryAfterMillis: Long) {
        val bounded = retryAfterMillis.coerceIn(1L, MAX_RETRY_AFTER_MILLIS)
        val until = System.currentTimeMillis() + bounded
        sourcePauseMutex.withLock {
            sourceBlockedUntilMillis[source] = maxOf(sourceBlockedUntilMillis[source] ?: 0L, until)
        }
    }

    private suspend fun awaitSourcePause(source: ReiAnixCatalogSource) {
        while (true) {
            val waitMillis = sourcePauseMutex.withLock {
                val until = sourceBlockedUntilMillis[source] ?: return@withLock 0L
                val wait = (until - System.currentTimeMillis()).coerceAtLeast(0L)
                if (wait == 0L) sourceBlockedUntilMillis.remove(source)
                wait
            }
            if (waitMillis == 0L) return
            delay(waitMillis)
        }
    }

    /**
     * Jikan's public API allows at most 3 requests/second and 60/minute.
     * A conservative 334ms minimum spacing satisfies the per-second window;
     * the rolling one-minute window handles the stricter sustained limit.
     */
    private suspend fun awaitJikanRateLimit() {
        while (true) {
            val waitMillis = jikanRateMutex.withLock {
                val now = System.currentTimeMillis()
                while (jikanRequestTimes.isNotEmpty() && now - jikanRequestTimes.first() >= JIKAN_WINDOW_MILLIS) {
                    jikanRequestTimes.removeFirst()
                }
                val spacingWait = if (lastJikanRequestAtMillis == Long.MIN_VALUE) {
                    0L
                } else {
                    (JIKAN_MINIMUM_INTERVAL_MILLIS - (now - lastJikanRequestAtMillis)).coerceAtLeast(0L)
                }
                val minuteWait = if (jikanRequestTimes.size >= JIKAN_MAX_REQUESTS_PER_MINUTE) {
                    (jikanRequestTimes.first() + JIKAN_WINDOW_MILLIS - now).coerceAtLeast(1L)
                } else {
                    0L
                }
                val wait = maxOf(spacingWait, minuteWait)
                if (wait == 0L) {
                    jikanRequestTimes.addLast(now)
                    lastJikanRequestAtMillis = now
                }
                wait
            }
            if (waitMillis == 0L) return
            delay(waitMillis)
        }
    }

    suspend fun activeRequestCount(): Int = mutex.withLock { inFlight.size }

    /** Cancels pending work and permanently closes this coordinator. */
    fun cancelAll() {
        ownerJob.cancel()
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 4
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 20_000L
        const val MAX_ALLOWED_CONCURRENT_REQUESTS = 16
        const val MIN_TIMEOUT_MILLIS = 100L
        const val MAX_TIMEOUT_MILLIS = 120_000L
        const val MAX_OPERATION_KEY_LENGTH = 256
        const val JIKAN_MAX_REQUESTS_PER_SECOND = 3
        const val JIKAN_MAX_REQUESTS_PER_MINUTE = 60
        const val JIKAN_MINIMUM_INTERVAL_MILLIS = 334L
        const val JIKAN_WINDOW_MILLIS = 60_000L
        const val DEFAULT_RETRY_AFTER_MILLIS = 2_000L
        const val MAX_RETRY_AFTER_MILLIS = 6L * 60L * 60L * 1_000L
    }
}
