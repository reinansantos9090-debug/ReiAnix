package com.reiflix.reiflix_local.data.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixShikimoriDiscoveryPolicyTest {
    @Test
    fun completedAttemptUsesTwentyFourHourIdentityCooldown() {
        assertTrue(shouldDeferShikimoriDiscoveryAttempt("same", 1_000.0, "completed", "same", 1_060.0))
        assertFalse(shouldDeferShikimoriDiscoveryAttempt("same", 1_000.0, "completed", "same", 1_000.0 + SHIKIMORI_DISCOVERY_COOLDOWN_SECONDS))
    }

    @Test
    fun transientAttemptUsesShorterRetryWindow() {
        assertTrue(shouldDeferShikimoriDiscoveryAttempt("same", 1_000.0, "transient", "same", 1_060.0))
        assertFalse(shouldDeferShikimoriDiscoveryAttempt("same", 1_000.0, "transient", "same", 1_000.0 + SHIKIMORI_TRANSIENT_RETRY_DELAY_SECONDS))
    }

    @Test
    fun identityChangesAndInvalidTimestampsDoNotDefer() {
        assertFalse(shouldDeferShikimoriDiscoveryAttempt("old", 1_000.0, "completed", "new", 1_001.0))
        assertFalse(shouldDeferShikimoriDiscoveryAttempt("same", Double.NaN, "completed", "same", 1_001.0))
        assertFalse(shouldDeferShikimoriDiscoveryAttempt(null, 1_000.0, "completed", "same", 1_001.0))
    }
}
