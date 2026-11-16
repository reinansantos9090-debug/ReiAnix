package com.reianix.reianix_local

import com.reianix.reianix_local.player.cast.LocalCastMediaServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LocalCastMediaServerTest {
    @Test fun fullFileRange() {
        assertEquals(0L to 999L, LocalCastMediaServer.parseRange(null, 1000L))
    }

    @Test fun prefixRange() {
        assertEquals(100L to 999L, LocalCastMediaServer.parseRange("bytes=100-", 1000L))
    }

    @Test fun boundedRange() {
        assertEquals(100L to 199L, LocalCastMediaServer.parseRange("bytes=100-199", 1000L))
    }

    @Test fun suffixRange() {
        assertEquals(900L to 999L, LocalCastMediaServer.parseRange("bytes=-100", 1000L))
    }

    @Test fun oversizeSuffixIsClamped() {
        assertEquals(0L to 999L, LocalCastMediaServer.parseRange("bytes=-2000", 1000L))
    }

    @Test fun rangePastEndIsRejected() {
        assertThrows(IOException::class.java) {
            LocalCastMediaServer.parseRange("bytes=1000-", 1000L)
        }
    }

    @Test fun multipleRangesAreRejected() {
        assertThrows(IOException::class.java) {
            LocalCastMediaServer.parseRange("bytes=0-2,4-5", 1000L)
        }
    }

    @Test fun zeroSizeIsRejected() {
        assertThrows(IOException::class.java) {
            LocalCastMediaServer.parseRange(null, 0L)
        }
    }
}
