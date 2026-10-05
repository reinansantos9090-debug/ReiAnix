package com.reiflix.reiflix_local.ui.host

import com.reiflix.reiflix_local.MainActivity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.storage.ReiAnixStorageRoute
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModelFactory
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModelFactory

/**
 * Native Compose storage surface layered over the existing Settings screen.
 *
 * The screen is only a projection of the existing LibraryStore/native capability
 * snapshot. It does not own permissions, SQLite or scan orchestration.
 */
class ReiAnixComposeStorageHost(
    private val activity: MainActivity,
) {
    private var composeView: ComposeView? = null

    val isVisible: Boolean
        get() = composeView?.visibility == View.VISIBLE

    fun show() {
        ensureAttached().visibility = View.VISIBLE
    }

    fun hide() {
        composeView?.visibility = View.GONE
    }

    fun handleBack(): Boolean {
        if (!isVisible) return false
        hide()
        return true
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

        val factory = ReiAnixLibraryViewModelFactory(activity.applicationContext)
        val viewModel = ViewModelProvider(activity, factory).get(ReiAnixLibraryViewModel::class.java)
        val settingsFactory = ReiAnixSettingsViewModelFactory(activity.applicationContext)
        val settingsViewModel = ViewModelProvider(activity, settingsFactory).get(ReiAnixSettingsViewModel::class.java)
        view.setContent {
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            ReiAnixComposeRoot(
                themeMode = settingsState.settings["appearance.theme"],
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                ) {
                    ReiAnixStorageRoute(
                        viewModel = viewModel,
                        onBack = ::hide,
                        onRemoveSaf = viewModel::removeSafTree,
                        onRequestMediaAccess = { activity.requestNativeStorageAction("request_media_access") },
                        onOpenBroadSettings = { activity.requestNativeStorageAction("open_broad_storage_settings") },
                        onCheckAccess = { activity.requestNativeStorageAction("check_storage_access") },
                    )
                }
            }
        }

        root.addView(view)
        composeView = view
        return view
    }
}
