package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadSizeProbeTest {
    @Test fun headLengthIsExactWhenContentLengthIdentifiesTheCompleteVideo() {
        assertEquals(219_000_000L,DownloadSizeProbe.directLength(
            200,219_000_000L,null,null,"video/mp4",
        ))
        assertEquals(219_000_000L,DownloadSizeProbe.directLength(
            200,219_000_000L,null,"identity","video/mp4; charset=binary",
        ))
    }

    @Test fun oneByteRangeRevealsFullResourceNotTheOneByteResponse() {
        assertEquals(229_638_144L,DownloadSizeProbe.directLength(
            206,1L,"bytes 0-0/229638144","identity","video/mp4",
        ))
        assertNull(DownloadSizeProbe.directLength(
            206,1L,"bytes 1-1/229638144",null,"video/mp4",
        ))
        assertNull(DownloadSizeProbe.directLength(
            206,1L,"bytes 0-0/*",null,"video/mp4",
        ))
    }

    @Test fun neverTreatsPlaylistOrHtmlOrCompressedBytesAsVideoSize() {
        assertNull(DownloadSizeProbe.directLength(
            200,9220L,null,null,"application/vnd.apple.mpegurl",
        ))
        assertNull(DownloadSizeProbe.directLength(
            200,1000L,null,null,"text/html",
        ))
        assertNull(DownloadSizeProbe.directLength(
            200,1200L,null,"gzip","video/mp4",
        ))
        assertNull(DownloadSizeProbe.directLength(
            200,-1L,null,null,"video/mp4",
        ))
    }

    @Test fun hlsMasterFollowsSameHighestBandwidthPolicyAsDownloader() {
        val master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720\n../high.m3u8\n"
        val choice=DownloadSizeProbe.parseMaster(master,"https://example.org/watch/list.m3u8")
        assertEquals("https://example.org/high.m3u8",choice?.url)
        assertEquals(3_000_000L,choice?.bandwidth)
    }

    @Test fun parserCountsVodSegmentsAndRejectsLiveOrUnsupportedRanges() {
        val manifest="#EXTM3U\n#EXTINF:4.0,\na.ts\n#EXTINF:6.5,\nb.ts\n#EXT-X-ENDLIST"
        val plan=DownloadSizeProbe.parseVod(manifest,"https://example.org/media/playlist.m3u8")
        assertEquals(2,plan?.segments?.size)
        assertEquals(10.5,plan?.durationSeconds ?: 0.0,0.001)
        assertEquals("https://example.org/media/a.ts",plan?.segments?.first())
        assertNull(DownloadSizeProbe.parseVod("#EXTM3U\n#EXTINF:4,\na.ts",
            "https://example.org/list.m3u8"))
        assertNull(DownloadSizeProbe.parseVod(
            "#EXTM3U\n#EXT-X-BYTERANGE:512@0\na.ts\n#EXT-X-ENDLIST",
            "https://example.org/list.m3u8"))
    }

    @Test fun bitrateReturnsEstimateOnlyAndNeverFabricatesAnExactLength() {
        assertEquals(2_500_000L,DownloadSizeProbe.bitrateEstimate(10.0,2_000_000))
        assertNull(DownloadSizeProbe.bitrateEstimate(10.0,null))
        assertNull(DownloadSizeProbe.bitrateEstimate(Double.NaN,2_000_000))
        assertNull(DownloadSizeProbe.Result.Unknown.bytes)
        assertFalse(DownloadSizeProbe.Result.Unknown.exact)
    }

    @Test fun googlevideoClenMayRevealExactBytesWithoutRequestingTheFile() {
        assertEquals(229_638_144L,DownloadSizeProbe.trustedUrlLength(
            "https://rr4---sn-example.googlevideo.com/videoplayback?id=abc&clen=229638144&dur=1042",
        ))
        assertNull(DownloadSizeProbe.trustedUrlLength(
            "https://googlevideo.com.evil.example/videoplayback?clen=999999999",
        ))
        assertNull(DownloadSizeProbe.trustedUrlLength(
            "https://cdn.example/file.mp4?clen=999999999",
        ))
        assertNull(DownloadSizeProbe.trustedUrlLength(
            "https://r1.googlevideo.com/other?clen=9999",
        ))
        assertNull(DownloadSizeProbe.trustedUrlLength(
            "https://r1.googlevideo.com/videoplayback?clen=noclen",
        ))
    }

    @Test fun encryptedPlaylistDoesNotClaimExactRemuxedSize() {
        val p=DownloadSizeProbe.parseVod(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST",
            "https://example.org/show/list.m3u8")
        assertTrue(p?.encrypted==true)
    }
}
