package com.reianix.reianix_local.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import com.reianix.reianix_local.ui.ReiAnixComposeRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import kotlinx.coroutines.launch
import org.junit.Test

class ReiAnixNavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        val context = composeRule.activity
        navController = TestNavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixNavigationHost(
                    navController = navController,
                    home = {
                        Box(Modifier.fillMaxSize().background(Color.Green).testTag("profile-transition-home")) {
                            NavigationTestScreen("Início")
                        }
                    },
                    profile = {
                        Box(Modifier.fillMaxSize().background(Color.Red).testTag("profile-transition-profile")) {
                            Text("Meu perfil de teste")
                        }
                    },
                    library = { NavigationTestScreen("Biblioteca", includeScroll = true) },
                    myList = { NavigationTestScreen("Minha Lista") },
                    search = { NavigationTestScreen("Buscar") },
                    settings = { NavigationTestScreen("Ajustes") },
                    details = { args ->
                        Column {
                            Text("Details")
                            Text("animeId=" + args.animeId)
                            Text("origin=" + args.origin)
                        }
                    },
                    player = { args ->
                        Column {
                            Text("Player")
                            Text("episodeId=" + args.episodeId)
                            Text("animeId=" + args.animeId)
                            Text("origin=" + args.origin)
                        }
                    },
                )
            }
        }

        composeRule.waitForIdle()
    }

    @Test
    fun defaultHomeHostRendersRealHomeRoute() {
        composeRule.setContent {
            ReiAnixComposeRoot {
                ReiAnixNavigationHost(
                    navController = navController,
                    library = { NavigationTestScreen("Biblioteca") },
                    search = { NavigationTestScreen("Buscar") },
                    settings = { NavigationTestScreen("Ajustes") },
                    details = { NavigationTestScreen("Detalhes") },
                    player = { NavigationTestScreen("Player") },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ReiAnix").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pesquisar na biblioteca").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ReiAnixRoutes.BOTTOM_NAV_CONTENT_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun profileTransitionKeepsOutgoingContentVisibleAndRestoresHomeOnBack() {
        val home = composeRule.onNodeWithTag("profile-transition-home")
        val profile = composeRule.onNodeWithTag("profile-transition-profile")
        val bottomBarItem = composeRule.onNodeWithContentDescription("Início")
        val originalBounds = home.getUnclippedBoundsInRoot()

        fun centerPixel(tag: String): Color {
            val pixels = composeRule.onNodeWithTag(tag).captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2]
        }

        composeRule.onNodeWithText("Increment Início").performClick()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnIdle { navController.navigate(ReiAnixRoutes.PROFILE) }
            composeRule.mainClock.advanceTimeBy(64L)
            val enteringProfile = centerPixel("profile-transition-profile")
            assertTrue("Home must still be visible while Profile starts fading in", enteringProfile.green > .6f)
            assertTrue(enteringProfile.red < .3f)

            composeRule.mainClock.advanceTimeBy(300L)
            assertTrue("Profile must fade in", centerPixel("profile-transition-profile").red > enteringProfile.red + .2f)
            composeRule.mainClock.advanceTimeBy(450L)
            profile.assertIsDisplayed()
            home.assertDoesNotExist()
            bottomBarItem.assertDoesNotExist()

            composeRule.runOnIdle { navController.popBackStack() }
            composeRule.mainClock.advanceTimeBy(64L)
            val leavingProfile = centerPixel("profile-transition-home")
            assertTrue("Profile must still be visible while Home starts fading in", leavingProfile.red > .6f)
            assertTrue(leavingProfile.green < .3f)

            composeRule.mainClock.advanceTimeBy(750L)
            home.assertIsDisplayed()
            profile.assertDoesNotExist()
            bottomBarItem.assertIsDisplayed()
            assertEquals(originalBounds, home.getUnclippedBoundsInRoot())
            composeRule.onNodeWithText("Início counter=1").assertIsDisplayed()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertEquals(ReiAnixRoutes.HOME, navController.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun everyTabReplacesPreviousContentAfterTheTransition() {
        val destinations = listOf(
            ReiAnixRoutes.HOME to "Início counter=0",
            ReiAnixRoutes.LIBRARY to "Biblioteca counter=0",
            ReiAnixRoutes.SEARCH to "Buscar counter=0",
            ReiAnixRoutes.SETTINGS to "Ajustes counter=0",
        )
        for ((route, label) in destinations.drop(1) + destinations.take(1)) {
            composeRule.runOnIdle { navController.navigateToTopLevel(route) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(label).assertIsDisplayed()
            destinations.filter { it.first != route }.forEach { (_, other) ->
                composeRule.onNodeWithText(other).assertDoesNotExist()
            }
            composeRule.onNodeWithContentDescription(ReiAnixRoutes.BOTTOM_NAV_CONTENT_DESCRIPTION)
                .assertIsDisplayed()
        }
    }

    @Test
    fun topLevelNavigationDoesNotDuplicateAnyDestination() {
        listOf(
            "Início" to ReiAnixRoutes.HOME,
            "Biblioteca" to ReiAnixRoutes.LIBRARY,
            "Buscar" to ReiAnixRoutes.SEARCH,
            "Ajustes" to ReiAnixRoutes.SETTINGS,
        ).forEach { (label, route) ->
            clickTopLevel(label)
            clickTopLevel(label)
            clickTopLevel(label)

            assertEquals(route, navController.currentBackStackEntry?.destination?.route)
            if (route == ReiAnixRoutes.HOME) {
                assertTrue(navController.previousBackStackEntry == null)
            } else {
                assertEquals(
                    ReiAnixRoutes.HOME,
                    navController.previousBackStackEntry?.destination?.route,
                )
            }
        }
    }

    @Test
    fun selectedStateIsExposedForEachTopLevelDestination() {
        listOf("Início", "Biblioteca", "Buscar", "Ajustes").forEach { label ->
            clickTopLevel(label)
            composeRule.onNodeWithText(label).assertIsSelected()
        }
    }

    @Test
    fun bottomNavigationIsHiddenOnDetailsAndPlayerAndRestoredOnBack() {
        // Item semantics directly identify the bar without relying on a
        // transient NavHost transition or a separate test-only view.
        val bottomItem = composeRule.onNodeWithContentDescription("Ajustes")
        bottomItem.assertIsDisplayed()

        composeRule.runOnIdle {
            navController.navigateToDetails("42", ReiAnixRoutes.HOME)
        }
        composeRule.waitForIdle()
        bottomItem.assertDoesNotExist()

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        bottomItem.assertIsDisplayed()

        composeRule.runOnIdle {
            navController.navigateToDetails("42", ReiAnixRoutes.HOME)
            navController.navigateToPlayer("episode-7", "42", ReiAnixRoutes.DETAILS_ORIGIN)
        }
        composeRule.waitForIdle()
        bottomItem.assertDoesNotExist()
    }

    @Test
    fun tabStateIsRestoredAfterSwitchingTabs() {
        clickTopLevel("Biblioteca")
        composeRule.onNodeWithText("Biblioteca counter=0").assertExists()
        composeRule.onNodeWithText("Increment Biblioteca").performClick()
        composeRule.onNodeWithText("Biblioteca counter=1").assertExists()

        clickTopLevel("Início")
        clickTopLevel("Biblioteca")

        composeRule.onNodeWithText("Biblioteca counter=1").assertExists()
    }

    @Test
    fun tabStateIsRestoredForSecondDestination() {
        clickTopLevel("Buscar")
        composeRule.onNodeWithText("Buscar counter=0").assertExists()
        composeRule.onNodeWithText("Increment Buscar").performClick()
        composeRule.onNodeWithText("Buscar counter=1").assertExists()

        clickTopLevel("Ajustes")
        clickTopLevel("Buscar")

        composeRule.onNodeWithText("Buscar counter=1").assertExists()
        composeRule.onNodeWithText("Buscar").assertIsSelected()
    }

    @Test
    fun tabScrollPositionIsRestoredAndContentStaysAboveBottomNavigation() {
        clickTopLevel("Biblioteca")

        composeRule.onNodeWithText("Jump Biblioteca to item 18").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Biblioteca item=29").assertIsDisplayed()

        val finalItemBottom =
            composeRule
                .onNodeWithText("Biblioteca item=29")
                .getUnclippedBoundsInRoot()
                .bottom
        val bottomNavigationTop =
            composeRule
                .onNodeWithContentDescription(ReiAnixRoutes.BOTTOM_NAV_CONTENT_DESCRIPTION)
                .getUnclippedBoundsInRoot()
                .top

        assertTrue(
            "Scrollable content overlaps the bottom navigation: itemBottom=$finalItemBottom, " +
                "navigationTop=$bottomNavigationTop",
            finalItemBottom <= bottomNavigationTop,
        )

        clickTopLevel("Início")
        clickTopLevel("Biblioteca")

        composeRule.onNodeWithText("Biblioteca item=29").assertIsDisplayed()
    }

    @Test
    fun myListRemainsASecondaryRouteAndUsesLibraryShellContext() {
        navController.navigateToMyList()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.MY_LIST,
            navController.currentBackStackEntry?.destination?.route,
        )
        composeRule.onNodeWithText("Minha Lista").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ReiAnixRoutes.BOTTOM_NAV_CONTENT_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Biblioteca").assertIsSelected()
    }

    @Test
    fun detailsFromEveryTopLevelDestinationPreservesOriginAndBackReturnsToSource() {
        listOf(
            ReiAnixRoutes.HOME,
            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH,
            ReiAnixRoutes.SETTINGS,
        ).forEach { origin ->
            val first = "anime-$origin"
            val second = "recommended-2-$origin"
            val third = "recommended-3-$origin"
            composeRule.runOnIdle {
                navController.navigateToTopLevel(origin)
                navController.navigateToDetails(first, origin)
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithText("animeId=$first").assertExists()
            composeRule.onNodeWithText("origin=$origin").assertExists()
            composeRule.onNodeWithContentDescription("Ajustes").assertDoesNotExist()

            // Same destination pattern but different anime IDs must push entries.
            composeRule.runOnIdle { navController.navigateToDetails(second, origin) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("animeId=$second").assertExists()
            assertEquals(first, navController.previousBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID))

            composeRule.runOnIdle { navController.navigateToDetails(third, origin) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("animeId=$third").assertExists()
            assertEquals(second, navController.previousBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID))

            // Back must restore each previous anime, then the originating tab.
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("animeId=$second").assertExists()
            composeRule.onNodeWithContentDescription("Ajustes").assertDoesNotExist()

            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()
            composeRule.onNodeWithText("animeId=$first").assertExists()

            composeRule.activity.onBackPressedDispatcher.onBackPressed()
            composeRule.waitForIdle()
            assertEquals(origin, navController.currentBackStackEntry?.destination?.route)
            composeRule.onNodeWithText(
                when (origin) {
                    ReiAnixRoutes.HOME -> "Início"
                    ReiAnixRoutes.LIBRARY -> "Biblioteca"
                    ReiAnixRoutes.SEARCH -> "Buscar"
                    ReiAnixRoutes.SETTINGS -> "Ajustes"
                    else -> "Buscar"
                },
            ).assertIsSelected()
        }
    }

    @Test
    fun allTopLevelRoutesAreReachable() {
        val destinations = listOf(
            "Biblioteca" to ReiAnixRoutes.LIBRARY,
            "Buscar" to ReiAnixRoutes.SEARCH,
            "Ajustes" to ReiAnixRoutes.SETTINGS,
            "Início" to ReiAnixRoutes.HOME,
        )

        destinations.forEach { (label, route) ->
            clickTopLevel(label)
            assertEquals(route, navController.currentBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun detailsPreservesOriginAndBackReturnsToSource() {
        navController.navigateToTopLevel(ReiAnixRoutes.SEARCH)
        navController.navigateToDetails("42", ReiAnixRoutes.SEARCH)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("animeId=42").assertExists()
        composeRule.onNodeWithText("origin=" + ReiAnixRoutes.SEARCH).assertExists()
        assertTrue(navController.currentBackStackEntry?.destination?.route == ReiAnixRoutes.DETAILS)
        composeRule.onNodeWithContentDescription("Ajustes").assertDoesNotExist()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.SEARCH,
            navController.currentBackStackEntry?.destination?.route,
        )
    }

    @Test
    fun detailsFromLibraryPreservesOriginAndBackReturnsToLibrary() {
        navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY)
        navController.navigateToDetails("anime-library-42", ReiAnixRoutes.LIBRARY)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("animeId=anime-library-42").assertExists()
        composeRule.onNodeWithText("origin=" + ReiAnixRoutes.LIBRARY).assertExists()
        composeRule.onNodeWithContentDescription("Ajustes").assertDoesNotExist()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.LIBRARY,
            navController.currentBackStackEntry?.destination?.route,
        )
        composeRule.onNodeWithText("Biblioteca").assertIsSelected()
    }

    @Test
    fun reopeningSameDetailsDoesNotCreateDuplicateEntry() {
        navController.navigateToDetails("42", ReiAnixRoutes.HOME)
        navController.navigateToDetails("42", ReiAnixRoutes.HOME)
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.HOME,
            navController.previousBackStackEntry?.destination?.route,
        )

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.HOME,
            navController.currentBackStackEntry?.destination?.route,
        )
    }

    @Test
    fun playerBackReturnsToDetailsWithStableIds() {
        navController.navigateToTopLevel(ReiAnixRoutes.LIBRARY)
        navController.navigateToDetails("42", ReiAnixRoutes.LIBRARY)
        navController.navigateToPlayer("episode-7", "42", ReiAnixRoutes.DETAILS_ORIGIN)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("episodeId=episode-7").assertExists()
        composeRule.onNodeWithText("animeId=42").assertExists()
        composeRule.onNodeWithText("Biblioteca").assertIsNotDisplayed()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.DETAILS,
            navController.currentBackStackEntry?.destination?.route,
        )
        composeRule.onNodeWithText("animeId=42").assertExists()
        composeRule.onNodeWithText("origin=" + ReiAnixRoutes.LIBRARY).assertExists()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        assertEquals(
            ReiAnixRoutes.LIBRARY,
            navController.currentBackStackEntry?.destination?.route,
        )
    }

    @Test
    fun encodedArgumentsRoundTripThroughNavBackStackEntry() {
        val animeId = "anime / 07 § especial"
        val episodeId = "episode/ 07?& especial"
        val origin = "details/search source"

        navController.navigateToDetails(animeId, origin)
        composeRule.waitForIdle()

        assertEquals(
            animeId,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID),
        )
        assertEquals(
            origin,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN),
        )

        navController.navigateToPlayer(episodeId, animeId, origin)
        composeRule.waitForIdle()

        assertEquals(
            episodeId,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_EPISODE_ID),
        )
        assertEquals(
            animeId,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID),
        )
        assertEquals(
            origin,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN),
        )
    }

    @Test
    fun blankNavigationArgumentsAreRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReiAnixRoutes.details("  ", ReiAnixRoutes.HOME)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReiAnixRoutes.player("episode-7", "  ", ReiAnixRoutes.DETAILS_ORIGIN)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ReiAnixRoutes.player("  ", "anime-7", ReiAnixRoutes.DETAILS_ORIGIN)
        }
    }

    @Test
    fun routeArgumentDefaultsUseSemanticOrigins() {
        navController.navigate("details/42")
        composeRule.waitForIdle()
        assertEquals(
            ReiAnixRoutes.HOME,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN),
        )

        composeRule.activity.onBackPressedDispatcher.onBackPressed()
        composeRule.waitForIdle()

        navController.navigate("player/episode-7?animeId=42")
        composeRule.waitForIdle()
        assertEquals(
            ReiAnixRoutes.DETAILS_ORIGIN,
            navController.currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN),
        )
    }

    @Test
    fun routeBuildersUseStableEncodedArguments() {
        assertEquals(
            "details/anime%2F42?origin=library",
            ReiAnixRoutes.details("anime/42", ReiAnixRoutes.LIBRARY),
        )
        assertEquals(
            "player/episode%2F7?animeId=anime%2F42&origin=details%2Fanime%2F42",
            ReiAnixRoutes.player(
                episodeId = "episode/7",
                animeId = "anime/42",
                origin = "details/anime/42",
            ),
        )
    }

    private fun clickTopLevel(label: String) {
        composeRule.onNodeWithText(label, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
    }

    @Composable
    private fun NavigationTestScreen(
        label: String,
        includeScroll: Boolean = false,
    ) {
        var counter by rememberSaveable { mutableIntStateOf(0) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        if (!includeScroll) {
            Column {
                Text(label + " counter=" + counter)
                Button(onClick = { counter += 1 }) {
                    Text("Increment " + label)
                }
            }
            return
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(label + " counter=" + counter)
            Button(onClick = { counter += 1 }) {
                Text("Increment " + label)
            }
            Button(
                onClick = {
                    scope.launch {
                        listState.scrollToItem(18)
                    }
                },
            ) {
                Text("Jump " + label + " to item 18")
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                state = listState,
            ) {
                items(30) { index ->
                    Text(
                        text = label + " item=" + index,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    )
                }
            }
        }
    }
}
