package com.reianix.reianix_local

import com.reianix.reianix_local.player.PlayerMediaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMediaDurationPolicyTest {
    @Test
    fun prefersMedia3DurationAndUsesLocalMetadataOnlyAsFallback() {
        assertEquals(12_000L, PlayerMediaPolicy.resolveDurationMs(12_000L, 10_000L))
        assertEquals(10_000L, PlayerMediaPolicy.resolveDurationMs(0L, 10_000L))
        assertEquals(10_000L, PlayerMediaPolicy.resolveDurationMs(-1L, 10_000L))
        assertEquals(0L, PlayerMediaPolicy.resolveDurationMs(0L, null))
        assertEquals(0L, PlayerMediaPolicy.resolveDurationMs(-1L, 0L))
    }

    @Test
    fun requiresMedia3SeekCommandAndClampsOnlyWhenDurationIsKnown() {
        assertTrue(PlayerMediaPolicy.isSeekAllowed(true))
        assertFalse(PlayerMediaPolicy.isSeekAllowed(false))
        assertEquals(0L, PlayerMediaPolicy.clampSeekPosition(-20L, 10_000L))
        assertEquals(10_000L, PlayerMediaPolicy.clampSeekPosition(12_000L, 10_000L))
        assertEquals(12_000L, PlayerMediaPolicy.clampSeekPosition(12_000L, 0L))
        assertEquals(12_000L, PlayerMediaPolicy.clampSeekPosition(12_000L, -1L))
    }

    @Test
    fun normalizesCommonContainerMimeTypesReportedAsApplication() {
        assertEquals(
            "video/x-matroska",
            PlayerMediaPolicy.resolveVideoMimeType("application/x-matroska", "episode.mkv"),
        )
        assertEquals(
            "video/webm",
            PlayerMediaPolicy.resolveVideoMimeType("application/webm", "episode.webm"),
        )
        assertEquals(
            "video/mp4",
            PlayerMediaPolicy.resolveVideoMimeType("application/mp4", "episode.mp4"),
        )
    }
}
