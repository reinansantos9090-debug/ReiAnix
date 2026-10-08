package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilterEngine
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilters
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixLibraryFiltersTest {

    @Test
    fun queryAndGenreUseOnlyRealCatalogFields() {
        val anime = anime(
            7L,
            "Example Anime",
            genres = listOf(ReiAnixGenreUiModel("action", "Action")),
        )
        val other = anime(
            8L,
            "Other",
            genres = listOf(ReiAnixGenreUiModel("drama", "Drama")),
        )

        assertEquals(
            listOf(7L),
            ReiAnixLibraryFilterEngine.filter(
                listOf(anime, other),
                ReiAnixLibraryFilters(query = "action"),
            ).map { it.id },
        )
        assertEquals(
            listOf(7L),
            ReiAnixLibraryFilterEngine.filter(
                listOf(anime, other),
                ReiAnixLibraryFilters(selectedGenreKey = "action"),
            ).map { it.id },
        )
    }

    @Test
    fun favoriteWatchingAndCompletedMatchPersistedStates() {
        val favorite = anime(1L, "Favorite", favorite = true)
        val watching = anime(2L, "Watching", episodeState = ReiAnixConsumptionState.IN_PROGRESS)
        val completed = anime(3L, "Completed", episodeState = ReiAnixConsumptionState.WATCHED)
        val completedMissing = anime(
            4L,
            "Completed Missing",
            episodeState = ReiAnixConsumptionState.WATCHED,
            availability = ReiAnixMediaAvailability.MISSING,
        )

        assertEquals(
            listOf(1L),
            ReiAnixLibraryFilterEngine.filter(
                listOf(favorite, watching, completed, completedMissing),
                ReiAnixLibraryFilters(favoritesOnly = true),
            ).map { it.id },
        )
        assertEquals(
            listOf(2L),
            ReiAnixLibraryFilterEngine.filter(
                listOf(favorite, watching, completed, completedMissing),
                ReiAnixLibraryFilters(watchingOnly = true),
            ).map { it.id },
        )
        assertEquals(
            listOf(3L),
            ReiAnixLibraryFilterEngine.filter(
                listOf(favorite, watching, completed, completedMissing),
                ReiAnixLibraryFilters(completedOnly = true),
            ).map { it.id },
        )
    }

    @Test
    fun combinedFiltersRemainStableAndDoNotUseListPositionAsIdentity() {
        val first = anime(
            7L,
            "Target",
            genres = listOf(ReiAnixGenreUiModel("action", "Action")),
            favorite = true,
        )
        val second = anime(
            11L,
            "Target 2",
            genres = listOf(ReiAnixGenreUiModel("action", "Action")),
            favorite = true,
        )

        val result = ReiAnixLibraryFilterEngine.filter(
            listOf(first, second),
            ReiAnixLibraryFilters(
                query = "target",
                selectedGenreKey = "action",
                favoritesOnly = true,
            ),
        )

        assertEquals(listOf("anime:7", "anime:11"), result.map { it.stableKey })
    }

    private fun anime(
        id: Long,
        title: String,
        genres: List<ReiAnixGenreUiModel> = emptyList(),
        favorite: Boolean = false,
        episodeState: ReiAnixConsumptionState = ReiAnixConsumptionState.UNWATCHED,
        availability: ReiAnixMediaAvailability = ReiAnixMediaAvailability.AVAILABLE,
    ) = ReiAnixAnimeUiModel(
        id = id,
        title = title,
        year = 2026,
        genres = genres,
        favorite = favorite,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = ReiAnixArtworkUiModel(null, null),
        metadataAvailability = ReiAnixMetadataAvailability.UNRESOLVED,
        seasons = listOf(
            ReiAnixSeasonUiModel(
                animeId = id,
                number = 1,
                title = "Season 1",
                episodes = listOf(
                    ReiAnixEpisodeUiModel(
                        id = id * 10 + 1,
                        animeId = id,
                        seasonNumber = 1,
                        number = 1.0,
                        title = "Episode 1",
                        fileName = "episode.mkv",
                        media = ReiAnixLocalMediaUiModel(
                            reference = "content://example/$id",
                            uri = "content://example/$id",
                            path = null,
                            mediaIdentity = "identity-$id",
                            sourceAvailabilityState = availability.name.lowercase(),
                            availability = availability,
                        ),
                        progressSeconds = if (episodeState == ReiAnixConsumptionState.IN_PROGRESS) 10.0 else null,
                        durationSeconds = 100.0,
                        watched = episodeState != ReiAnixConsumptionState.UNWATCHED,
                        consumptionState = episodeState,
                        artwork = null,
                    ),
                ),
            ),
        ),
        specials = emptyList(),
        mediaFiles = emptyList(),
        playbackTargetEpisodeId = null,
    )
}
