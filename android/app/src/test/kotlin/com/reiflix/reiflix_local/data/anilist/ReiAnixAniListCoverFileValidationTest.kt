package com.reiflix.reiflix_local.data.anilist

import android.graphics.Bitmap
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReiAnixAniListCoverFileValidationTest {
    @Test
    fun onlyReadableDecodableNonEmptyLocalImagesAreAccepted() {
        val directory = File(
            System.getProperty("java.io.tmpdir"),
            "reianix-cover-validation-${UUID.randomUUID()}",
        )
        assertTrue(directory.mkdirs())

        try {
            val validImage = File(directory, "valid.png")
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                val compressed = validImage.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue(compressed)
            } finally {
                bitmap.recycle()
            }

            val emptyFile = File(directory, "empty.png").apply { createNewFile() }
            val nonImage = File(directory, "not-an-image.png").apply {
                writeText("not an image")
            }
            val truncatedImage = File(directory, "truncated.png").apply {
                val bytes = validImage.readBytes()
                writeBytes(bytes.copyOfRange(0, minOf(33, bytes.size)))
            }

            assertTrue(isUsableLocalCoverFile(validImage.absolutePath))
            assertFalse(isUsableLocalCoverFile(emptyFile.absolutePath))
            assertFalse(isUsableLocalCoverFile(nonImage.absolutePath))
            assertFalse(isUsableLocalCoverFile(truncatedImage.absolutePath))
            assertFalse(isUsableLocalCoverFile(File(directory, "missing.png").absolutePath))
            assertFalse(isUsableLocalCoverFile("   "))
        } finally {
            directory.deleteRecursively()
        }
    }
}
