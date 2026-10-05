package com.reiflix.reiflix_local.ui.host

import com.reiflix.reiflix_local.MainActivity
import com.reiflix.reiflix_local.bridge.NativeMailbox
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reiflix.reiflix_local.ui.ReiAnixComposeRoot
import com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsRoute
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModelFactory
import org.json.JSONObject

class ReiAnixComposeSettingsHost(
    private val activity: MainActivity,
) {
    private var composeView: ComposeView? = null
    private var viewModel: ReiAnixSettingsViewModel? = null

    val isVisible: Boolean
        get() = composeView?.visibility == View.VISIBLE

    fun show() {
        val view = ensureAttached()
        view.visibility = View.VISIBLE
        viewModel?.refresh()
    }

    fun hide() {
        composeView?.visibility = View.GONE
    }

    fun handleBack(): Boolean {
        if (!isVisible) return false
        hide()
        publishNavigation("back")
        return true
    }

    fun dispose() {
        composeView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        viewModel = null
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

        val factory = ReiAnixSettingsViewModelFactory(activity.applicationContext)
        viewModel = ViewModelProvider(activity, factory).get(ReiAnixSettingsViewModel::class.java)
        val settingsViewModel = viewModel!!

        view.setContent {
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            ReiAnixComposeRoot(
                themeMode = settingsState.settings["appearance.theme"],
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .imePadding(),
                ) {
                    ReiAnixSettingsRoute(
                        viewModel = settingsViewModel,
                        onBack = { handleBack() },
                        onOpenCategory = { label ->
                            if (label != "Armazenamento") {
                                hide()
                            }
                            publishNavigation("category", label)
                        },
                    )
                }
            }
        }

        root.addView(view)
        composeView = view
        return view
    }

    private fun publishNavigation(destination: String, category: String? = null) {
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
}
