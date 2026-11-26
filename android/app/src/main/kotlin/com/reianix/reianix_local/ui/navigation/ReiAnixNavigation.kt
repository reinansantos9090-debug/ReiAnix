package com.reianix.reianix_local.ui.navigation

import android.net.Uri
import androidx.annotation.Keep
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home as OutlinedHome
import androidx.compose.material.icons.outlined.List as OutlinedList
import androidx.compose.material.icons.outlined.Search as OutlinedSearch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import com.reianix.reianix_local.ui.details.ReiAnixDetailsRoute
import com.reianix.reianix_local.ui.details.ReiAnixAniListDetailsRoute
import com.reianix.reianix_local.ui.collector.ReiAnixCollectorJourneyRoute
import com.reianix.reianix_local.ui.collections.ReiAnixCollectionsRoute
import com.reianix.reianix_local.ui.discovery.ReiAnixTimelineHubRoute
import com.reianix.reianix_local.ui.home.ReiAnixHomeRoute
import com.reianix.reianix_local.ui.profile.ReiAnixProfileRoute
import com.reianix.reianix_local.ui.theme.ReiAnixWallpaperScope
import com.reianix.reianix_local.ui.library.ReiAnixLibraryRoute
import com.reianix.reianix_local.ui.motion.reiAnixScreenEnterTransition
import com.reianix.reianix_local.ui.motion.reiAnixScreenExitTransition
import com.reianix.reianix_local.ui.motion.reiAnixDetailsEnterTransition
import com.reianix.reianix_local.ui.motion.reiAnixDetailsExitTransition
import com.reianix.reianix_local.ui.motion.reiAnixDetailsPopEnterTransition
import com.reianix.reianix_local.ui.motion.reiAnixDetailsPopExitTransition
import com.reianix.reianix_local.ui.motion.reiAnixScreenPopEnterTransition
import com.reianix.reianix_local.ui.motion.reiAnixScreenPopExitTransition
import com.reianix.reianix_local.ui.motion.ReiAnixMotionPolicy
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeOut
import com.reianix.reianix_local.ui.mylist.ReiAnixMyListRoute
import com.reianix.reianix_local.ui.player.ReiAnixPlayerRoute
import com.reianix.reianix_local.ui.search.ReiAnixSearchRoute
import com.reianix.reianix_local.ui.shell.ReiAnixAppShell
import com.reianix.reianix_local.ui.shell.ReiAnixBottomNavDestination
import com.reianix.reianix_local.viewmodel.ReiAnixLibraryViewModel

@Keep
object ReiAnixRoutes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val MY_LIST = "my_list"
    const val COLLECTIONS = "collections"
    const val SEARCH = "search"

    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings_general"
    const val PROFILE = "profile"
    const val STORAGE = "storage"
    const val ORGANIZE = "organize"
    const val RECOVERY = "recovery"
    const val COLLECTOR = "collector"
    const val TIMELINE = "timeline/{section}"
    const val ARG_SECTION = "section"
    const val DETAILS = "details/{animeId}?origin={origin}"
    const val ANILIST_DETAILS = "anilist_details/{anilistId}?origin={origin}"
    const val PLAYER = "player/{episodeId}?animeId={animeId}&origin={origin}"

    const val ARG_ANIME_ID = "animeId"
    const val ARG_ANILIST_ID = "anilistId"
    const val ARG_EPISODE_ID = "episodeId"
    const val ARG_ORIGIN = "origin"
    const val DETAILS_ORIGIN = "details"
    const val BOTTOM_NAV_CONTENT_DESCRIPTION = "ReiAnixBottomNavigation"

    /**
     * Only destinations that can be opened without route arguments are valid
     * shell/start destinations. Details and Player require validated arguments
     * and must be opened through their route builders.
     */
    fun isSupportedShellDestination(route: String): Boolean {
        val normalized = route.trim()
        return topLevelDestinations.any { it.route == normalized } ||
            normalized == MY_LIST ||
            normalized == COLLECTIONS ||
            normalized == STORAGE ||
            normalized == ORGANIZE ||
            normalized == RECOVERY ||
            normalized == COLLECTOR
    }

    /**
     * Prevent a stale/invalid external request from making NavHost fail while
     * constructing its graph. Callers log when normalization was necessary.
     */
    fun normalizeStartDestination(route: String): String =
        route.trim().takeIf(::isSupportedShellDestination) ?: HOME

    private fun encode(value: String): String = Uri.encode(requireArgument(value))

    private fun requireArgument(value: String): String =
        value.trim().also {
            require(it.isNotEmpty()) {
                "Navigation arguments must not be blank"
            }
        }

    /** Strictly scoped deep link; the Timeline owns tab switching in place. */
    fun timeline(section: String = "animes"): String {
        require(section in setOf("animes", "roleta", "colecionador")) {
            "Seção da linha do tempo inválida."
        }
        return "timeline/" + section
    }

    fun details(animeId: String, origin: String): String =
        "details/" + encode(animeId) + "?origin=" + encode(origin)

    fun aniListDetails(anilistId: Int, origin: String): String {
        require(anilistId > 0) { "AniList ID must be positive" }
        return "anilist_details/" + anilistId + "?origin=" + encode(origin)
    }

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
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.OutlinedHome,
    ),
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.LIBRARY,
        label = "Biblioteca",
        selectedIcon = Icons.Filled.List,
        unselectedIcon = Icons.Outlined.OutlinedList,
    ),
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.SEARCH,
        label = "Buscar",
        selectedIcon = Icons.Filled.Search,
        unselectedIcon = Icons.Outlined.OutlinedSearch,
    ),
    ReiAnixBottomNavDestination(
        route = ReiAnixRoutes.SETTINGS,
        label = "Ajustes",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Filled.Settings,
    ),
)

internal fun shouldNavigateToTopLevel(currentRoute: String?, requestedRoute: String): Boolean =
    currentRoute != requestedRoute

fun NavHostController.navigateToTopLevel(route: String) {
    if (topLevelDestinations.none { it.route == route }) {
        android.util.Log.w("ReiAnixNavigation", "Ignoring unknown top-level route=" + route)
        return
    }
    // Do not animate or re-enter the already-active tab. Its saved sub-stack
    // and LazyListState stay untouched; another tab still uses save/restore.
    if (!shouldNavigateToTopLevel(currentDestination?.route, route)) return
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

fun NavHostController.navigateToCollector() {
    navigate(
        ReiAnixRoutes.COLLECTOR,
        navOptions { launchSingleTop = true },
    )
}

fun NavHostController.navigateToOrganize() {
    navigate(
        ReiAnixRoutes.ORGANIZE,
        navOptions { launchSingleTop = true },
    )
}

fun NavHostController.navigateToMyList() {
    // Minha Lista is intentionally a secondary destination (the visual source
    // screens expose it from Home/library actions rather than the bottom bar).
    // Restore any state previously saved when another top-level destination was
    // selected, while keeping repeated taps from stacking another My List entry.
    navigate(
        ReiAnixRoutes.MY_LIST,
        navOptions {
            launchSingleTop = true
            restoreState = true
        },
    )
}

/**
 * Detail pages are individual history entries even though they use the same
 * destination pattern. launchSingleTop would replace the previous anime when
 * opening a recommendation, breaking A -> B -> C -> Back -> B -> A.
 */
internal fun shouldPushAnimeDetails(
    currentRoute: String?,
    currentAnimeId: String?,
    requestedAnimeId: String,
): Boolean = currentRoute != ReiAnixRoutes.DETAILS ||
    currentAnimeId?.trim() != requestedAnimeId.trim()

fun NavHostController.navigateToDetails(
    animeId: String,
    origin: String,
) {
    // Ignore a repeated tap on the same *active* anime; otherwise always
    // push a new entry so each detail screen retains scroll/selected season.
    if (!shouldPushAnimeDetails(
            currentRoute = currentDestination?.route,
            currentAnimeId = currentBackStackEntry?.arguments?.getString(ReiAnixRoutes.ARG_ANIME_ID),
            requestedAnimeId = animeId,
        )
    ) return

    navigate(ReiAnixRoutes.details(animeId, origin))
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
 * Details (including AniList) consume the full available viewport instead of
 * reserving the bottom bar's height. Primary tabs keep their original chrome.
 */
internal fun shouldShowReiAnixBottomNavigation(
    route: String?,
    selectedBottomRoute: String?,
    enabled: Boolean,
): Boolean = enabled &&
    selectedBottomRoute != null &&
    route != ReiAnixRoutes.STORAGE &&
    route != ReiAnixRoutes.TIMELINE &&
    route != ReiAnixRoutes.DETAILS &&
    route != ReiAnixRoutes.ANILIST_DETAILS

/**
 * Production entry point wired to the canonical local-library projections.
 * Settings and Storage remain injectable Compose destinations so callers can
 * reuse Activity-scoped ViewModels and native storage capabilities.
 */
@Composable
fun ReiAnixNavigationHost(
    navController: NavHostController = rememberNavController(),
    homeViewModel: ReiAnixLibraryViewModel =
        com.reianix.reianix_local.ui.library.rememberReiAnixLibraryViewModel(),
    library: @Composable () -> Unit = {},
    myList: @Composable () -> Unit = {
        ReiAnixMyListRoute(
            navController = navController,
            viewModel = homeViewModel,
            cardSize = "medium",
        )
    },
    organize: @Composable () -> Unit = {},
    collector: @Composable () -> Unit = {
        ReiAnixCollectorJourneyRoute(
            navController = navController,
            libraryViewModel = homeViewModel,
        )
    },
    search: @Composable () -> Unit = {},
    settings: @Composable () -> Unit = {},
    storage: @Composable () -> Unit = {},
    recovery: @Composable () -> Unit = {},
    details: @Composable (ReiAnixDetailsArgs) -> Unit = {},
    appearanceCardSize: String = "medium",
    appearanceShowThumbnails: Boolean = true,
    libraryPageSize: Int = 0,
    librarySortDefault: String = "added_desc",
    continueWatchingEnabled: Boolean = true,
    continueWatchingLimit: Int = 10,
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
    onOpenCollector: () -> Unit = {},
    settingsGeneral: @Composable () -> Unit = settings,
) {
    ReiAnixNavigationHost(
        home = {
            ReiAnixHomeRoute(
                navController = navController,
                viewModel = homeViewModel,
                cardSize = appearanceCardSize,
                showThumbnails = appearanceShowThumbnails,
                continueWatchingEnabled = continueWatchingEnabled,
                continueWatchingLimit = continueWatchingLimit,
                onOpenCollector = onOpenCollector,
            )
        },
        library = {
            ReiAnixLibraryRoute(
                navController = navController,
                viewModel = homeViewModel,
                cardSize = appearanceCardSize,
                pageSize = libraryPageSize,
                defaultSortKey = librarySortDefault,
            )
        },
        myList = myList,
        collections = {
            ReiAnixCollectionsRoute(
                navController = navController,
                viewModel = homeViewModel,
            )
        },
        organize = organize,
        collector = collector,
        timeline = { section ->
            ReiAnixTimelineHubRoute(
                navController = navController,
                viewModel = homeViewModel,
                initialSection = section,
            )
        },
        search = {
            ReiAnixSearchRoute(
                navController = navController,
                viewModel = homeViewModel,
                cardSize = appearanceCardSize,
            )
        },
        appearanceCardSize = appearanceCardSize,
        appearanceShowThumbnails = appearanceShowThumbnails,
        settings = settings,
        settingsGeneral = settingsGeneral,
        profile = { ReiAnixProfileRoute(navController, homeViewModel) },
        storage = storage,
        recovery = recovery,
        details = { args ->
            ReiAnixDetailsRoute(
                navController = navController,
                viewModel = homeViewModel,
                animeId = args.animeId,
                origin = args.origin,
                showThumbnails = appearanceShowThumbnails,
            )
        },
        player = player,
        modifier = modifier,
        navController = navController,
        startDestination = startDestination,
        showBottomNavigation = showBottomNavigation,
        onOpenCollector = onOpenCollector,
    )
}

@Composable
fun ReiAnixNavigationHost(
    home: @Composable () -> Unit,
    library: @Composable () -> Unit,
    myList: @Composable () -> Unit = {},
    collections: @Composable () -> Unit = {},
    organize: @Composable () -> Unit = {},
    collector: @Composable () -> Unit = {},
    timeline: @Composable (String) -> Unit = {},
    search: @Composable () -> Unit,
    settings: @Composable () -> Unit,
    storage: @Composable () -> Unit = {},
    recovery: @Composable () -> Unit = {},
    details: @Composable (ReiAnixDetailsArgs) -> Unit,
    player: @Composable (ReiAnixPlayerArgs) -> Unit,
    appearanceCardSize: String = "medium",
    appearanceShowThumbnails: Boolean = true,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = ReiAnixRoutes.HOME,
    showBottomNavigation: Boolean = true,
    onOpenCollector: () -> Unit = {},
    settingsGeneral: @Composable () -> Unit = settings,
    profile: @Composable () -> Unit = {},
) {
    val effectiveStartDestination = ReiAnixRoutes.normalizeStartDestination(startDestination)
    LaunchedEffect(startDestination, effectiveStartDestination) {
        if (startDestination != effectiveStartDestination) {
            android.util.Log.w(
                "ReiAnixNavigation",
                "Unsupported start destination=" + startDestination +
                    "; using " + effectiveStartDestination,
            )
        }
    }

    val entryState = navController.currentBackStackEntryAsState()
    // Derived route/origin values do not invalidate the shell when the user
    // follows an anime recommendation to another entry on the SAME route.
    // NavHost itself still observes every back-stack entry normally.
    val currentRoute by remember(entryState) {
        derivedStateOf { entryState.value?.destination?.route }
    }
    val detailOrigin by remember(entryState) {
        derivedStateOf {
            entryState.value?.arguments?.getString(ReiAnixRoutes.ARG_ORIGIN)
                ?.trim().orEmpty()
        }
    }
    ReiAnixNavigationJankState(currentRoute)
    val selectedBottomRoute = when {
        currentRoute == ReiAnixRoutes.MY_LIST -> ReiAnixRoutes.LIBRARY
        currentRoute == ReiAnixRoutes.TIMELINE -> ReiAnixRoutes.HOME
        currentRoute == ReiAnixRoutes.DETAILS &&
            detailOrigin == ReiAnixRoutes.MY_LIST -> ReiAnixRoutes.LIBRARY
        currentRoute == ReiAnixRoutes.DETAILS &&
            topLevelDestinations.any { it.route == detailOrigin } -> detailOrigin
        currentRoute == ReiAnixRoutes.ANILIST_DETAILS &&
            topLevelDestinations.any { it.route == detailOrigin } -> detailOrigin
        topLevelDestinations.any { it.route == currentRoute } -> currentRoute
        else -> null
    }
    val shouldShowBottomNavigation = shouldShowReiAnixBottomNavigation(
        route = currentRoute,
        selectedBottomRoute = selectedBottomRoute,
        enabled = showBottomNavigation,
    )

    ReiAnixWallpaperScope(enabled = currentRoute != ReiAnixRoutes.PLAYER && currentRoute != ReiAnixRoutes.RECOVERY) {
    ReiAnixAppShell(
        modifier = modifier,
        selectedRoute = selectedBottomRoute,
        bottomDestinations = topLevelDestinations,
        onBottomDestinationClick = navController::navigateToTopLevel,
        showBottomNavigation = shouldShowBottomNavigation,
        navigationContent = { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = effectiveStartDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            enterTransition = {
                when (reiAnixRouteAnimation(
                    initialState.destination.route, targetState.destination.route,
                    initialState.id == targetState.id,
                    ReiAnixMotionPolicy.systemAnimationsEnabled(),
                )) {
                    ReiAnixRouteAnimation.NONE -> EnterTransition.None
                    ReiAnixRouteAnimation.DETAILS -> reiAnixDetailsEnterTransition()
                    ReiAnixRouteAnimation.STANDARD -> reiAnixScreenEnterTransition()
                }
            },
            exitTransition = exit@{
                val fromRoute = initialState.destination.route
                val fromBottomDestination = fromRoute == ReiAnixRoutes.MY_LIST ||
                    topLevelDestinations.any { it.route == fromRoute }
                if (targetState.destination.route == ReiAnixRoutes.DETAILS &&
                    showBottomNavigation && fromBottomDestination
                ) {
                    // The shared viewport grows when the bottom bar disappears.
                    // Don't draw the outgoing screen at that new height behind Details.
                    return@exit fadeOut(animationSpec = snap())
                }
                when (reiAnixRouteAnimation(
                    initialState.destination.route, targetState.destination.route,
                    initialState.id == targetState.id,
                    ReiAnixMotionPolicy.systemAnimationsEnabled(),
                )) {
                    ReiAnixRouteAnimation.NONE -> ExitTransition.None
                    ReiAnixRouteAnimation.DETAILS -> reiAnixDetailsExitTransition()
                    ReiAnixRouteAnimation.STANDARD -> reiAnixScreenExitTransition()
                }
            },
            popEnterTransition = {
                when (reiAnixRouteAnimation(
                    initialState.destination.route, targetState.destination.route,
                    initialState.id == targetState.id,
                    ReiAnixMotionPolicy.systemAnimationsEnabled(),
                )) {
                    ReiAnixRouteAnimation.NONE -> EnterTransition.None
                    ReiAnixRouteAnimation.DETAILS -> reiAnixDetailsPopEnterTransition()
                    ReiAnixRouteAnimation.STANDARD -> reiAnixScreenPopEnterTransition()
                }
            },
            popExitTransition = {
                when (reiAnixRouteAnimation(
                    initialState.destination.route, targetState.destination.route,
                    initialState.id == targetState.id,
                    ReiAnixMotionPolicy.systemAnimationsEnabled(),
                )) {
                    ReiAnixRouteAnimation.NONE -> ExitTransition.None
                    ReiAnixRouteAnimation.DETAILS -> reiAnixDetailsPopExitTransition()
                    ReiAnixRouteAnimation.STANDARD -> reiAnixScreenPopExitTransition()
                }
            },
        ) {
            reiAnixGuardedComposable(navController, ReiAnixRoutes.HOME) { home() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.LIBRARY) { library() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.MY_LIST) { myList() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.COLLECTIONS) { collections() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.ORGANIZE) { organize() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.SEARCH) { search() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.SETTINGS) { settings() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.SETTINGS_GENERAL) { settingsGeneral() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.PROFILE) { profile() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.STORAGE) { storage() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.RECOVERY) { recovery() }
            reiAnixGuardedComposable(navController, ReiAnixRoutes.COLLECTOR) { collector() }
            reiAnixGuardedComposable(navController, 
                route = ReiAnixRoutes.TIMELINE,
                arguments = listOf(
                    navArgument(ReiAnixRoutes.ARG_SECTION) {
                        type = NavType.StringType
                        defaultValue = "animes"
                    },
                ),
            ) { entry ->
                timeline(entry.arguments?.getString(ReiAnixRoutes.ARG_SECTION) ?: "animes")
            }

            reiAnixGuardedComposable(navController, 
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

            reiAnixGuardedComposable(navController, 
                route = ReiAnixRoutes.ANILIST_DETAILS,
                arguments = listOf(
                    navArgument(ReiAnixRoutes.ARG_ANILIST_ID) {
                        type = NavType.IntType
                    },
                    navArgument(ReiAnixRoutes.ARG_ORIGIN) {
                        type = NavType.StringType
                        defaultValue = ReiAnixRoutes.SEARCH
                    },
                ),
            ) { entry ->
                val anilistId = entry.arguments?.getInt(ReiAnixRoutes.ARG_ANILIST_ID) ?: 0
                val origin = entry.arguments
                    ?.getString(ReiAnixRoutes.ARG_ORIGIN)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { ReiAnixRoutes.SEARCH }
                ReiAnixAniListDetailsRoute(
                    navController = navController,
                    anilistId = anilistId,
                    origin = origin,
                )
            }

            reiAnixGuardedComposable(navController, 
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
    )
    }
}
