package com.reiflix.reiflix_local.storage

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VideoThumbnailFileValidationTest {

    @Test
    fun rejectsBlackAndWhiteJpegsThatAreOtherwiseDecodable() {
        listOf(Color.BLACK, Color.WHITE).forEach { color ->
            val bitmap = Bitmap.createBitmap(80, 45, Bitmap.Config.ARGB_8888)
            val file = File.createTempFile("reianix-invalid-frame-", ".jpg")
            try {
                bitmap.eraseColor(color)
                file.outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
                }

                // The files are real, decodable JPEGs; rejection must be visual, not file-format based.
                assertFalse(VideoThumbnailExtractor.isUsableThumbnailFile(file.absolutePath))
            } finally {
                bitmap.recycle()
                file.delete()
            }
        }
    }

    @Test
    fun acceptsAVisuallyStructuredDarkSceneFromTheSavedJpeg() {
        val bitmap = Bitmap.createBitmap(80, 45, Bitmap.Config.ARGB_8888)
        val file = File.createTempFile("reianix-dark-scene-", ".jpg")
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val gray = 6 + ((x * 3 + y * 5) % 42)
                    bitmap.setPixel(x, y, Color.rgb(gray, gray, gray))
                }
            }
            file.outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output))
            }

            assertTrue(VideoThumbnailExtractor.isUsableThumbnailFile(file.absolutePath))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    @Test
    fun rejectsCorruptAndEmptyCacheFiles() {
        val empty = File.createTempFile("reianix-empty-frame-", ".jpg")
        val corrupt = File.createTempFile("reianix-corrupt-frame-", ".jpg")
        try {
            corrupt.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
            assertFalse(VideoThumbnailExtractor.isUsableThumbnailFile(empty.absolutePath))
            assertFalse(VideoThumbnailExtractor.isUsableThumbnailFile(corrupt.absolutePath))
        } finally {
            empty.delete()
            corrupt.delete()
        }
    }
}
