package com.reianix.reianix_local.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

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
        Box(
            Modifier
                .fillMaxSize()
                .blockOutgoingDestinationTouches(navController, entry.id),
        ) {
            content(entry)
        }
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
