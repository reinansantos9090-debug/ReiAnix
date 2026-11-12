package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadHlsPolicyTest {
    @Test fun resolvesVodSegmentsAndSequence() {
        val p=DownloadHls.parse("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:4,\na.ts\n#EXTINF:4,\n../b.ts\n#EXT-X-ENDLIST","https://example.org/show/list.m3u8")
        assertEquals(listOf("https://example.org/show/a.ts","https://example.org/b.ts"),p.segments.map {it.url})
        assertEquals(listOf(7L,8L),p.segments.map {it.sequence})
    }
    @Test fun refusesLiveAndDrmRatherThanSavingBrokenFiles() {
        listOf("#EXTM3U\n#EXTINF:4,\na.ts", "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST").forEach {
            try { DownloadHls.parse(it,"https://example.org/list.m3u8");fail("Unsupported playlist accepted") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun preservesCommasInsideQuotedAttributes() {
        assertEquals("a,b",DownloadHls.attributes("#EXT-X-KEY:METHOD=AES-128,URI=\"a,b\"")["URI"])
    }
    @Test fun episodeOnlyNamesHaveNoAnimeEvidence() {
        assertEquals("",DownloadIdentity.title("S01.E01.mkv"))
        assertEquals("",DownloadIdentity.title("downloadfile.bin"))
        assertTrue(DownloadIdentity.title("One Piece - Episódio 877.mp4").contains("One Piece",true))
    }
    @Test fun videoPlaybackNamesAreNotTreatedAsAnimeTitles() {
        assertTrue(DownloadIdentity.isGenericMediaName("videoplayback.bin.mp4"))
        assertTrue(DownloadIdentity.isGenericMediaName("videoplayback.bin"))
        assertEquals("", DownloadIdentity.title("videoplayback.bin.mp4"))
        assertEquals("", DownloadIdentity.title("Animes Online"))
        assertFalse(DownloadIdentity.isGenericMediaName("One Piece Dublado Episódio 4.mp4"))
    }
    @Test fun capturesEpisodeFromSourcePageInsteadOfOpaqueCdnName() {
        assertEquals("One Piece Dublado Episódio 4",
            DownloadIdentity.episodeTitleFromPage(
                "Animes Online", "https://site.example/one-piece-dublado-episodio-4/"))
        assertEquals("One Piece Dublado Episódio 4",
            DownloadIdentity.episodeTitleFromPage(
                "One Piece Dublado Episódio 4 - Animes Online",
                "https://site.example/watch/123"))
        assertEquals("", DownloadIdentity.episodeTitleFromPage(
            "Animes Online", "https://rr2.googlevideo.com/videoplayback?id=opaque"))
        assertEquals("", DownloadIdentity.episodeTitleFromPage(
            "Animes Online", "https://site.example/watch/123"))
    }

    @Test fun masterSelectsDefaultExternalAudioRendition() {
        val master = "#EXTM3U\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Other\",DEFAULT=NO,URI=\"other/audio.m3u8\"\n" +
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Main\",DEFAULT=YES,URI=\"main/audio.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO=\"audio\"\nvideo.m3u8\n"
        assertEquals("https://cdn.example/show/main/audio.m3u8",
            DownloadHls.audioRenditionUrl(master,"https://cdn.example/show/master.m3u8","audio"))
        assertNull(DownloadHls.audioRenditionUrl(master,"https://cdn.example/show/master.m3u8","missing"))
    }

    @Test fun byterangePlaylistUsesExplicitAndImplicitOffsets() {
        val src="#EXTM3U\n#EXT-X-MAP:URI=\"media.mp4\",BYTERANGE=\"400@0\"\n"+
            "#EXT-X-MEDIA-SEQUENCE:2\n#EXTINF:4,\n#EXT-X-BYTERANGE:800@400\nmedia.mp4\n"+
            "#EXTINF:4,\n#EXT-X-BYTERANGE:600\nmedia.mp4\n#EXT-X-ENDLIST"
        val p=DownloadHls.parse(src,"https://cdn.example/show/playlist.m3u8")
        assertEquals(400L,p.initByteSpan?.length)
        assertEquals(400L,p.segments[0].byteSpan?.start)
        assertEquals(1200L,p.segments[1].byteSpan?.start)
        assertEquals(1799L,p.segments[1].byteSpan?.end)
    }

    @Test fun implicitOffsetAcrossDifferentResourceIsRejected() {
        val src="#EXTM3U\n#EXTINF:3,\n#EXT-X-BYTERANGE:100\nother.m4s\n#EXT-X-ENDLIST"
        try {
            DownloadHls.parse(src,"https://cdn.example/show/playlist.m3u8")
            fail("Playlist without a defined byte offset must be rejected")
        } catch(_:IllegalArgumentException) {}
    }

    @Test fun encryptedRangeKeepsKeyAndSpan() {
        val src="#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"+
            "#EXTINF:3,\n#EXT-X-BYTERANGE:188@0\nsegment.ts\n#EXT-X-ENDLIST"
        val p=DownloadHls.parse(src,"https://cdn.example/show/master.m3u8")
        assertEquals(188L,p.segments.single().byteSpan?.length)
        assertEquals("https://cdn.example/show/key.bin",p.segments.single().key)
    }

}
