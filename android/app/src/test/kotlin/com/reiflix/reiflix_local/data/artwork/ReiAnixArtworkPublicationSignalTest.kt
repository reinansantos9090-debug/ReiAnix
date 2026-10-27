package com.reiflix.reiflix_local.data.artwork

import com.reiflix.reiflix_local.data.anilist.shouldDiscoverMissingArtwork
import com.reiflix.reiflix_local.data.anilist.boundedAniListSearchTerms
import com.reiflix.reiflix_local.data.anilist.rotateArtworkDiscoveryIds
import com.reiflix.reiflix_local.data.anilist.MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
import com.reiflix.reiflix_local.data.anilist.artworkDiscoveryAttemptPreferenceKey
import com.reiflix.reiflix_local.data.anilist.shouldDeferArtworkDiscoveryAttempt

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixArtworkPublicationSignalTest {
    private fun publication() = ReiAnixArtworkPublication(
        requestId = "request-test-17",
        entityType = "anime",
        entityId = "812",
        artworkType = "poster",
        sourceKind = "anilist",
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
        assertTrue(signal.events.replayCache.isEmpty())
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
        assertTrue(signal.events.replayCache.isEmpty())
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
    fun legacyCoverCacheRequiresAnArtworkAssociationBeforeItCanSatisfyCurrentUrl() {
        assertFalse(
            shouldReuseLegacyCoverCache(
                hasArtworkPathAssociation = false,
                hasCurrentOrLocalAssociation = false,
            ),
        )
        assertFalse(
            shouldReuseLegacyCoverCache(
                hasArtworkPathAssociation = true,
                hasCurrentOrLocalAssociation = false,
            ),
        )
        assertTrue(
            shouldReuseLegacyCoverCache(
                hasArtworkPathAssociation = true,
                hasCurrentOrLocalAssociation = true,
            ),
        )
    }

    @Test
    fun invalidCoverPointerIsClearedOnlyWhenStillCurrentAndNotUserOwned() {
        assertTrue(
            shouldClearInvalidArtworkPointer(
                invalidPointerCandidate = "/cache/broken-cover.jpg",
                currentPointer = "/cache/broken-cover.jpg",
                currentPointerIsManual = false,
            ),
        )
        assertFalse(
            shouldClearInvalidArtworkPointer(
                invalidPointerCandidate = "/cache/broken-cover.jpg",
                currentPointer = "/cache/new-cover.jpg",
                currentPointerIsManual = false,
            ),
        )
        assertFalse(
            shouldClearInvalidArtworkPointer(
                invalidPointerCandidate = "/cache/broken-cover.jpg",
                currentPointer = "/cache/broken-cover.jpg",
                currentPointerIsManual = true,
            ),
        )
        assertFalse(
            shouldClearInvalidArtworkPointer(
                invalidPointerCandidate = null,
                currentPointer = "/cache/broken-cover.jpg",
                currentPointerIsManual = false,
            ),
        )
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
    fun exhaustedLegacyFailuresWithoutBackoffRemainRecoverable() {
        assertTrue(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = null,
                failureCount = MAX_AUTOMATIC_ARTWORK_FAILURES,
                nowSeconds = 10_000.0,
                force = false,
            ),
        )
        assertTrue(
            shouldRetryArtworkRequest(
                status = "failed",
                retryAtSeconds = null,
                failureCount = MAX_AUTOMATIC_ARTWORK_FAILURES,
                nowSeconds = 10_000.0,
                force = true,
            ),
        )
    }

    @Test
    fun exhaustedFailuresRespectPersistedSevenDayBackoffThenRecover() {
        assertFalse(
            shouldRetryArtworkRequest(
                status = "retry_wait",
                retryAtSeconds = 20_000.0,
                failureCount = MAX_AUTOMATIC_ARTWORK_FAILURES,
                nowSeconds = 19_999.0,
                force = false,
            ),
        )
        assertTrue(
            shouldRetryArtworkRequest(
                status = "retry_wait",
                retryAtSeconds = 20_000.0,
                failureCount = MAX_AUTOMATIC_ARTWORK_FAILURES,
                nowSeconds = 20_000.0,
                force = false,
            ),
        )
    }

    @Test
    fun retryBackoffIsBoundedAndNeverPermanentlyLocksExhaustedArtwork() {
        assertTrue(shouldScheduleArtworkRetry(MAX_AUTOMATIC_ARTWORK_FAILURES - 1, retryable = true))
        assertFalse(shouldScheduleArtworkRetry(MAX_AUTOMATIC_ARTWORK_FAILURES, retryable = true))
        assertFalse(shouldScheduleArtworkRetry(1, retryable = false))
        assertEquals(60L, artworkRetryDelaySeconds(1, retryable = true))
        assertEquals(
            PERMANENT_ARTWORK_RETRY_DELAY_SECONDS,
            artworkRetryDelaySeconds(1, retryable = false),
        )
        assertEquals(
            EXHAUSTED_ARTWORK_RETRY_DELAY_SECONDS,
            artworkRetryDelaySeconds(MAX_AUTOMATIC_ARTWORK_FAILURES, retryable = true),
        )
        assertEquals(
            EXHAUSTED_ARTWORK_RETRY_DELAY_SECONDS,
            artworkRetryDelaySeconds(MAX_AUTOMATIC_ARTWORK_FAILURES, retryable = false),
        )
    }

    @Test
    fun staleRequestFailuresCannotMutateCurrentArtworkOrUserOwnedArtwork() {
        assertTrue(
            shouldApplyArtworkFailure(
                requestUrlIsCurrent = true,
                artworkIsUserOwned = false,
            ),
        )
        assertFalse(
            shouldApplyArtworkFailure(
                requestUrlIsCurrent = false,
                artworkIsUserOwned = false,
            ),
        )
        assertFalse(
            shouldApplyArtworkFailure(
                requestUrlIsCurrent = true,
                artworkIsUserOwned = true,
            ),
        )
        assertFalse(
            shouldApplyArtworkFailure(
                requestUrlIsCurrent = false,
                artworkIsUserOwned = true,
            ),
        )
    }

    @Test
    fun duplicateOrdinaryRequestsPreserveScheduledRetryButForceMayReplaceIt() {
        assertTrue(shouldPreserveScheduledArtworkRetry(force = false, retryJobActive = true))
        assertFalse(shouldPreserveScheduledArtworkRetry(force = true, retryJobActive = true))
        assertFalse(shouldPreserveScheduledArtworkRetry(force = false, retryJobActive = false))
    }

    @Test
    fun invalidArtworkRowsRemainEligibleWhenNoBackoffStateBlocksThem() {
        assertTrue(
            shouldRetryArtworkRequest(
                status = "invalid",
                retryAtSeconds = null,
                failureCount = 0,
                nowSeconds = 10_000.0,
                force = false,
            ),
        )
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

        val received = withTimeout(1_000L) { signal.events.first() }
        assertEquals("request-test-17", received.requestId)
        assertEquals("anime", received.entityType)
        assertEquals("812", received.entityId)
        assertEquals("poster", received.artworkType)
        assertEquals("anilist", received.sourceKind)
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
    fun existingUrlLocalCoverAndRecentlyFetchedRowsAreNotRediscovered() {
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
        assertFalse(shouldDiscoverMissingArtwork(null, false, true, false, null, 99_000.0, 100_000.0))
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
    fun previousNoMatchOutcomesCanBeRetriedAfterCooldownWithoutGuessingManualRows() {
        assertTrue(shouldDiscoverMissingArtwork(null, false, false, false, "ambiguous", null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork(null, false, false, false, "not_found", null, 100_000.0))
        assertFalse(
            shouldDiscoverMissingArtwork(
                null, false, false, false, "not_found",
                100_000.0 - 60.0, 100_000.0,
            ),
        )
        assertTrue(
            shouldDiscoverMissingArtwork(
                null, false, false, false, "ambiguous",
                100_000.0 - MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS - 1.0, 100_000.0,
            ),
        )
        assertFalse(shouldDiscoverMissingArtwork(null, false, false, true, null, null, 100_000.0))
        assertTrue(shouldDiscoverMissingArtwork(null, false, true, true, null, null, 100_000.0))
    }

    @Test
    fun catalogueLargerThanRunBudgetProgressesAcrossPersistedCursor() {
        val ids = (1L..40L).toList()
        val firstRun = rotateArtworkDiscoveryIds(ids, 0L).take(12)
        val secondRun = rotateArtworkDiscoveryIds(ids, firstRun.last()).take(12)
        val thirdRun = rotateArtworkDiscoveryIds(ids, secondRun.last()).take(12)
        val fourthRun = rotateArtworkDiscoveryIds(ids, thirdRun.last())

        assertEquals((1L..12L).toList(), firstRun)
        assertEquals((13L..24L).toList(), secondRun)
        assertEquals((25L..36L).toList(), thirdRun)
        assertEquals((37L..40L).toList(), fourthRun.take(4))
        assertEquals(ids.toSet(), (firstRun + secondRun + thirdRun + fourthRun.take(4)).toSet())
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


}
