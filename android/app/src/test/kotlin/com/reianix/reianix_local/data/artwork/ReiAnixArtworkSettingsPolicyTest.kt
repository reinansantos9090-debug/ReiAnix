package com.reianix.reianix_local.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixArtworkSettingsPolicyTest {
    @Test fun configuredArtworkCacheSizeIsUsedInsteadOfFixed128MB() {
        for (size in listOf(64, 128, 256, 512)) {
            assertEquals(size.toLong() * 1024L * 1024L, artworkCacheLimitBytes(size.toString()))
        }
        assertEquals(128L * 1024L * 1024L, artworkCacheLimitBytes(null))
        assertEquals(128L * 1024L * 1024L, artworkCacheLimitBytes("bogus"))
        assertEquals(128L * 1024L * 1024L, artworkCacheLimitBytes("1024"))
    }

    @Test fun artworkDownloadPreferenceBlocksNewRemoteImagesFromAllProviders() {
        assertTrue(artworkDownloadsAllowed(null))
        assertTrue(artworkDownloadsAllowed("true"))
        assertTrue(artworkDownloadsAllowed(" TRUE "))
        assertFalse(artworkDownloadsAllowed("false"))
        assertFalse(artworkDownloadsAllowed(" FALSE "))
    }
}
