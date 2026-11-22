package com.reianix.reianix_local.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixOutgoingTouchGuardTest {
    @Test
    fun allNavigationScreensRejectInputFromAnOlderBackStackEntry() {
        val everyRoute = listOf(
            ReiAnixRoutes.HOME,
            ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.MY_LIST,
            ReiAnixRoutes.COLLECTIONS,
            ReiAnixRoutes.ORGANIZE,
            ReiAnixRoutes.SEARCH,
            ReiAnixRoutes.SETTINGS,
            ReiAnixRoutes.SETTINGS_GENERAL,
            ReiAnixRoutes.PROFILE,
            ReiAnixRoutes.STORAGE,
            ReiAnixRoutes.RECOVERY,
            ReiAnixRoutes.COLLECTOR,
            ReiAnixRoutes.TIMELINE,
            ReiAnixRoutes.DETAILS,
            ReiAnixRoutes.ANILIST_DETAILS,
            ReiAnixRoutes.PLAYER,
        )
        // The same guard is used for every NavHost destination, including
        // player, settings and secondary screens, not only Details/Library.
        for (route in everyRoute) {
            assertTrue(route, isActiveNavigationDestination("current-entry", "current-entry"))
            assertFalse(route, isActiveNavigationDestination("outgoing-entry", "current-entry"))
            assertFalse(route, isActiveNavigationDestination(null, "current-entry"))
        }
    }

    @Test
    fun sameRouteIsNotSufficientWhenTwoInstancesAreTransitioning() {
        assertFalse(isActiveNavigationDestination("first-details-instance", "second-details-instance"))
        assertTrue(isActiveNavigationDestination("second-details-instance", "second-details-instance"))
    }

    @Test
    fun leavingDetailsForLibraryImmediatelyDisablesItsControls() {
        assertTrue(isActiveDetailsDestination("details-entry", "details-entry", ReiAnixRoutes.DETAILS))
        assertFalse(isActiveDetailsDestination("details-entry", "library-entry", ReiAnixRoutes.LIBRARY))
    }

    @Test
    fun anotherDetailsInstanceCannotReceiveTapsDuringTheTransition() {
        // The route can be the same for both entries, but only the active entry
        // should be allowed to open an episode.
        assertFalse(isActiveDetailsDestination("old-details", "new-details", ReiAnixRoutes.DETAILS))
        assertTrue(isActiveDetailsDestination("new-details", "new-details", ReiAnixRoutes.DETAILS))
    }

    @Test
    fun launchingPlayerDisablesOutgoingDetailsWhileReturningRestoresInteraction() {
        assertFalse(isActiveDetailsDestination("details-entry", "player-entry", ReiAnixRoutes.PLAYER))
        assertTrue(isActiveDetailsDestination("details-entry", "details-entry", ReiAnixRoutes.DETAILS))
    }

    @Test
    fun missingOrMismatchedBackStackEntriesAreNeverInteractive() {
        assertFalse(isActiveDetailsDestination(null, "details-entry", ReiAnixRoutes.DETAILS))
        assertFalse(isActiveDetailsDestination("details-entry", null, ReiAnixRoutes.DETAILS))
        assertFalse(isActiveDetailsDestination("details-entry", "details-entry", ReiAnixRoutes.HOME))
    }
}
