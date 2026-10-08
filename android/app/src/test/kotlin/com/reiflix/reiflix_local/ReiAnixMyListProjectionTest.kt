package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import com.reiflix.reiflix_local.ui.mylist.ReiAnixMyListFilter
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixMyListProjectionTest {

    @Test
    fun allAndFavoritesOnlyExposePersistedMyListMembership() {
        val library = listOf(
            anime(id = 1L, title = "Zulu", favorite = true),
            anime(id = 2L, title = "Alpha", favorite = false),
            anime(id = 3L, title = "Beta", favorite = true),
        )

        val all = ReiAnixLibraryViewModel.projectMyListAnimes(
            library,
            ReiAnixMyListFilter.ALL,
        )
        val favorites = ReiAnixLibraryViewModel.projectMyListAnimes(
            library,
            ReiAnixMyListFilter.FAVORITES,
        )

        assertEquals(listOf(3L, 1L), all.map { it.id })
        assertEquals(all.map { it.id }, favorites.map { it.id })
    }

    @Test
    fun watchingAndCompletedFiltersRemainScopedToMyList() {
        val library = listOf(
            anime(
                id = 1L,
                title = "Watching Saved",
                favorite = true,
                episodeState = ReiAnixConsumptionState.IN_PROGRESS,
            ),
            anime(
                id = 2L,
                title = "Watching Not Saved",
                favorite = false,
                episodeState = ReiAnixConsumptionState.IN_PROGRESS,
            ),
            anime(
                id = 3L,
                title = "Completed Saved",
                favorite = true,
                episodeState = ReiAnixConsumptionState.COMPLETED,
            ),
            anime(
                id = 4L,
                title = "Completed Not Saved",
                favorite = false,
                episodeState = ReiAnixConsumptionState.COMPLETED,
            ),
        )

        val watching = ReiAnixLibraryViewModel.projectMyListAnimes(
            library,
            ReiAnixMyListFilter.WATCHING,
        )
        val completed = ReiAnixLibraryViewModel.projectMyListAnimes(
            library,
            ReiAnixMyListFilter.COMPLETED,
        )

        assertEquals(listOf(1L), watching.map { it.id })
        assertEquals(listOf(3L), completed.map { it.id })
    }

    @Test
    fun orderingIsDeterministicWhenTitlesMatch() {
        val library = listOf(
            anime(id = 22L, title = " Same ", favorite = true),
            anime(id = 11L, title = "same", favorite = true),
        )

        val result = ReiAnixLibraryViewModel.projectMyListAnimes(
            library,
            ReiAnixMyListFilter.ALL,
        )

        assertEquals(listOf(11L, 22L), result.map { it.id })
        assertTrue(result.all { it.stableKey.startsWith("anime:") })
    }

    private fun anime(
        id: Long,
        title: String,
        favorite: Boolean,
        episodeState: ReiAnixConsumptionState? = null,
    ): ReiAnixAnimeUiModel {
        val episodes = episodeState?.let { state ->
            listOf(
                ReiAnixEpisodeUiModel(
                    id = id * 10L,
                    animeId = id,
                    seasonNumber = 1,
                    number = 1.0,
                    title = "Episode 1",
                    fileName = "episode-$id.mkv",
                    media = ReiAnixLocalMediaUiModel(
                        reference = "content://reianix/$id",
                        uri = "content://reianix/$id",
                        path = null,
                        mediaIdentity = "identity:$id",
                        sourceAvailabilityState = "available",
                        availability = ReiAnixMediaAvailability.AVAILABLE,
                    ),
                    progressSeconds = if (state == ReiAnixConsumptionState.IN_PROGRESS) 10.0 else 0.0,
                    durationSeconds = 100.0,
                    watched = state == ReiAnixConsumptionState.COMPLETED,
                    consumptionState = state,
                    artwork = null,
                ),
            )
        }.orEmpty()

        return ReiAnixAnimeUiModel(
            id = id,
            title = title,
            year = null,
            genres = emptyList(),
            favorite = favorite,
            mediaKind = ReiAnixMediaKind.SERIES,
            artwork = null,
            metadataAvailability = ReiAnixMetadataAvailability.UNKNOWN,
            seasons = emptyList(),
            specials = emptyList(),
            mediaFiles = episodes,
        )
    }
}
