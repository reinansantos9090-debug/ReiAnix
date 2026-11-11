package com.reiflix.reiflix_local.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserMediaOptionTest {
    private fun source(url: String, quality: String, format: String = "video") =
        BrowserMediaOption(url,"https://player.example/episode","frame-1",quality,format,"source",receivedAt=100L)

    @Test fun listsEveryIndependentQualityInsteadOfOnlyThePlayingRendition() {
        val low=source("https://cdn.example/episode-360p.mp4","360p")
        val high=source("https://cdn.example/episode-720p.mp4","720p")
        val playing=PlayerMediaCandidate(
            url=low.url,page=low.page,frame="frame-1",duration=1450.0,
            width=640,height=360,renderedWidth=640.0,renderedHeight=360.0,
            source="video",receivedAt=100L,
        )
        val results=BrowserMediaResults.combine(listOf(playing),listOf(low,high))
        assertEquals(2,results.size)
        assertEquals(listOf("720p","360p"),results.map { it.quality })
        assertTrue(results.last().capture!=null)
        assertTrue(results.first().capture==null)
    }

    @Test fun prefersDirectMp4OverTsAndHlsAtTheSameResolution() {
        val mp4=source("https://cdn.example/720.mp4","720p")
        val ts=source("https://cdn.example/720.ts","720p")
        val hls=source("https://cdn.example/720.m3u8","720p","hls")
        val results=BrowserMediaResults.combine(emptyList(),listOf(hls,ts,mp4))
        assertEquals(listOf(mp4.url,ts.url,hls.url),results.map { it.url })
    }

    @Test fun excludesUnrelatedOrDangerousLinks() {
        assertFalse(BrowserMediaOption.eligible(source("https://ads.example/ads/clip-720p.mp4","720p")))
        assertFalse(BrowserMediaOption.eligible(source("javascript:alert(1)","720p")))
        assertFalse(BrowserMediaOption.eligible(source("https://cdn.example/page.html","1080p")))
        assertFalse(BrowserMediaOption.eligible(source("https://user:pass@cdn.example/episode.mp4","480p")))
        assertTrue(BrowserMediaOption.eligible(source("https://cdn.example/stream.m3u8","1080p","hls")))
        assertTrue(BrowserMediaOption.eligible(source("https://cdn.example/manifest.mpd","1080p","dash")))
        assertTrue(BrowserMediaOption.eligible(
            source("https://cdn.example/media?id=42","720p").copy(mimeHint="video/mp4"),
        ))
        assertTrue(BrowserMediaOption.eligible(
            source("https://cdn.example/variant?token=abc","720p","hls")
                .copy(origin="master",mimeHint="application/vnd.apple.mpegurl"),
        ))
    }

    @Test fun preservesWhetherAQualityRequiresUnsupportedSeparateAudio() {
        val only=source("https://cdn.example/1080.m3u8","1080p","hls").copy(
            origin="master",separateAudio=true,
        )
        val found=BrowserMediaResults.combine(emptyList(),listOf(only))
        assertEquals(1,found.size)
        assertTrue(found.first().separateAudio)
    }
}
