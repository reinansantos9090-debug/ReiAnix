package com.reianix.reianix_local.data.anilist

import android.app.Application
import com.reianix.reianix_local.data.personal.ReiAnixPersonalAnimeService
import com.reianix.reianix_local.data.personal.ReiAnixWatchStatus
import com.reianix.reianix_local.data.releases.ReiAnixReleaseMonitor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReiAnixSupplementalFeaturesTest {
    @Test
    fun charactersFetchOnlyRolesAndPhotos() = runBlocking {
        var request: JSONObject? = null
        val transport = AniListTransport { _, body ->
            request = body
            AniListHttpResponse(200, """
                {"data":{"Media":{"characters":{"edges":[
                    {"role":"MAIN","node":{"id":1,"name":{"full":"Asta"},"image":{"medium":"https://img.example/asta.jpg"}}},
                    {"role":"SUPPORTING","node":{"id":2,"name":{"full":"Nero"},"image":{"medium":"http://untrusted.example/a.jpg"}}}
                ]}}}}
            """.trimIndent())
        }
        val result = ReiAnixAniListClient(transport, 0).getCharacters(222)
        assertTrue(result is AniListResult.Success)
        val characters = (result as AniListResult.Success).value
        assertEquals(2, characters.size)
        assertEquals("Principal", characters[0].role)
        assertEquals("Secundário", characters[1].role)
        assertEquals("https://img.example/asta.jpg", characters[0].imageUrl)
        assertNull(characters[1].imageUrl)
        assertTrue(request!!.getString("query").contains("characters(perPage: \$limit"))
        assertFalse(request!!.getString("query").contains("voiceActors"))
    }

    @Test
    fun airingsBatchUsesConfirmedIdsAndWindow() = runBlocking {
        var request: JSONObject? = null
        val transport = AniListTransport { _, body ->
            request = body
            AniListHttpResponse(200, """
                {"data":{"Page":{"pageInfo":{"hasNextPage":false},"airingSchedules":[
                    {"id":98,"mediaId":10,"episode":4,"airingAt":200,
                     "media":{"externalLinks":[{"site":"Crunchyroll","url":"https://www.crunchyroll.com/series/abc"}]}}
                ]}}}
            """.trimIndent())
        }
        val result = ReiAnixAniListClient(transport, 0).getRecentAirings(listOf(10), 100, 300)
        assertTrue(result is AniListResult.Success)
        assertEquals(4, (result as AniListResult.Success).value.items.single().episode)
        assertEquals(10, request!!.getJSONObject("variables").getJSONArray("ids").getInt(0))
        assertTrue(request!!.getString("query").contains("mediaId_in: \$ids"))
    }

    @Test
    fun onlyTrustedStreamingHostsCanBeOpened() {
        assertNotNull(ReiAnixReleaseMonitor.officialWatchLink(listOf(
            ReiAnixOfficialLink("Crunchyroll","https://www.crunchyroll.com/watch/123"))))
        assertNull(ReiAnixReleaseMonitor.officialWatchLink(listOf(
            ReiAnixOfficialLink("Crunchyroll","https://crunchyroll.com.evil.example/watch/123"))))
        assertNull(ReiAnixReleaseMonitor.officialWatchLink(listOf(
            ReiAnixOfficialLink("Crunchyroll","http://www.crunchyroll.com/watch/123"))))
    }

    @Test
    fun localListStatusesAndNotesKeepSeparatePreferenceNamespaces() {
        assertEquals(5, ReiAnixWatchStatus.entries.size)
        assertEquals("Quero assistir", ReiAnixWatchStatus.PLANNING.label)
        assertEquals("reianix.personal.status.42", ReiAnixPersonalAnimeService.statusKey(42))
        assertEquals("reianix.personal.rating.42", ReiAnixPersonalAnimeService.ratingKey(42))
    }
}
