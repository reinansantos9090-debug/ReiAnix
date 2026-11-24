package com.reianix.reianix_local.player.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Byte-range handling allows TV seeking without reading the entire episode. */
class ReiAnixTvMediaServerRangeTest {
    @Test fun supportsWholeFileAndSingleRange() {
        assertEquals(0L to 999L, ReiAnixTvMediaServer.parseRange(null, 1000))
        assertEquals(400L to 999L, ReiAnixTvMediaServer.parseRange("bytes=400-", 1000))
        assertEquals(20L to 49L, ReiAnixTvMediaServer.parseRange("bytes=20-49", 1000))
        assertEquals(900L to 999L, ReiAnixTvMediaServer.parseRange("bytes=-100", 1000))
        assertEquals(0L to 999L, ReiAnixTvMediaServer.parseRange("bytes=-2000", 1000))
    }

    @Test fun rejectsMalformedOrOutOfBoundsRanges() {
        assertNull(ReiAnixTvMediaServer.parseRange("bytes=2000-", 1000))
        assertNull(ReiAnixTvMediaServer.parseRange("bytes=90-80", 1000))
        assertNull(ReiAnixTvMediaServer.parseRange("bytes=-0", 1000))
        assertNull(ReiAnixTvMediaServer.parseRange("bytes=4-10,20-30", 1000))
        assertNull(ReiAnixTvMediaServer.parseRange("bytes=foo-bar", 1000))
    }
}
