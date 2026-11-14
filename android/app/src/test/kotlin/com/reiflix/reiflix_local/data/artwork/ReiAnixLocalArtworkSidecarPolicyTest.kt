package com.reiflix.reiflix_local.data.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLocalArtworkSidecarPolicyTest {
    @Test
    fun acceptsOnlyKnownPosterAndBackdropSidecarNames() {
        for (name in listOf("poster.jpg", "cover.PNG", "folder.webp", "front.jpeg")) {
            assertTrue("$name should be a poster sidecar", isLegacyArtworkSidecarCandidate(name, "poster"))
        }
        for (name in listOf("backdrop.jpg", "fanart.png", "banner.webp", "background.jpeg")) {
            assertTrue("$name should be a backdrop sidecar", isLegacyArtworkSidecarCandidate(name, "backdrop"))
        }
    }

    @Test
    fun rejectsHiddenUnrelatedAndUnsupportedSidecarFiles() {
        for (name in listOf(
                "",
                ".poster.jpg",
                "my-poster.jpg",
                "poster.txt",
                "poster.bmp",
                "cover art.jpg",
                "folder.",
        )) {
            assertFalse("$name is not an exact safe sidecar name", isLegacyArtworkSidecarCandidate(name, "poster"))
        }
    }

    @Test
    fun legacyCoverCacheReconstructionRequiresMatchingValidUnsharedFile() {
        assertTrue(shouldReconstructLegacyCoverCache(true, true, false))
        assertFalse(shouldReconstructLegacyCoverCache(false, true, false))
        assertFalse(shouldReconstructLegacyCoverCache(true, false, false))
        assertFalse(shouldReconstructLegacyCoverCache(true, true, true))
    }
}
