package com.reiflix.reiflix_local.ui.navigation

import android.net.Uri
import androidx.annotation.Keep
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import com.reiflix.reiflix_local.ui.details.ReiAnixDetailsRoute
import com.reiflix.reiflix_local.ui.home.ReiAnixHomeRoute
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryRoute
import com.reiflix.reiflix_local.ui.player.ReiAnixPlayerRoute
import com.reiflix.reiflix_local.ui.library.rememberReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.search.ReiAnixSearchRoute
import com.reiflix.reiflix_local.ui.theme.ReiAnixTokens
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

/**
 * Native Navigation Compose contract for ReiAnix.
 *
 * This is intentionally a navigation boundary rather than a replacement for
 * the existing Flet screens. MainActivity remains the current visual host
 * until a later migration step attaches the real screen slots.
 */
@Keep
object ReiAnixRoutes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    const val DETAILS = "details/{animeId}?origin={origin}"
    const val PLAYER = "player/{episodeId}?animeId={animeId}&origin={origin}"

    const val ARG_ANIME_ID = "animeId"
    const val ARG_EPISODE_ID = "episodeId"
    const val ARG_ORIGIN = "origin"
    const val DETAILS_ORIGIN = "details"
    const val BOTTOM_NAV_CONTENT_DESCRIPTION = "ReiAnixBottomNavigation"

    private fun encode(value: String): String =
        Uri.encode(requireArgument(value))

    private fun requireArgument(value: String): String =
        value.trim().also { require(it.isNotEmpty()) { "Navigation arguments must not be blank" } }

    fun details(animeId: String, origin: String): String =
        "details/" + encode(animeId) + "?origin=" + encode(origin)

    fun player(episodeId: String, animeId: String, origin: String): String =
        "player/" + encode(episodeId) + "?animeId=" + encode(animeId) + "&origin=" + encode(origin)
}

@Keep
data class ReiAnixDetailsArgs(
    val animeId: String,
    val origin: String,
)

@Keep
data class ReiAnixPlayerArgs(
    val episodeId: String,
    val animeId: String,
    val origin: String,
)

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(ReiAnixRoutes.HOME, "Início", Icons.Filled.Home),
    TopLevelDestination(ReiAnixRoutes.LIBRARY, "Biblioteca", Icons.AutoMirrored.Filled.List),
    TopLevelDestination(ReiAnixRoutes.SEARCH, "Buscar", Icons.Filled.Search),
    TopLevelDestination(ReiAnixRoutes.SETTINGS, "Ajustes", Icons.Filled.Settings),
)

/**
 * Navigate between main tabs without accumulating duplicate copies and while
 * asking Navigation Compose to retain/save each destination UI state.
 */
fun NavHostController.navigateToTopLevel(route: String) {
    require(topLevelDestinations.any { it.route == route }) {
        "Unknown ReiAnix top-level route: " + route
    }

    navigate(
        route,
        navOptions {
            launchSingleTop = true
            restoreState = true
            popUpTo(graph.findStartDestination().id) {
                saveState = true
            }
        },
    )
}

fun NavHostController.navigateToDetails(
    animeId: String,
    origin: String,
) {
    navigate(
        ReiAnixRoutes.details(animeId, origin),
        navOptions {
            launchSingleTop = true
        },
    )
}

fun NavHostController.navigateToPlayer(
    episodeId: String,
    animeId: String,
    origin: String,
) {
    navigate(
        ReiAnixRoutes.player(episodeId, animeId, origin),
        navOptions {
            launchSingleTop = true
        },
    )
}

/**
 * Native route graph with the real ReiAnix navigation surfaces:
 * Home, Library, Search, Settings, Details and Player.
 *
 * Screen implementations are injected as slots so this layer does not invent
 * domain data or duplicate SQLite/scanner/player business rules.
 */
/**
 * Production Compose entry point for the incremental migration.
 *
 * This overload wires the Home and Library routes to the same real library
 * ViewModel. Other surfaces remain injectable so the existing Flet host can
 * remain untouched until their own migration steps are explicitly authorized.
 */
@Composable
fun ReiAnixNavigationHost(
    navController: NavHostController = rememberNavController(),
    homeViewModel: ReiAnixLibraryViewModel = rememberReiAnixLibraryViewModel(),
    library: @Composable () -> Unit = {},
    search: @Composable () -> Unit = {},
    settings: @Composable () -> Unit = {},
    details: @Composable (ReiAnixDetailsArgs) -> Unit = {},
    player: @Composable (ReiAnixPlayerArgs) -> Unit = { args ->
        ReiAnixPlayerRoute(
            navController = navController,
            viewModel = homeViewModel,
            args = args,
        )
    },
    modifier: Modifier = Modifier,
    startDestination: String = ReiAnixRoutes.HOME,
    showBottomNavigation: Boolean = true,
) {
    ReiAnixNavigationHost(
        home = {
            ReiAnixHomeRoute(
                navController = navController,
                viewModel = homeViewModel,
            )
        },
        library = {
            ReiAnixLibraryRoute(
                navController = navController,
                viewModel = homeViewModel,
            )
        },
        search = {
            ReiAnixSearchRoute(
                navController = navController,
                viewModel = homeViewModel,
            )
        },
        settings = settings,
        details = { args ->
            ReiAnixDetailsRoute(
                navController = navController,
                viewModel = homeViewModel,
                animeId = args.animeId,
                origin = args.origin,
            )
        },
        player = player,
        modifier = modifier,
        navController = navController,
        startDestination = startDestination,
        showBottomNavigation = showBottomNavigation,
    )
}

@Composable
fun ReiAnixNavigationHost(
    home: @Composable () -> Unit,
    library: @Composable () -> Unit,
    search: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    details: @Composable (ReiAnixDetailsArgs) -> Unit,
    player: @Composable (ReiAnixPlayerArgs) -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = ReiAnixRoutes.HOME,
    showBottomNavigation: Boolean = true,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.safeDrawing,
        bottomBar = {
            if (showBottomNavigation && topLevelDestinations.any { it.route == currentRoute }) {
                NavigationBar(
                    modifier = Modifier.semantics {
                        contentDescription = ReiAnixRoutes.BOTTOM_NAV_CONTENT_DESCRIPTION
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ReiAnixTokens.Colors.primary,
                                selectedTextColor = ReiAnixTokens.Colors.primary,
                                unselectedIconColor = ReiAnixTokens.Colors.textMuted,
                                unselectedTextColor = ReiAnixTokens.Colors.textMuted,
                            ),
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(ReiAnixRoutes.HOME) {
                home()
            }
            composable(ReiAnixRoutes.LIBRARY) {
                library()
            }
            composable(ReiAnixRoutes.SEARCH) {
                search()
            }
            composable(ReiAnixRoutes.SETTINGS) {
                settings()
            }
            composable(
                route = ReiAnixRoutes.DETAILS,
                arguments = listOf(
                    navArgument(ReiAnixRoutes.ARG_ANIME_ID) {
                        type = NavType.StringType
                    },
                    navArgument(ReiAnixRoutes.ARG_ORIGIN) {
                        type = NavType.StringType
                        defaultValue = ReiAnixRoutes.HOME
                    },
                ),
            ) { entry ->
                val animeId = entry.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID)
                    ?.trim()
                    .orEmpty()
                val origin = entry.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { ReiAnixRoutes.HOME }

                details(
                    ReiAnixDetailsArgs(
                        animeId = animeId,
                        origin = origin,
                    ),
                )
            }
            composable(
                route = ReiAnixRoutes.PLAYER,
                arguments = listOf(
                    navArgument(ReiAnixRoutes.ARG_EPISODE_ID) {
                        type = NavType.StringType
                    },
                    navArgument(ReiAnixRoutes.ARG_ANIME_ID) {
                        type = NavType.StringType
                    },
                    navArgument(ReiAnixRoutes.ARG_ORIGIN) {
                        type = NavType.StringType
                        defaultValue = ReiAnixRoutes.DETAILS_ORIGIN
                    },
                ),
            ) { entry ->
                val episodeId = entry.arguments?.getString(ReiAnixRoutes.ARG_EPISODE_ID)
                    ?.trim()
                    .orEmpty()
                val animeId = entry.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID)
                    ?.trim()
                    .orEmpty()
                val origin = entry.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { ReiAnixRoutes.DETAILS_ORIGIN }

                player(
                    ReiAnixPlayerArgs(
                        episodeId = episodeId,
                        animeId = animeId,
                        origin = origin,
                    ),
                )
            }
        }
    }
}
