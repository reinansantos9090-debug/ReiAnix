package com.reiflix.reiflix_local.ui.navigation

import android.net.Uri
import androidx.annotation.Keep
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.reiflix.reiflix_local.ui.mylist.ReiAnixMyListRoute
import com.reiflix.reiflix_local.ui.player.ReiAnixPlayerRoute
import com.reiflix.reiflix_local.ui.search.ReiAnixSearchRoute
import com.reiflix.reiflix_local.ui.shell.ReiAnixAppShell
import com.reiflix.reiflix_local.ui.shell.ReiAnixBottomNavDestination
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel

@Keep
object ReiAnixRoutes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val MY_LIST = "my_list"
    const val SEARCH = "search"

    const val SETTINGS = "settings"
    const val STORAGE = "storage"
    const val DETAILS = "details/{animeId}?origin={origin}"
    const val PLAYER = "player/{episodeId}?animeId={animeId}&origin={origin}"

    const val ARG_ANIME_ID = "animeId"
    const val ARG_EPISODE_ID = "episodeId"
    const val ARG_ORIGIN = "origin"
    const val DETAILS_ORIGIN = "details"
    const val BOTTOM_NAV_CONTENT_DESCRIPTION = "ReiAnixBottomNavigation"

    private fun encode(value: String): String = Uri.encode(requireArgument(value))

    private fun requireArgument(value: String): String =
        value.trim().also {
            require(it.isNotEmpty()) {
                "Navigation arguments must not be blank"
            }
        }

    fun details(animeId: String, origin: String): String =
        "details/" + encode(animeId) + "?origin=" + encode(origin)

    fun player(episodeId: String, animeId: String, origin: String): String =
        "player/" + encode(episodeId) +
            "?animeId=" + encode(animeId) +
            "&origin=" + encode(origin)
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

private val topLevelDestinations = listOf(
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.HOME,
        label = "Início",
        selectedIcon = androidx.compose.material.icons.Icons.Filled.Home,
        unselectedIcon = androidx.compose.material.icons.Icons.Outlined.Home,
    ),
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.LIBRARY,
        label = "Biblioteca",
        selectedIcon = androidx.compose.material.icons.Icons.Filled.List,
        unselectedIcon = androidx.compose.material.icons.Icons.Outlined.List,
    ),
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.SEARCH,
        label = "Buscar",
        selectedIcon = androidx.compose.material.icons.Icons.Filled.Search,
        unselectedIcon = androidx.compose.material.icons.Icons.Outlined.Search,
    ),
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.SETTINGS,
        label = "Ajustes",
        selectedIcon = androidx.compose.material.icons.Icons.Filled.Settings,
        unselectedIcon = androidx.compose.material.icons.Icons.Outlined.Settings,
    ),
)

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
        navOptions { launchSingleTop = true },
    )
}

fun NavHostController.navigateToPlayer(
    episodeId: String,
    animeId: String,
    origin: String,
) {
    navigate(
        ReiAnixRoutes.player(episodeId, animeId, origin),
        navOptions { launchSingleTop = true },
    )
}

/**
 * Production entry point wired to the real local-library projections.
 * Settings and Storage remain injectable secondary surfaces so they can reuse
 * Activity-scoped ViewModels and the existing Android/Python boundaries.
 */
@Composable
fun ReiAnixNavigationHost(
    navController: NavHostController = rememberNavController(),
    homeViewModel: ReiAnixLibraryViewModel =
        com.reiflix.reiflix_local.ui.library.rememberReiAnixLibraryViewModel(),
    library: @Composable () -> Unit = {},
    myList: @Composable () -> Unit = {
        ReiAnixMyListRoute(
            navController = navController,
            viewModel = homeViewModel,
        )
    },
    search: @Composable () -> Unit = {},
    settings: @Composable () -> Unit = {},
    storage: @Composable () -> Unit = {},
    details: @Composable (ReiAnixDetailsArgs) -> Unit = {},
    player: @Composable (ReiAnixPlayerArgs) -> Unit = { args ->
        ReiAnixPlayerRoute(
            navController = navController,
            viewModel = homeViewModel,
            args = args,
        )
    },
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
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
        myList = myList,
        search = {
            ReiAnixSearchRoute(
                navController = navController,
                viewModel = homeViewModel,
            )
        },
        settings = settings,
        storage = storage,
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
    myList: @Composable () -> Unit = {},
    search: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    storage: @Composable () -> Unit = {},
    details: @Composable (ReiAnixDetailsArgs) -> Unit,
    player: @Composable (ReiAnixPlayerArgs) -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = ReiAnixRoutes.HOME,
    showBottomNavigation: Boolean = true,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    ReiAnixAppShell(
        modifier = modifier,
        currentRoute = currentRoute,
        bottomDestinations = topLevelDestinations,
        onBottomDestinationClick = navController::navigateToTopLevel,
        showBottomNavigation = showBottomNavigation,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(ReiAnixRoutes.HOME) { home() }
            composable(ReiAnixRoutes.LIBRARY) { library() }
            composable(ReiAnixRoutes.MY_LIST) { myList() }
            composable(ReiAnixRoutes.SEARCH) { search() }
            composable(ReiAnixRoutes.SETTINGS) { settings() }
            composable(ReiAnixRoutes.STORAGE) { storage() }

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
                val animeId = entry.arguments
                    ?.getString(ReiAnixRoutes.ARG_ANIME_ID)
                    ?.trim()
                    .orEmpty()
                val origin = entry.arguments
                    ?.getString(ReiAnixRoutes.ARG_ORIGIN)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { ReiAnixRoutes.HOME }

                details(ReiAnixDetailsArgs(animeId = animeId, origin = origin))
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
                val episodeId = entry.arguments
                    ?.getString(ReiAnixRoutes.ARG_EPISODE_ID)
                    ?.trim()
                    .orEmpty()
                val animeId = entry.arguments
                    ?.getString(ReiAnixRoutes.ARG_ANIME_ID)
                    ?.trim()
                    .orEmpty()
                val origin = entry.arguments
                    ?.getString(ReiAnixRoutes.ARG_ORIGIN)
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
