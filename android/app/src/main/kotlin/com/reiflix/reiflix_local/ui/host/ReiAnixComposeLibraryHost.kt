package com.reiflix.reiflix_local.ui.host

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.MainActivity
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.navigation.ReiAnixNavigationHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import com.reiflix.reiflix_local.ui.navigation.navigateToMyList
import com.reiflix.reiflix_local.ui.navigation.navigateToTopLevel
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsRoute
import com.reiflix.reiflix_local.ui.storage.ReiAnixStorageRoute
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModelFactory
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModelFactory
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
) {
    private var composeView: ComposeView? = null

    @Volatile
    private var composeNavController: NavHostController? = null

    val isVisible: Boolean
        get() = composeView?.visibility == View.VISIBLE

    /**
     * Shows the shell, using [startDestination] only when the Compose root is
     * first attached. Subsequent calls navigate inside the existing back stack.
     */
    fun show(
        startDestination: String = ReiAnixRoutes.LIBRARY,
        resetBackStack: Boolean = false,
    ) {
        val view = ensureAttached()
        if (view.tag != CONTENT_TAG) {
            view.tag = CONTENT_TAG
            view.setContent {
            val settingsViewModel = androidx.compose.runtime.remember {
                ViewModelProvider(
                    activity,
                    ReiAnixSettingsViewModelFactory(activity.applicationContext),
                ).get(ReiAnixSettingsViewModel::class.java)
            }
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

            val appearanceCardSize = settingsState.settings["appearance.card_size"]
                ?.takeIf { it in setOf("small", "medium", "large") }
                ?: "medium"
            val appearanceShowThumbnails = settingsState.settings["appearance.show_thumbnails"]
                ?.let { it == "true" }
                ?: true
            val libraryGridDensity = settingsState.settings["library.grid_density"]
                ?.takeIf { it in setOf("small", "medium", "large") }
                ?: "medium"

            ReiAnixComposeRoot(
                themeMode = settingsState.settings["appearance.theme"],
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

                    val libraryViewModel = androidx.compose.runtime.remember {
                        ViewModelProvider(
                            activity,
                            ReiAnixLibraryViewModelFactory(activity.applicationContext),
                        ).get(ReiAnixLibraryViewModel::class.java)
                    }
                    ReiAnixNavigationHost(
                        navController = navController,
                        homeViewModel = libraryViewModel,
                        myList = {
                            com.reiflix.reiflix_local.ui.mylist.ReiAnixMyListRoute(
                                navController = navController,
                                viewModel = libraryViewModel,
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
                                    if (label == "Armazenamento") {
                                        navController.navigate(
                                            ReiAnixRoutes.STORAGE,
                                            navOptions {
                                                launchSingleTop = true
                                            },
                                        )
                                    } else {
                                        hide()
                                        publishSettingsNavigation("category", label)
                                    }
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
                        showBottomNavigation = startDestination != ReiAnixRoutes.STORAGE,
                    )
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
     * transitional shell is dismissed and the existing Flet navigation receives
     * the legacy back contract.
     */
    fun handleBack(): Boolean {
        val controller = composeNavController ?: return false
        if (controller.previousBackStackEntry != null) {
            return controller.popBackStack()
        }

        val route = controller.currentBackStackEntry?.destination?.route
        when (route) {
            ReiAnixRoutes.SETTINGS,
            ReiAnixRoutes.STORAGE,
            -> hideAndPublishSettingsBack()
            else -> hideAndPublishLegacyBack()
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
        const val CONTENT_TAG = "reianix_compose_app_shell_content"
        const val TAG = "[REIANIX][COMPOSE_SHELL]"
    }
}
