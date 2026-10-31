package com.reiflix.reiflix_local.data.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AniListGraphqlRequestControllerTest {
    @Test
    fun missingHeadersUseTheConservativeThirtyRequestsPerMinuteBaseline() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller()

        controller.execute(ReiAnixAniListClient.ENDPOINT) { AniListHttpResponse(200, "{}") }
        controller.execute(ReiAnixAniListClient.ENDPOINT) { AniListHttpResponse(200, "{}") }

        assertEquals(listOf(2_200L), clock.waits)
    }

    @Test
    fun higherNinetyPerMinuteLimitRequiresThreeConsistentObservations() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller()

        repeat(4) { index ->
            controller.execute(ReiAnixAniListClient.ENDPOINT) {
                AniListHttpResponse(
                    200,
                    "{}",
                    headers = mapOf(
                        "X-RateLimit-Limit" to "90",
                        "X-RateLimit-Remaining" to (80 - index).toString(),
                    ),
                )
            }
        }

        assertEquals(listOf(2_200L, 2_200L, 734L), clock.waits)
    }

    @Test
    fun lowerThirtyPerMinuteLimitIsAppliedImmediately() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller()

        repeat(3) {
            controller.execute(ReiAnixAniListClient.ENDPOINT) {
                AniListHttpResponse(
                    200,
                    "{}",
                    headers = mapOf(
                        "X-RateLimit-Limit" to "90",
                        "X-RateLimit-Remaining" to "80",
                    ),
                )
            }
        }
        controller.execute(ReiAnixAniListClient.ENDPOINT) {
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Limit" to "30",
                    "X-RateLimit-Remaining" to "20",
                ),
            )
        }
        controller.execute(ReiAnixAniListClient.ENDPOINT) { AniListHttpResponse(200, "{}") }

        assertEquals(2_200L, clock.waits.last())
    }

    @Test
    fun exhaustedQuotaWaitsUntilTheServerResetTimestamp() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller()
        val resetAtSeconds = (clock.now + 10_000L) / 1_000L

        controller.execute(ReiAnixAniListClient.ENDPOINT) {
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Limit" to "30",
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to resetAtSeconds.toString(),
                ),
            )
        }
        controller.execute(ReiAnixAniListClient.ENDPOINT) { AniListHttpResponse(200, "{}") }

        assertEquals(listOf(10_000L), clock.waits)
    }

    @Test
    fun retryAfterAndResetChooseTheLaterDeadlineAndRetryAutomatically() = runBlocking {
        val clock = FakeClock()
        val resetAtSeconds = (clock.now + 8_000L) / 1_000L
        val responses = listOf(
            AniListHttpResponse(
                429,
                "{}",
                headers = mapOf(
                    "Retry-After" to "5",
                    "X-RateLimit-Limit" to "30",
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to resetAtSeconds.toString(),
                ),
            ),
            AniListHttpResponse(200, "{}"),
        )
        var calls = 0
        val controller = clock.controller(intervalOverrideMs = 0L)
        val result = controller.execute(ReiAnixAniListClient.ENDPOINT) {
            responses[calls++]
        }

        assertEquals(200, result.statusCode)
        assertEquals(2, calls)
        assertEquals(listOf(8_000L), clock.waits)
    }

    @Test
    fun repeatedRateLimitResponsesStopAfterBoundedAutomaticRetries() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val controller = clock.controller(intervalOverrideMs = 0L)

        val result = controller.execute(ReiAnixAniListClient.ENDPOINT) {
            calls++
            AniListHttpResponse(429, "{}")
        }

        assertEquals(429, result.statusCode)
        assertEquals(3, calls)
        assertEquals(listOf(60_000L, 120_000L), clock.waits)
    }

    @Test
    fun cooldownWaitDoesNotHoldTheSharedRequestMutex() = runBlocking {
        val clock = FakeClock()
        val firstWaitStarted = CompletableDeferred<Unit>()
        val competingWaitStarted = CompletableDeferred<Unit>()
        val releaseFirstWait = CompletableDeferred<Unit>()
        var delayCalls = 0
        var firstRequestCalls = 0
        val controller = AniListGraphqlRequestController(
            nowMillis = { clock.now },
            delayMillis = { wait ->
                when (delayCalls++) {
                    0 -> {
                        firstWaitStarted.complete(Unit)
                        releaseFirstWait.await()
                        clock.now += wait
                    }
                    1 -> {
                        competingWaitStarted.complete(Unit)
                        throw CancellationException("cancel competing waiter")
                    }
                    else -> clock.now += wait
                }
            },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        val limitedRequest = async {
            controller.execute(ReiAnixAniListClient.ENDPOINT) {
                firstRequestCalls++
                if (firstRequestCalls == 1) AniListHttpResponse(429, "{}")
                else AniListHttpResponse(200, "{}")
            }
        }
        firstWaitStarted.await()

        val competingRequest = async {
            runCatching {
                controller.execute(ReiAnixAniListClient.ENDPOINT) {
                    error("A competing request must respect the shared cooldown.")
                }
            }
        }
        withTimeout(1_000L) { competingWaitStarted.await() }
        assertTrue(competingRequest.await().exceptionOrNull() is CancellationException)

        releaseFirstWait.complete(Unit)
        assertEquals(200, limitedRequest.await().statusCode)
        assertEquals(2, firstRequestCalls)
    }

    @Test
    fun exhaustedQuotaIsHonoredWhenTheLimitHeaderIsMissing() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller(intervalOverrideMs = 0L)
        val resetAtSeconds = (clock.now + 10_000L) / 1_000L

        controller.execute(ReiAnixAniListClient.ENDPOINT) {
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to resetAtSeconds.toString(),
                ),
            )
        }
        controller.execute(ReiAnixAniListClient.ENDPOINT) { AniListHttpResponse(200, "{}") }

        assertEquals(listOf(10_000L), clock.waits)
    }

    @Test
    fun retryAfterAcceptsTheHttpDateForm() = runBlocking {
        val clock = FakeClock()
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date(clock.now + 7_000L))
        var calls = 0
        val controller = clock.controller(intervalOverrideMs = 0L)

        val result = controller.execute(ReiAnixAniListClient.ENDPOINT) {
            calls++
            if (calls == 1) {
                AniListHttpResponse(429, "{}", headers = mapOf("Retry-After" to date))
            } else {
                AniListHttpResponse(200, "{}")
            }
        }

        assertEquals(200, result.statusCode)
        assertEquals(2, calls)
        assertEquals(listOf(7_000L), clock.waits)
    }

    @Test
    fun invalidOrIncompleteHeadersFallBackToConservativeRate() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller()

        controller.execute(ReiAnixAniListClient.ENDPOINT) {
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Limit" to "not-a-number",
                    "X-RateLimit-Remaining" to "-1",
                    "X-RateLimit-Reset" to "invalid",
                ),
            )
        }
        controller.execute(ReiAnixAniListClient.ENDPOINT) {
            AniListHttpResponse(200, "{}", headers = mapOf("X-RateLimit-Limit" to "90"))
        }

        assertEquals(listOf(2_200L), clock.waits)
    }

    @Test
    fun concurrentGraphQlRequestsAreSerializedByTheSharedController() = runBlocking {
        val clock = FakeClock()
        val controller = clock.controller(intervalOverrideMs = 0L)
        var activeRequests = 0
        var maximumConcurrentRequests = 0

        coroutineScope {
            List(8) {
                async {
                    controller.execute(ReiAnixAniListClient.ENDPOINT) {
                        activeRequests++
                        maximumConcurrentRequests = maxOf(maximumConcurrentRequests, activeRequests)
                        yield()
                        activeRequests--
                        AniListHttpResponse(200, "{}")
                    }
                }
            }.forEach { it.await() }
        }

        assertEquals(1, maximumConcurrentRequests)
    }

    @Test
    fun cancellationDuringGlobalWaitDoesNotSendAnotherRequest() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val controller = AniListGraphqlRequestController(
            nowMillis = { clock.now },
            delayMillis = { throw CancellationException("cancelled during global wait") },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        controller.execute(ReiAnixAniListClient.ENDPOINT) {
            calls++
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Limit" to "30",
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to ((clock.now + 20_000L) / 1_000L).toString(),
                ),
            )
        }
        try {
            controller.execute(ReiAnixAniListClient.ENDPOINT) {
                calls++
                AniListHttpResponse(200, "{}")
            }
        } catch (_: CancellationException) {
            // Expected: cancellation propagates while the global cooldown is active.
        }

        assertEquals(1, calls)
    }

    @Test
    fun transientFailuresAreNotClassifiedAsDefinitiveNegativeLookups() {
        assertTrue(AniListError.RateLimited(null).isTransientForAniListRetry())
        assertTrue(AniListError.Network("offline").isTransientForAniListRetry())
        assertTrue(AniListError.Http(429).isTransientForAniListRetry())
        assertTrue(AniListError.Http(503).isTransientForAniListRetry())
        assertTrue(AniListError.InvalidResponse("invalid").isTransientForAniListRetry())
        assertFalse(AniListError.InvalidRequest("invalid id").isTransientForAniListRetry())
    }

    private class FakeClock(
        var now: Long = 1_700_000_000_000L,
    ) {
        val waits = mutableListOf<Long>()

        suspend fun wait(milliseconds: Long) {
            waits += milliseconds
            now += milliseconds
        }

        fun controller(intervalOverrideMs: Long? = null) = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = ::wait,
            minimumIntervalOverrideMs = intervalOverrideMs,
            logger = {},
        )
    }
}
