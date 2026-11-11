package com.reiflix.reiflix_local.downloads

import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadIdentityPersistenceTest {
    @Test
    fun providerDisplayNameCannotReplaceUserSelectedEpisodeName() {
        val expected = "Boku no Hero Academia 4 Dublado Episódio I - Animes Online.mp4"
        val id = "00000000-0000-0000-0000-000000000011"
        val uri = "content://com.reiflix.reiflix_local.downloads/$id/${Uri.encode(expected)}"
        // Models a prior release where a provider renamed the SQLite record.
        val item = DownloadItem(JSONObject()
            .put("id", id)
            .put("virtualUri", uri)
            .put("name", "Animes Online.mp4")
            .put("state", "COMPLETE"))
        assertEquals(expected, item.name)
    }

    @Test
    fun oldRecordWithoutVirtualUriStillUsesStoredName() {
        val item = DownloadItem(JSONObject()
            .put("id", "legacy")
            .put("name", "Episode 02.mkv"))
        assertEquals("Episode 02.mkv", item.name)
    }
}
