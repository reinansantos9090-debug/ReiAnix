package com.reianix.reianix_local.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixAnimeUiModelEpisodeSummaryTest {
    private fun episode(
        id: Long,
        availability: ReiAnixMediaAvailability,
        state: ReiAnixConsumptionState,
    ) = ReiAnixEpisodeUiModel(
        id = id,
        animeId = 1L,
        seasonNumber = 1,
        number = id.toDouble(),
        title = null,
        fileName = "episode-" + id + ".mkv",
        media = ReiAnixLocalMediaUiModel(
            reference = null,
            uri = null,
            path = null,
            mediaIdentity = null,
            sourceAvailabilityState = null,
            availability = availability,
        ),
        progressSeconds = null,
        durationSeconds = null,
        watched = state == ReiAnixConsumptionState.WATCHED,
        consumptionState = state,
        artwork = null,
    )

    private fun anime(episodes: List<ReiAnixEpisodeUiModel>) = ReiAnixAnimeUiModel(
        id = 1L,
        title = "Sample",
        year = null,
        genres = emptyList(),
        favorite = false,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = null,
        metadataAvailability = ReiAnixMetadataAvailability.AVAILABLE,
        seasons = listOf(ReiAnixSeasonUiModel(1L, 1, "Season 1", episodes)),
        specials = emptyList(),
        mediaFiles = emptyList(),
    )

    @Test
    fun emptyAnimeIsNotCompletedOrWatching() {
        val model = anime(emptyList())
        assertEquals(0, model.availableContentCount)
        assertFalse(model.isWatching)
        assertFalse(model.isCompleted)
    }

    @Test
    fun cachedSummaryPreservesAvailabilityAndPlaybackSemantics() {
        val episodes = listOf(
            episode(1L, ReiAnixMediaAvailability.AVAILABLE, ReiAnixConsumptionState.IN_PROGRESS),
            episode(2L, ReiAnixMediaAvailability.AVAILABLE, ReiAnixConsumptionState.WATCHED),
            episode(3L, ReiAnixMediaAvailability.SCOPE_UNAVAILABLE, ReiAnixConsumptionState.COMPLETED),
            episode(4L, ReiAnixMediaAvailability.MISSING, ReiAnixConsumptionState.COMPLETED),
        )
        val model = anime(episodes)
        repeat(3) {
            assertEquals(2, model.availableContentCount)
            assertTrue(model.isWatching)
            assertFalse(model.isCompleted)
        }
        assertEquals("2 episódios", model.episodeCountLabel)
    }

    @Test
    fun updatedImmutableCatalogItemGetsNewSummary() {
        val old = anime(
            listOf(episode(1L, ReiAnixMediaAvailability.AVAILABLE, ReiAnixConsumptionState.UNWATCHED)),
        )
        assertFalse(old.isCompleted)
        val completedEpisode =
            episode(1L, ReiAnixMediaAvailability.AVAILABLE, ReiAnixConsumptionState.COMPLETED)
        val updated = old.copy(
            seasons = listOf(ReiAnixSeasonUiModel(1L, 1, "Season 1", listOf(completedEpisode))),
        )
        assertTrue(updated.isCompleted)
        assertEquals(1, updated.availableContentCount)
        assertFalse(updated.isWatching)
        assertFalse(old.isCompleted)
    }
}
