package com.reiflix.reiflix_local.downloads

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadDisplaySizeTest {
    private fun item(bytes: Long, total: Long = -1, state: String = "DOWNLOADING",
        discovered: Long = 0, exact: Boolean = false,
        hls: Boolean = false, hlsEstimate: Long = 0, parts: Int = 0,
        partIndex: Int = 0): DownloadItem = DownloadItem(JSONObject()
        .put("id", "test").put("name", "Episode.mp4").put("state", state)
        .put("bytes", bytes).put("total", total)
        .put("detectedSize", discovered).put("detectedSizeExact", exact)
        .put("hls", hls).put("hlsEstimatedTotal", hlsEstimate)
        .put("hlsCount", parts).put("hlsIndex", partIndex))

    @Test fun usesEditorSizeUntilTransferSuppliesItsAuthoritativeTotal() {
        val early=item(bytes=10_000_000,discovered=173_000_000,exact=true)
        assertEquals(173_000_000L,downloadDisplaySize(early)?.bytes)
        assertTrue(downloadDisplaySize(early)?.exact == true)
        assertEquals(5,downloadPercent(early))
        val confirmed=item(bytes=20_000_000,total=174_200_000,
            discovered=173_000_000,exact=true)
        assertEquals(174_200_000L,downloadDisplaySize(confirmed)?.bytes)
        assertEquals(11,downloadPercent(confirmed))
    }

    @Test fun hlsEstimateRemainsApproximateAndUsesSegmentProgress() {
        val hls=item(bytes=8_000_000,discovered=173_000_000,exact=false,
            hls=true,parts=20,partIndex=1)
        assertEquals(173_000_000L,downloadDisplaySize(hls)?.bytes)
        assertFalse(downloadDisplaySize(hls)!!.exact)
        assertEquals(5,downloadPercent(hls))
        val refined=item(bytes=20_000_000,discovered=173_000_000,exact=false,
            hls=true,hlsEstimate=168_000_000,parts=20,partIndex=4)
        assertEquals(168_000_000L,downloadDisplaySize(refined)?.bytes)
        assertEquals(20,downloadPercent(refined))
    }

    @Test fun neverUsesGuessedLengthAsLimitOrFalse100Percent() {
        val estimated=item(bytes=4_000_000,discovered=175_000_000,exact=false)
        assertNull(downloadPercent(estimated))
        val almost=item(bytes=173_000_000,discovered=173_000_000,exact=true)
        assertEquals(99,downloadPercent(almost))
        val completed=item(bytes=174_200_000,total=174_200_000,
            state="COMPLETE",discovered=173_000_000,exact=true)
        assertEquals(100,downloadPercent(completed))
    }

    @Test fun discardsStaleSizeWhenMoreBytesThanExpectedArrive() {
        val invalid=item(bytes=200_000_000,discovered=173_000_000,exact=true)
        assertNull(downloadDisplaySize(invalid))
        assertNull(downloadPercent(invalid))
    }
}
