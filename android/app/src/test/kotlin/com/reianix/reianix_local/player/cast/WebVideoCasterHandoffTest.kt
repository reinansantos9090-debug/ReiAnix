package com.reianix.reianix_local.player.cast

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebVideoCasterHandoffTest {
    @Test fun sharesPublishedVideoExactlyLikeOfficialExternalPlayerIntent() {
        val url = "http://192.168.1.10:45678/media/abc123"
        val intent = WebVideoCasterHandoff.createVideoIntent(
            url, "Solo Leveling - Episódio 01",
        )
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.instantbits.cast.webvideo", intent.`package`)
        assertEquals(url, intent.dataString)
        assertEquals("video/*", intent.type)
        assertEquals("Solo Leveling - Episódio 01", intent.getStringExtra("title"))
        assertTrue(intent.getBooleanExtra("secure_uri", false))
        @Suppress("DEPRECATION")
        val headers = intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")
        assertNotNull(headers)
        assertTrue(headers!!.isEmpty)
    }

    @Test fun doesNotLeakUnrelatedHttpUrlOrLocalStorageUri() {
        for (url in listOf(
            "content://media/external/video/5",
            "file:///storage/emulated/0/Movies/test.mkv",
            "https://example.com/video.mp4",
            "http://192.168.1.10:45678/other",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                WebVideoCasterHandoff.createVideoIntent(url, "Episode")
            }
        }
    }

    @Test fun offersOfficialPlayStoreLinkWhenAppIsMissing() {
        assertEquals(
            "market://details?id=com.instantbits.cast.webvideo",
            WebVideoCasterHandoff.storeIntent().dataString,
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.instantbits.cast.webvideo",
            WebVideoCasterHandoff.storeWebIntent().dataString,
        )
    }
}
