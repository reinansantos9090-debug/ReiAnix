package com.reiflix.reiflix_local.ui.host

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.MainActivity
import com.reiflix.reiflix_local.ReiAnixBootstrapPhase
import com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus
import com.reiflix.reiflix_local.R
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.navigation.ReiAnixNavigationHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToMyList
import com.reiflix.reiflix_local.ui.navigation.navigateToCollector
import com.reiflix.reiflix_local.ui.organize.ReiAnixOrganizeRoute
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsRoute
import com.reiflix.reiflix_local.ui.recovery.ReiAnixRecoveryRoute
import com.reiflix.reiflix_local.ui.storage.ReiAnixLibraryFolderOnboarding
import com.reiflix.reiflix_local.ui.storage.ReiAnixStorageRoute
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Single native Compose root used during the incremental Flet -> Compose cutover.
 *
 * The legacy class name is retained because MainActivity, packaging checks and
 * the existing bridge already depend on it. Runtime-wise this is now the app
 * shell host rather than a Library-only surface.
 */
class ReiAnixComposeLibraryHost(
    private val activity: MainActivity,
    private val libraryViewModel: ReiAnixLibraryViewModel,
    private val settingsViewModel: ReiAnixSettingsViewModel,
) {
    private var composeView: ComposeView? = null

    @Volatile
    private var composeNavController: NavHostController? = null
    private var lastHomeBackAtElapsedRealtimeMs: Long = 0L
    private var lastBackAtElapsedRealtimeMs: Long = 0L

    val isVisible: Boolean
        get() = composeView?.visibility == View.VISIBLE

    /**
     * Shows the single Compose shell. The first attach uses [startDestination];
     * later calls reuse the same ComposeView/NavHostController rather than
     * creating another root or another setContent tree.
     */
    fun show(
        startDestination: String = ReiAnixRoutes.LIBRARY,
        resetBackStack: Boolean = false,
    ) {
        val view = ensureAttached()
        if (view.tag != CONTENT_TAG) {
            view.tag = CONTENT_TAG
            view.setContent {
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            val appearanceCardSize by settingsViewModel.appearanceCardSize.collectAsStateWithLifecycle()
            val appearanceShowThumbnails by settingsViewModel.appearanceShowThumbnails.collectAsStateWithLifecycle()
            val libraryGridDensity by settingsViewModel.libraryGridDensity.collectAsStateWithLifecycle()
            val libraryPageSize by settingsViewModel.libraryPageSize.collectAsStateWithLifecycle()
            val librarySortDefault by settingsViewModel.librarySortDefault.collectAsStateWithLifecycle()
            val continueWatchingEnabled by settingsViewModel.continueWatchingEnabled.collectAsStateWithLifecycle()
            val continueWatchingLimit by settingsViewModel.continueWatchingLimit.collectAsStateWithLifecycle()
            val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
            val bootstrapState by libraryViewModel.bootstrapState.collectAsStateWithLifecycle()

            LaunchedEffect(
                bootstrapState.phase,
                bootstrapState.stage,
                bootstrapState.persistenceStatus,
            ) {
                if (bootstrapState.phase == ReiAnixBootstrapPhase.DEGRADED) {
                    android.util.Log.w(
                        TAG,
                        "APP_BOOTSTRAP_DEGRADED stage=${bootstrapState.stage} " +
                            "persistence=${bootstrapState.persistenceStatus} " +
                            "warningCount=${bootstrapState.warnings.size}",
                    )
                }
            }
            val nativeOnboarding by activity.nativeStorageOnboardingState.collectAsStateWithLifecycle()

            // A route mount must not cause remote reconciliation. Observe scan-state
            // transitions only; drop the first snapshot so a stored/idle state on process
            // startup never triggers AniList or artwork work.
            LaunchedEffect(libraryViewModel) {
                libraryViewModel.uiState
                    .map { it.scanInProgress }
                    .distinctUntilChanged()
                    .drop(1)
                    .filter { scanning -> !scanning }
                    .collectLatest {
                        if (libraryViewModel.uiState.value.animes.isEmpty()) return@collectLatest
                        val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
                        try {
                            withContext(Dispatchers.IO) {
                                dependencies.aniListIntegrationService.reconcileUnmatchedLibraryMetadata()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w(
                                TAG,
                                "Native AniList reconciliation after scan failed. errorType=" +
                                    error::class.java.simpleName,
                            )
                        }
                        runCatching { dependencies.artworkService.requestPendingArtwork() }
                            .onFailure { error ->
                                android.util.Log.w(
                                    TAG,
                                    "Native artwork backfill after scan failed. errorType=" +
                                        error::class.java.simpleName,
                                )
                            }
                    }
            }

            // Recover persisted poster/backdrop URLs on ordinary app opens too.
            // This path deliberately does not run title matching or issue AniList
            // metadata lookups; it only replays the canonical artwork queue.
            LaunchedEffect(
                libraryViewModel,
                bootstrapState.phase,
                bootstrapState.persistenceStatus,
                libraryState.animes.size,
            ) {
                if (
                    bootstrapState.phase != ReiAnixBootstrapPhase.READY ||
                    bootstrapState.persistenceStatus != ReiAnixBootstrapPersistenceStatus.HEALTHY ||
                    libraryState.animes.isEmpty()
                ) return@LaunchedEffect

                val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
                try {
                    withContext(Dispatchers.IO) {
                        dependencies.aniListIntegrationService.recoverMissingArtworkMetadata()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w(
                        TAG,
                        "Native missing-artwork metadata discovery failed. errorType=" +
                            error::class.java.simpleName,
                    )
                }
                runCatching { dependencies.artworkService.requestPendingArtwork() }
                    .onFailure { error ->
                        android.util.Log.w(
                            TAG,
                            "Native artwork startup backfill failed. errorType=" +
                                error::class.java.simpleName,
                        )
                    }
            }

            ReiAnixComposeRoot(
                themeMode = themeMode,
            ) {
                val navController = rememberNavController()
                    DisposableEffect(navController) {
                        composeNavController = navController
                        onDispose {
                            if (composeNavController === navController) {
                                composeNavController = null
                            }
                        }
                    }

                    val currentRoute by navController.currentBackStackEntryAsState()
                    LaunchedEffect(currentRoute?.destination?.route) {
                        val route = currentRoute?.destination?.route
                        if (route != ReiAnixRoutes.HOME && route != ReiAnixRoutes.RECOVERY) {
                            lastHomeBackAtElapsedRealtimeMs = 0L
                        }
                    }
                    val onboardingAllowed =
                        currentRoute?.destination?.route in setOf(
                            ReiAnixRoutes.HOME,
                            ReiAnixRoutes.LIBRARY,
                        )

                    Box(modifier = Modifier.fillMaxSize()) {
                    ReiAnixNavigationHost(
                        navController = navController,
                        homeViewModel = libraryViewModel,
                        myList = {
                            com.reiflix.reiflix_local.ui.mylist.ReiAnixMyListRoute(
                                navController = navController,
                                viewModel = libraryViewModel,
                                cardSize = appearanceCardSize,
                            )
                        },
                        organize = {
                            ReiAnixOrganizeRoute(
                                navController = navController,
                                viewModel = libraryViewModel,
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        handleBack()
                                    }
                                },
                                onOpenStorageAccess = {
                                    activity.requestNativeStorageAction("open_broad_storage_settings")
                                },
                                onRequestMediaAccess = {
                                    activity.requestNativeStorageAction("request_media_access")
                                },
                                onAddFolder = libraryViewModel::selectSafTree,
                                onRemoveFolder = libraryViewModel::removeSafTree,
                                onOpenSettings = {
                                    navController.navigate(
                                        ReiAnixRoutes.SETTINGS,
                                        navOptions {
                                            launchSingleTop = true
                                        },
                                    )
                                },
                            )
                        },
                        appearanceCardSize = appearanceCardSize,
                        appearanceShowThumbnails = appearanceShowThumbnails,
                        libraryGridDensity = libraryGridDensity,
                        libraryPageSize = libraryPageSize,
                        librarySortDefault = librarySortDefault,
                        continueWatchingEnabled = continueWatchingEnabled,
                        continueWatchingLimit = continueWatchingLimit,
                        settings = {
                            ReiAnixSettingsRoute(
                                viewModel = settingsViewModel,
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        handleBack()
                                    }
                                },
                                onOpenCategory = { label ->
                                    // Every known Settings category is now owned by
                                    // ReiAnixSettingsRoute. Keep this callback only as a
                                    // defensive boundary for an unexpected future label;
                                    // never leave the Compose Settings surface for a
                                    // legacy/Flet category view.
                                    android.util.Log.w(
                                        TAG,
                                        "Ignoring unsupported Compose Settings category=" + label,
                                    )
                                },
                                onOpenStorage = {
                                    navController.navigate(
                                        ReiAnixRoutes.STORAGE,
                                        navOptions {
                                            launchSingleTop = true
                                        },
                                    )
                                },
                                scanInProgress = libraryState.scanInProgress,
                                scanState = libraryState.scanState,
                                lastScanStatus = libraryState.lastScanStatus,
                            )
                        },
                        recovery = {
                            ReiAnixRecoveryRoute(
                                viewModel = settingsViewModel,
                                failureReason = bootstrapState.error,
                            )
                        },
                        storage = {
                            ReiAnixStorageRoute(
                                viewModel = libraryViewModel,
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        handleBack()
                                    }
                                },
                                onRemoveSaf = libraryViewModel::removeSafTree,
                                onRequestMediaAccess = {
                                    activity.requestNativeStorageAction("request_media_access")
                                },
                                onOpenBroadSettings = {
                                    activity.requestNativeStorageAction("open_broad_storage_settings")
                                },
                                onCheckAccess = {
                                    activity.requestNativeStorageAction("check_storage_access")
                                },
                            )
                        },
                        startDestination = startDestination,
                        showBottomNavigation = true,
                        onOpenCollector = {
                            navController.navigateToCollector()
                        },
                    )
                    if (onboardingAllowed && nativeOnboarding.isPromptVisible) {
                        ReiAnixLibraryFolderOnboarding(
                            state = nativeOnboarding.phase,
                            message = nativeOnboarding.message,
                            error = nativeOnboarding.error,
                            onCancel = activity::dismissNativeStorageOnboarding,
                            onSelectFolder = {
                                activity.requestNativeStorageAction("select_tree")
                            },
                        )
                    }
                }
                }
            }
        } else {
            navigateToRequestedDestination(
                route = startDestination,
                resetBackStack = resetBackStack,
            )
        }

        view.visibility = View.VISIBLE
    }

    fun hide() {
        composeView?.visibility = View.GONE
    }

    /**
     * Handles Back for the visible native Compose navigation graph.
     *
     * Navigation Compose owns all transitions while this shell is visible:
     * nested routes pop one entry, secondary roots return to an appropriate
     * in-app destination, and Home/Recovery use a single native exit policy.
     * The Flet bridge is not consulted to decide a Compose destination.
     */
    fun handleBack(): Boolean {
        val controller = composeNavController ?: return false
        val now = SystemClock.elapsedRealtime()
        val previousBack = lastBackAtElapsedRealtimeMs
        if (previousBack > 0L && now - previousBack < BACK_DEBOUNCE_WINDOW_MS) {
            android.util.Log.i(
                TAG,
                "NATIVE_BACK_DUPLICATE_SUPPRESSED deltaMs=" + (now - previousBack),
            )
            return true
        }
        lastBackAtElapsedRealtimeMs = now
        val route = controller.currentBackStackEntry?.destination?.route

        if (route != ReiAnixRoutes.HOME && route != ReiAnixRoutes.RECOVERY) {
            lastHomeBackAtElapsedRealtimeMs = 0L
        }

        if (controller.previousBackStackEntry != null) {
            return controller.popBackStack()
        }

        return when (route) {
            ReiAnixRoutes.HOME,
            ReiAnixRoutes.RECOVERY -> handleRootBack()

            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH,
            ReiAnixRoutes.SETTINGS,
            ReiAnixRoutes.MY_LIST,
            ReiAnixRoutes.ORGANIZE,
            ReiAnixRoutes.COLLECTOR -> {
                controller.navigateToTopLevel(ReiAnixRoutes.HOME)
                true
            }

            ReiAnixRoutes.STORAGE -> {
                controller.navigateToTopLevel(ReiAnixRoutes.SETTINGS)
                true
            }

            ReiAnixRoutes.DETAILS,
            ReiAnixRoutes.ANILIST_DETAILS,
            ReiAnixRoutes.PLAYER -> {
                android.util.Log.w(
                    TAG,
                    "Native Back had no origin for secondary route=" + route + "; returning Home",
                )
                controller.navigateToTopLevel(ReiAnixRoutes.HOME)
                true
            }

            else -> {
                android.util.Log.w(
                    TAG,
                    "Native Back received unknown route=" + (route ?: "null") + "; returning Home",
                )
                controller.navigateToTopLevel(ReiAnixRoutes.HOME)
                true
            }
        }
    }

    private fun handleRootBack(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = lastHomeBackAtElapsedRealtimeMs
        if (previous > 0L && now - previous <= HOME_DOUBLE_BACK_WINDOW_MS) {
            lastHomeBackAtElapsedRealtimeMs = 0L
            android.util.Log.i(TAG, "NATIVE_ROOT_BACK_EXIT")
            activity.finish()
        } else {
            lastHomeBackAtElapsedRealtimeMs = now
            Toast.makeText(
                activity,
                "Pressione voltar novamente para sair",
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }

    fun dispose() {
        composeView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        composeNavController = null
        composeView = null
    }

    private fun navigateToRequestedDestination(
        route: String,
        resetBackStack: Boolean,
    ) {
        val controller = composeNavController ?: return
        val destination = route.trim()

        // Validate before touching the existing stack. An unsupported request
        // must not remove the active screen and then fail during navigate().
        if (!ReiAnixRoutes.isSupportedShellDestination(destination)) {
            android.util.Log.w(
                TAG,
                "Ignoring unsupported Compose shell destination=" + route +
                    "; current back stack was preserved",
            )
            return
        }

        if (resetBackStack) {
            val startDestinationId = controller.graph.findStartDestination().id
            controller.popBackStack(startDestinationId, true)
            controller.navigate(
                destination,
                navOptions {
                    launchSingleTop = true
                },
            )
            return
        }

        when (destination) {
            ReiAnixRoutes.HOME,
            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH,
            -> controller.navigateToTopLevel(destination)

            ReiAnixRoutes.MY_LIST -> controller.navigateToMyList()

            ReiAnixRoutes.ORGANIZE,
            ReiAnixRoutes.SETTINGS,
            ReiAnixRoutes.STORAGE,
            -> controller.navigate(
                destination,
                navOptions {
                    launchSingleTop = true
                },
            )

            else -> {
                android.util.Log.w(
                    TAG,
                    "Ignoring unsupported Compose shell destination=" + route,
                )
            }
        }
    }

    private fun ensureAttached(): ComposeView {
        composeView?.let { existing ->
            if (existing.parent != null) return existing
        }

        val view = ComposeView(activity).apply {
            // ComponentActivity initializes LifecycleOwner, ViewModelStoreOwner and
            // SavedStateRegistryOwner on its content hierarchy through setContentView().
            // Installing this as the Activity content root keeps Compose and Navigation
            // attached to those owners on the very first launch (and after recreation).
            setId(R.id.reianix_compose_app_shell)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            elevation = 100f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            visibility = View.GONE
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
        }
        activity.setContentView(view)
        composeView = view
        return view
    }

    private companion object {
        const val CONTENT_TAG = "reianix_compose_app_shell_content"
        const val TAG = "[REIANIX][COMPOSE_SHELL]"
        const val BACK_DEBOUNCE_WINDOW_MS = 300L
        const val HOME_DOUBLE_BACK_WINDOW_MS = 2_000L
    }
}
