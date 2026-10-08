package com.reiflix.reiflix_local.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.details.ReiAnixDetailsScreen
import com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsAnimeUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
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
import com.reiflix.reiflix_local.ui.navigation.ReiAnixNavigationHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToDetails
import androidx.activity.ComponentActivity
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReiAnixLibraryNavigationInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        navController = TestNavHostController(composeRule.activity).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }
    }

    @Test
    fun realLibraryCardOpensComposeDetailsWithCanonicalAnimeIdAndBackReturnsToLibrary() {
        val animeId = 42L
        val anime = testAnime(animeId)

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixNavigationHost(
                    navController = navController,
                    library = {
                        ReiAnixLibraryScreen(
                            state = ReiAnixLibraryUiState(
                                status = ReiAnixLibraryLoadStatus.READY,
                                sourceAvailable = true,
                                sourceState = "AVAILABLE",
                            ),
                            filters = ReiAnixLibraryFilters(),
                            visibleAnimes = listOf(anime),
                            genres = emptyList(),
                            onQueryChange = {},
                            onGenreSelected = {},
                            onToggleFavorites = {},
                            onToggleWatching = {},
                            onToggleCompleted = {},
                            onClearFilters = {},
                            onRefresh = {},
                            onOpenDetails = { selectedId ->
                                navController.navigateToDetails(
                                    animeId = selectedId.toString(),
                                    origin = ReiAnixRoutes.LIBRARY,
                                )
                            },
                        )
                    },
                    details = { args ->
                        ReiAnixDetailsScreen(
                            state = ReiAnixDetailsUiState(
                                status = ReiAnixDetailsLoadStatus.READY,
                                sourceAvailable = true,
                                sourceState = "AVAILABLE",
                                anime = ReiAnixDetailsAnimeUiModel(
                                    id = animeId,
                                    title = anime.title,
                                    year = anime.year,
                                    genres = anime.genres,
                                    score = anime.score,
                                    favorite = anime.favorite,
                                    artwork = anime.artwork,
                                    episodeCount = anime.contentEpisodes.size,
                                    playbackTargetEpisodeId = null,
                                    shouldContinue = false,
                                ),
                            ),
                            onBack = { navController.popBackStack() },
                            onRetry = {},
                            onWatch = {},
                            onToggleFavorite = {},
                        )
                    },
                    search = {},
                    settings = {},
                    player = {},
                    startDestination = ReiAnixRoutes.LIBRARY,
                    showBottomNavigation = false,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText(anime.title).performClick()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.DETAILS,
            navController.currentBackStackEntry?.destination?.route,
        )
        assertEquals(
            animeId.toString(),
            navController.currentBackStackEntry?.arguments
                ?.getString(ReiAnixRoutes.ARG_ANIME_ID),
        )
        composeRule.onNodeWithText("Integration Anime").assertIsDisplayed()
        composeRule.onNodeWithText("Detalhes").assertIsDisplayed()
        composeRule.onNodeWithText("Biblioteca").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Voltar").performClick()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.LIBRARY,
            navController.currentBackStackEntry?.destination?.route,
        )
        composeRule.onNodeWithText("Seu conteúdo local").assertIsDisplayed()
    }

    private fun testAnime(id: Long): ReiAnixAnimeUiModel =
        ReiAnixAnimeUiModel(
            id = id,
            title = "Integration Anime",
            year = 2026,
            genres = listOf(ReiAnixGenreUiModel("action", "Action")),
            favorite = false,
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
                                reference = "content://integration/$id",
                                uri = "content://integration/$id",
                                path = null,
                                mediaIdentity = "integration-$id",
                                sourceAvailabilityState = "available",
                                availability = ReiAnixMediaAvailability.AVAILABLE,
                            ),
                            progressSeconds = null,
                            durationSeconds = 100.0,
                            watched = false,
                            consumptionState = ReiAnixConsumptionState.UNWATCHED,
                            artwork = null,
                        ),
                    ),
                ),
            ),
            specials = emptyList(),
            mediaFiles = emptyList(),
            playbackTargetEpisodeId = null,
            score = 86.0,
        )

    @Test
    fun defaultNavigationHostOpensTheRealLibraryRoute() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixNavigationHost()
            }
        }

        composeRule.onNodeWithText("Biblioteca", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Seu conteúdo local").assertIsDisplayed()
    }
}
