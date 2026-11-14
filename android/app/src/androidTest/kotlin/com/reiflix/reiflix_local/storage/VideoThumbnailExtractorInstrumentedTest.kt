package com.reiflix.reiflix_local.storage

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoThumbnailExtractorInstrumentedTest {
    @Test
    fun missingThumbnailCacheDirectoryIsCreatedBeforeExtraction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val thumbnailCache = File(context.cacheDir, "reiflix/thumbnails")
        thumbnailCache.deleteRecursively()
        assertTrue(!thumbnailCache.exists())

        val source = File(context.cacheDir, "invalid-episode-thumbnail-source.mp4")
        source.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5))
        try {
            // The fixture is deliberately not a decodable video. This test is
            // about cache-directory recovery, not successful frame decoding.
            VideoThumbnailExtractor.extract(
                context,
                Uri.fromFile(source),
                source.length(),
                source.lastModified(),
                "episode-cache-directory-test",
            )
            assertTrue("thumbnail cache directory should be recreated", thumbnailCache.isDirectory)
        } finally {
            source.delete()
            thumbnailCache.deleteRecursively()
        }
    }
}
