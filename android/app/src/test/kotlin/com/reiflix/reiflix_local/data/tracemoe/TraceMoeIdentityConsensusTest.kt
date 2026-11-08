package com.reiflix.reiflix_local.data.tracemoe

import com.reiflix.reiflix_local.data.anilist.shouldAllowTraceMoeWithAutoMatchSetting
import com.reiflix.reiflix_local.data.anilist.shouldHonorTraceMoeSuccessfulCooldown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TraceMoeIdentityConsensusTest {
    @Test
    fun twoDistinctEpisodesAgreeingOnSameAniListIdAreAccepted() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.97)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.94)),
        ))
        assertNotNull(consensus)
        assertEquals(16498, consensus?.anilistId)
        assertEquals(2, consensus?.supportingEpisodes)
        assertEquals(0.955, consensus?.averageSimilarity ?: 0.0, 0.0001)
    }

    @Test
    fun oneEpisodeAloneCannotIdentifyAnAnime() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.99)),
        ))
        assertNull(consensus)
    }

    @Test
    fun twoDifferentEpisodeMatchesWithoutMajorityAreRejected() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.97)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.96)),
        ))
        assertNull(consensus)
    }

    @Test
    fun lowSimilarityFramesCannotAuthorizeAnAssociation() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.89)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
            13L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.86)),
        ))
        assertNull(consensus)
    }

    @Test
    fun framesWithNearTiedAnimeCandidatesAreIgnored() {
        val closeCandidates = listOf(
            TraceMoeSearchMatch(anilistId = 16498, similarity = 0.97),
            TraceMoeSearchMatch(anilistId = 21, similarity = 0.96),
        )
        val consensus = evaluateTraceMoeIdentityConsensus(
            mapOf(
                11L to closeCandidates,
                12L to closeCandidates,
                13L to closeCandidates,
            ),
        )

        assertNull(consensus)
    }

    @Test
    fun twoEpisodeMajoritySurvivesOneConflictingThirdFrame() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.97)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.94)),
            13L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.92)),
        ))
        assertNotNull(consensus)
        assertEquals(16498, consensus?.anilistId)
        assertEquals(2, consensus?.supportingEpisodes)
    }

    @Test
    fun aClearFirstEpisodeCanIdentifyImmediately() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.96))),
        )

        assertNotNull(result)
        assertEquals(16498, result?.anilistId)
        assertEquals(1, result?.supportingEpisodes)
    }

    @Test
    fun oneWeakEpisodeWaitsForAnotherEpisodeToAgree() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.89))),
        )

        assertNull(result)
    }

    @Test
    fun twoEpisodesAgreeingAtModerateSimilarityAreEnough() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(
                11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
                12L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.87)),
            ),
        )

        assertNotNull(result)
        assertEquals(16498, result?.anilistId)
        assertEquals(2, result?.supportingEpisodes)
    }

    @Test
    fun twoOfThreeEpisodesCanCorrectOneConflictingResult() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(
                11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
                12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.87)),
                13L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.86)),
            ),
        )

        assertNotNull(result)
        assertEquals(16498, result?.anilistId)
        assertEquals(2, result?.supportingEpisodes)
    }

    @Test
    fun twoDifferentWeakEpisodesNeedAThirdMatchingResult() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(
                11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
                12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.87)),
            ),
        )

        assertNull(result)
    }

    @Test
    fun threeInconclusiveEpisodesDoNotProduceAnUnverifiedIdentity() {
        val window = linkedMapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.89)),
            13L to listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.87)),
        )

        assertNull(evaluateTraceMoeProgressiveIdentity(window))
    }

    @Test
    fun evidenceWindowHelperMaintainsOnlyThreeLatestResponses() {
        var window = linkedMapOf<Long, List<TraceMoeSearchMatch>>()
        window = appendTraceMoeEpisodeMatches(
            window,
            11L,
            listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
        )
        window = appendTraceMoeEpisodeMatches(
            window,
            12L,
            listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.89)),
        )
        window = appendTraceMoeEpisodeMatches(
            window,
            13L,
            listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.87)),
        )
        assertNull(evaluateTraceMoeProgressiveIdentity(window))

        window = appendTraceMoeEpisodeMatches(
            window,
            14L,
            listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.88)),
        )
        assertNull(evaluateTraceMoeProgressiveIdentity(window))
        assertEquals(listOf(12L, 13L, 14L), window.keys.toList())

        window = appendTraceMoeEpisodeMatches(
            window,
            15L,
            listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.87)),
        )

        assertEquals(listOf(13L, 14L, 15L), window.keys.toList())
        val confirmed = evaluateTraceMoeProgressiveIdentity(window)
        assertNotNull(confirmed)
        assertEquals(42, confirmed?.anilistId)
        assertEquals(2, confirmed?.supportingEpisodes)
    }

    @Test
    fun restoringProgressUsesTheMostRecentThreeSuccessfulEpisodes() {
        val results = linkedMapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.89)),
            13L to listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.87)),
            14L to listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.88)),
            15L to listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.87)),
        )

        val window = latestTraceMoeEpisodeMatches(results)

        assertEquals(3, MAX_TRACE_MOE_EPISODE_SAMPLES)
        assertEquals(listOf(13L, 14L, 15L), window.keys.toList())
        assertEquals(42, evaluateTraceMoeProgressiveIdentity(window)?.anilistId)
    }

    @Test
    fun addingAnEpisodeDoesNotDiscardEarlierSuccessfulApiIdsOutsideEvidenceWindow() {
        val successfulApiEpisodeIds = linkedSetOf(11L, 12L, 13L, 14L, 15L)
        var window = linkedMapOf(
            13L to listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.87)),
            14L to listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.88)),
            15L to listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.87)),
        )
        window = appendTraceMoeEpisodeMatches(
            window,
            16L,
            listOf(TraceMoeSearchMatch(anilistId = 42, similarity = 0.86)),
        )
        successfulApiEpisodeIds.add(16L)

        assertEquals(listOf(14L, 15L, 16L), window.keys.toList())
        assertEquals(setOf(11L, 12L, 13L, 14L, 15L, 16L), successfulApiEpisodeIds)
    }


    @Test
    fun identificationStopsAfterThreeSuccessfulResponses() {
        assertTrue(hasRemainingTraceMoeResponseSlots(0))
        assertTrue(hasRemainingTraceMoeResponseSlots(1))
        assertTrue(hasRemainingTraceMoeResponseSlots(2))
        assertFalse(hasRemainingTraceMoeResponseSlots(3))
        assertFalse(hasRemainingTraceMoeResponseSlots(4))
    }

    @Test
    fun anUnreplacedFailureDoesNotMarkAnIncompleteSessionCompleted() {
        assertFalse(shouldCompleteTraceMoeIdentification(0, hadUnreplacedFailure = true))
        assertFalse(shouldCompleteTraceMoeIdentification(1, hadUnreplacedFailure = true))
        assertFalse(shouldCompleteTraceMoeIdentification(2, hadUnreplacedFailure = true))
    }

    @Test
    fun threeValidResponsesCompleteEvenIfAnEarlierEpisodeFailed() {
        assertTrue(shouldCompleteTraceMoeIdentification(3, hadUnreplacedFailure = true))
    }

    @Test
    fun noMoreThumbnailsWithoutApiFailuresCanCompleteWithFewerThanThreeResponses() {
        assertTrue(shouldCompleteTraceMoeIdentification(1, hadUnreplacedFailure = false))
    }

    @Test
    fun failedEpisodeRequestsCanBeReplacedWithoutConsumingAValidResponseSlot() {
        assertTrue(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.BAD_IMAGE))
        assertTrue(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.TIMEOUT))
        assertTrue(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.NETWORK_UNAVAILABLE))
        assertTrue(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.SERVER_UNAVAILABLE))
    }

    @Test
    fun accountWideFailuresStopInsteadOfFloodingLaterEpisodes() {
        assertFalse(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.QUOTA_EXHAUSTED))
        assertFalse(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.AUTHENTICATION))
        assertFalse(shouldAdvanceToNextTraceMoeEpisodeAfterFailure(TraceMoeFailureKind.FORBIDDEN))
    }

    @Test
    fun anEmptyButSuccessfulApiResponseStillConsumesAResponseSlot() {
        // Every normal response increments the session counter regardless of the
        // number of Trace.moe candidates; this checks the three-slot boundary.
        assertTrue(hasRemainingTraceMoeResponseSlots(2))
        assertFalse(hasRemainingTraceMoeResponseSlots(3))
    }

    @Test
    fun explicitTraceMoeRequestRunsWhenAutomaticMatchingIsDisabled() {
        assertTrue(shouldAllowTraceMoeWithAutoMatchSetting(autoMatchEnabled = false, userInitiated = true))
        assertFalse(shouldAllowTraceMoeWithAutoMatchSetting(autoMatchEnabled = false, userInitiated = false))
    }

    @Test
    fun explicitTraceMoeRequestCanBypassSuccessfulIdentificationCooldown() {
        val previousAttempt = 1_000.0
        val now = 1_100.0

        assertFalse(
            shouldHonorTraceMoeSuccessfulCooldown(
                userInitiated = true,
                previousAttemptSeconds = previousAttempt,
                nowSeconds = now,
                cooldownSeconds = 86_400L,
            ),
        )
        assertTrue(
            shouldHonorTraceMoeSuccessfulCooldown(
                userInitiated = false,
                previousAttemptSeconds = previousAttempt,
                nowSeconds = now,
                cooldownSeconds = 86_400L,
            ),
        )
    }

    @Test
    fun concurrencyLimitIsRetryableAndDistinctFromQuotaExhaustion() {
        val kind = classifyTraceMoeHttpFailure(402, "Concurrency limit exceeded")

        assertEquals(TraceMoeFailureKind.CONCURRENCY_LIMIT, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun concurrencyMessageIsRetryableEvenInSuccessfulHttpEnvelope() {
        val kind = classifyTraceMoeHttpFailure(200, "Too many concurrent searches")

        assertEquals(TraceMoeFailureKind.CONCURRENCY_LIMIT, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun exhaustedSearchQuotaIsNotRetried() {
        val kind = classifyTraceMoeHttpFailure(402, "Search quota exceeded")

        assertEquals(TraceMoeFailureKind.QUOTA_EXHAUSTED, kind)
        assertFalse(kind.retryable)
    }

    @Test
    fun textualQuotaExhaustionIsNotRetriedEvenWithoutHttp402() {
        val kind = classifyTraceMoeHttpFailure(200, "Search quota exceeded")

        assertEquals(TraceMoeFailureKind.QUOTA_EXHAUSTED, kind)
        assertFalse(kind.retryable)
    }

    @Test
    fun rateLimitIsRetryable() {
        val kind = classifyTraceMoeHttpFailure(429, "Too many requests")

        assertEquals(TraceMoeFailureKind.RATE_LIMITED, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun textualRateLimitIsRetryableEvenWithoutHttp429() {
        val kind = classifyTraceMoeHttpFailure(200, "Rate limit exceeded")

        assertEquals(TraceMoeFailureKind.RATE_LIMITED, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun serverFailuresAreRetryable() {
        val kind = classifyTraceMoeHttpFailure(503, "Service unavailable")

        assertEquals(TraceMoeFailureKind.SERVER_UNAVAILABLE, kind)
        assertTrue(kind.retryable)
    }

    @Test
    fun invalidImagesAreNotRetried() {
        val kind = classifyTraceMoeHttpFailure(400, "Invalid image")

        assertEquals(TraceMoeFailureKind.BAD_IMAGE, kind)
        assertFalse(kind.retryable)
    }

    @Test
    fun authenticationFailuresAreNotRetried() {
        val kind = classifyTraceMoeHttpFailure(401, "Invalid API key")

        assertEquals(TraceMoeFailureKind.AUTHENTICATION, kind)
        assertFalse(kind.retryable)
    }

}
