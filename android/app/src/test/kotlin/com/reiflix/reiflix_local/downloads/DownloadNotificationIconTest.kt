package com.reiflix.reiflix_local.downloads

import com.reiflix.reiflix_local.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadNotificationIconTest {
    @Test fun activeAndPendingDownloadsUseTheOfficialReiAnixMark() {
        listOf("QUEUED", "DOWNLOADING", "WAITING", "VERIFYING", "SAVING").forEach { state ->
            assertEquals(state, R.drawable.ic_reianix_download_active, DownloadScheduling.downloadStateIcon(state))
        }
        assertEquals(R.drawable.ic_reianix_download_active, DownloadScheduling.downloadStateIcon(null))
    }

    @Test fun completedDownloadsKeepTheirDedicatedCheckMark() {
        assertEquals(R.drawable.ic_reianix_download_done, DownloadScheduling.downloadStateIcon("COMPLETE"))
    }

    @Test fun pausedAndFailedDownloadsPreserveTheirDistinctIndicators() {
        assertEquals(R.drawable.ic_reianix_download_paused, DownloadScheduling.downloadStateIcon("PAUSED"))
        listOf("ERROR", "CORRUPT").forEach { state ->
            assertEquals(state, R.drawable.ic_reianix_download_error, DownloadScheduling.downloadStateIcon(state))
        }
    }
}
