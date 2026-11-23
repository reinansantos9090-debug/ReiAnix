package com.reianix.reianix_local.ui.details

import com.reianix.reianix_local.ui.model.ReiAnixArtworkUiModel
import com.reianix.reianix_local.ui.model.ReiAnixConsumptionState
import com.reianix.reianix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability
import com.reianix.reianix_local.ui.model.ReiAnixSeasonUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReiAnixDetailsThumbnailRevisionTest {

    private fun episode(id: Long): ReiAnixEpisodeUiModel = ReiAnixEpisodeUiModel(
        id = id,
        animeId = 7L,
        seasonNumber = 1,
        number = id.toDouble(),
        title = null,
        fileName = "episode-$id.mkv",
        media = ReiAnixLocalMediaUiModel(
            reference = "content://media/video/$id",
            uri = "content://media/video/$id",
            path = null,
            mediaIdentity = "media-$id",
            sourceAvailabilityState = "AVAILABLE",
            availability = ReiAnixMediaAvailability.AVAILABLE,
        ),
        progressSeconds = 0.0,
        durationSeconds = 120.0,
        watched = false,
        consumptionState = ReiAnixConsumptionState.UNWATCHED,
        artwork = ReiAnixArtworkUiModel(localPath = "/cache/episode-$id.jpg", externalUrl = null),
        fileSizeBytes = 4096,
        modifiedAt = 100.0,
    )

    private fun anime(episodes: List<ReiAnixEpisodeUiModel>): ReiAnixDetailsAnimeUiModel =
        ReiAnixDetailsAnimeUiModel(
            id = 7L, title = "Test Anime", year = null,
            genres = emptyList(), score = null, favorite = false,
            artwork = null, episodeCount = episodes.size,
            playbackTargetEpisodeId = null, shouldContinue = false,
            seasons = listOf(ReiAnixSeasonUiModel(7L, 1, "Season 1", episodes)),
        )

    @Test
    fun progressOnlyChangesDoNotRestartThumbnailPreparation() {
        val initial = episode(1)
        val changed = initial.copy(progressSeconds = 70.0, watched = true)
        assertEquals(detailsThumbnailRevision(anime(listOf(initial))),
            detailsThumbnailRevision(anime(listOf(changed))))
    }

    @Test
    fun mediaAndThumbnailSourceChangesStillRequestUpdatedPreparation() {
        val initial = episode(1)
        val mediaUpdated = initial.copy(
            media = initial.media.copy(mediaIdentity = "new-media-revision"))
        val thumbnailUpdated = initial.copy(
            artwork = initial.artwork?.copy(localPath = "/cache/updated.jpg"))
        assertNotEquals(detailsThumbnailRevision(anime(listOf(initial))),
            detailsThumbnailRevision(anime(listOf(mediaUpdated))))
        assertNotEquals(detailsThumbnailRevision(anime(listOf(initial))),
            detailsThumbnailRevision(anime(listOf(thumbnailUpdated))))
    }

    @Test
    fun orderingAndDuplicateRowsDoNotCreateNewRevision() {
        val first = episode(1)
        val second = episode(2)
        assertEquals(detailsThumbnailRevision(anime(listOf(first, second))),
            detailsThumbnailRevision(anime(listOf(second, first, first))))
        assertEquals("", detailsThumbnailRevision(null))
    }

    @Test
    fun numberedSeasonIdentityNeverTraversesItsEpisodeList() {
        val episodes = object : AbstractList<ReiAnixEpisodeUiModel>() {
            override val size: Int get() = error("Numbered season must not scan episodes")
            override fun get(index: Int): ReiAnixEpisodeUiModel = error("Unexpected access")
        }
        val season = ReiAnixSeasonUiModel(7L, 2, "Season II", episodes)
        assertEquals("anime:7:season:number:2", season.stableKey)
        assertEquals(season.stableKey, season.stableKey)
    }

    @Test
    fun unnamedNumberlessSeasonRetainsCanonicalEpisodeSignature() {
        val season = ReiAnixSeasonUiModel(7L, null, "  ", listOf(episode(9), episode(3)))
        assertEquals("anime:7:season:episodes:3-9", season.stableKey)
    }
}
