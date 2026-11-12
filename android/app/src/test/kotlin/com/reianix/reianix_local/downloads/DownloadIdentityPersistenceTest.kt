package com.reianix.reianix_local.downloads

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
        val uri = "content://com.reianix.reianix_local.downloads/$id/${Uri.encode(expected)}"
        // Models a prior release where a provider renamed the SQLite record.
        val item = DownloadItem(JSONObject()
            .put("id", id)
            .put("virtualUri", uri)
            .put("name", "Animes Online.mp4")
            .put("state", "COMPLETE"))
        assertEquals(expected, item.name)
    }

    @Test
    fun convertedTsUsesFinalMp4NameAndMimeInsteadOfOldVirtualSuffix() {
        val id = "00000000-0000-0000-0000-000000000012"
        val uri = "content://com.reianix.reianix_local.downloads/$id/Episode%201.ts"
        val item = DownloadItem(JSONObject()
            .put("id", id)
            .put("virtualUri", uri)
            .put("name", "Episode 1.mp4")
            .put("mime", "video/mp4")
            .put("remuxSourceName", "Episode 1.ts")
            .put("state", "SAVING"))
        assertEquals("Episode 1.mp4", item.name)
    }

    @Test
    fun mislabeledMp4ContainingTsKeepsCorrectedTsExtension() {
        val id = "00000000-0000-0000-0000-000000000013"
        val uri = "content://com.reianix.reianix_local.downloads/$id/Episode%202.mp4"
        val item = DownloadItem(JSONObject()
            .put("id", id)
            .put("virtualUri", uri)
            .put("name", "Episode 2.ts")
            .put("mime", "video/mp2t")
            .put("state", "SAVING"))
        assertEquals("Episode 2.ts", item.name)
    }

    @Test
    fun oldRecordWithoutVirtualUriStillUsesStoredName() {
        val item = DownloadItem(JSONObject()
            .put("id", "legacy")
            .put("name", "Episode 02.mkv"))
        assertEquals("Episode 02.mkv", item.name)
    }
}
