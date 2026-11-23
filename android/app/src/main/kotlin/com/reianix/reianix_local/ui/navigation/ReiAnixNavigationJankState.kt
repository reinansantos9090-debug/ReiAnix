package com.reianix.reianix_local.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.PerformanceMetricsState

/**
 * Route labels are added only for the user's explicitly enabled JankStats
 * session. No sampling, logging, or new state is introduced on normal builds.
 */
@Composable
internal fun ReiAnixNavigationJankState(route: String?) {
    if (!Log.isLoggable("ReiAnixJank", Log.DEBUG)) return
    val view = LocalView.current
    DisposableEffect(view, route) {
        val state = PerformanceMetricsState.getHolderForHierarchy(view).state
        state?.putState("ReiAnix.route", route ?: "unknown")
        onDispose { state?.removeState("ReiAnix.route") }
    }
}
