package com.reiflix.reiflix_local.data.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixExternalRequestCoordinatorTest {
    @Test
    fun concurrentCallersForTheSameSourceAndKeyShareOneOperation() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = async {
            coordinator.execute(ReiAnixCatalogSource.KITSU, "anime:42") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "shared-result"
            }
        }
        entered.await()
        val second = async {
            coordinator.execute(ReiAnixCatalogSource.KITSU, "anime:42") {
                calls.incrementAndGet()
                "duplicate-result"
            }
        }
        yield()
        release.complete(Unit)

        assertEquals("shared-result", first.await())
        assertEquals("shared-result", second.await())
        assertEquals(1, calls.get())
        coordinator.cancelAll()
    }

    @Test
    fun cancellingOneAwaiterDoesNotCancelTheSharedRequest() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = async {
            coordinator.execute(ReiAnixCatalogSource.TENRAI, "anime:88") {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                "complete"
            }
        }
        entered.await()
        val second = async {
            coordinator.execute(ReiAnixCatalogSource.TENRAI, "anime:88") {
                calls.incrementAndGet()
                "duplicate"
            }
        }
        yield()
        first.cancel()
        assertTrue(first.isCancelled)
        release.complete(Unit)
        assertEquals("complete", second.await())
        assertEquals(1, calls.get())
        coordinator.cancelAll()
    }

    @Test
    fun providerRegistryAppliesSharedDeduplicationAndNormalizedSearchKeys() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val provider = object : ReiAnixAnimeCatalogProvider {
            override val source = ReiAnixCatalogSource.KITSU
            override suspend fun searchAnime(query: String, limit: Int): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                return ReiAnixCatalogResult.Success(emptyList())
            }
            override suspend fun getAnime(externalId: String) =
                ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
        }
        val registry = ReiAnixCatalogProviderRegistry(listOf(provider), coordinator)
        val registered = registry.provider(ReiAnixCatalogSource.KITSU)!!
        val first = async { registered.searchAnime("  Example Anime ", 10) }
        entered.await()
        val second = async { registered.searchAnime("example   anime", 10) }
        yield()
        release.complete(Unit)
        assertTrue(first.await() is ReiAnixCatalogResult.Success)
        assertTrue(second.await() is ReiAnixCatalogResult.Success)
        assertEquals(1, calls.get())
        registry.cancelPendingRequests()
    }

    @Test
    fun globalConcurrencyLimitAppliesAcrossDifferentCatalogKeys() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 2)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val started = AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val requests = (0 until 5).map { index ->
            async {
                coordinator.execute(ReiAnixCatalogSource.TENRAI, "anime:$index") {
                    val nowActive = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, nowActive) }
                    if (started.incrementAndGet() == 2) twoStarted.complete(Unit)
                    release.await()
                    active.decrementAndGet()
                    index
                }
            }
        }

        withTimeout(2_000L) { twoStarted.await() }
        assertEquals(2, maximum.get())
        release.complete(Unit)
        assertEquals((0 until 5).toSet(), requests.map { it.await() }.toSet())
        assertEquals(2, maximum.get())
        coordinator.cancelAll()
    }

    @Test
    fun successfulSearchResultsAreCachedByTheSharedProviderRegistry() = runBlocking {
        val calls = AtomicInteger()
        val provider = object : ReiAnixAnimeCatalogProvider {
            override val source = ReiAnixCatalogSource.TENRAI
            override suspend fun searchAnime(query: String, limit: Int): ReiAnixCatalogResult<List<ReiAnixCatalogAnime>> {
                calls.incrementAndGet()
                return ReiAnixCatalogResult.Success(emptyList())
            }
            override suspend fun getAnime(externalId: String) =
                ReiAnixCatalogResult.Success<ReiAnixCatalogAnime?>(null)
        }
        val registry = ReiAnixCatalogProviderRegistry(
            listOf(provider),
            ReiAnixExternalRequestCoordinator(),
        )
        val registered = registry.provider(ReiAnixCatalogSource.TENRAI)!!
        registered.searchAnime("  Cached Title ", 10)
        registered.searchAnime("cached   title", 10)
        assertEquals(1, calls.get())
        registry.cancelPendingRequests()
    }

    @Test
    fun tenraiRequestsRespectConservativePublicRateLimit() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator(maxConcurrentRequests = 1)
        val starts = mutableListOf<Long>()
        coordinator.execute(ReiAnixCatalogSource.TENRAI, "first") {
            starts += System.currentTimeMillis()
        }
        coordinator.execute(ReiAnixCatalogSource.TENRAI, "second") {
            starts += System.currentTimeMillis()
        }
        assertEquals(2, starts.size)
        assertTrue("Tenrai requests must be spaced to stay below 3 requests/second.", starts[1] - starts[0] >= 300L)
        coordinator.cancelAll()
    }

    @Test
    fun requestTimeoutIsFinite() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator(requestTimeoutMillis = 250L)
        val entered = CompletableDeferred<Unit>()
        val caller = async {
            coordinator.execute(ReiAnixCatalogSource.ANILIST, "timeout") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val failure = runCatching { caller.await() }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        coordinator.cancelAll()
    }

    @Test
    fun explicitOwnerShutdownCancelsOutstandingWork() = runBlocking {
        val coordinator = ReiAnixExternalRequestCoordinator()
        val entered = CompletableDeferred<Unit>()
        val caller = async {
            coordinator.execute(ReiAnixCatalogSource.ANIPUB, "shutdown") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        coordinator.cancelAll()
        val failure = runCatching { caller.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
