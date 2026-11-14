package com.reianix.reianix_local.downloads

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
    @Test fun aDirectLowerResolutionFileRanksAboveHigherResolutionManifest() {
        val direct=source("https://cdn.example/episode-480p.mp4","480p")
        val manifest=source("https://player.example/master.m3u8","1080p","hls")
        val list=BrowserMediaResults.combine(emptyList(),listOf(manifest,direct))
        assertEquals(listOf(direct.url,manifest.url),list.map{it.url})
    }

    @Test fun anUnverifiedPlayerPageNeverOutranksDirectMedia() {
        val direct=source("https://cdn.example/show/episode.mp4","720p")
        val fakePage=PlayerMediaCandidate("https://player.example/watch/episode-1",
            "https://player.example/watch/episode-1","f1",1200.0,
            1920,1080,640.0,360.0,"video",100L)
        val list=BrowserMediaResults.combine(listOf(fakePage),listOf(direct))
        assertEquals(direct.url,list.first().url)
    }

    @Test fun knownQualityWinsOverUnknownDuplicateInEitherOrder() {
        val known=source("https://cdn.example/episode.mp4","720p")
        val unknown=known.copy(quality="Qualidade não informada")
        for(options in listOf(listOf(known,unknown),listOf(unknown,known))) {
            val results=BrowserMediaResults.combine(emptyList(),options)
            assertEquals(1,results.size)
            assertEquals("720p",results.single().quality)
        }
    }
    @Test fun expandedMasterIsNotAnUnknownThirdResolution() {
        val master=source("https://cdn.example/master.m3u8","Qualidade não informada","hls")
        val high=source("https://cdn.example/1080.m3u8","1080p","hls")
            .copy(masterUrl=master.url,estimatedBytes=500_000_000L)
        val medium=source("https://cdn.example/720.m3u8","720p","hls")
            .copy(masterUrl=master.url)
        val results=BrowserMediaResults.combine(emptyList(),listOf(master,high,medium))
        assertEquals(listOf("1080p","720p"),results.map {it.quality})
        assertEquals(500_000_000L,results.first().estimatedBytes)
        // An unrelated standalone playlist must remain selectable.
        assertEquals(1,BrowserMediaResults.combine(emptyList(),listOf(master)).size)
    }
}
