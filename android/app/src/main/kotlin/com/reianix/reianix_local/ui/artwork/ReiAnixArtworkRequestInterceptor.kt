package com.reianix.reianix_local.ui.artwork

import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred

/**
 * Coalesces simultaneous HTTP requests for the SAME artwork cache key and size.
 * The first request always runs immediately. Waiting requests proceed normally
 * after it has populated Coil's disk/memory cache. This neither changes the
 * canonical artwork source nor cancels work during scrolling.
 */
internal class ArtworkInFlightRequests {
    private val pending = HashMap<String, CompletableDeferred<Boolean>>()

    suspend fun <T> run(
        key: String,
        successful: (T) -> Boolean,
        work: suspend () -> T,
    ): T {
        while (true) {
            var leader = false
            val completion = synchronized(pending) {
                pending.getOrPut(key) {
                    leader = true
                    CompletableDeferred()
                }
            }

            if (!leader) {
                // If the leader failed, elect a new leader instead of making all
                // the waiters retry the same remote URL at once.
                if (completion.await()) return work()
                continue
            }

            var succeeded = false
            try {
                return work().also { succeeded = successful(it) }
            } finally {
                // Also release followers on failure or coroutine cancellation;
                // never leave a stale key that could block future artwork.
                synchronized(pending) {
                    if (pending[key] === completion) pending.remove(key)
                }
                completion.complete(succeeded)
            }
        }
    }
}

/** Applied only to ReiAnix's remote artwork requests, not local thumbnails. */
class ReiAnixArtworkRequestInterceptor : Interceptor {
    private val inFlight = ArtworkInFlightRequests()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val diskKey = chain.request.diskCacheKey
        val source = chain.request.data.toString()
        if (
            diskKey.isNullOrBlank() ||
            !diskKey.startsWith("reianix-artwork-disk|") ||
            !(source.startsWith("https://", ignoreCase = true) ||
                source.startsWith("http://", ignoreCase = true))
        ) {
            return chain.proceed(chain.request)
        }
        return inFlight.run(
            key = diskKey + "|" + chain.size,
            successful = { it is SuccessResult },
        ) {
            chain.proceed(chain.request)
        }
    }
}
