package com.reiflix.reiflix_local

import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.scanner.DiscoveredMediaMetadata
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
        assertTrue(parsed.getJSONObject("fieldConfidence").has("episode_number"))
        assertEquals("saf", document.getJSONObject("metadataAvailability").getString("source"))
        assertFalse(document.getJSONObject("metadataAvailability").getBoolean("sizeAvailable"))
        assertFalse(document.getJSONObject("metadataAvailability").getBoolean("modifiedAtAvailable"))
        assertTrue(document.isNull("size"))
        assertTrue(document.isNull("modifiedAt"))
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

        val parsed = document.getJSONObject("parsedMedia")
        val availability = document.getJSONObject("metadataAvailability")
        assertEquals("unknown", parsed.getString("kind"))
        assertTrue(parsed.getJSONArray("unknownFields").length() >= 1)
        assertEquals("content://provider/document/123", document.getString("uri"))
        assertTrue(availability.getBoolean("sizeAvailable"))
        assertTrue(availability.getBoolean("modifiedAtAvailable"))
        assertEquals(128L, document.getLong("size"))
        assertEquals(1_700_000_000_000L, document.getLong("modifiedAt"))
        assertTrue(availability.getBoolean("providerIdAvailable"))
    }

    @Test
    fun zeroSizeIsAvailableWhenSuppliedByAnAuthoritativeSource() {
        val document = JSONObject()
        DiscoveredMediaMetadata.attach(
            document = document,
            fileName = "empty.mkv",
            relativePath = "empty.mkv",
            source = "mediastore",
            mimeType = "video/x-matroska",
            sizeBytes = 0L,
            modifiedAtMillis = null,
            providerId = "42",
        )
        assertEquals(0L, document.getLong("size"))
        assertTrue(document.getJSONObject("metadataAvailability").getBoolean("sizeAvailable"))
        assertTrue(document.isNull("modifiedAt"))
        assertFalse(document.getJSONObject("metadataAvailability").getBoolean("modifiedAtAvailable"))
    }

    @Test
    fun unknownSizeAndDateBecomeJsonNullAndDoNotInvalidateDiscovery() {
        val document = JSONObject().put("uri", "content://provider/document/77")
        DiscoveredMediaMetadata.attach(
            document = document,
            fileName = "odd.mkv",
            relativePath = "odd.mkv",
            source = "saf",
            mimeType = null,
            sizeBytes = null,
            modifiedAtMillis = null,
            providerId = "77",
            nameAvailable = true,
            mimeTypeAvailable = false,
        )
        val availability = document.getJSONObject("metadataAvailability")
        assertTrue(document.has("parsedMedia"))
        assertTrue(document.isNull("size"))
        assertTrue(document.isNull("modifiedAt"))
        assertTrue(document.isNull("mimeType"))
        assertFalse(availability.getBoolean("sizeAvailable"))
        assertFalse(availability.getBoolean("modifiedAtAvailable"))
        assertFalse(availability.getBoolean("mimeTypeAvailable"))
        assertTrue(availability.getBoolean("providerIdAvailable"))
        assertEquals("accessible", availability.getString("accessState"))
    }

    @Test
    fun fallbackFieldsDoNotPretendToBeProviderMetadata() {
        val document = JSONObject().put("name", "video-15").put("mimeType", "video/*")
        DiscoveredMediaMetadata.attach(
            document = document,
            fileName = "video-15",
            relativePath = "video-15",
            source = "mediastore",
            mimeType = null,
            sizeBytes = null,
            modifiedAtMillis = null,
            providerId = "0",
            nameAvailable = false,
            mimeTypeAvailable = false,
        )
        val availability = document.getJSONObject("metadataAvailability")
        assertFalse(availability.getBoolean("nameAvailable"))
        assertFalse(availability.getBoolean("mimeTypeAvailable"))
        assertFalse(availability.getBoolean("providerIdAvailable"))
        assertTrue(document.isNull("mimeType"))
    }

    @Test
    fun filesystemPathIsNotClaimedAsProviderIssuedId() {
        val document = JSONObject()
        DiscoveredMediaMetadata.attach(
            document = document,
            fileName = "sample.mkv",
            relativePath = "Videos/sample.mkv",
            source = "broad-storage",
            mimeType = "video/x-matroska",
            sizeBytes = 0L,
            modifiedAtMillis = 1L,
            providerId = "/storage/emulated/0/Videos/sample.mkv",
            mimeTypeAvailable = false,
            accessState = "enumerated",
        )
        val availability = document.getJSONObject("metadataAvailability")
        assertFalse(availability.getBoolean("providerIdAvailable"))
        assertFalse(availability.getBoolean("mimeTypeAvailable"))
        assertTrue(availability.getBoolean("sizeAvailable"))
        assertEquals("enumerated", availability.getString("accessState"))
        assertEquals("video/x-matroska", document.getString("mimeType"))
    }

    @Test
    fun legacyCacheZeroDoesNotBecomeKnownWithoutAvailabilityEvidence() {
        val legacy = JSONObject().put("size", 0L).put("modifiedAt", 0L)
        assertNull(DiscoveredMediaMetadata.cachedLongOrNull(legacy, "size", "sizeAvailable"))
        assertNull(DiscoveredMediaMetadata.cachedLongOrNull(legacy, "modifiedAt", "modifiedAtAvailable"))
    }

    @Test
    fun cachedZeroIsPreservedWhenAvailabilityBitConfirmsIt() {
        val cached = JSONObject()
            .put("size", 0L)
            .put("modifiedAt", 0L)
            .put("metadataAvailability", JSONObject().put("sizeAvailable", true).put("modifiedAtAvailable", true))
        assertEquals(0L, DiscoveredMediaMetadata.cachedLongOrNull(cached, "size", "sizeAvailable"))
        assertEquals(0L, DiscoveredMediaMetadata.cachedLongOrNull(cached, "modifiedAt", "modifiedAtAvailable"))
    }

    @Test
    fun cachedStringAvailabilityRejectsMissingAndFallbackValuesEvenWhenFlagIsTrue() {
        val fallbackName = JSONObject()
            .put("name", "video-15")
            .put("metadataAvailability", JSONObject().put("nameAvailable", true))
        val missingMime = JSONObject()
            .put("mimeType", JSONObject.NULL)
            .put("metadataAvailability", JSONObject().put("mimeTypeAvailable", true))
        val fallbackMime = JSONObject()
            .put("mimeType", "video/*")
            .put("metadataAvailability", JSONObject().put("mimeTypeAvailable", true))
        val validName = JSONObject()
            .put("name", "Series.mkv")
            .put("metadataAvailability", JSONObject().put("nameAvailable", true))
        val explicitlyUnavailable = JSONObject()
            .put("name", "Series.mkv")
            .put("metadataAvailability", JSONObject().put("nameAvailable", false))

        assertFalse(DiscoveredMediaMetadata.cachedStringAvailable(fallbackName, "name", "nameAvailable", "video-15"))
        assertFalse(DiscoveredMediaMetadata.cachedStringAvailable(missingMime, "mimeType", "mimeTypeAvailable", "video/*"))
        assertFalse(DiscoveredMediaMetadata.cachedStringAvailable(fallbackMime, "mimeType", "mimeTypeAvailable", "video/*"))
        assertTrue(DiscoveredMediaMetadata.cachedStringAvailable(validName, "name", "nameAvailable"))
        assertFalse(DiscoveredMediaMetadata.cachedStringAvailable(explicitlyUnavailable, "name", "nameAvailable"))
    }

    @Test
    fun readableEmptyFileCanHaveKnownZeroLengthWithoutReadingVideoBytes() {
        val metadata = BroadStorageScanner.readLocalFileMetadata(
            isRegularFile = { true },
            canRead = { true },
            length = { 0L },
            verifySizeWithoutReading = { 0L },
            lastModified = { 1_700_000_000_000L },
        )
        assertTrue(metadata.fileReadable)
        assertEquals(0L, metadata.sizeBytes)
        assertEquals(1_700_000_000_000L, metadata.modifiedAtMillis)
    }

    @Test
    fun ambiguousZeroLengthRemainsUnknownWhenDescriptorCannotConfirmIt() {
        val metadata = BroadStorageScanner.readLocalFileMetadata(
            isRegularFile = { true },
            canRead = { true },
            length = { 0L },
            verifySizeWithoutReading = { throw IOException("file was removed or cannot be opened") },
            lastModified = { 0L },
        )
        assertFalse(metadata.fileReadable)
        assertNull(metadata.sizeBytes)
        assertNull(metadata.modifiedAtMillis)
    }

    @Test
    fun unreadableFileKeepsMetadataUnknownWithoutRetryingIo() {
        var lengthCalls = 0
        var verifyCalls = 0
        val metadata = BroadStorageScanner.readLocalFileMetadata(
            isRegularFile = { true },
            canRead = { false },
            length = { lengthCalls++; 100L },
            verifySizeWithoutReading = { verifyCalls++; 100L },
            lastModified = { 1_700_000_000_000L },
        )
        assertFalse(metadata.fileReadable)
        assertNull(metadata.sizeBytes)
        assertNull(metadata.modifiedAtMillis)
        assertEquals(0, lengthCalls)
        assertEquals(0, verifyCalls)
    }
}
