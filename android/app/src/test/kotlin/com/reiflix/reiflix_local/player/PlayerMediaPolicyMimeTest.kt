package com.reiflix.reiflix_local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerMediaPolicyMimeTest {
    @Test
    fun trustsVideoMimeFromContentProviderWhenUriHasNoFilenameExtension() {
        assertEquals(
            "video/mp4",
            PlayerMediaPolicy.resolveVideoMimeType(
                providerMime = "video/mp4",
                displayName = null,
            ),
        )
    }

    @Test
    fun usesFilenameExtensionWhenProviderReturnsGenericBinaryMime() {
        assertEquals(
            "video/x-matroska",
            PlayerMediaPolicy.resolveVideoMimeType(
                providerMime = "application/octet-stream",
                displayName = "episode-01.mkv",
            ),
        )
    }

    @Test
    fun fallsBackToKnownExtensionWhenProviderReportsApplicationContainerMime() {
        assertEquals(
            "video/x-matroska",
            PlayerMediaPolicy.resolveVideoMimeType(
                providerMime = "application/x-matroska",
                displayName = "episode.mkv",
            ),
        )
        assertEquals(
            "video/mp4",
            PlayerMediaPolicy.resolveVideoMimeType(
                providerMime = "application/mp4",
                displayName = "episode.mp4",
            ),
        )
    }

    @Test
    fun resolvesCommonLocalVideoExtensionsWithoutProviderMime() {
        assertEquals("video/mp4", PlayerMediaPolicy.resolveVideoMimeType(null, "episode.mp4"))
        assertEquals("video/webm", PlayerMediaPolicy.resolveVideoMimeType(null, "episode.webm"))
        assertEquals("video/quicktime", PlayerMediaPolicy.resolveVideoMimeType(null, "episode.mov"))
    }

    @Test
    fun leavesUnknownContentTypesToMedia3AutoDetection() {
        assertNull(
            PlayerMediaPolicy.resolveVideoMimeType(
                providerMime = "application/octet-stream",
                displayName = "episode_without_known_extension",
            ),
        )
    }
}
