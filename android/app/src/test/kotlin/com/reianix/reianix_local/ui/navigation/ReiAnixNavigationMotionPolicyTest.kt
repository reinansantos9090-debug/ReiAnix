package com.reianix.reianix_local.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixNavigationMotionPolicyTest {
    @Test fun differentRecommendedAnimesStillAnimateOnSameRoute() {
        assertEquals(ReiAnixRouteAnimation.DETAILS,
            reiAnixRouteAnimation(ReiAnixRoutes.DETAILS, ReiAnixRoutes.DETAILS,
                sameEntry = false, systemAnimationsEnabled = true))
    }

    @Test fun unchangedEntryAndSystemMotionOffNeverAnimate() {
        assertEquals(ReiAnixRouteAnimation.NONE,
            reiAnixRouteAnimation(ReiAnixRoutes.HOME, ReiAnixRoutes.HOME,
                sameEntry = true, systemAnimationsEnabled = true))
        assertEquals(ReiAnixRouteAnimation.NONE,
            reiAnixRouteAnimation(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.DETAILS,
                sameEntry = false, systemAnimationsEnabled = false))
    }

    @Test fun detailAndAniListUseTargetedMotionWithoutChangingPrimaryTabs() {
        assertEquals(ReiAnixRouteAnimation.DETAILS,
            reiAnixRouteAnimation(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.DETAILS,
                sameEntry = false, systemAnimationsEnabled = true))
        assertEquals(ReiAnixRouteAnimation.DETAILS,
            reiAnixRouteAnimation(ReiAnixRoutes.ANILIST_DETAILS, ReiAnixRoutes.SEARCH,
                sameEntry = false, systemAnimationsEnabled = true))
        assertEquals(ReiAnixRouteAnimation.STANDARD,
            reiAnixRouteAnimation(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.SEARCH,
                sameEntry = false, systemAnimationsEnabled = true))
    }

    @Test fun tappingActiveTabDoesNotRestartItsTransitionOrResetState() {
        assertFalse(shouldNavigateToTopLevel(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.LIBRARY))
        assertTrue(shouldNavigateToTopLevel(ReiAnixRoutes.DETAILS, ReiAnixRoutes.LIBRARY))
        assertTrue(shouldNavigateToTopLevel(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.HOME))
    }
}
