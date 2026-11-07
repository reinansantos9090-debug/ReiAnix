package com.reiflix.reiflix_local.data.tracemoe

import org.junit.Assert.assertEquals
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
    fun aGoogleWebTitleHintCannotIdentifyFromOneEpisodeByItself() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(
                11L to listOf(
                    TraceMoeSearchMatch(
                        anilistId = 16498,
                        similarity = 0.99,
                        source = TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION,
                    ),
                ),
            ),
        )

        assertNull(result)
    }

    @Test
    fun matchingGoogleWebTitleHintsNeedTwoDistinctEpisodeThumbnails() {
        val result = evaluateTraceMoeProgressiveIdentity(
            mapOf(
                11L to listOf(
                    TraceMoeSearchMatch(16498, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION),
                ),
                12L to listOf(
                    TraceMoeSearchMatch(16498, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION),
                ),
            ),
        )

        assertNotNull(result)
        assertEquals(16498, result?.anilistId)
        assertEquals(2, result?.supportingEpisodes)
    }

    @Test
    fun bestEffortFallbackNeverPromotesAGoogleOnlyTitleHint() {
        val fallback = evaluateTraceMoeBestEffortIdentity(
            linkedMapOf(
                11L to listOf(
                    TraceMoeSearchMatch(16498, 0.86, TraceMoeEvidenceSource.GOOGLE_WEB_DETECTION),
                ),
                12L to emptyList(),
                13L to emptyList(),
            ),
        )

        assertNull(fallback)
    }

}
