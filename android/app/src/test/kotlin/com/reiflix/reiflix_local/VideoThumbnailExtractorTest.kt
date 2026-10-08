package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VideoThumbnailExtractorTest {
    @Test
    fun durationMetadataIsParsedAsMillisecondsWithoutUnitConversion() {
        assertEquals(123456L, VideoThumbnailExtractor.parseDurationMs("123456"))
        assertEquals(0L, VideoThumbnailExtractor.parseDurationMs(null))
        assertEquals(0L, VideoThumbnailExtractor.parseDurationMs("invalid"))
        assertEquals(0L, VideoThumbnailExtractor.parseDurationMs("-42"))
    }

    @Test
    fun stableIdentityChangesOnlyWhenCacheRelevantInputsChange() {
        val sameA = VideoThumbnailExtractor.cacheKey("stable-media-id", 123L, 456L)
        val sameB = VideoThumbnailExtractor.cacheKey("stable-media-id", 123L, 456L)
        val differentSize = VideoThumbnailExtractor.cacheKey("stable-media-id", 124L, 456L)
        val differentMtime = VideoThumbnailExtractor.cacheKey("stable-media-id", 123L, 457L)
        val differentIdentity = VideoThumbnailExtractor.cacheKey("other-media-id", 123L, 456L)

        assertEquals(sameA, sameB)
        assertNotEquals(sameA, differentSize)
        assertNotEquals(sameA, differentMtime)
        assertNotEquals(sameA, differentIdentity)
    }
}
