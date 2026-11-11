package com.reiflix.reiflix_local.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTsRemuxTest {
    @Test fun remuxesOnlyCompatibleVideoAndOptionalAudio() {
        assertTrue(DownloadTsRemux.compatibleTracks(listOf("video/avc"),33))
        assertTrue(DownloadTsRemux.compatibleTracks(listOf("video/avc","audio/mp4a-latm"),33))
        assertTrue(DownloadTsRemux.compatibleTracks(listOf("video/hevc","audio/mp4a-latm"),33))
    }

    @Test fun neverDropsOrConvertsUnsupportedTracks() {
        assertFalse(DownloadTsRemux.compatibleTracks(listOf("video/avc","audio/ac3"),33))
        assertFalse(DownloadTsRemux.compatibleTracks(listOf("video/avc","audio/mp4a-latm","text/vtt"),33))
        assertFalse(DownloadTsRemux.compatibleTracks(listOf("audio/mp4a-latm"),33))
        assertFalse(DownloadTsRemux.compatibleTracks(listOf("video/avc","video/avc"),33))
        assertFalse(DownloadTsRemux.compatibleTracks(listOf("video/hevc","audio/mp4a-latm"),23))
    }
}
