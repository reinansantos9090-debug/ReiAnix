package com.reianix.reianix_local.ui.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.reianix.reianix_local.ui.motion.LocalReiAnixDestinationActive
import com.reianix.reianix_local.ui.motion.LocalReiAnixDestinationSettled
import androidx.compose.runtime.getValue
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout

/**
 * Compare back-stack entry identities, not destination names. Every outgoing
 * destination remains composed during the NavHost cross-fade and may still be
 * visually present; only the newest entry should accept interactions.
 */
internal fun isActiveNavigationDestination(
    entryId: String?,
    currentEntryId: String?,
): Boolean = entryId != null && entryId == currentEntryId

/** A second Details instance must never count as the current Details entry. */
internal fun isActiveDetailsDestination(
    detailsEntryId: String?,
    currentEntryId: String?,
    currentRoute: String?,
): Boolean = isActiveNavigationDestination(detailsEntryId, currentEntryId) &&
    currentRoute == ReiAnixRoutes.DETAILS

/**
 * Register destinations through one shared guard, preserving NavHost's normal
 * enter/exit animations, route arguments, back-stack scopes and saved state.
 * Read the live foreground entry for each touch event. This avoids the tiny
 * frame gap between a back action and Compose recomposition, when the stale
 * screen could otherwise still accept a very fast tap.
 */
internal fun NavGraphBuilder.reiAnixGuardedComposable(
    navController: NavHostController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val foreground by navController.currentBackStackEntryAsState()
        val active = isActiveNavigationDestination(entry.id, foreground?.id)
        val settled = active && !transition.isRunning &&
            transition.currentState == EnterExitState.Visible &&
            transition.targetState == EnterExitState.Visible
        val touchPlacement = if (route == ReiAnixRoutes.DETAILS) {
            if (foreground?.destination?.route == ReiAnixRoutes.LIBRARY) {
                Modifier.drawOutgoingDetailsInPlace()
            } else {
                Modifier
            }
        } else {
            Modifier
        }

        Box(
            Modifier
                .fillMaxSize()
                .then(touchPlacement)
                .blockOutgoingDestinationTouches(navController, entry.id),
        ) {
            CompositionLocalProvider(
                LocalReiAnixDestinationActive provides active,
                LocalReiAnixDestinationSettled provides settled,
            ) {
                content(entry)
            }
        }
    }
}

/**
 * On pop, NavHost draws Details above Library. Move the Details hit area away
 * while drawing it at its usual coordinates so NavHost can finish the fade.
 * Keep the real content composed and measured throughout the transition.
 */
private fun Modifier.drawOutgoingDetailsInPlace(): Modifier = drawWithContent {
    translate(left = -size.width * 2f) {
        this@drawWithContent.drawContent()
    }
}.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(x = placeable.width * 2, y = 0)
    }
}

/**
 * Check each pointer event against NavController's live entry at the initial
 * pass, ahead of the child click handlers. A gesture that began before a pop
 * cannot finish as a click on the outgoing screen after navigation changes.
 * There are no timers or artificial delays; the active route is unrestricted.
 */
internal fun Modifier.blockOutgoingDestinationTouches(
    navController: NavHostController,
    entryId: String,
): Modifier = pointerInput(navController, entryId) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!isActiveNavigationDestination(entryId, navController.currentBackStackEntry?.id)) {
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}
