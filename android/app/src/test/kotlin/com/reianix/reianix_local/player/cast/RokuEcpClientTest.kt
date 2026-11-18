package com.reianix.reianix_local.player.cast

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RokuEcpClientTest {
    @Test fun receiverCheckRequiresOurSideloadedApp() {
        val apps = """<apps><app id="837">YouTube</app>
            <app type="appl" id="dev" version="1.0.1">ReiAnix Receiver</app></apps>"""
        assertTrue(RokuEcpClient.hasReiAnixReceiver(apps))
        assertFalse(RokuEcpClient.hasReiAnixReceiver("<apps><app id=\"dev\">Other app</app></apps>"))
        assertFalse(RokuEcpClient.hasReiAnixReceiver("<apps><app id=\"837\">ReiAnix Receiver</app></apps>"))
    }

    @Test fun launchEncodesUrlAndTitleAndPreservesResumeSeconds() {
        val path = RokuEcpClient.buildLaunchPath(
            "http://192.168.1.8:32123/media/a-b", "Episódio 02 | ReiAnix",
            "mp4", 125_000L, true,
        )
        val parsed = Uri.parse("http://roku:8060$path")
        assertEquals("/launch/dev", parsed.path)
        assertEquals("http://192.168.1.8:32123/media/a-b", parsed.getQueryParameter("contentId"))
        assertEquals("Episódio 02 | ReiAnix", parsed.getQueryParameter("title"))
        assertEquals("mp4", parsed.getQueryParameter("format"))
        assertEquals("125", parsed.getQueryParameter("position"))
        assertEquals("1", parsed.getQueryParameter("autoplay"))
    }

    @Test fun playbackFormatIsExplicitAndNeverPretendsToTranscode() {
        assertEquals("mp4", RokuEcpClient.streamFormat("video/mp4"))
        assertEquals("mkv", RokuEcpClient.streamFormat("video/x-matroska"))
        assertThrows(java.io.IOException::class.java) {
            RokuEcpClient.streamFormat("video/webm")
        }
    }
}
