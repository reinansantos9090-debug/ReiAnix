package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.scanner.DiscoveredMediaMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredMediaMetadataTest {
    @Test
    fun attachesParserResultWithoutReplacingContentUri() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAnime/document/primary%3AAnime%2FShow%2FShow%20S02E03.mkv"
        val document = JSONObject().put("uri", uri).put("name", "Show S02E03.mkv")

        DiscoveredMediaMetadata.attach(
            document = document,
            fileName = "Show S02E03.mkv",
            relativePath = "Anime/Show/Show S02E03.mkv",
            source = "saf",
            mimeType = "video/x-matroska",
            sizeBytes = null,
            modifiedAtMillis = null,
            providerId = "primary:Anime/Show/Show S02E03.mkv",
        )

        assertEquals(uri, document.getString("uri"))
        val parsed = document.getJSONObject("parsedMedia")
        assertEquals("Show", parsed.getString("animeTitle"))
        assertEquals(2, parsed.getInt("season"))
        assertEquals(3.0, parsed.getDouble("episodeNumber"), 0.0)
        assertEquals("saf", document.getJSONObject("metadataAvailability").getString("source"))
        assertFalse(document.getJSONObject("metadataAvailability").getBoolean("sizeAvailable"))
        assertFalse(document.getJSONObject("metadataAvailability").getBoolean("modifiedAtAvailable"))
    }

    @Test
    fun unknownEpisodeStillRemainsAValidDiscovery() {
        val document = JSONObject().put("uri", "content://provider/document/123")

        DiscoveredMediaMetadata.attach(
            document = document,
            fileName = "mystery_clip.mkv",
            relativePath = "Anime/mystery_clip.mkv",
            source = "mediastore",
            mimeType = "video/x-matroska",
            sizeBytes = 128L,
            modifiedAtMillis = 1_700_000_000_000L,
            providerId = "123",
        )

        assertTrue(document.has("parsedMedia"))
        assertEquals("unknown", document.getJSONObject("parsedMedia").getString("kind"))
        assertEquals("content://provider/document/123", document.getString("uri"))
        assertTrue(document.getJSONObject("metadataAvailability").getBoolean("sizeAvailable"))
        assertTrue(document.getJSONObject("metadataAvailability").getBoolean("modifiedAtAvailable"))
    }
}
