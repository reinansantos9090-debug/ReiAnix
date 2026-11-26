package com.reianix.reianix_local.ui.navigation

import com.reianix.reianix_local.ui.motion.SCREEN_TRANSITION_MILLIS
import com.reianix.reianix_local.ui.motion.reiAnixPrimaryTabProgress
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

    @Test fun everyDistinctPrimaryTabPairUsesTheSharedMotion() {
        val tabs = listOf(ReiAnixRoutes.HOME, ReiAnixRoutes.LIBRARY, ReiAnixRoutes.SEARCH, ReiAnixRoutes.SETTINGS)
        for (from in tabs) for (to in tabs) {
            assertEquals(from != to, reiAnixIsPrimaryTabTransition(from, to))
        }
        assertFalse(reiAnixIsPrimaryTabTransition(ReiAnixRoutes.SEARCH, ReiAnixRoutes.DETAILS))
        assertFalse(reiAnixIsPrimaryTabTransition(null, ReiAnixRoutes.SEARCH))
    }

    @Test fun sharedTabProgressReducesOutgoingOpacityWithoutAnArtificialDelay() {
        assertEquals(150, SCREEN_TRANSITION_MILLIS)
        assertEquals(0f, reiAnixPrimaryTabProgress(0f), 0.0001f)
        assertEquals(1f, reiAnixPrimaryTabProgress(1f), 0.0001f)
        assertEquals(0.75f, reiAnixPrimaryTabProgress(0.5f), 0.0001f)
        var previous = 0f
        for (frame in 1..150) {
            val progress = reiAnixPrimaryTabProgress(frame / 150f)
            assertTrue(progress >= previous)
            assertTrue(progress in 0f..1f)
            previous = progress
        }
    }

    @Test fun tappingActiveTabDoesNotRestartItsTransitionOrResetState() {
        assertFalse(shouldNavigateToTopLevel(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.LIBRARY))
        assertTrue(shouldNavigateToTopLevel(ReiAnixRoutes.DETAILS, ReiAnixRoutes.LIBRARY))
        assertTrue(shouldNavigateToTopLevel(ReiAnixRoutes.LIBRARY, ReiAnixRoutes.HOME))
    }
}
