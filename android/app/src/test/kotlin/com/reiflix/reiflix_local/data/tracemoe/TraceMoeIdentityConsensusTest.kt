package com.reiflix.reiflix_local.data.tracemoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TraceMoeIdentityConsensusTest {
    @Test
    fun oneVeryStrongFrameCanIdentifyAnAnime() {
        val consensus = evaluateTraceMoeSingleFrameIdentity(
            listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.995)),
        )
        assertNotNull(consensus)
        assertEquals(16498, consensus?.anilistId)
        assertEquals(1, consensus?.supportingEpisodes)
    }

    @Test
    fun oneFrameBelowStrictSimilarityThresholdIsRejected() {
        val consensus = evaluateTraceMoeSingleFrameIdentity(
            listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.97)),
        )
        assertNull(consensus)
    }

    @Test
    fun oneFrameWithCloseAlternativeIsRejected() {
        val consensus = evaluateTraceMoeSingleFrameIdentity(
            listOf(
                TraceMoeSearchMatch(aniListId = 16498, similarity = 0.995),
                TraceMoeSearchMatch(aniListId = 21, similarity = 0.96),
            ),
        )
        assertNull(consensus)
    }

    @Test
    fun twoDistinctEpisodesAgreeingOnSameAniListIdAreAccepted() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.97)),
            12L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.94)),
        ))
        assertNotNull(consensus)
        assertEquals(16498, consensus?.anilistId)
        assertEquals(2, consensus?.supportingEpisodes)
        assertEquals(0.955, consensus?.averageSimilarity ?: 0.0, 0.0001)
    }

    @Test
    fun oneEpisodeAloneCannotIdentifyAnAnime() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.99)),
        ))
        assertNull(consensus)
    }

    @Test
    fun twoDifferentEpisodeMatchesWithoutMajorityAreRejected() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.97)),
            12L to listOf(TraceMoeSearchMatch(aniListId = 21, similarity = 0.96)),
        ))
        assertNull(consensus)
    }

    @Test
    fun lowSimilarityFramesCannotAuthorizeAnAssociation() {
        val consensus = evaluateTraceMoeIdentityConsensus(mapOf(
            11L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.89)),
            12L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.88)),
            13L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.86)),
        ))
        assertNull(consensus)
    }

    @Test
    fun framesWithNearTiedAnimeCandidatesAreIgnored() {
        val closeCandidates = listOf(
            TraceMoeSearchMatch(aniListId = 16498, similarity = 0.97),
            TraceMoeSearchMatch(aniListId = 21, similarity = 0.96),
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
            11L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.97)),
            12L to listOf(TraceMoeSearchMatch(aniListId = 16498, similarity = 0.94)),
            13L to listOf(TraceMoeSearchMatch(aniListId = 21, similarity = 0.92)),
        ))
        assertNotNull(consensus)
        assertEquals(16498, consensus?.anilistId)
        assertEquals(2, consensus?.supportingEpisodes)
    }
}
