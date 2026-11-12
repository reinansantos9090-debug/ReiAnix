package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadIdentitySharedNameTest {
    @Test fun nuvioSharedEpisodeTitleOverridesGenericManifestName() {
        assertEquals("Solo Leveling - Episódio 01.mp4",DownloadIdentity.suggestedName(
            "Solo Leveling - Episódio 1",
            "https://cdn.example/streams/solo-leveling/index.m3u8", "index.m3u8"))
    }

    @Test fun namedVideoIsNeverOverwrittenByCdnTitle() {
        assertEquals("Black Clover Episódio 03.mkv",DownloadIdentity.suggestedName(
            "Solo Leveling - Episódio 1",
            "https://cdn.example/index.m3u8", "Black Clover Episódio 03.mkv"))
    }

    @Test fun unknownEpisodeNumberIsNotInvented() {
        // Series name is useful even without a known episode number.
        assertEquals("Solo Leveling.mp4",DownloadIdentity.suggestedName(
            "Solo Leveling",
            "https://cdn.example/index.m3u8", "index.m3u8"))
        assertTrue(DownloadIdentity.isGenericMediaName("videoplayback.bin.mp4"))
        assertTrue(DownloadIdentity.isGenericMediaName("index.avi"))
    }
}
