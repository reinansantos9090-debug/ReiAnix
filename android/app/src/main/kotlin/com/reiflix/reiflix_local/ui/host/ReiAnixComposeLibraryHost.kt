package com.reiflix.reiflix_local.ui.host

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
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
import com.reiflix.reiflix_local.R
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.navigation.ReiAnixNavigationHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToMyList
import com.reiflix.reiflix_local.ui.organize.ReiAnixOrganizeRoute
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsRoute
import com.reiflix.reiflix_local.ui.storage.ReiAnixLibraryFolderOnboarding
import com.reiflix.reiflix_local.ui.storage.ReiAnixStorageRoute
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import org.json.JSONObject

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

    /**
     * Timestamp of the most recent Back press received at the Compose shell's
     * Home root. Only this single shell-level boundary owns the double-back
     * exit contract.
     */
    private var lastHomeBackAtElapsedRealtimeMs: Long = 0L

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
            val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()

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
                            )
                        },
                        organize = {
                            ReiAnixOrganizeRoute(
                                navController = navController,
                                viewModel = libraryViewModel,
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        hideAndPublishLegacyBack()
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
                        settings = {
                            ReiAnixSettingsRoute(
                                viewModel = settingsViewModel,
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        hideAndPublishSettingsBack()
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
                            )
                        },
                        storage = {
                            ReiAnixStorageRoute(
                                viewModel = libraryViewModel,
                                onBack = {
                                    if (!navController.popBackStack()) {
                                        hide()
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
                        onRouteChanged = ::publishComposeRouteChanged,
                        onOpenCollector = {
                            publishLegacyNavigation("collector")
                        },
                    )
                    val onboardingState = libraryState.storage.onboardingState.trim().lowercase()
                    if (
                        onboardingAllowed &&
                        onboardingState in setOf("checking", "needs_folder", "folder_picker_open", "error")
                    ) {
                        ReiAnixLibraryFolderOnboarding(
                            state = onboardingState,
                            message = libraryState.storage.onboardingMessage,
                            error = libraryState.storage.onboardingError,
                            onSelectFolder = libraryViewModel::selectSafTree,
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
     * Back is consumed by the Compose stack first. At a root destination the
     * existing logical navigation authority receives the legacy back contract.
     * The separate Settings/Storage host classes are not used as visual siblings.
     */
    fun handleBack(): Boolean {
        val controller = composeNavController ?: return false
        if (controller.previousBackStackEntry != null) {
            lastHomeBackAtElapsedRealtimeMs = 0L
            return controller.popBackStack()
        }

        val route = controller.currentBackStackEntry?.destination?.route
        return when (route) {
            ReiAnixRoutes.HOME -> handleHomeBack()
            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH,
            ReiAnixRoutes.SETTINGS,
            ReiAnixRoutes.MY_LIST,
            ReiAnixRoutes.ORGANIZE,
            -> {
                // Keep root Back inside the single Compose navigation owner.
                lastHomeBackAtElapsedRealtimeMs = 0L
                controller.navigateToTopLevel(ReiAnixRoutes.HOME)
                true
            }
            ReiAnixRoutes.STORAGE -> {
                lastHomeBackAtElapsedRealtimeMs = 0L
                controller.navigateToTopLevel(ReiAnixRoutes.SETTINGS)
                true
            }
            else -> {
                lastHomeBackAtElapsedRealtimeMs = 0L
                hideAndPublishLegacyBack()
                true
            }
        }
    }

    private fun handleHomeBack(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val withinDoubleBackWindow =
            lastHomeBackAtElapsedRealtimeMs > 0L &&
                now - lastHomeBackAtElapsedRealtimeMs <= HOME_DOUBLE_BACK_WINDOW_MS

        if (withinDoubleBackWindow) {
            lastHomeBackAtElapsedRealtimeMs = 0L
            activity.finishAndRemoveTask()
            return true
        }

        lastHomeBackAtElapsedRealtimeMs = now
        Toast.makeText(
            activity,
            "Pressione voltar novamente para sair",
            Toast.LENGTH_SHORT,
        ).show()
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

        if (resetBackStack) {
            val startDestinationId = controller.graph.findStartDestination().id
            controller.popBackStack(startDestinationId, true)
            controller.navigate(
                route,
                navOptions {
                    launchSingleTop = true
                },
            )
            return
        }

        when (route) {
            ReiAnixRoutes.HOME,
            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH,
            -> controller.navigateToTopLevel(route)

            ReiAnixRoutes.MY_LIST -> controller.navigateToMyList()

            ReiAnixRoutes.ORGANIZE,
            ReiAnixRoutes.SETTINGS,
            ReiAnixRoutes.STORAGE,
            -> controller.navigate(
                route,
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

    /**
     * Compose is the visible navigation owner on Android. Publish only the
     * stable route identity so Python can keep its existing lifecycle/domain
     * navigation projection synchronized without rendering a second UI tree.
     */
    private fun publishLegacyNavigation(destination: String) {
        NativeMailbox.writeBestEffort(
            activity,
            JSONObject()
                .put("type", "compose_library_navigation")
                .put("payload", JSONObject().put("destination", destination)),
        )
    }

    private fun publishComposeRouteChanged(
        route: String,
        animeId: String?,
        episodeId: String?,
        origin: String?,
    ) {
        // A first Back on Home is only valid while the user remains on Home.
        // Any logical route transition invalidates the pending double-back window.
        if (route != ReiAnixRoutes.HOME) {
            lastHomeBackAtElapsedRealtimeMs = 0L
        }
        val payload = JSONObject()
            .put("route", route)
            .put("animeId", animeId ?: "")
            .put("episodeId", episodeId ?: "")
            .put("origin", origin ?: "")
        NativeMailbox.writeBestEffort(
            activity,
            JSONObject()
                .put("type", "compose_navigation_changed")
                .put("payload", payload),
        )
    }

    private fun hideAndPublishLegacyBack() {
        hide()
        NativeMailbox.writeBestEffort(
            activity,
            JSONObject()
                .put("type", "compose_library_navigation")
                .put("payload", JSONObject().put("destination", "back")),
        )
    }

    private fun hideAndPublishSettingsBack() {
        hide()
        publishSettingsNavigation("back", null)
    }

    private fun publishSettingsNavigation(
        destination: String,
        category: String? = null,
    ) {
        val payload = JSONObject()
            .put("destination", destination)
            .put("category", category ?: "")
        NativeMailbox.writeBestEffort(
            activity,
            JSONObject()
                .put("type", "compose_settings_navigation")
                .put("payload", payload),
        )
    }

    private fun ensureAttached(): ComposeView {
        composeView?.let { existing ->
            if (existing.parent != null) return existing
        }

        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: error("MainActivity content root is unavailable")

        val view = ComposeView(activity).apply {
            // Stable ID lets Compose restore rememberSaveable/NavController
            // state when MainActivity is recreated for configuration changes.
            id = R.id.reianix_compose_app_shell
            layoutParams = FrameLayout.LayoutParams(
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
        root.addView(view)
        composeView = view
        return view
    }

    private companion object {
        const val HOME_DOUBLE_BACK_WINDOW_MS = 2_000L
        const val CONTENT_TAG = "reianix_compose_app_shell_content"
        const val TAG = "[REIANIX][COMPOSE_SHELL]"
    }
}
