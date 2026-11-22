package com.reianix.reianix_local.ui.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * NavHost cross-fades two destinations, so the leaving Details screen can stay
 * visible above the Library briefly after a pop. It must not keep accepting
 * episode taps while the incoming destination is already current.
 *
 * Match the actual back-stack entry, not only its route: two different Details
 * entries can exist at the same time during a recommendations navigation.
 */
internal fun isActiveDetailsDestination(
    detailsEntryId: String?,
    currentEntryId: String?,
    currentRoute: String?,
): Boolean = detailsEntryId != null &&
    detailsEntryId == currentEntryId &&
    currentRoute == ReiAnixRoutes.DETAILS

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
