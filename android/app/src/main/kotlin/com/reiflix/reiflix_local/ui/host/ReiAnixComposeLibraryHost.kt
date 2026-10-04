package com.reiflix.reiflix_local.ui.host

import com.reiflix.reiflix_local.MainActivity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.navigation.ReiAnixNavigationHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes

/**
 * Reversible presentation bridge for the real Library route.
 *
 * Flutter/Flet remains the application's primary host. This view is attached
 * only while the existing logical navigation route is "library"; all data and
 * commands still flow through the established Python/SQLite projection.
 *
 * Navigation inside this Compose surface is real Navigation Compose:
 * Library -> Details, using the canonical anime ID in the route.
 */
class ReiAnixComposeLibraryHost(
    private val activity: MainActivity,
) {
    private var composeView: ComposeView? = null
    @Volatile
    private var composeNavController: NavHostController? = null

    val isVisible: Boolean
        get() = composeView?.visibility == View.VISIBLE

    fun show() {
        composeNavController?.let { controller ->
            if (controller.currentDestination?.route != ReiAnixRoutes.LIBRARY) {
                controller.popBackStack(ReiAnixRoutes.LIBRARY, false)
            }
        }
        val view = ensureAttached()
        view.visibility = View.VISIBLE
        if (view.tag != CONTENT_TAG) {
            view.setContent {
                ReiAnixComposeRoot {
                    val navController = rememberNavController()
                    DisposableEffect(navController) {
                        composeNavController = navController
                        onDispose {
                            if (composeNavController === navController) {
                                composeNavController = null
                            }
                        }
                    }

                    ReiAnixNavigationHost(
                        navController = navController,
                        startDestination = ReiAnixRoutes.LIBRARY,
                        showBottomNavigation = false,
                    )
                }
            }
            view.tag = CONTENT_TAG
        }
    }

    fun hide() {
        composeView?.visibility = View.GONE
    }

    /**
     * Handles Back inside the embedded Compose navigation stack.
     *
     * Returns true only when a nested Compose destination was popped. The
     * caller keeps ownership of the root Library -> Flet back transition.
     */
    fun handleBack(): Boolean {
        val controller = composeNavController ?: return false
        return controller.previousBackStackEntry != null && controller.popBackStack()
    }

    fun dispose() {
        composeView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        composeView = null
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
        const val CONTENT_TAG = "reianix_compose_library_content"
    }
}
