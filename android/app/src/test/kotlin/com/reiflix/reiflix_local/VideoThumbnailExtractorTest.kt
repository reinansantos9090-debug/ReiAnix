package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun normalizesCanonicalFilePathsAndKeepsContentUris() {
        val file = VideoThumbnailExtractor.normalizeLocalMediaReference("/storage/emulated/0/Anime/Episode 01.mkv")
        val fileUri = java.net.URI(file)
        assertEquals("file", fileUri.scheme)
        assertEquals("/storage/emulated/0/Anime/Episode 01.mkv", fileUri.path)

        val content = VideoThumbnailExtractor.normalizeLocalMediaReference("content://media/external/video/media/71")
        val contentUri = java.net.URI(content)
        assertEquals("content", contentUri.scheme)
        assertEquals("/external/video/media/71", contentUri.path)
    }

    @Test
    fun rejectsAbsentAndNonLocalEpisodeSources() {
        assertEquals(null, VideoThumbnailExtractor.normalizeLocalMediaReference(null))
        assertEquals(null, VideoThumbnailExtractor.normalizeLocalMediaReference("  "))
        assertEquals(null, VideoThumbnailExtractor.normalizeLocalMediaReference("https://example.invalid/episode.mkv"))
    }

    @Test
    fun frameCandidatesAvoidTheZeroTimestampAndStayWithinAnEpisode() {
        val durationMs = 24L * 60L * 1_000L
        val candidates = VideoThumbnailExtractor.frameCandidateTimestampsUs(durationMs)

        assertEquals(8, candidates.size)
        assertTrue(candidates.all { it > 0L && it < durationMs * 1_000L })
        assertTrue(candidates.zipWithNext().all { (first, second) -> first < second })
        assertTrue(VideoThumbnailExtractor.frameCandidateTimestampsUs(0L).size <= 8)
    }

    @Test
    fun frameQualityRejectsBlackWhiteAndUniformImages() {
        val black = VideoThumbnailExtractor.analyzeLuminanceSamples(IntArray(400) { 0 })!!
        val white = VideoThumbnailExtractor.analyzeLuminanceSamples(IntArray(400) { 255 })!!
        val uniformGray = VideoThumbnailExtractor.analyzeLuminanceSamples(IntArray(400) { 118 })!!

        assertFalse(VideoThumbnailExtractor.isUsableFrame(black))
        assertFalse(VideoThumbnailExtractor.isUsableFrame(white))
        assertFalse(VideoThumbnailExtractor.isUsableFrame(uniformGray))
    }

    @Test
    fun frameQualityCanKeepAStructuredDarkNightScene() {
        val darkScene = IntArray(400) { index ->
            5 + (index % 20) * 2 + (index / 20) % 3
        }
        val metrics = VideoThumbnailExtractor.analyzeLuminanceSamples(darkScene)!!

        assertTrue(metrics.meanLuminance < 40.0)
        assertTrue(VideoThumbnailExtractor.isUsableFrame(metrics))
    }

    @Test
    fun detailedFrameScoresHigherThanAFlatButTechnicallyNonExtremeFrame() {
        val flat = VideoThumbnailExtractor.analyzeLuminanceSamples(IntArray(400) { 80 + (it % 16) })!!
        val detailed = VideoThumbnailExtractor.analyzeLuminanceSamples(IntArray(400) { (it * 37) % 220 + 18 })!!

        assertTrue(VideoThumbnailExtractor.isUsableFrame(flat))
        assertTrue(
            VideoThumbnailExtractor.frameQualityScore(detailed) >
                VideoThumbnailExtractor.frameQualityScore(flat),
        )
    }
}
