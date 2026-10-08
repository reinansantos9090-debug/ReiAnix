package com.reiflix.reiflix_local.ui.artwork

import org.junit.Assert.assertEquals
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
        val memoryB = buildArtworkMemoryCacheKey("anime:10:poster", "https://img/a.jpg", 320, 480)
        val memoryDifferentSize = buildArtworkMemoryCacheKey("anime:10:poster", "https://img/a.jpg", 512, 768)
        val diskA = buildArtworkDiskCacheKey("https://img/a.jpg")
        val diskB = buildArtworkDiskCacheKey("https://img/a.jpg")

        assertEquals(memoryA, memoryB)
        assertEquals(diskA, diskB)
        assertNotEquals(memoryA, memoryDifferentSize)
    }
}
