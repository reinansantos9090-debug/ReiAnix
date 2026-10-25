package com.reiflix.reiflix_local.ui.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import java.io.File
import org.junit.Test

class ReiAnixLocalArtworkTest {

    @Test
    fun sampleSizeDownscalesWhenSourceExceedsRequestedDimension() {
        assertEquals(2, calculateSampleSize(1000, 700, 512))
        assertEquals(4, calculateSampleSize(2048, 1200, 512))
        assertEquals(1, calculateSampleSize(512, 300, 512))
        assertEquals(2, calculateSampleSize(513, 300, 512))
    }

    @Test
    fun targetDimensionUsesActualMeasuredSlotAndRetainsExistingCap() {
        assertEquals(320, resolveTargetDimensionPx(900, 280, 320))
        assertEquals(450, resolveTargetDimensionPx(420, 450, 1024))
        assertEquals(512, resolveTargetDimensionPx(0, 0, 512))
        assertEquals(0, resolveTargetDimensionPx(400, 400, 0))
    }

    @Test
    fun artworkCacheKeysStayStableAcrossRecompositionInputs() {
        val memoryA = buildArtworkMemoryCacheKey("anime:10:poster", "https://img/a.jpg", 320, 480)
        val memoryB = buildArtworkMemoryCacheKey("anime:10:poster", "https://img/a.jpg", 322, 484)
        val memoryDifferentSize = buildArtworkMemoryCacheKey("anime:10:poster", "https://img/a.jpg", 512, 768)
        val diskA = buildArtworkDiskCacheKey("https://img/a.jpg")
        val diskB = buildArtworkDiskCacheKey("https://img/a.jpg")

        assertEquals(memoryA, memoryB)
        assertEquals(diskA, diskB)
        assertNotEquals(memoryA, memoryDifferentSize)
        val memoryRevisionA = buildArtworkMemoryCacheKey(
            "episode:42:thumbnail", "/cache/episode.jpg", 320, 180, "1024-1000",
        )
        val memoryRevisionB = buildArtworkMemoryCacheKey(
            "episode:42:thumbnail", "/cache/episode.jpg", 320, 180, "1030-2000",
        )
        val diskRevisionA = buildArtworkDiskCacheKey("/cache/episode.jpg", "1024-1000")
        val diskRevisionB = buildArtworkDiskCacheKey("/cache/episode.jpg", "1030-2000")
        assertNotEquals(memoryRevisionA, memoryRevisionB)
        assertNotEquals(diskRevisionA, diskRevisionB)
    }

    @Test
    fun localArtworkSourceRevisionChangesWhenTheFileIsRegenerated() {
        val file = File.createTempFile("reianix-thumb-revision-", ".jpg")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val firstRevision = localArtworkSourceRevision(file.absolutePath)

            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            file.setLastModified(file.lastModified() + 2_000L)
            val secondRevision = localArtworkSourceRevision(file.absolutePath)

            assertNotEquals(firstRevision, secondRevision)
        } finally {
            file.delete()
        }
    }

    @Test
    fun bucketDimensionPxRoundsToNearestStep() {
        assertEquals(320, bucketDimensionPx(320))
        assertEquals(320, bucketDimensionPx(322))
        assertEquals(512, bucketDimensionPx(512))
        assertEquals(0, bucketDimensionPx(0))
    }

    @Test
    fun missingOriginDoesNotCreateAnInvalidCandidateOrPretendToLoad() {
        assertEquals(
            emptyList<String>(),
            artworkCandidates(null, null, null, "file://invalid-remote"),
        )
        assertEquals("none", artworkSourceKind(null))
        assertEquals("remote_http", artworkSourceKind("https://img.example/poster.jpg"))
        assertEquals("content_uri", artworkSourceKind("content://media/poster/1"))
    }

    @Test
    fun episodeThumbnailCandidatesNeverFallBackToRemoteSeriesArtwork() {
        assertEquals(
            listOf("/cache/generated-episode.jpg"),
            artworkCandidatesForType(
                artworkType = "episode_thumbnail",
                localPath = "/cache/generated-episode.jpg",
                fallbackLocalPath = null,
                externalUrl = "https://cdn.example/series-poster.jpg",
                fallbackExternalUrl = "https://cdn.example/backdrop.jpg",
            ),
        )
        assertEquals(
            emptyList<String>(),
            artworkCandidatesForType(
                artworkType = "episode_thumbnail",
                localPath = null,
                fallbackLocalPath = null,
                externalUrl = "https://cdn.example/series-poster.jpg",
                fallbackExternalUrl = null,
            ),
        )
    }

    @Test
    fun composeArtworkPrefersLocalCandidatesBeforeRemoteFallbacks() {
        assertEquals(
            listOf("/cache/poster.jpg", "https://img/a.jpg", "https://img/b.jpg"),
            artworkCandidates("/cache/poster.jpg", null, "https://img/a.jpg", "https://img/b.jpg"),
        )
        assertEquals(
            listOf("/cache/poster.jpg", "https://img/a.jpg", "https://img/b.jpg"),
            artworkCandidates("https://img/a.jpg", "/cache/poster.jpg", "https://img/a.jpg", "https://img/b.jpg"),
        )
        assertEquals(
            listOf("content://media/poster/10", "https://img/a.jpg"),
            artworkCandidates("content://media/poster/10", null, "https://img/a.jpg", "file://invalid-remote"),
        )
        assertEquals(
            emptyList<String>(),
            artworkCandidates("https://img/a.jpg", "https://img/b.jpg", null, "file://invalid-remote"),
        )
    }
}
