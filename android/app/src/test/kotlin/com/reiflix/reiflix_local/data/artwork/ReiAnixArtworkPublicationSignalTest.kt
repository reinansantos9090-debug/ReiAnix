package com.reiflix.reiflix_local.data.artwork

import com.reiflix.reiflix_local.data.anilist.shouldDiscoverMissingArtwork
import com.reiflix.reiflix_local.data.anilist.boundedAniListSearchTerms
import com.reiflix.reiflix_local.data.anilist.rotateArtworkDiscoveryIds
import com.reiflix.reiflix_local.data.anilist.MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
import com.reiflix.reiflix_local.data.anilist.artworkDiscoveryAttemptPreferenceKey
import com.reiflix.reiflix_local.data.anilist.shouldDeferArtworkDiscoveryAttempt
import com.reiflix.reiflix_local.data.anilist.aniListSearchTermsSignature
import com.reiflix.reiflix_local.data.anilist.shouldSkipStableAniListRetry
import com.reiflix.reiflix_local.data.anilist.PersistedAniListSearchSignature
import com.reiflix.reiflix_local.data.anilist.ANILIST_STABLE_MATCH_RETRY_TTL_SECONDS
import com.reiflix.reiflix_local.data.anilist.persistedAniListSearchSignaturePreferenceKey
import com.reiflix.reiflix_local.data.anilist.autoMatchFailureStatus
import com.reiflix.reiflix_local.data.anilist.AniListError
import com.reiflix.reiflix_local.data.anilist.AniListGraphqlRequestController
import com.reiflix.reiflix_local.data.anilist.AniListHttpResponse
import com.reiflix.reiflix_local.data.anilist.AniListGraphqlRequestControllerTest

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.JUnitCore

class ReiAnixArtworkPublicationSignalTest {
    @Test
    fun existingAniListControllerRegressionSuiteRunsInReleaseCertification() {
        // The release-certification allowlist already executes this class. Run the
        // controller's complete existing JUnit class here so its separate manual-only
        // workflow can remain workflow_dispatch without leaving those regressions out
        // of push certification.
        val result = JUnitCore.runClasses(AniListGraphqlRequestControllerTest::class.java)
        val failures = result.failures.joinToString("\n") { it.trace }

        assertEquals("The existing controller suite must execute all ten test cases.", 10, result.runCount)
        assertEquals("Controller regression failures:\n$failures", 0, result.failureCount)
        assertEquals("The controller suite must not skip tests.", 0, result.ignoreCount)
    }

    @Test
    fun repeatedRateLimitResponsesStopAfterBoundedAutomaticRetries() = runBlocking {
        var now = 1_700_000_000_000L
        val waits = mutableListOf<Long>()
        var calls = 0
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> waits += wait; now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        val result = controller.execute("https://graphql.anilist.co") {
            calls++
            AniListHttpResponse(429, "{}")
        }

        assertEquals(429, result.statusCode)
        assertEquals(3, calls)
        assertEquals(listOf(60_000L, 120_000L), waits)
    }

    @Test
    fun cooldownWaitDoesNotHoldTheSharedRequestMutex() = runBlocking {
        var now = 1_700_000_000_000L
        val firstWaitStarted = CompletableDeferred<Unit>()
        val competingWaitStarted = CompletableDeferred<Unit>()
        val releaseFirstWait = CompletableDeferred<Unit>()
        var delayCalls = 0
        var firstRequestCalls = 0
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait ->
                when (delayCalls++) {
                    0 -> {
                        firstWaitStarted.complete(Unit)
                        releaseFirstWait.await()
                        now += wait
                    }
                    1 -> {
                        competingWaitStarted.complete(Unit)
                        throw CancellationException("cancel competing waiter")
                    }
                    else -> now += wait
                }
            },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        val limitedRequest = async {
            controller.execute("https://graphql.anilist.co") {
                firstRequestCalls++
                if (firstRequestCalls == 1) AniListHttpResponse(429, "{}")
                else AniListHttpResponse(200, "{}")
            }
        }
        firstWaitStarted.await()

        val competingRequest = async {
            runCatching {
                controller.execute("https://graphql.anilist.co") {
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
    fun exhaustedQuotaWithoutResetRetainsTheFullSixtySecondCooldown() = runBlocking {
        var now = 1_700_000_000_000L
        val startedAt = now
        val waits = mutableListOf<Long>()
        val requestTimes = mutableListOf<Long>()
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> waits += wait; now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        controller.execute("https://graphql.anilist.co") {
            requestTimes += now
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf("X-RateLimit-Remaining" to "0"),
            )
        }
        controller.execute("https://graphql.anilist.co") {
            requestTimes += now
            AniListHttpResponse(200, "{}")
        }

        assertEquals(2, requestTimes.size)
        assertEquals(60_000L, requestTimes[1] - startedAt)
        assertEquals(listOf(60_000L), waits)
    }

    @Test
    fun exhaustedQuotaWithMalformedResetFallsBackToSixtySeconds() = runBlocking {
        var now = 1_700_000_000_000L
        val startedAt = now
        val waits = mutableListOf<Long>()
        val requestTimes = mutableListOf<Long>()
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> waits += wait; now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        controller.execute("https://graphql.anilist.co") {
            requestTimes += now
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to "not-a-timestamp",
                ),
            )
        }
        controller.execute("https://graphql.anilist.co") {
            requestTimes += now
            AniListHttpResponse(200, "{}")
        }

        assertEquals(2, requestTimes.size)
        assertEquals(60_000L, requestTimes[1] - startedAt)
        assertEquals(listOf(60_000L), waits)
    }

    @Test
    fun exhaustedQuotaWithExpiredResetFallsBackToSixtySeconds() = runBlocking {
        var now = 1_700_000_000_000L
        val startedAt = now
        val waits = mutableListOf<Long>()
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> waits += wait; now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        controller.execute("https://graphql.anilist.co") {
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to ((now - 1_000L) / 1_000L).toString(),
                ),
            )
        }
        controller.execute("https://graphql.anilist.co") { AniListHttpResponse(200, "{}") }

        assertEquals(60_000L, now - startedAt)
        assertEquals(listOf(60_000L), waits)
    }

    @Test
    fun exhaustedQuotaWithFutureResetWaitsUntilTheServerDeadline() = runBlocking {
        var now = 1_700_000_000_000L
        val startedAt = now
        val resetDelayMs = 180_000L
        val waits = mutableListOf<Long>()
        val requestTimes = mutableListOf<Long>()
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> waits += wait; now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )
        val resetAtSeconds = (now + resetDelayMs) / 1_000L

        controller.execute("https://graphql.anilist.co") {
            requestTimes += now
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to resetAtSeconds.toString(),
                ),
            )
        }
        controller.execute("https://graphql.anilist.co") {
            requestTimes += now
            AniListHttpResponse(200, "{}")
        }

        assertEquals(2, requestTimes.size)
        assertEquals(resetDelayMs, requestTimes[1] - startedAt)
        assertEquals(listOf(resetDelayMs), waits)
    }

    @Test
    fun retryAfterAndResetAlwaysUseTheLaterValidDeadline() = runBlocking {
        val scenarios = listOf(
            Triple(150L, 120L, 150_000L),
            Triple(90L, 180L, 180_000L),
        )
        scenarios.forEach { (retryAfterSeconds, resetDelaySeconds, expectedWaitMs) ->
            var now = 1_700_000_000_000L
            val waits = mutableListOf<Long>()
            var calls = 0
            val controller = AniListGraphqlRequestController(
                nowMillis = { now },
                delayMillis = { wait -> waits += wait; now += wait },
                minimumIntervalOverrideMs = 0L,
                logger = {},
            )
            val resetAtSeconds = (now / 1_000L) + resetDelaySeconds

            val result = controller.execute("https://graphql.anilist.co") {
                calls++
                if (calls == 1) {
                    AniListHttpResponse(
                        429,
                        "{}",
                        headers = mapOf(
                            "Retry-After" to retryAfterSeconds.toString(),
                            "X-RateLimit-Remaining" to "0",
                            "X-RateLimit-Reset" to resetAtSeconds.toString(),
                        ),
                    )
                } else {
                    AniListHttpResponse(200, "{}")
                }
            }

            assertEquals(200, result.statusCode)
            assertEquals(2, calls)
            assertEquals(listOf(expectedWaitMs), waits)
        }
    }

    @Test
    fun exhaustedQuotaCooldownIsSharedAndDoesNotHoldTheMutexDuringWait() = runBlocking {
        var now = 1_700_000_000_000L
        val firstWaitStarted = CompletableDeferred<Unit>()
        val competingWaitStarted = CompletableDeferred<Unit>()
        val releaseFirstWait = CompletableDeferred<Unit>()
        var delayCalls = 0
        var networkCalls = 0
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait ->
                when (delayCalls++) {
                    0 -> {
                        firstWaitStarted.complete(Unit)
                        releaseFirstWait.await()
                        now += wait
                    }
                    1 -> {
                        competingWaitStarted.complete(Unit)
                        throw CancellationException("cancel competing quota waiter")
                    }
                    else -> now += wait
                }
            },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )

        controller.execute("https://graphql.anilist.co") {
            networkCalls++
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf("X-RateLimit-Remaining" to "0"),
            )
        }

        val firstConsumer = async {
            controller.execute("https://graphql.anilist.co") {
                networkCalls++
                AniListHttpResponse(200, "{}")
            }
        }
        firstWaitStarted.await()

        val secondConsumer = async {
            runCatching {
                controller.execute("https://graphql.anilist.co") {
                    networkCalls++
                    error("A competing consumer must not bypass the shared cooldown.")
                }
            }
        }
        withTimeout(1_000L) { competingWaitStarted.await() }
        assertTrue(secondConsumer.await().exceptionOrNull() is CancellationException)
        assertEquals(1, networkCalls)

        releaseFirstWait.complete(Unit)
        assertEquals(200, firstConsumer.await().statusCode)
        assertEquals(2, networkCalls)
    }

    @Test
    fun exhaustedQuotaIsHonoredWhenTheLimitHeaderIsMissing() = runBlocking {
        var now = 1_700_000_000_000L
        val waits = mutableListOf<Long>()
        val controller = AniListGraphqlRequestController(
            nowMillis = { now },
            delayMillis = { wait -> waits += wait; now += wait },
            minimumIntervalOverrideMs = 0L,
            logger = {},
        )
        val resetAtSeconds = (now + 10_000L) / 1_000L

        controller.execute("https://graphql.anilist.co") {
            AniListHttpResponse(
                200,
                "{}",
                headers = mapOf(
                    "X-RateLimit-Remaining" to "0",
                    "X-RateLimit-Reset" to resetAtSeconds.toString(),
                ),
            )
        }
        controller.execute("https://graphql.anilist.co") { AniListHttpResponse(200, "{}") }

        assertEquals(listOf(10_000L), waits)
    }

    private fun publication(
        entityId: String = "812",
        revision: String = "sha256-revision-1",
        requestId: String = "request-test-17",
    ) = ReiAnixArtworkPublication(
        requestId = requestId,
        entityType = "anime",
        entityId = entityId,
        artworkType = "poster",
        sourceKind = "anilist",
        revision = revision,
    )

    @Test
    fun publishedFileWithoutCommittedAssociationIsRejected() {
        val signal = ReiAnixArtworkPublicationSignal()

        val accepted = signal.publishIfCommitted(
            publication = publication(),
            associationCommitted = false,
            sourceFileExists = true,
        )

        assertFalse(accepted)
        assertTrue(signal.events.value.isEmpty())
    }

    @Test
    fun missingArtworkOriginIsNotReportedAsPublished() {
        val signal = ReiAnixArtworkPublicationSignal()

        val accepted = signal.publishIfCommitted(
            publication = publication(),
            associationCommitted = true,
            sourceFileExists = false,
        )

        assertFalse(accepted)
        assertTrue(signal.events.value.isEmpty())
    }

    @Test
    fun missingNativeArtworkCacheDirectoryIsCreatedOnDemand() {
        val root = File(
            System.getProperty("java.io.tmpdir"),
            "reianix-artwork-test-" + UUID.randomUUID(),
        )
        val cache = File(root, "data/covers/artwork")
        try {
            assertFalse(cache.exists())
            assertTrue(ensureArtworkCacheDirectory(cache))
            assertTrue(cache.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun legacyFailedArtworkWithoutRetryTimestampCanBeRetried() {
        assertTrue(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = null,
                failureCount = 1,
                nowSeconds = 1_000.0,
                force = false,
            ),
        )
    }

    @Test
    fun retryWaitRowsRemainDeferredUntilTheirPersistedBackoffExpires() {
        assertFalse(
            shouldRetryArtworkRequest(
                status = "retry_wait",
                retryAtSeconds = 2_000.0,
                failureCount = 1,
                nowSeconds = 1_999.0,
                force = false,
            ),
        )
        assertTrue(
            shouldRetryArtworkRequest(
                status = "retry_wait",
                retryAtSeconds = 2_000.0,
                failureCount = 1,
                nowSeconds = 2_000.0,
                force = false,
            ),
        )
    }

    @Test
    fun automaticArtworkRetriesStopAtTheConfiguredLimitButForcedRetryRemainsAvailable() {
        assertTrue(
            shouldRetryArtworkRequest(
                status = "retry_wait",
                retryAtSeconds = 10_000.0,
                failureCount = 4,
                nowSeconds = 10_000.0,
                force = false,
            ),
        )
        assertFalse(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = null,
                failureCount = 5,
                nowSeconds = 10_000.0,
                force = false,
            ),
        )
        assertFalse(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = 10_000.0,
                failureCount = 99,
                nowSeconds = 10_000.0,
                force = false,
            ),
        )
        assertTrue(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = 99_999.0,
                failureCount = 99,
                nowSeconds = 10_000.0,
                force = true,
            ),
        )
    }

    @Test
    fun backoffIsExponentialCappedAndHonorsRetryAfter() {
        assertEquals(60L, artworkRetryDelaySeconds(failureCount = 1, retryable = true))
        assertEquals(120L, artworkRetryDelaySeconds(failureCount = 2, retryable = true))
        assertEquals(21_600L, artworkRetryDelaySeconds(failureCount = 99, retryable = true))
        assertEquals(900L, artworkRetryDelaySeconds(1, true, retryAfterSeconds = 900L))
        assertEquals(86_400L, artworkRetryDelaySeconds(failureCount = 1, retryable = false))
    }

    @Test
    fun imageServerStatusClassificationDistinguishesTransientAndPermanentResponses() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 599)) {
            assertTrue("Expected transient HTTP $status", isRetryableArtworkHttpStatus(status))
        }
        for (status in listOf(400, 401, 403, 404, 410)) {
            assertFalse("Expected cooldown HTTP $status", isRetryableArtworkHttpStatus(status))
        }
    }

    @Test
    fun retryAfterSupportsDeltaSecondsAndHttpDate() {
        assertEquals(120L, parseArtworkRetryAfterSeconds("120", 1_767_225_600_000L))
        assertEquals(120L, parseArtworkRetryAfterSeconds(
            "Thu, 01 Jan 2026 00:02:00 GMT",
            1_767_225_600_000L,
        ))
        assertEquals(null, parseArtworkRetryAfterSeconds("not-a-date", 1_767_225_600_000L))
    }

    @Test
    fun transportSqliteAndHttpFailurePoliciesRemainDistinct() {
        assertTrue(isRetryableArtworkFailure(httpStatus = null, transportOrFileIoFailure = true, sqlitePersistenceFailure = false))
        assertTrue(isRetryableArtworkFailure(httpStatus = null, transportOrFileIoFailure = false, sqlitePersistenceFailure = true))
        assertFalse(isRetryableArtworkFailure(httpStatus = null, transportOrFileIoFailure = false, sqlitePersistenceFailure = false))
        assertTrue(isRetryableArtworkFailure(httpStatus = 429, transportOrFileIoFailure = false, sqlitePersistenceFailure = false))
        assertFalse(isRetryableArtworkFailure(httpStatus = 404, transportOrFileIoFailure = true, sqlitePersistenceFailure = true))
    }

    @Test
    fun declaredArtworkLengthMustMatchReceivedBytes() {
        assertTrue(artworkResponseMatchesContentLength(-1L, 0L))
        assertTrue(artworkResponseMatchesContentLength(128L, 128L))
        assertFalse(artworkResponseMatchesContentLength(128L, 127L))
    }

    @Test
    fun abandonedTemporaryFilesExcludeFilesOwnedByActiveDownloads() {
        val root = File(
            System.getProperty("java.io.tmpdir"),
            "reianix-artwork-temp-test-" + UUID.randomUUID(),
        )
        try {
            assertTrue(root.mkdirs())
            val abandoned = File(root, ".abandoned.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val active = File(root, ".active.tmp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            File(root, "valid-cover.jpg").writeBytes(byteArrayOf(7, 8, 9))

            assertEquals(
                listOf(abandoned),
                abandonedArtworkTemporaryFiles(root, setOf(active.absolutePath)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun permanentFailuresAreEligibleAgainAfterCooldown() {
        assertFalse(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = 90_000.0,
                failureCount = 2,
                nowSeconds = 89_999.0,
                force = false,
            ),
        )
        assertTrue(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = 90_000.0,
                failureCount = 2,
                nowSeconds = 90_000.0,
                force = false,
            ),
        )
    }

    @Test
    fun invalidGeneratedThumbnailSelectionKeepsUsablePathsAndDeduplicatesInvalidRows() {
        val invalid = invalidGeneratedThumbnailPaths(
            paths = listOf(
                "/cache/black.jpg",
                "/cache/valid.jpg",
                "/cache/black.jpg",
                "",
                "   ",
            ),
            isUsable = { it.endsWith("/valid.jpg") },
        )

        assertEquals(listOf("/cache/black.jpg"), invalid)
    }

    @Test
    fun committedArtworkPublicationCanBeObservedAfterConsumerStarts() = runBlocking {
        val signal = ReiAnixArtworkPublicationSignal()
        assertTrue(
            signal.publishIfCommitted(
                publication = publication(),
                associationCommitted = true,
                sourceFileExists = true,
            ),
        )

        val snapshot = withTimeout(1_000L) { signal.events.first { it.isNotEmpty() } }
        val received = snapshot.values.single()
        assertEquals("request-test-17", received.requestId)
        assertEquals("anime", received.entityType)
        assertEquals("812", received.entityId)
        assertEquals("poster", received.artworkType)
        assertEquals("anilist", received.sourceKind)
        assertEquals("sha256-revision-1", received.revision)
    }

    @Test
    fun simultaneousArtworkPublicationsRemainAvailableWithoutAnActiveCollector() {
        val signal = ReiAnixArtworkPublicationSignal()

        assertTrue(signal.publishIfCommitted(publication(), associationCommitted = true, sourceFileExists = true))
        assertTrue(
            signal.publishIfCommitted(
                publication(entityId = "913", requestId = "request-test-18"),
                associationCommitted = true,
                sourceFileExists = true,
            ),
        )

        assertEquals(
            setOf("812", "913"),
            signal.events.value.values.map { it.entityId }.toSet(),
        )
    }

    @Test
    fun duplicateArtworkRevisionIsIgnoredButAReplacementRevisionIsRetained() {
        val signal = ReiAnixArtworkPublicationSignal()
        assertTrue(signal.publishIfCommitted(publication(), associationCommitted = true, sourceFileExists = true))

        assertFalse(
            signal.publishIfCommitted(
                publication(requestId = "duplicate-request"),
                associationCommitted = true,
                sourceFileExists = true,
            ),
        )
        assertTrue(
            signal.publishIfCommitted(
                publication(revision = "sha256-revision-2", requestId = "replacement-request"),
                associationCommitted = true,
                sourceFileExists = true,
            ),
        )

        assertEquals(1, signal.events.value.size)
        assertEquals("sha256-revision-2", signal.events.value.values.single().revision)
    }

    @Test
    fun missingPosterWithoutSavedUrlIsEligibleForDiscovery() {
        assertTrue(shouldDiscoverMissingArtwork(
            coverUrl = null,
            hasUsableLocalCover = false,
            hasAniListId = false,
            manualMetadata = false,
            matchStatus = null,
            metadataFetchedAtSeconds = null,
            nowSeconds = 100_000.0,
        ))
    }

    @Test
    fun validUrlAndUsableLocalCoverDoNotEnterMetadataDiscovery() {
        assertFalse(shouldDiscoverMissingArtwork("https://example.test/poster.jpg", false, false, false, null, null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork("not-a-url", false, false, false, null, null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork(
            "https://example.test/missing.jpg",
            hasUsableLocalCover = false,
            hasAniListId = true,
            hasExhaustedArtworkFailure = true,
            manualMetadata = false,
            matchStatus = null,
            metadataFetchedAtSeconds = null,
            nowSeconds = 100_000.0,
        ))
        assertFalse(shouldDiscoverMissingArtwork(null, true, false, false, null, null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork(null, false, true, false, null, 99_000.0, 100_000.0))
    }

    @Test
    fun recentMetadataTimestampDoesNotMaskRetryAfterTransientTransportFailure() {
        assertTrue(
            shouldDiscoverMissingArtwork(
                coverUrl = null,
                hasUsableLocalCover = false,
                hasAniListId = true,
                manualMetadata = false,
                matchStatus = "network_error",
                metadataFetchedAtSeconds = 99_000.0,
                nowSeconds = 100_000.0,
            ),
        )
        assertFalse(shouldDeferArtworkDiscoveryAttempt(null, 100_000.0))
    }

    @Test
    fun validHttpArtworkUrlsAreNotRediscoveredByTheJvmPolicy() {
        for (url in listOf(
                "https://example.test/poster.jpg",
                "http://example.test/poster.jpg",
                "HTTPS://example.test/poster.jpg",
        )) {
            assertFalse(
                "A usable remote artwork URL must not be rediscovered: $url",
                shouldDiscoverMissingArtwork(
                    coverUrl = url,
                    hasUsableLocalCover = false,
                    hasAniListId = false,
                    manualMetadata = false,
                    matchStatus = null,
                    metadataFetchedAtSeconds = null,
                    nowSeconds = 100_000.0,
                ),
            )
        }
    }

    @Test
    fun malformedNonHttpAndCredentialBearingArtworkUrlsRemainEligibleForRecovery() {
        for (url in listOf(
                "not-a-url",
                "ftp://example.test/poster.jpg",
                "file:///cache/poster.jpg",
                "https:///missing-host.jpg",
                "https://bad host/poster.jpg",
                "https://user:password@example.test/poster.jpg",
        )) {
            assertTrue(
                "An unusable remote artwork reference must remain eligible for recovery: $url",
                shouldDiscoverMissingArtwork(
                    coverUrl = url,
                    hasUsableLocalCover = false,
                    hasAniListId = false,
                    manualMetadata = false,
                    matchStatus = null,
                    metadataFetchedAtSeconds = null,
                    nowSeconds = 100_000.0,
                ),
            )
        }
    }

    @Test
    fun unresolvedAndManualRowsDoNotReceiveGuessedArtwork() {
        assertTrue(shouldDiscoverMissingArtwork(null, false, false, false, "ambiguous", null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork(null, false, false, false, "not_found", null, 100_000.0))
        assertFalse(shouldDiscoverMissingArtwork(null, false, false, true, null, null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork(null, false, true, true, null, null, 100_000.0))
    }

    @Test
    fun alternateTitleSearchTermsAreNormalizedDeduplicatedAndKeptInPriorityOrder() {
        assertEquals(
            listOf("Attack on Titan", "進撃の巨人", "Shingeki no Kyojin", "Attack on Titan: Junior High"),
            boundedAniListSearchTerms(
                listOf(
                    " Attack on Titan ",
                    "ATTACK   ON TITAN",
                    "進撃の巨人",
                    "Shingeki no Kyojin",
                    "Attack on Titan: Junior High",
                    "A",
                ),
            ),
        )
    }

    @Test
    fun alternateTitleSearchTermsRespectRequestBudget() {
        assertEquals(
            listOf("A meaningful title", "Romanized title"),
            boundedAniListSearchTerms(
                listOf("", "A meaningful title", "Romanized title", "English title"),
                limit = 2,
            ),
        )
    }


    @Test
    fun persistedArtworkDiscoveryAttemptsEnforceCooldownAcrossProcessRestarts() {
        assertTrue(shouldDeferArtworkDiscoveryAttempt(90_000.0, 100_000.0))
        assertTrue(shouldDeferArtworkDiscoveryAttempt(100_000.0, 100_000.0))
        assertFalse(
            shouldDeferArtworkDiscoveryAttempt(
                100_000.0 - MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS,
                100_000.0,
            ),
        )
        assertFalse(shouldDeferArtworkDiscoveryAttempt(null, 100_000.0))
        assertFalse(shouldDeferArtworkDiscoveryAttempt(Double.NaN, 100_000.0))
    }

    @Test
    fun artworkDiscoveryAttemptPreferencesUseStablePositiveAnimeIds() {
        assertEquals(
            "__reianix.internal.artwork_discovery_attempt_v1.812",
            artworkDiscoveryAttemptPreferenceKey(812L),
        )
    }

    @Test
    fun missingArtworkDiscoveryCursorRotatesFairlyAndWraps() {
        assertEquals(
            listOf(30L, 40L, 10L, 20L),
            rotateArtworkDiscoveryIds(listOf(40L, 10L, 20L, 30L), 20L),
        )
    }

    @Test
    fun missingArtworkDiscoveryCursorStartsAtBeginningAfterLastId() {
        assertEquals(
            listOf(10L, 20L, 30L),
            rotateArtworkDiscoveryIds(listOf(30L, 10L, 20L), 99L),
        )
    }


    @Test
    fun stableNoMatchIsSkippedOnlyForAnUnchangedSearchSignatureWithinTtl() {
        val terms = listOf("Attack on Titan", "Shingeki no Kyojin")
        val signature = aniListSearchTermsSignature(terms)
        val previous = PersistedAniListSearchSignature(signature, 100_000.0)

        assertTrue(shouldSkipStableAniListRetry("not_found", previous, signature, 101_000.0))
        assertTrue(shouldSkipStableAniListRetry("ambiguous", previous, signature, 101_000.0))
        assertFalse(shouldSkipStableAniListRetry("not_found", previous, "changed-signature", 101_000.0))
        assertFalse(
            shouldSkipStableAniListRetry(
                "not_found",
                previous,
                signature,
                100_000.0 + ANILIST_STABLE_MATCH_RETRY_TTL_SECONDS + 1,
            ),
        )
        assertFalse(shouldSkipStableAniListRetry("network_error", previous, signature, 101_000.0))
    }

    @Test
    fun stableSearchSignatureDeduplicatesEquivalentUnicodeTitles() {
        assertEquals(
            aniListSearchTermsSignature(listOf("Shingeki no Kyōjin")),
            aniListSearchTermsSignature(listOf("Shingeki-no-Kyojin", "Shingeki no Kyojin")),
        )
    }

    @Test
    fun stableSearchSignaturePreferencesUseCanonicalAnimeIds() {
        assertEquals(
            "__reianix.internal.anilist_search_signature_v1.812",
            persistedAniListSearchSignaturePreferenceKey(812L),
        )
    }


    @Test
    fun titleSearchSignatureIncludesSeasonAndYearEvidence() {
        val terms = listOf("Fruits Basket")
        val base = aniListSearchTermsSignature(terms)
        val season = aniListSearchTermsSignature(terms, seasonEvidence = 2)
        val year = aniListSearchTermsSignature(terms, yearEvidence = 2019)
        val format = aniListSearchTermsSignature(terms, mediaKindEvidence = "MOVIE")

        assertEquals(64, base.length)
        assertTrue(base != season)
        assertTrue(base != year)
        assertTrue(season != year)
        assertTrue(base != format)
        assertTrue(year != format)
    }


    @Test
    fun rateLimitAndTransportFailuresRemainRetryableInsteadOfNotFound() {
        assertEquals("rate_limited", autoMatchFailureStatus(AniListError.Http(429)))
        assertEquals("rate_limited", autoMatchFailureStatus(AniListError.RateLimited(30)))
        assertEquals("network_error", autoMatchFailureStatus(AniListError.Network("timeout")))
        assertEquals("network_error", autoMatchFailureStatus(AniListError.Http(503)))
        assertEquals("invalid_response", autoMatchFailureStatus(AniListError.GraphQl(listOf("partial"))))
        assertTrue(autoMatchFailureStatus(AniListError.InvalidRequest("blank title")) == null)
    }


}
