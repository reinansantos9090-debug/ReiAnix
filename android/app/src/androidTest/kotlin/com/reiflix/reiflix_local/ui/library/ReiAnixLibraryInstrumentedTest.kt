package com.reiflix.reiflix_local.ui.library

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixArtworkUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixConsumptionState
import com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixMediaKind
import com.reiflix.reiflix_local.ui.model.ReiAnixMetadataAvailability
import com.reiflix.reiflix_local.ui.model.ReiAnixSeasonUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReiAnixLibraryInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingStateShowsLibraryAndScannerProgress() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = ReiAnixLibraryUiState(
                        status = ReiAnixLibraryLoadStatus.LOADING,
                        scanInProgress = true,
                        scanState = "SCANNING",
                    ),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = emptyList(),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Biblioteca").assertIsDisplayed()
        composeRule.onNodeWithText("Carregando enquanto a biblioteca é atualizada…").assertIsDisplayed()
    }

    @Test
    fun readyCardsShowRealTitleEpisodeCountAndGenre() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(anime(7L, "Example Anime")),
                    genres = listOf(ReiAnixGenreUiModel("action", "Action")),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Example Anime").assertIsDisplayed()
        composeRule.onNodeWithText("1 episódio").assertIsDisplayed()
        composeRule.onNodeWithText("Action").assertIsDisplayed()
    }

    @Test
    fun stateBadgesReflectRealStateAndFavoriteActionUsesCanonicalId() {
        var toggledId = -1L
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(
                        anime(
                            7L,
                            "Watching Favorite",
                            favorite = true,
                            episodeState = ReiAnixConsumptionState.IN_PROGRESS,
                        ),
                    ),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                    onToggleFavorite = { toggledId = it },
                )
            }
        }

        composeRule.onNodeWithText("Assistindo").assertIsDisplayed()
        composeRule.onNodeWithText("Na lista").assertIsDisplayed()
        composeRule.onNodeWithText("Concluído").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Remover da Minha Lista").performClick()
        assertEquals(7L, toggledId)
    }

    @Test
    fun completedStateShowsOnlyCompletedBadge() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(
                        anime(
                            8L,
                            "Completed",
                            episodeState = ReiAnixConsumptionState.WATCHED,
                        ),
                    ),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeRule.onNodeWithText("Concluído").assertIsDisplayed()
        composeRule.onNodeWithText("Assistindo").assertDoesNotExist()
        composeRule.onNodeWithText("Na lista").assertDoesNotExist()
    }

    @Test
    fun favoriteWithoutProgressAndMissingArtworkShowsOnlyFavoriteState() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(
                        anime(
                            9L,
                            "Favorite Without Progress",
                            artwork = null,
                            favorite = true,
                        ),
                    ),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                    onToggleFavorite = {},
                )
            }
        }

        composeRule.onNodeWithText("Sem arte").assertIsDisplayed()
        composeRule.onNodeWithText("Na lista").assertIsDisplayed()
        composeRule.onNodeWithText("Assistindo").assertDoesNotExist()
        composeRule.onNodeWithText("Concluído").assertDoesNotExist()
    }

    @Test
    fun searchButtonUsesProvidedNavigationAction() {
        var opened = false
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = emptyList(),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                    onSearch = { opened = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Pesquisar na biblioteca").performClick()
        assertTrue(opened)
    }

    @Test
    fun clickingCardEmitsCanonicalAnimeId() {
        var selectedId = -1L
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(anime(42L, "Clickable")),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = { selectedId = it },
                )
            }
        }

        composeRule.onNodeWithText("Clickable").performClick()
        assertEquals(42L, selectedId)
    }

    @Test
    fun missingArtworkShowsExplicitPlaceholder() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(anime(7L, "No Artwork", artwork = null)),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Sem arte").assertIsDisplayed()
    }

    @Test
    fun filteredEmptyStateHasRecoveryAction() {
        var cleared = 0
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(query = "does-not-exist"),
                    visibleAnimes = emptyList(),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = { cleared++ },
                    onRefresh = {},
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Nenhum resultado").assertIsDisplayed()
        composeRule.onNodeWithText("Limpar filtros").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun errorStateUsesRecoveryAction() {
        var refreshes = 0
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = ReiAnixLibraryUiState(
                        status = ReiAnixLibraryLoadStatus.ERROR,
                        error = "Falha real",
                    ),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = emptyList(),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = { refreshes++ },
                    onOpenDetails = {},
                )
            }
        }

        composeRule.onNodeWithText("Falha real").assertIsDisplayed()
        composeRule.onNodeWithText("Tentar novamente").performClick()
        assertEquals(1, refreshes)
    }

    private fun readyState() = ReiAnixLibraryUiState(
        status = ReiAnixLibraryLoadStatus.READY,
        revision = 1L,
        sourceAvailable = true,
        sourceState = "AVAILABLE",
    )

    private fun anime(
        id: Long,
        title: String,
        artwork: ReiAnixArtworkUiModel? = ReiAnixArtworkUiModel(null, null),
        favorite: Boolean = false,
        episodeState: ReiAnixConsumptionState? = null,
    ) = ReiAnixAnimeUiModel(
        id = id,
        title = title,
        year = 2026,
        genres = listOf(ReiAnixGenreUiModel("action", "Action")),
        favorite = favorite,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = artwork,
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
                            sourceAvailabilityState = "available",
                            availability = ReiAnixMediaAvailability.AVAILABLE,
                        ),
                        progressSeconds = episodeState?.let { state ->
                            when (state) {
                                ReiAnixConsumptionState.IN_PROGRESS -> 10.0
                                ReiAnixConsumptionState.WATCHED -> 100.0
                                ReiAnixConsumptionState.UNWATCHED -> 0.0
                                else -> 0.0
                            }
                        },
                        durationSeconds = 100.0,
                        watched = episodeState == ReiAnixConsumptionState.WATCHED,
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
