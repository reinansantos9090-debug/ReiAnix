package com.reianix.reianix_local.player

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
    /** Immersive host policy; allow short-edge cutouts on Android 9 and 10. */
    fun applyApplicationImmersivePolicy(useContextAppearance: Boolean = true) {
        applyImmersive(useContextAppearance)
        if (Build.VERSION.SDK_INT in 28..29) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /** App preference only; the native player retains its independent policy. */
    fun applyAppPreference(immersive: Boolean, themeMode: String = "dark") {
        if (immersive) applyApplicationImmersivePolicy(useContextAppearance = false)
        else {
            applyNormal(useContextAppearance = false)
            if (Build.VERSION.SDK_INT in 28..29) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        val light = when (themeMode) {
            "light" -> true
            "system" -> (window.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
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

/** Standalone app-owned screens follow the same persisted preference as MainActivity. */
internal class ReiAnixAppWindowPreference(private val activity: androidx.activity.ComponentActivity) {
    private val controller = SystemUiController(activity.window)
    private var immersive = true
    private var readJob: kotlinx.coroutines.Job? = null

    fun apply() {
        controller.applyAppPreference(immersive)
    }

    fun refresh() {
        readJob?.cancel()
        readJob = activity.lifecycleScope.launch {
            try {
                val value = com.reianix.reianix_local.ReiAnixAppDependencies.from(activity)
                    .sqliteSettingsRepository.getPreference(
                        com.reianix.reianix_local.data.settings.ReiAnixSettingsContract.APP_IMMERSIVE_KEY,
                        "true",
                    )
                immersive = com.reianix.reianix_local.data.settings.ReiAnixSettingsContract.appImmersiveEnabled(value)
                if (!activity.isFinishing && !activity.isDestroyed) apply()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Preserve the last applied policy if local preferences are unavailable.
            }
        }
    }
}
