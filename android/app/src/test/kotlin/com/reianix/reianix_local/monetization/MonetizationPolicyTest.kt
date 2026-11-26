package com.reianix.reianix_local.monetization

import org.junit.Assert.*
import org.junit.Test

class MonetizationPolicyTest {
    @Test fun expiryAndPremium() {
        val now = 123456789L
        val until = MonetizationPolicy.rewardUntil(now)
        assertEquals(86_400_000L, until - now)
        assertTrue(MonetizationPolicy.suppressAds(false, until, until - 1))
        assertFalse(MonetizationPolicy.suppressAds(false, until, until))
        assertTrue(MonetizationPolicy.suppressAds(true, 0, now))
    }
    @Test fun distinctArtAndFreeAccess() {
        val banner = MonetizationPolicy.artKey(true, "same")
        val wallpaper = MonetizationPolicy.artKey(false, "same")
        assertNotEquals(banner, wallpaper)
        assertTrue(MonetizationPolicy.artAvailable(false, 2, banner, emptySet()))
        assertFalse(MonetizationPolicy.artAvailable(false, 3, banner, emptySet()))
        assertTrue(MonetizationPolicy.artAvailable(false, 3, banner, setOf(banner)))
        assertFalse(MonetizationPolicy.artAvailable(false, 3, wallpaper, setOf(banner)))
        assertTrue(MonetizationPolicy.artAvailable(true, 50, wallpaper, emptySet()))
    }
}
