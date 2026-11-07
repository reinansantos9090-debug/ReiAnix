package com.reiflix.reiflix_local.data.tracemoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

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
    fun thirdInconclusiveFrameFallsBackToTheStrongestTraceMoeCandidate() {
        val fallback = evaluateTraceMoeBestEffortIdentity(
            linkedMapOf(
                11L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.88)),
                12L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.84)),
                13L to listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.91)),
            ),
        )

        assertNotNull(fallback)
        assertEquals(5114, fallback?.anilistId)
        assertEquals(0.91, fallback?.averageSimilarity ?: 0.0, 0.0001)
        assertEquals(1, fallback?.supportingEpisodes)
    }

    @Test
    fun emptyTraceMoeResultsDoNotProduceAnUnverifiedFallback() {
        assertNull(
            evaluateTraceMoeBestEffortIdentity(
                linkedMapOf(
                    11L to emptyList(),
                    12L to emptyList(),
                    13L to emptyList(),
                ),
            ),
        )
    }

    @Test
    fun onlyTheFirstThreeEpisodeResultsCanContributeToIdentification() {
        val results = linkedMapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.89)),
            13L to listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.87)),
            14L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.98)),
            15L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.97)),
        )

        val bounded = firstTraceMoeEpisodeMatches(results)
        assertEquals(3, MAX_TRACE_MOE_EPISODE_SAMPLES)
        assertEquals(listOf(11L, 12L, 13L), bounded.keys.toList())
        assertNull(evaluateTraceMoeProgressiveIdentity(bounded))
    }

    @Test
    fun pendingStartupReplaySkipsAlreadyProcessedEpisodesAndRespectsRemainingSlots() {
        val candidates = listOf(
            TraceMoeEpisodeSample(11L, File("11.jpg")),
            TraceMoeEpisodeSample(12L, File("12.jpg")),
            TraceMoeEpisodeSample(12L, File("12-duplicate.jpg")),
            TraceMoeEpisodeSample(13L, File("13.jpg")),
            TraceMoeEpisodeSample(14L, File("14.jpg")),
        )

        val pending = pendingTraceMoeEpisodeSamples(candidates, processedEpisodeIds = setOf(11L))

        assertEquals(listOf(12L, 13L), pending.map(TraceMoeEpisodeSample::episodeId))
    }

    @Test
    fun pendingStartupReplayReturnsNothingWhenAllSampleSlotsAreAlreadyProcessed() {
        val candidates = listOf(
            TraceMoeEpisodeSample(11L, File("11.jpg")),
            TraceMoeEpisodeSample(12L, File("12.jpg")),
            TraceMoeEpisodeSample(13L, File("13.jpg")),
        )

        assertEquals(
            emptyList<TraceMoeEpisodeSample>(),
            pendingTraceMoeEpisodeSamples(candidates, processedEpisodeIds = setOf(11L, 12L, 13L)),
        )
    }

    @Test
    fun onlyTemporaryServerResponsesAreRetriedImmediately() {
        assertEquals(true, isRetryableTraceMoeHttpStatus(408))
        assertEquals(true, isRetryableTraceMoeHttpStatus(425))
        assertEquals(true, isRetryableTraceMoeHttpStatus(500))
        assertEquals(true, isRetryableTraceMoeHttpStatus(503))
        assertEquals(false, isRetryableTraceMoeHttpStatus(400))
        assertEquals(false, isRetryableTraceMoeHttpStatus(404))
        // Guest HTTP 402 is transient only for the shared-IP concurrency slot.
        assertEquals(true, isRetryableTraceMoeHttpStatus(402, "Concurrency limit exceeded"))
        assertEquals(false, isRetryableTraceMoeHttpStatus(402, "Search quota depleted"))
        assertEquals(false, isRetryableTraceMoeHttpStatus(402, "Unknown payment-required response"))
        // HTTP 429 is deferred by Retry-After instead of being retried immediately.
        assertEquals(false, isRetryableTraceMoeHttpStatus(429))
        assertEquals(false, isRetryableTraceMoeHttpStatus(200))
    }

    @Test
    fun retryAfterHeaderIsParsedAndBounded() {
        assertEquals(30L, parseTraceMoeRetryAfterSeconds("30"))
        assertEquals(1L, parseTraceMoeRetryAfterSeconds("0"))
        assertEquals(3_600L, parseTraceMoeRetryAfterSeconds("99999"))
        assertEquals(null, parseTraceMoeRetryAfterSeconds("not-a-number"))
        assertEquals(null, parseTraceMoeRetryAfterSeconds(null))
        assertEquals(
            30L,
            parseTraceMoeRetryAfterSeconds(
                value = "Thu, 01 Jan 1970 00:00:30 GMT",
                nowEpochMillis = 0L,
            ),
        )
        assertEquals(
            null,
            parseTraceMoeRetryAfterSeconds(
                value = "not-an-http-date",
                nowEpochMillis = 0L,
            ),
        )
    }

    @Test
    fun http402ConcurrencyAndQuotaFailuresUseDifferentSafeRetryDeadlines() {
        assertEquals(
            ReiAnixTraceMoeClient.DEFAULT_CONCURRENCY_RETRY_AFTER_SECONDS,
            traceMoeRetryAfterSecondsForHttpFailure(402, "Concurrency limit exceeded"),
        )
        assertEquals(
            ReiAnixTraceMoeClient.DEFAULT_QUOTA_RETRY_AFTER_SECONDS,
            traceMoeRetryAfterSecondsForHttpFailure(
                402,
                "Search quota depleted (quota per 24 hours: 100, used: 100)",
            ),
        )
        assertEquals(
            null,
            traceMoeRetryAfterSecondsForHttpFailure(402, "Unknown response"),
        )
        assertEquals(45L, traceMoeRetryAfterSecondsForHttpFailure(429, null, "45"))
    }

    @Test
    fun persistedRateLimitDeadlineNeverShortensAConcurrentRetryAfter() {
        assertEquals(5_000L, mergeTraceMoeRateLimitDeadlineMillis(5_000L, 2_000L))
        assertEquals(8_000L, mergeTraceMoeRateLimitDeadlineMillis(5_000L, 8_000L))
        assertEquals(0L, mergeTraceMoeRateLimitDeadlineMillis(-1L, 0L))
    }

    @Test
    fun persistedRateLimitDeadlineBlocksUntilItsAbsoluteTime() {
        assertEquals(3_000L, remainingTraceMoeRateLimitDelayMillis(8_000L, 5_000L))
        assertEquals(0L, remainingTraceMoeRateLimitDelayMillis(5_000L, 5_000L))
        assertEquals(0L, remainingTraceMoeRateLimitDelayMillis(4_000L, 5_000L))
    }

}
