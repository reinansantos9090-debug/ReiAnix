package com.reiflix.reiflix_local.downloads

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

}
