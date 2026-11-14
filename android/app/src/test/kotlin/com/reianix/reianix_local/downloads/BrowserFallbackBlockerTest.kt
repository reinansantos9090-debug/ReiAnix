package com.reianix.reianix_local.downloads

import java.io.StringReader
import org.junit.Assert.*
import org.junit.Test

class BrowserFallbackBlockerTest {
    @Test fun blocksAdsWithoutBreakingVideoOrExceptions() {
        val dollar='$'
        val lines=listOf(
            "! demo", "||adserver.example^"+dollar+"third-party",
            "||ads.cdn.test^"+dollar+"image",
            "@@||ads.cdn.test^"+dollar+"image,domain=video.test",
            "||video.cdn.test^"+dollar+"media",
            "##.ad-banner", "video.test##.overlay-ad"
        ).joinToString("\n")
        val f=BrowserFallbackBlocker.compile(listOf(false to StringReader(lines)))
        assertTrue(f.blocks("https://adserver.example/track.js","https://video.test/watch","script",false))
        assertFalse(f.blocks("https://ads.cdn.test/a.png","https://video.test/watch","image",false))
        assertTrue(f.blocks("https://ads.cdn.test/a.png","https://other.test/","image",false))
        assertFalse(f.blocks("https://adserver.example/","https://video.test/","document",false))
        assertTrue(f.blocks("https://video.cdn.test/movie.mp4","https://video.test","media",false))
        assertTrue(f.css("https://video.test/watch",false).contains(".overlay-ad"))
        assertTrue(f.css("https://video.test/watch",false).contains(".ad-banner"))
    }
    @Test fun keepsAdvancedListsOutOfBalancedMode() {
        val f=BrowserFallbackBlocker.compile(listOf(true to StringReader("||tracker.test^")))
        assertFalse(f.blocks("https://tracker.test/pixel","https://example.org","image",false))
        assertTrue(f.blocks("https://tracker.test/pixel","https://example.org","image",true))
    }
}
