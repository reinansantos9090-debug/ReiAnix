package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.player.LocalSubtitleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSubtitleResolverTest {
    @Test
    fun maps_only_media3_supported_local_sidecar_types() {
        assertEquals("application/x-subrip", LocalSubtitleResolver.mimeForExtension("srt"))
        assertEquals("text/x-ssa", LocalSubtitleResolver.mimeForExtension("ssa"))
        assertEquals("text/x-ssa", LocalSubtitleResolver.mimeForExtension("ass"))
        assertEquals("text/vtt", LocalSubtitleResolver.mimeForExtension("vtt"))
        assertEquals("application/pgs", LocalSubtitleResolver.mimeForExtension("sup"))
        assertNull(LocalSubtitleResolver.mimeForExtension("idx"))
        assertNull(LocalSubtitleResolver.mimeForExtension("nfo"))
    }
}
