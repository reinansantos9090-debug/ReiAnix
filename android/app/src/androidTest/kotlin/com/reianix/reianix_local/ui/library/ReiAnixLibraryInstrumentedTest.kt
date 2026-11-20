package com.reianix.reianix_local.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.captureToImage
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperFrame
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperVisible
import com.reianix.reianix_local.ui.theme.WallpaperFrame
import com.reianix.reianix_local.ui.theme.WallpaperImage
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.reianix.reianix_local.ui.ReiAnixComposeRoot
import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixArtworkUiModel
import com.reianix.reianix_local.ui.model.ReiAnixConsumptionState
import com.reianix.reianix_local.ui.model.ReiAnixEpisodeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixGenreUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reianix.reianix_local.ui.model.ReiAnixLibraryUiState
import com.reianix.reianix_local.ui.model.ReiAnixLocalMediaUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaAvailability
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import com.reianix.reianix_local.ui.model.ReiAnixMetadataAvailability
import com.reianix.reianix_local.ui.model.ReiAnixSeasonUiModel
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
        composeRule.onNodeWithText("Concluído").assertIsNotDisplayed()
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
        composeRule.onNodeWithText("Assistindo").assertIsNotDisplayed()
        composeRule.onNodeWithText("Na lista").assertIsNotDisplayed()
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
        composeRule.onNodeWithText("Assistindo").assertIsNotDisplayed()
        composeRule.onNodeWithText("Concluído").assertIsNotDisplayed()
    }

    @Test
    fun missingArtworkRetryActionIsAlwaysVisibleAndInvokesCallback() {
        var requested = false
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(anime(21L, "Without Poster", artwork = null)),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                    onRetryMissingArtwork = { requested = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Baixar capas que faltam")
            .assertIsDisplayed()
            .performClick()
        assertTrue(requested)
    }

    @Test
    fun filterPanelAppliesSelectionsImmediatelyAndCombinesTabs() {
        var applied: ReiAnixLibraryFilters? = null
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(anime(7L, "Example Anime")),
                    genres = emptyList(),
                    onQueryChange = {},
                    onGenreSelected = {},
                    onToggleFavorites = {},
                    onToggleWatching = {},
                    onToggleCompleted = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onOpenDetails = {},
                    onApplyFilters = { applied = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Filtros da biblioteca").performClick()
        composeRule.onNodeWithContentDescription("Ordenar").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Gêneros").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Estado").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Etiqueta").assertIsDisplayed()
        composeRule.onNodeWithText("Aplicar").assertDoesNotExist()
        composeRule.onNodeWithText("Cancelar").assertDoesNotExist()
        composeRule.onNodeWithText("Limpar").assertDoesNotExist()

        val list = composeRule.onNodeWithTag("library-filter-options", useUnmergedTree = true)
        composeRule.onNodeWithContentDescription("Estado").performClick()
        list.performScrollToIndex(1)
        composeRule.onNodeWithText("Quero assistir").performClick()
        composeRule.onNodeWithTag("library-filter-check", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("PLANNING", applied?.watchState)
            assertEquals("ALL", applied?.labelFilter)
        }

        composeRule.onNodeWithContentDescription("Etiqueta").performClick()
        list.performScrollToIndex(1)
        composeRule.onNodeWithText("Com notas pessoais").performClick()
        composeRule.onNodeWithTag("library-filter-check", useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("PLANNING", applied?.watchState)
            assertEquals("WITH_NOTE", applied?.labelFilter)
        }

        // Reverting one selection to Todos preserves the filter on the other tab.
        list.performScrollToIndex(0)
        composeRule.onNodeWithText("Todos").performClick()
        composeRule.runOnIdle {
            assertEquals("PLANNING", applied?.watchState)
            assertEquals("ALL", applied?.labelFilter)
        }
    }

    @Test
    fun wallpaperNeverPaintsOverLibraryFilterPanel() {
        val wallpaperColor = Color(0xFFFF00FF)
        composeRule.setContent {
            ReiAnixComposeRoot(themeMode = "dark") {
                val image = remember {
                    val bitmap = android.graphics.Bitmap.createBitmap(16, 32, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.MAGENTA)
                    WallpaperImage(bitmap.asImageBitmap(), 0f)
                }
                Box(Modifier.fillMaxSize().background(wallpaperColor)) {
                    CompositionLocalProvider(
                        LocalReiAnixWallpaperVisible provides true,
                        LocalReiAnixWallpaperFrame provides WallpaperFrame(image, ReiAnixWallpaper(), IntSize(1080, 2400)),
                    ) {
                        ReiAnixLibraryScreen(
                            state = readyState(),
                            filters = ReiAnixLibraryFilters(),
                            visibleAnimes = listOf(anime(7L, "Example Anime")),
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
            }
        }
        composeRule.onNodeWithContentDescription("Filtros da biblioteca").performClick()
        composeRule.onNodeWithText("Filtros da biblioteca").assertIsDisplayed()
        val panel = composeRule.onNodeWithTag("library-filter-sheet", useUnmergedTree = true)
        for (tab in listOf("Ordenar", "Gêneros", "Estado", "Etiqueta")) {
            composeRule.onNodeWithContentDescription(tab).assertIsDisplayed().performClick()
            val pixels = panel.captureToImage().toPixelMap()
            var wallpaperPixels = 0
            var sampled = 0
            // Avoid rounded corners and test the actual interior of the modal.
            for (y in pixels.height / 8 until pixels.height * 7 / 8 step 4) {
                for (x in pixels.width / 8 until pixels.width * 7 / 8 step 4) {
                    val p = pixels[x, y]
                    if (p.red > .8f && p.blue > .8f && p.green < .2f) wallpaperPixels++
                    sampled++
                }
            }
            assertTrue("Wallpaper must not be painted inside the filter panel", sampled > 0 && wallpaperPixels == 0)
        }
    }

    @Test
    fun filterSheetStaysFixedAtScrollBoundariesAndAcrossTabs() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixLibraryScreen(
                    state = readyState(),
                    filters = ReiAnixLibraryFilters(),
                    visibleAnimes = listOf(anime(7L, "Example Anime")),
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
        // Measure the library window before the modal adds its own Compose root.
        val windowHeight = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.height
        composeRule.onNodeWithContentDescription("Filtros da biblioteca").performClick()
        val panel = composeRule.onNodeWithTag("library-filter-sheet", useUnmergedTree = true)
        val list = composeRule.onNodeWithTag("library-filter-options", useUnmergedTree = true)
        val tabs = composeRule.onNodeWithContentDescription("Ordenar")
        val header = composeRule.onNodeWithText("Filtros da biblioteca")
        val panelBounds = panel.fetchSemanticsNode().boundsInRoot
        // The old modifier constrained the anchor itself, leaving the sheet at
        // the top. Allow system-bar insets while checking the lower placement.
        assertTrue("Painel precisa começar abaixo do topo da janela",
            panelBounds.top > windowHeight * 0.35f)
        assertTrue("Painel precisa terminar junto à parte inferior",
            panelBounds.bottom >= windowHeight * 0.90f)
        assertTrue("Painel deve ocupar aproximadamente 48% da altura",
            panelBounds.height in (windowHeight * 0.40f)..(windowHeight * 0.60f))
        val tabBounds = tabs.fetchSemanticsNode().boundsInRoot
        val headerBounds = header.fetchSemanticsNode().boundsInRoot

        fun assertAnchored() {
            assertEquals(panelBounds, panel.fetchSemanticsNode().boundsInRoot)
            assertEquals(tabBounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertEquals(headerBounds, header.fetchSemanticsNode().boundsInRoot)
        }

        list.performScrollToIndex(ReiAnixLibrarySort.OPTIONS.lastIndex)
        repeat(3) {
            list.performTouchInput { swipeUp() }
            assertAnchored()
        }
        list.performScrollToIndex(0)
        repeat(3) {
            list.performTouchInput { swipeDown() }
            assertAnchored()
        }
        // A short list must not resize or drag the modal sheet, either.
        composeRule.onNodeWithContentDescription("Gêneros").performClick()
        list.performTouchInput { swipeUp() }
        assertAnchored()
        composeRule.onNodeWithContentDescription("Etiqueta").performClick()
        list.performTouchInput { swipeDown() }
        assertAnchored()
        tabs.assertIsDisplayed()
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
