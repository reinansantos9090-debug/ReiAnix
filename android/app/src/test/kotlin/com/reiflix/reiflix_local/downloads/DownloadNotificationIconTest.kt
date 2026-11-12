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
    @Test fun activeDownloadsUseOfficialReiAnixStatusIcon() {
        listOf("QUEUED", "DOWNLOADING", "WAITING", "VERIFYING", "SAVING").forEach { state ->
            assertEquals(state, R.drawable.ic_reianix_notification, DownloadScheduling.iconForState(state))
        }
        assertEquals(R.drawable.ic_reianix_notification, DownloadScheduling.iconForState(null))
    }

    @Test fun completedDownloadsUseDistinctCheckmarkIcon() {
        assertEquals(R.drawable.ic_reianix_download_complete, DownloadScheduling.iconForState("COMPLETE"))
    }

    @Test fun pausedAndFailedDownloadsKeepTheirExistingIcon() {
        listOf("PAUSED", "ERROR", "CANCELLED", "CORRUPT").forEach { state ->
            assertEquals(state, android.R.drawable.stat_sys_download, DownloadScheduling.iconForState(state))
        }
    }
}
