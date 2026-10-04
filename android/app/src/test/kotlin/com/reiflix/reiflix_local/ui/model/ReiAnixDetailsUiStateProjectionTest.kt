package com.reiflix.reiflix_local.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixDetailsUiStateProjectionTest {
    @Test
    fun progressOnlySnapshotKeepsEpisodeIdentityAndContinueTarget() {
        val first = libraryState(
            playbackTargetEpisodeId = 7L,
            progressEpisodeId = null,
            consumptionState = ReiAnixConsumptionState.UNWATCHED,
        )
        val afterPlayback = libraryState(
            playbackTargetEpisodeId = 7L,
            progressEpisodeId = 7L,
            consumptionState = ReiAnixConsumptionState.IN_PROGRESS,
        )

        val before = ReiAnixDetailsUiStateProjection.from(first, 700L)
        val after = ReiAnixDetailsUiStateProjection.from(afterPlayback, 700L)

        assertEquals(
            listOf(6L, 7L, 8L),
            after.anime!!.seasons.single().episodes.map { it.id }.filter { it in 6L..8L },
        )
        assertEquals(7L, before.anime?.playbackTargetEpisodeId)
        assertEquals(7L, after.anime?.playbackTargetEpisodeId)
        assertFalse(before.anime?.shouldContinue ?: true)
        assertTrue(after.anime?.shouldContinue ?: false)

        val persistedProgress = after.anime
            ?.seasons
            ?.single()
            ?.episodes
            ?.single { it.id == 7L }

        assertEquals(18.0, persistedProgress?.progressSeconds)
        assertEquals(ReiAnixConsumptionState.IN_PROGRESS, persistedProgress?.consumptionState)
    }

    @Test
    fun repeatedProjectionUsesTheSameCanonicalEpisodeIds() {
        val state = libraryState(
            playbackTargetEpisodeId = 7L,
            progressEpisodeId = 7L,
            consumptionState = ReiAnixConsumptionState.IN_PROGRESS,
        )

        val projections = (1..4).map {
            ReiAnixDetailsUiStateProjection.from(state, 700L)
        }

        projections.forEach { projection ->
            assertEquals(
                (1L..10L).toList(),
                projection.anime!!.seasons.single().episodes.map { it.id },
            )
            assertEquals(7L, projection.anime.playbackTargetEpisodeId)
            assertTrue(projection.anime.shouldContinue)
        }
    }

    private fun libraryState(
        playbackTargetEpisodeId: Long,
        progressEpisodeId: Long?,
        consumptionState: ReiAnixConsumptionState,
    ): ReiAnixLibraryUiState {
        val episodes = (1L..10L).map { id ->
            val inProgress = id == progressEpisodeId
            ReiAnixEpisodeUiModel(
                id = id,
                animeId = 700L,
                seasonNumber = 1,
                number = id.toDouble(),
                title = "Episode $id",
                fileName = "Episode-$id.mkv",
                media = ReiAnixLocalMediaUiModel(
                    reference = "content://episode/$id",
                    uri = "content://episode/$id",
                    path = null,
                    mediaIdentity = "episode-$id",
                    sourceAvailabilityState = "available",
                    availability = ReiAnixMediaAvailability.AVAILABLE,
                ),
                progressSeconds = if (inProgress) 18.0 else 0.0,
                durationSeconds = 100.0,
                watched = false,
                consumptionState = if (inProgress) consumptionState else ReiAnixConsumptionState.UNWATCHED,
                artwork = null,
            )
        }

        val anime = ReiAnixAnimeUiModel(
            id = 700L,
            title = "Details regression",
            year = null,
            genres = emptyList(),
            favorite = false,
            mediaKind = ReiAnixMediaKind.SERIES,
            artwork = null,
            metadataAvailability = ReiAnixMetadataAvailability.UNRESOLVED,
            seasons = listOf(
                ReiAnixSeasonUiModel(
                    animeId = 700L,
                    number = 1,
                    title = "Temporada 1",
                    episodes = episodes,
                ),
            ),
            specials = emptyList(),
            mediaFiles = emptyList(),
            playbackTargetEpisodeId = playbackTargetEpisodeId,
        )

        return ReiAnixLibraryUiState(
            status = ReiAnixLibraryLoadStatus.READY,
            sourceAvailable = true,
            sourceState = "AVAILABLE",
            animes = listOf(anime),
        )
    }
}
