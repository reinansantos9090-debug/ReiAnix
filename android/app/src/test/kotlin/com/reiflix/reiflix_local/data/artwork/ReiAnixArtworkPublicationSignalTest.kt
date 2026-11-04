package com.reiflix.reiflix_local.data.artwork

import com.reiflix.reiflix_local.data.anilist.shouldDiscoverMissingArtwork
import com.reiflix.reiflix_local.data.anilist.boundedAniListSearchTerms
import com.reiflix.reiflix_local.data.anilist.rotateArtworkDiscoveryIds
import com.reiflix.reiflix_local.data.anilist.MISSING_ARTWORK_LOOKUP_COOLDOWN_SECONDS
import com.reiflix.reiflix_local.data.anilist.artworkDiscoveryAttemptPreferenceKey
import com.reiflix.reiflix_local.data.anilist.shouldDeferArtworkDiscoveryAttempt
import com.reiflix.reiflix_local.data.sqlite.shouldIncludeArtworkRecord

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
    fun automaticRetriesRemainEligibleAfterManyFailuresOncePersistedBackoffExpires() {
        assertTrue(shouldRetryArtworkRequest("failed", null, 99, 10_000.0, false))
        assertFalse(shouldRetryArtworkRequest("retry_wait", 10_001.0, 99, 10_000.0, false))
        assertTrue(shouldRetryArtworkRequest("failed", 10_000.0, 99, 10_000.0, false))
        assertTrue(shouldRetryArtworkRequest("failed", null, 99, 10_000.0, true))
    }

    @Test
    fun rankedArtworkCandidatesContinueAfterRejectedImageSources() = runBlocking {
        val candidates = listOf(
            ReiAnixArtworkCandidate(11L, "https://img.example/rejected.jpg", null),
            ReiAnixArtworkCandidate(12L, "https://img.example/probable.jpg", null),
            ReiAnixArtworkCandidate(13L, "https://img.example/next.jpg", null),
        )
        val attempted = mutableListOf<Long>()
        val recovered = firstSuccessfulArtworkCandidate(candidates, { listOf(it.posterUrl, it.posterFallbackUrl) }) { candidate, url ->
            attempted += candidate.anilistId
            url == "https://img.example/probable.jpg"
        }
        assertTrue(recovered)
        assertEquals(listOf(11L, 12L), attempted)
    }

    @Test
    fun provisionalPosterPublishesFirstAndOnlyVerifiedCandidatesRunAfterItExists() {
        val provisional = ReiAnixArtworkCandidate(
            anilistId = 41L,
            posterUrl = "https://img.example/provisional.jpg",
            bannerUrl = null,
        )
        val verified = ReiAnixArtworkCandidate(
            anilistId = 42L,
            posterUrl = "https://img.example/verified.jpg",
            bannerUrl = null,
            identityVerified = true,
        )

        assertEquals(
            listOf(provisional, verified),
            posterCandidatesForArtworkPass(listOf(provisional, verified), posterAlreadyAvailable = false),
        )
        assertEquals(
            listOf(verified),
            posterCandidatesForArtworkPass(listOf(provisional, verified), posterAlreadyAvailable = true),
        )
    }

    @Test
    fun verifiedCandidateSurvivesBoundedQueueAfterFullAliasProvisionalBatch() {
        val provisional = (1..256).map { id ->
            ReiAnixArtworkCandidate(
                anilistId = id.toLong(),
                posterUrl = "https://img.example/$id.jpg",
                bannerUrl = null,
            )
        }
        val verified = ReiAnixArtworkCandidate(
            anilistId = 999L,
            posterUrl = "https://img.example/verified.jpg",
            bannerUrl = null,
            identityVerified = true,
        )

        val bounded = boundedArtworkCandidates(provisional + verified, 256)

        assertEquals(256, bounded.size)
        assertEquals(verified, bounded.first())
        assertFalse(bounded.none { it.identityVerified })
    }

    @Test
    fun failedLargePosterVariantFallsBackWithoutBlockingTheCandidate() = runBlocking {
        val candidate = ReiAnixArtworkCandidate(
            anilistId = 41L,
            posterUrl = "https://img.example/poster-extra-large.jpg",
            bannerUrl = null,
            posterFallbackUrl = "https://img.example/poster-large.jpg",
        )
        val attempted = mutableListOf<String>()
        val recovered = firstSuccessfulArtworkCandidate(listOf(candidate), { listOf(it.posterUrl, it.posterFallbackUrl) }) { _, url ->
            attempted += url
            url.endsWith("poster-large.jpg")
        }
        assertTrue(recovered)
        assertEquals(listOf("https://img.example/poster-extra-large.jpg", "https://img.example/poster-large.jpg"), attempted)
    }

    @Test
    fun posterAndBannerCandidateVariantsAreRecoveredIndependently() = runBlocking {
        val candidates = listOf(
            ReiAnixArtworkCandidate(21L, "https://img.example/poster.jpg", null),
            ReiAnixArtworkCandidate(22L, null, "https://img.example/banner.jpg"),
        )
        val posters = mutableListOf<Long>()
        val banners = mutableListOf<Long>()
        assertTrue(firstSuccessfulArtworkCandidate(candidates, { listOf(it.posterUrl, it.posterFallbackUrl) }) { candidate, _ ->
            posters += candidate.anilistId
            true
        })
        assertTrue(firstSuccessfulArtworkCandidate(candidates, { listOf(it.bannerUrl) }) { candidate, _ ->
            banners += candidate.anilistId
            true
        })
        assertEquals(listOf(21L), posters)
        assertEquals(listOf(22L), banners)
    }

    @Test
    fun blankCandidateVariantsDoNotBlockLaterUsableSources() = runBlocking {
        val candidates = listOf(
            ReiAnixArtworkCandidate(31L, " ", " "),
            ReiAnixArtworkCandidate(32L, null, "https://img.example/banner.jpg"),
        )
        val attempted = mutableListOf<Long>()
        assertTrue(firstSuccessfulArtworkCandidate(candidates, { listOf(it.bannerUrl) }) { candidate, url ->
            attempted += candidate.anilistId
            url.endsWith("banner.jpg")
        })
        assertEquals(listOf(32L), attempted)
    }

    @Test
    fun retryPolicyClassifiesHttpTransportAndPersistenceFailuresSeparately() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 599)) {
            assertTrue("Expected HTTP $status to be retryable", isRetryableArtworkHttpStatus(status))
        }
        for (status in listOf(400, 401, 403, 404, 410)) {
            assertFalse("Expected HTTP $status to use permanent cooldown", isRetryableArtworkHttpStatus(status))
        }
        assertTrue(isRetryableArtworkFailure(null, transportOrFileIoFailure = true, sqlitePersistenceFailure = false))
        assertTrue(isRetryableArtworkFailure(null, transportOrFileIoFailure = false, sqlitePersistenceFailure = true))
        assertFalse(isRetryableArtworkFailure(null, transportOrFileIoFailure = false, sqlitePersistenceFailure = false))
        assertTrue(isRetryableArtworkFailure(429, transportOrFileIoFailure = false, sqlitePersistenceFailure = false))
        assertFalse(isRetryableArtworkFailure(404, transportOrFileIoFailure = true, sqlitePersistenceFailure = true))
    }

    @Test
    fun retryBackoffIsExponentialCappedAndHonorsRetryAfter() {
        assertEquals(60L, artworkRetryDelaySeconds(1, true))
        assertEquals(120L, artworkRetryDelaySeconds(2, true))
        assertEquals(21_600L, artworkRetryDelaySeconds(99, true))
        assertEquals(900L, artworkRetryDelaySeconds(1, true, retryAfterSeconds = 900L))
        assertEquals(86_400L, artworkRetryDelaySeconds(1, false))
    }

    @Test
    fun retryAfterParsesDeltaSecondsHttpDatesAndInvalidValues() {
        assertEquals(120L, parseArtworkRetryAfterSeconds("120", 1_767_225_600_000L))
        assertEquals(120L, parseArtworkRetryAfterSeconds("Thu, 01 Jan 2026 00:02:00 GMT", 1_767_225_600_000L))
        assertEquals(null, parseArtworkRetryAfterSeconds("not-a-date", 1_767_225_600_000L))
    }

    @Test
    fun failedArtworkRowsWithValidFilesRemainInTheComposeProjection() {
        val file = File.createTempFile("reianix-projection-artwork", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(shouldIncludeArtworkRecord("failed", file.absolutePath))
            assertTrue(shouldIncludeArtworkRecord("ready", null))
            assertFalse(shouldIncludeArtworkRecord("failed", null))
            assertFalse(shouldIncludeArtworkRecord("failed", "   "))
            assertFalse(shouldIncludeArtworkRecord("failed", "/missing/artwork.jpg"))
        } finally {
            file.delete()
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
    fun onlySecureHttpsArtworkUrlsAreNotRediscoveredByTheJvmPolicy() {
        for (url in listOf(
                "https://example.test/poster.jpg",
                "HTTPS://example.test/poster.jpg",
        )) {
            assertFalse(
                "A usable HTTPS artwork URL must not be rediscovered: $url",
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
                "http://example.test/poster.jpg",
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
    fun everyIdentityResolutionStateAndManualMetadataRemainEligibleForArtworkOnlyLookup() {
        for (status in listOf("confirmed", "probable", "ambiguous", "rejected", "unresolved", "not_found")) {
            assertTrue(
                "Identity status $status must not block artwork-only discovery.",
                shouldDiscoverMissingArtwork(null, false, false, false, status, null, 100_000.0),
            )
        }
        assertTrue(shouldDiscoverMissingArtwork(null, false, false, true, "ambiguous", 99_999.0, 100_000.0))
        assertFalse(shouldDiscoverMissingArtwork(null, true, false, false, "unresolved", null, 100_000.0))
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
    fun twoDistinctTitleAliasesPromoteTheSameProviderRecordAndPoster() {
        val tracker = ReiAnixArtworkConsensusTracker()
        val first = consensusCandidate("anilist", "anilist:101", "https://img.example/saigo-cover.jpg", "saigo original title")
        val second = first.copy(matchedTitleAliases = setOf("saigo english title"))
        assertFalse(tracker.collect(91L, listOf(first)).any { it.identityVerified })
        assertTrue(tracker.collect(91L, listOf(second)).any { it.identityVerified })
    }

    @Test
    fun repeatedVariantsForOneAliasDoNotCountAsTwoTitleVotes() {
        val tracker = ReiAnixArtworkConsensusTracker()
        val candidate = consensusCandidate("anilist", "anilist:101", "https://img.example/saigo-cover.jpg", "same original alias")
        assertFalse(tracker.collect(92L, listOf(candidate)).any { it.identityVerified })
        assertFalse(tracker.collect(92L, listOf(candidate.copy())).any { it.identityVerified })
    }

    @Test
    fun twoIndependentProvidersPromoteOnlyTheSamePosterImage() {
        val tracker = ReiAnixArtworkConsensusTracker()
        val anilist = consensusCandidate("anilist", "anilist:101", "https://cdn.example/shared-poster.jpg", "original title")
        val kitsu = consensusCandidate("kitsu", "kitsu:202", "https://cdn.example/shared-poster.jpg", "english title")
        assertFalse(tracker.collect(93L, listOf(anilist)).any { it.identityVerified })
        val promoted = tracker.collect(93L, listOf(kitsu))
        assertTrue(promoted.size >= 2)
        assertTrue(promoted.all { it.identityVerified })
    }

    @Test
    fun twoRecordsFromOneProviderCannotConfirmTheSamePosterImage() {
        val tracker = ReiAnixArtworkConsensusTracker()
        val first = consensusCandidate("kitsu", "kitsu:201", "https://cdn.example/shared.jpg", "original title")
        val second = consensusCandidate("kitsu", "kitsu:202", "https://cdn.example/shared.jpg", "english title")
        assertFalse(tracker.collect(94L, listOf(first)).any { it.identityVerified })
        assertFalse(tracker.collect(94L, listOf(second)).any { it.identityVerified })
    }

    @Test
    fun incompatibleCandidatesCannotContributeConsensusVotes() {
        val tracker = ReiAnixArtworkConsensusTracker()
        val incompatible = consensusCandidate("anilist", "anilist:301", "https://img.example/wrong.jpg", "first alias")
            .copy(identityEvidenceEligible = false)
        val otherProvider = consensusCandidate("kitsu", "kitsu:302", "https://img.example/wrong.jpg", "second alias")
        assertFalse(tracker.collect(95L, listOf(incompatible)).any { it.identityVerified })
        assertFalse(tracker.collect(95L, listOf(otherProvider)).any { it.identityVerified })
    }

    private fun consensusCandidate(source: String, record: String, url: String, alias: String) =
        ReiAnixArtworkCandidate(
            anilistId = 0L, posterUrl = url, bannerUrl = null, source = source,
            identityEvidenceKey = record, matchedTitleAliases = setOf(alias), identityEvidenceEligible = true,
        )


    @Test
    fun aliasSearchAttributionCountsOnlyUnambiguousOriginalAliases() {
        val matcher = com.reiflix.reiflix_local.data.catalog.ReiAnixCatalogIdentityMatcher
        val aliases = matcher.searchAliasAttribution(listOf("Alpha Title", "Beta Title", "ALPHA   TITLE"))
        assertEquals(setOf("alpha title"), aliases[matcher.normalizeCatalogTitle("Alpha Title")])
        assertEquals(setOf("beta title"), aliases[matcher.normalizeCatalogTitle("Beta Title")])
    }

    @Test
    fun providerConsensusPromotesOnlyThePosterUrlThatActuallyMatched() {
        val tracker = ReiAnixArtworkConsensusTracker()
        val first = consensusCandidate("anilist", "anilist:401", "https://cdn.example/primary.jpg", "original title")
            .copy(posterFallbackUrl = "https://shared.example/confirmed.jpg")
        val second = consensusCandidate("kitsu", "kitsu:402", "https://shared.example/confirmed.jpg", "english title")
        assertFalse(tracker.collect(96L, listOf(first)).any { it.identityVerified })
        val promoted = tracker.collect(96L, listOf(second))
        val verified = promoted.first { it.identityVerified }
        assertEquals("https://shared.example/confirmed.jpg", verified.posterUrl)
        assertEquals(null, verified.posterFallbackUrl)
    }

    @Test
    fun artworkConsensusUrlNormalizationIgnoresHostCaseDefaultPortAndFragment() {
        assertEquals(
            "https://cdn.example/poster.jpg?quality=80",
            artworkConsensusUrlKey("HTTPS://CDN.EXAMPLE:443/poster.jpg?quality=80#preview"),
        )
        assertTrue(
            artworkConsensusUrlKey("https://cdn.example/poster.jpg?quality=80") !=
                artworkConsensusUrlKey("https://cdn.example/poster.jpg?quality=90"),
        )
        assertEquals(
            "https://cdn.example/folder%20name/poster.jpg",
            artworkConsensusUrlKey("https://cdn.example/folder%20name/poster.jpg#preview"),
        )
    }

}
