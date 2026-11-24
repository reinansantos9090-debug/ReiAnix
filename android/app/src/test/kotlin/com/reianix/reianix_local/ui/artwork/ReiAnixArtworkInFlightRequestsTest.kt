package com.reianix.reianix_local.ui.artwork

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReiAnixArtworkInFlightRequestsTest {
    @Test
    fun duplicateRequestWaitsWhileFirstRunsImmediately() = runBlocking {
        val requests = ArtworkInFlightRequests()
        val started = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val runs = AtomicInteger()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run("poster|320x480", { it }) {
                runs.incrementAndGet()
                started.complete(Unit)
                releaseFirst.await()
                true
            }
        }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run("poster|320x480", { it }) {
                runs.incrementAndGet()
                true
            }
        }
        assertEquals(1, runs.get())
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        // The second caller resumes after the first caches the resource.
        assertEquals(2, runs.get())
    }

    @Test
    fun distinctSizesDoNotDelayFirstLoadOfAnotherCard() = runBlocking<Unit> {
        val requests = ArtworkInFlightRequests()
        val waiting = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            requests.run("poster|320x480", { it }) {
                waiting.await()
                true
            }
        }
        assertEquals(
            true,
            requests.run("poster|512x768", { it }) { true },
        )
        waiting.complete(Unit)
        first.await()
    }

    @Test
    fun failedRequestDoesNotPoisonFutureAttempts() = runBlocking {
        val requests = ArtworkInFlightRequests()
        var calls = 0
        assertEquals(false, requests.run("poster", { it }) { calls++; false })
        assertEquals(true, requests.run("poster", { it }) { calls++; true })
        assertEquals(2, calls)
    }
}
