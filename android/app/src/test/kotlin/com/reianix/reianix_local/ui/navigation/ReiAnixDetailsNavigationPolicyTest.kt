package com.reianix.reianix_local.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixDetailsNavigationPolicyTest {

    @Test
    fun recommendationsAlwaysPushAnotherAnimeInsteadOfReplacingCurrentEntry() {
        assertTrue(shouldPushAnimeDetails(ReiAnixRoutes.LIBRARY, null, "101"))
        assertTrue(shouldPushAnimeDetails(ReiAnixRoutes.DETAILS, "101", "202"))
        assertTrue(shouldPushAnimeDetails(ReiAnixRoutes.DETAILS, "202", "303"))
        // Navigating back to an earlier anime through a recommendation should
        // still create its own entry; only an already-current anime is ignored.
        assertTrue(shouldPushAnimeDetails(ReiAnixRoutes.DETAILS, "303", "101"))
    }

    @Test
    fun currentAnimeDoesNotStackDuplicateDetailsForRepeatedTaps() {
        assertFalse(shouldPushAnimeDetails(ReiAnixRoutes.DETAILS, "101", "101"))
        assertFalse(shouldPushAnimeDetails(ReiAnixRoutes.DETAILS, " 101 ", "101"))
        assertTrue(shouldPushAnimeDetails(ReiAnixRoutes.ANILIST_DETAILS, null, "101"))
    }

    @Test
    fun bothDetailsRoutesHideTheBottomBarRegardlessOfOrigin() {
        for (origin in listOf(ReiAnixRoutes.HOME, ReiAnixRoutes.LIBRARY, ReiAnixRoutes.SEARCH)) {
            assertFalse(shouldShowReiAnixBottomNavigation(ReiAnixRoutes.DETAILS, origin, true))
            assertFalse(shouldShowReiAnixBottomNavigation(ReiAnixRoutes.ANILIST_DETAILS, origin, true))
        }
    }

    @Test
    fun primaryTabsKeepTheBottomBarWhileSecondaryScreensStayHidden() {
        for (tab in listOf(
            ReiAnixRoutes.HOME, ReiAnixRoutes.LIBRARY,
            ReiAnixRoutes.SEARCH, ReiAnixRoutes.SETTINGS,
        )) {
            assertTrue(shouldShowReiAnixBottomNavigation(tab, tab, true))
            assertFalse(shouldShowReiAnixBottomNavigation(tab, tab, false))
        }
        assertTrue(shouldShowReiAnixBottomNavigation(ReiAnixRoutes.MY_LIST, ReiAnixRoutes.LIBRARY, true))
        assertFalse(shouldShowReiAnixBottomNavigation(ReiAnixRoutes.STORAGE, ReiAnixRoutes.LIBRARY, true))
        assertFalse(shouldShowReiAnixBottomNavigation(ReiAnixRoutes.TIMELINE, ReiAnixRoutes.HOME, true))
        assertFalse(shouldShowReiAnixBottomNavigation(ReiAnixRoutes.PLAYER, null, true))
    }
}
