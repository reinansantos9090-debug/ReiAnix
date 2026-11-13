package com.reianix.reianix_local.downloads

import org.junit.Assert.*
import org.junit.Test

class BrowserHlsDiscoveryTest {
    private val source=BrowserMediaOption("https://cdn.example/master.m3u8",
        "https://page.example/episode","player","Qualidade não informada","hls","source")

    @Test fun expandsBeforeAnyPlaybackMetadataAndUsesFinalRedirectBase() {
        val text="""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
            high/index.m3u8?token=abc
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            medium/index.m3u8
        """.trimIndent()
        val variants=BrowserHlsDiscovery.variants(text,"https://media.example/vod/master.m3u8",source)
        assertEquals(listOf("1080p","720p"),variants.map {it.quality})
        assertEquals("https://media.example/vod/high/index.m3u8?token=abc",variants.first().url)
        assertTrue(variants.all {it.masterUrl==source.url && it.page==source.page})
        assertFalse(variants.any {it.quality=="360p"})
        val choices=BrowserMediaResults.combine(emptyList(),listOf(source)+variants)
        assertEquals(listOf("1080p","720p"),choices.map {it.quality})
    }

    @Test fun rejectsHtmlAndUnsafeChildrenWithoutInventingResolution() {
        assertTrue(BrowserHlsDiscovery.variants("<html>403</html>",source.url,source).isEmpty())
        val text="""
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080
            javascript:alert(1)
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            unlabeled.m3u8
        """.trimIndent()
        assertTrue(BrowserHlsDiscovery.variants(text,source.url,source).isEmpty())
    }
}
