package com.reiflix.reiflix_local.ui.details

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import androidx.compose.runtime.mutableStateOf
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReiAnixDetailsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readyHeroShowsRealMetadataAndDispatchesExistingActions() {
        var watchedEpisodeId: Long? = null
        var favoriteAnimeId: Long? = null
        var backCount = 0

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixDetailsScreen(
                    state = ReiAnixDetailsUiState(
                        status = ReiAnixDetailsLoadStatus.READY,
                        sourceAvailable = true,
                        sourceState = "AVAILABLE",
                        anime = ReiAnixDetailsAnimeUiModel(
                            id = 42L,
                            title = "ReiAnix Test",
                            year = 2026,
                            genres = listOf(
                                ReiAnixGenreUiModel("action", "Action"),
                                ReiAnixGenreUiModel("drama", "Drama"),
                            ),
                            score = 86.0,
                            favorite = true,
                            artwork = ReiAnixArtworkUiModel(null, null),
                            episodeCount = 12,
                            playbackTargetEpisodeId = 71L,
                            shouldContinue = true,
                        ),
                    ),
                    onBack = { backCount++ },
                    onRetry = {},
                    onWatch = { watchedEpisodeId = it },
                    onToggleFavorite = { favoriteAnimeId = it },
                )
            }
        }

        composeRule.onNodeWithText("ReiAnix Test").assertIsDisplayed()
        composeRule.onNodeWithText("2026").assertIsDisplayed()
        composeRule.onNodeWithText("Nota 8.6/10").assertIsDisplayed()
        composeRule.onNodeWithText("12 episódios").assertIsDisplayed()
        composeRule.onNodeWithText("Action").assertIsDisplayed()
        composeRule.onNodeWithText("Drama").assertIsDisplayed()
        composeRule.onNodeWithText("Continuar").performClick()
        composeRule.onNodeWithContentDescription("Remover da Minha Lista").performClick()
        composeRule.onNodeWithContentDescription("Voltar").performClick()

        assertEquals(71L, watchedEpisodeId)
        assertEquals(42L, favoriteAnimeId)
        assertEquals(1, backCount)
    }

    @Test
    fun episodeMenuDispatchesPersistedEpisodeIdAndWatchedState() {
        var dispatchedEpisodeId: Long? = null
        var dispatchedWatched: Boolean? = null

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixDetailsScreen(
                    state = detailsStateWithEpisodes(1),
                    onBack = {},
                    onRetry = {},
                    onWatch = {},
                    onToggleFavorite = {},
                    onSetEpisodeWatched = { id, watched ->
                        dispatchedEpisodeId = id
                        dispatchedWatched = watched
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Ações do episódio Episode 1").performClick()
        composeRule.onNodeWithText("Marcar como visto").performClick()

        assertEquals(1L, dispatchedEpisodeId)
        assertEquals(true, dispatchedWatched)
    }

    @Test
    fun episodeListKeepsEpisodeIdentityWhenProgressChanges() {
        val state = mutableStateOf(detailsStateWithEpisodes(10))

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixDetailsScreen(
                    state = state.value,
                    onBack = {},
                    onRetry = {},
                    onWatch = {},
                    onToggleFavorite = {},
                    onSetEpisodeWatched = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("E07 • Episode 7").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ações do episódio Episode 7").performClick()
        composeRule.onNodeWithText("Marcar como visto").assertIsDisplayed()

        val current = state.value
        val anime = current.anime!!
        val season = anime.seasons.single()
        state.value = current.copy(
            anime = anime.copy(
                seasons = listOf(
                    season.copy(
                        episodes = season.episodes.map { episode ->
                            if (episode.id == 7L) {
                                episode.copy(
                                    progressSeconds = 18.0,
                                    consumptionState = ReiAnixConsumptionState.IN_PROGRESS,
                                )
                            } else {
                                episode
                            }
                        },
                    ),
                ),
                playbackTargetEpisodeId = 7L,
                shouldContinue = true,
            ),
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText("E07 • Episode 7").assertIsDisplayed()
        composeRule.onNodeWithText("18% assistido").assertIsDisplayed()
        composeRule.onNodeWithText("Marcar como visto").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ações do episódio Episode 7").assertIsDisplayed()
    }

    @Test
    fun detailsReopensRepeatedlyAfterEpisode07ProgressWithoutDroppingAdjacentEpisodes() {
        var watchedEpisodeId: Long? = null
        val state = mutableStateOf(detailsStateWithEpisodes(10))
        val visible = mutableStateOf(true)

        composeRule.setContent {
            ReiAnixComposeRoot {
                if (visible.value) {
                    ReiAnixDetailsScreen(
                        state = state.value,
                        onBack = {},
                        onRetry = {},
                        onWatch = { watchedEpisodeId = it },
                        onToggleFavorite = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("E06 • Episode 6").assertIsDisplayed()
        composeRule.onNodeWithText("E07 • Episode 7").assertIsDisplayed()
        composeRule.onNodeWithText("E08 • Episode 8").assertIsDisplayed()

        val initial = state.value
        val anime = initial.anime!!
        val season = anime.seasons.single()
        state.value = initial.copy(
            anime = anime.copy(
                seasons = listOf(
                    season.copy(
                        episodes = season.episodes.map { episode ->
                            if (episode.id == 7L) {
                                episode.copy(
                                    progressSeconds = 18.0,
                                    consumptionState = ReiAnixConsumptionState.IN_PROGRESS,
                                )
                            } else {
                                episode
                            }
                        },
                    ),
                ),
                playbackTargetEpisodeId = 7L,
                shouldContinue = true,
            ),
        )

        composeRule.waitForIdle()
        composeRule.onNodeWithText("E06 • Episode 6").assertIsDisplayed()
        composeRule.onNodeWithText("E07 • Episode 7").assertIsDisplayed()
        composeRule.onNodeWithText("E08 • Episode 8").assertIsDisplayed()
        composeRule.onNodeWithText("18% assistido").assertIsDisplayed()
        composeRule.onNodeWithText("Continuar").performClick()

        repeat(3) {
            assertEquals(7L, watchedEpisodeId)
            visible.value = false
            composeRule.waitForIdle()
            visible.value = true
            composeRule.waitForIdle()

            composeRule.onNodeWithText("E06 • Episode 6").assertIsDisplayed()
            composeRule.onNodeWithText("E07 • Episode 7").assertIsDisplayed()
            composeRule.onNodeWithText("E08 • Episode 8").assertIsDisplayed()
            composeRule.onNodeWithText("18% assistido").assertIsDisplayed()
            composeRule.onNodeWithText("Continuar").performClick()
        }
    }

    @Test
    fun seasonSelectorShowsOnlyTheSelectedSeasonInCanonicalOrder() {
        val state = detailsStateWithSeasons(
            listOf(
                season(1, 3),
                season(2, 3),
            ),
        )

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixDetailsScreen(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onWatch = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Selecionar Temporada 1").assertIsDisplayed()
        composeRule.onNodeWithText("E01 • Episode 1").assertIsDisplayed()
        composeRule.onNodeWithText("E02 • Episode 2").assertIsDisplayed()
        composeRule.onNodeWithText("E03 • Episode 3").assertIsDisplayed()
        composeRule.onNodeWithText("E11 • Episode 11").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Selecionar Temporada 2").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("E11 • Episode 11").assertIsDisplayed()
        composeRule.onNodeWithText("E12 • Episode 12").assertIsDisplayed()
        composeRule.onNodeWithText("E13 • Episode 13").assertIsDisplayed()
        composeRule.onNodeWithText("E01 • Episode 1").assertDoesNotExist()
    }

    @Test
    fun largeEpisodeSeasonUsesLazyListAndRetainsLastEpisode() {
        val state = detailsStateWithSeasons(
            listOf(season(1, 300)),
        )

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixDetailsScreen(
                    state = state,
                    onBack = {},
                    onRetry = {},
                    onWatch = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeRule.onNodeWithText("E01 • Episode 1").assertIsDisplayed()
        composeRule.onNodeWithTag("details-episode-list")
            .performScrollToNode(hasText("E300 • Episode 300"))
        composeRule.onNodeWithText("E300 • Episode 300").assertIsDisplayed()
    }

    @Test
    fun missingOptionalMetadataIsNotInvented() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixDetailsScreen(
                    state = ReiAnixDetailsUiState(
                        status = ReiAnixDetailsLoadStatus.READY,
                        sourceAvailable = true,
                        sourceState = "AVAILABLE",
                        anime = ReiAnixDetailsAnimeUiModel(
                            id = 43L,
                            title = "Local Only",
                            year = null,
                            genres = emptyList(),
                            score = null,
                            favorite = false,
                            artwork = null,
                            episodeCount = null,
                            playbackTargetEpisodeId = null,
                            shouldContinue = false,
                        ),
                    ),
                    onBack = {},
                    onRetry = {},
                    onWatch = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeRule.onNodeWithText("Local Only").assertIsDisplayed()
        composeRule.onNodeWithText("Assistir").assertDoesNotExist()
        composeRule.onNodeWithText("2026").assertDoesNotExist()
        composeRule.onNodeWithText("Nota 8.6/10").assertDoesNotExist()
        composeRule.onNodeWithText("episódios").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Adicionar à Minha Lista").assertIsDisplayed()
        composeRule.onNodeWithText("Nenhuma mídia local disponível para reprodução.").assertIsDisplayed()
    }
    private fun detailsStateWithEpisodes(episodeCount: Int): ReiAnixDetailsUiState =
        detailsStateWithSeasons(listOf(season(1, episodeCount)))

    private fun detailsStateWithSeasons(
        seasons: List<com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel>,
    ) = ReiAnixDetailsUiState(
        status = ReiAnixDetailsLoadStatus.READY,
        sourceAvailable = true,
        sourceState = "AVAILABLE",
        anime = ReiAnixDetailsAnimeUiModel(
            id = 700L,
            title = "Episode List Test",
            year = null,
            genres = emptyList(),
            score = null,
            favorite = false,
            artwork = null,
            episodeCount = seasons.sumOf { it.episodes.size },
            playbackTargetEpisodeId = seasons.firstOrNull()?.episodes?.firstOrNull()?.id,
            shouldContinue = false,
            seasons = seasons,
        ),
    )

    private fun season(
        number: Int,
        count: Int,
    ): com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel =
        com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel(
            animeId = 700L,
            number = number,
            title = "Temporada $number",
            episodes = (1..count).map { offset ->
                val id = ((number - 1) * 10L) + offset
                ReiAnixEpisodeUiModel(
                    id = id,
                    animeId = 700L,
                    seasonNumber = number,
                    number = id.toDouble(),
                    title = "Episode $id",
                    fileName = "Episode-$id.mkv",
                    media = com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel(
                        reference = "content://episode/$id",
                        uri = "content://episode/$id",
                        path = null,
                        mediaIdentity = "episode-$id",
                        sourceAvailabilityState = "available",
                        availability = com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability.AVAILABLE,
                    ),
                    progressSeconds = 0.0,
                    durationSeconds = 100.0,
                    watched = false,
                    consumptionState = ReiAnixConsumptionState.UNWATCHED,
                    artwork = null,
                )
            },
        )

}
