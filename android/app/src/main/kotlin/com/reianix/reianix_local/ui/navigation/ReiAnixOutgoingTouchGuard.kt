package com.reianix.reianix_local.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
 * Observe the foreground entry inside the destination, not in the NavGraph
 * builder, because the builder is remembered across route changes.
 */
internal fun NavGraphBuilder.reiAnixGuardedComposable(
    navController: NavHostController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        val currentEntry by navController.currentBackStackEntryAsState()
        Box(
            Modifier
                .fillMaxSize()
                .blockOutgoingDestinationTouches(
                    isActiveNavigationDestination(entry.id, currentEntry?.id),
                ),
        ) {
            content(entry)
        }
    }
}

/**
 * Consume the full pointer gesture at the initial pass, ahead of outgoing child
 * click handlers. The active screen remains unrestricted and its transitions
 * are left completely intact.
 */
internal fun Modifier.blockOutgoingDestinationTouches(active: Boolean): Modifier =
    if (active) this else pointerInput(Unit) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
