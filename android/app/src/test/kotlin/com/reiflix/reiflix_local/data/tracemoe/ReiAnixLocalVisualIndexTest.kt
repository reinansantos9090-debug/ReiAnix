package com.reiflix.reiflix_local.data.tracemoe

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReiAnixLocalVisualIndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsAConfirmedReferenceAndFindsTheSameThumbnailAfterRecreation() = runBlocking {
        val image = createPatternThumbnail(File(temporaryFolder.root, "episode.jpg"))
        val indexFile = File(temporaryFolder.root, "data/visual-index/references.json")
        val firstIndex = ReiAnixLocalVisualIndex(indexFile)

        firstIndex.rememberConfirmedReference(image, 1234)
        val firstResult = firstIndex.search(image)
        val secondResult = ReiAnixLocalVisualIndex(indexFile).search(image)

        assertEquals(1, firstResult.size)
        assertEquals(1234, firstResult.single().anilistId)
        assertEquals(1.0, firstResult.single().similarity, 0.0)
        assertEquals(firstResult, secondResult)
        assertTrue(indexFile.isFile)
    }

    @Test
    fun localSimilarityUsesTheSameSquaredL2MappingAsMilvus() {
        val first = List(33) { 0 }
        val second = first.toMutableList().apply { this[0] = 4 }

        assertEquals(
            (255.0 - 16.0) / 255.0,
            ReiAnixMpeg7ColorLayout.similarity(first, second),
            0.000001,
        )
    }

    @Test
    fun doesNotProduceAResultBeforeAReferenceHasBeenConfirmed() = runBlocking {
        val image = createPatternThumbnail(File(temporaryFolder.root, "episode.jpg"))
        val index = ReiAnixLocalVisualIndex(File(temporaryFolder.root, "empty-index.json"))

        assertTrue(index.search(image).isEmpty())
    }

    @Test
    fun treatsUniformImagesAsUnsupportedInsteadOfInventingAVisualMatch() = runBlocking {
        val uniform = File(temporaryFolder.root, "uniform.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff777777.toInt())
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                uniform.writeBytes(output.toByteArray())
            }
        } finally {
            bitmap.recycle()
        }

        val index = ReiAnixLocalVisualIndex(File(temporaryFolder.root, "index.json"))
        assertTrue(index.search(uniform).isEmpty())
    }

    private fun createPatternThumbnail(file: File): File {
        val width = 96
        val height = 54
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val light = ((x / 8) + (y / 6)) % 2 == 0
                val value = if (light) 238 else 18
                val red = (value + (x * 3 % 17)).coerceAtMost(255)
                val green = (value + (y * 5 % 19)).coerceAtMost(255)
                val blue = (value + ((x + y) * 2 % 23)).coerceAtMost(255)
                pixels[y * width + x] =
                    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                file.writeBytes(output.toByteArray())
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}