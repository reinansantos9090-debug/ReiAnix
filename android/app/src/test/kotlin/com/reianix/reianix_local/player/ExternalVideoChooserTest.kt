package com.reianix.reianix_local.player

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExternalVideoChooserTest {
    @Suppress("DEPRECATION")
    private fun targetIntent(chooser: Intent): Intent =
        chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            ?: error("Android chooser sem intent de vídeo")

    @Test fun opensSafUriWithTemporaryReadGrantAndNoForcedApp() {
        val context = RuntimeEnvironment.getApplication()
        val videoUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2Fanime.mkv")
        val chooser = ExternalVideoChooser.createIntent(context, videoUri)
        val target = targetIntent(chooser)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(videoUri, target.data)
        assertEquals("video/*", target.type)
        assertEquals(null, target.`package`)
        assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(videoUri, target.clipData?.getItemAt(0)?.uri)
        assertEquals(videoUri, chooser.clipData?.getItemAt(0)?.uri)
        assertEquals("Abrir com...", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
    }

    @Test fun acceptsMediaStoreVideos() {
        val context = RuntimeEnvironment.getApplication()
        val videoUri = Uri.parse("content://media/external/video/media/37")
        val target = targetIntent(ExternalVideoChooser.createIntent(context, videoUri))
        assertEquals(videoUri, target.data)
        assertEquals(Intent.ACTION_VIEW, target.action)
    }

    @Test fun wrapsFileUriInsteadOfExposingFileScheme() {
        val context = RuntimeEnvironment.getApplication()
        val localFile = File(context.cacheDir, "external-chooser-test.mp4")
        localFile.writeBytes(byteArrayOf(0, 1, 2, 3))
        try {
            val target = targetIntent(
                ExternalVideoChooser.createIntent(context, Uri.fromFile(localFile)),
            )
            assertNotNull(target.data)
            assertEquals("content", target.data?.scheme)
            assertFalse(target.dataString?.startsWith("file:") == true)
            assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            localFile.delete()
        }
    }

    @Test fun rejectsUnrelatedRemoteOrUnsupportedUri() {
        val context = RuntimeEnvironment.getApplication()
        for (uri in listOf(
            Uri.parse("https://example.com/anime.mp4"),
            Uri.parse("http://192.168.0.10/video.mp4"),
            Uri.parse("javascript:alert(1)"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ExternalVideoChooser.createIntent(context, uri)
            }
        }
    }
}
