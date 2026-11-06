package com.reiflix.reiflix_local.data.tracemoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
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
    fun fifthEpisodeCanResolveAPreviouslyUnconfirmedFourEpisodeWindow() {
        val results = linkedMapOf(
            11L to listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.88)),
            12L to listOf(TraceMoeSearchMatch(anilistId = 21, similarity = 0.89)),
            13L to listOf(TraceMoeSearchMatch(anilistId = 5114, similarity = 0.87)),
            14L to listOf(TraceMoeSearchMatch(anilistId = 30276, similarity = 0.88)),
        )
        assertNull(evaluateTraceMoeProgressiveIdentity(results))
        results[15L] = listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.87))
        val consensus = evaluateTraceMoeProgressiveIdentity(results)
        assertNotNull(consensus)
        assertEquals(16498, consensus?.anilistId)
        assertEquals(2, consensus?.supportingEpisodes)
    }


    @Test
    fun laterEpisodeCanConfirmAfterMoreThanFiveNonDecisiveThumbnails() = runBlocking {
        val channel = Channel<TraceMoeEpisodeSample>(Channel.UNLIMITED)
        val searchedEpisodeIds = mutableListOf<Long>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            collectTraceMoeSamplesUntilConfirmed(
                samples = channel.receiveAsFlow(),
                initialMatchesByEpisode = emptyMap(),
                isSampleUsable = { true },
                search = { sample ->
                    searchedEpisodeIds += sample.episodeId
                    val anilistId = if (sample.episodeId >= 10L) {
                        42
                    } else {
                        1_000 + sample.episodeId.toInt()
                    }
                    listOf(TraceMoeSearchMatch(anilistId = anilistId, similarity = 0.87))
                },
            )
        }

        (1L..11L).forEach { episodeId ->
            channel.send(
                TraceMoeEpisodeSample(
                    episodeId = episodeId,
                    imageFile = File("episode-$episodeId.jpg"),
                ),
            )
        }

        // The channel remains open, but two later matching frames end collection.
        val decision = result.await()
        assertEquals((1L..11L).toList(), searchedEpisodeIds)
        assertEquals(11, decision.matchesByEpisode.size)
        assertNotNull(decision.consensus)
        assertEquals(42, decision.consensus?.anilistId)
        assertEquals(2, decision.consensus?.supportingEpisodes)
    }

    @Test
    fun firstClearThumbnailConfirmsWithoutWaitingForLaterEpisodes() = runBlocking {
        val channel = Channel<TraceMoeEpisodeSample>(Channel.UNLIMITED)
        val searchedEpisodeIds = mutableListOf<Long>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            collectTraceMoeSamplesUntilConfirmed(
                samples = channel.receiveAsFlow(),
                initialMatchesByEpisode = emptyMap(),
                isSampleUsable = { true },
                search = { sample ->
                    searchedEpisodeIds += sample.episodeId
                    listOf(TraceMoeSearchMatch(anilistId = 16498, similarity = 0.96))
                },
            )
        }

        channel.send(
            TraceMoeEpisodeSample(
                episodeId = 1L,
                imageFile = File("episode-1.jpg"),
            ),
        )

        val decision = result.await()
        assertEquals(listOf(1L), searchedEpisodeIds)
        assertEquals(1, decision.consensus?.supportingEpisodes)
        assertEquals(16498, decision.consensus?.anilistId)
    }

}
