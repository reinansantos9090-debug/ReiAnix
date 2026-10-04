package com.reiflix.reiflix_local.player

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Single authority for the host Activity system-bar policy.
 *
 * MainActivity and the native player use the same controller, but each Activity chooses its lifecycle policy.
 * NativePlayerActivity calls applyImmersive() while the player is active and
 * restores applyNormal() before returning to the host. Android-owned external
 * surfaces (permissions/settings/pickers) may reveal their own system UI while
 * they are in the foreground; the foreground Activity reapplies its policy when
 * focus returns.
 */
class SystemUiController(private val window: Window) {
    private val controller: WindowInsetsControllerCompat
        get() = WindowCompat.getInsetsController(window, window.decorView)

    /**
     * Reapply the host policy after resume/focus/configuration changes.
     * The application host is immersive; the explicit normal policy remains
     * available for temporary/external surfaces and player exit transitions.
     */
    /** Shared application policy for the immersive Flet host. */
    fun applyApplicationImmersivePolicy(useContextAppearance: Boolean = true) {
        applyImmersive(useContextAppearance)
    }

    /** Backward-compatible normal policy for external/temporary surfaces. */
    fun applyApplicationPolicy(useContextAppearance: Boolean = true) {
        applyNormal(useContextAppearance)
    }

    /** Player policy: edge-to-edge with system bars hidden. */
    fun applyImmersive(useContextAppearance: Boolean = true) {
        applyEdgeToEdgeWindow()
        if (useContextAppearance) {
            applySystemBarAppearance()
        }
        controller.apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Explicit normal policy for an Activity that needs visible bars. */
    fun applyNormal(useContextAppearance: Boolean = true) {
        applyEdgeToEdgeWindow()
        if (useContextAppearance) {
            applySystemBarAppearance()
        }
        controller.apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun applyEdgeToEdgeWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    private fun applySystemBarAppearance() {
        val nightMode = window.context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
        val darkTheme = nightMode == Configuration.UI_MODE_NIGHT_YES
        controller.apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
