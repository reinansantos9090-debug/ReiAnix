package com.reiflix.reiflix_local.data.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixPlayerEpisodeNavigationTest {
    @Test
    fun navigationMatchesCatalogOrderAndCarriesDestinationEdgeFlags() {
        val current = episode(id = 2L, number = 2.0)
        val result = ReiAnixPlayerEpisodeNavigator.resolve(
            current = current,
            episodes = listOf(
                episode(id = 1L, number = 1.0),
                current,
                episode(id = 3L, number = 3.0),
                episode(id = 4L, number = 4.0, missing = true),
                episode(id = 5L, number = 5.0, availability = "missing"),
                episode(id = 6L, number = 6.0, type = "special"),
                episode(id = 7L, number = 7.0, type = null),
            ),
        )

        assertEquals(3L, result.next?.id)
        assertEquals(1L, result.previous?.id)
        assertFalse(result.nextCanNext)
        assertTrue(result.nextCanPrevious)
        assertTrue(result.previousCanNext)
        assertFalse(result.previousCanPrevious)
    }

    @Test
    fun movieAndSpecialCurrentRowsCannotNavigateAsRegularEpisodes() {
        val current = episode(id = 9L, number = 1.0, type = "special")
        val result = ReiAnixPlayerEpisodeNavigator.resolve(
            current = current,
            episodes = listOf(
                episode(id = 8L, number = 0.0),
                current,
                episode(id = 10L, number = 2.0),
            ),
        )

        assertEquals(null, result.next)
        assertEquals(null, result.previous)
        assertFalse(result.nextCanNext)
        assertFalse(result.nextCanPrevious)
        assertFalse(result.previousCanNext)
        assertFalse(result.previousCanPrevious)
    }

    @Test
    fun missingAvailabilityDefaultsToAvailableButMissingRowsAreExcluded() {
        val current = episode(id = 1L, number = 1.0)
        val result = ReiAnixPlayerEpisodeNavigator.resolve(
            current = current,
            episodes = listOf(
                current,
                episode(id = 2L, number = 2.0, availability = null),
                episode(id = 3L, number = 3.0, missing = null),
            ),
        )

        assertEquals(2L, result.next?.id)
    }

    private fun episode(
        id: Long,
        number: Double?,
        type: String? = "episode",
        missing: Boolean? = false,
        availability: String? = "available",
        absoluteNumber: Double? = null,
    ) = ReiAnixEpisodeRecord(
        id = id,
        animeId = 1L,
        path = "/library/episode-$id.mkv",
        fileName = "episode-$id.mkv",
        season = 1,
        number = number,
        duration = null,
        progress = null,
        watched = false,
        mimeType = "video/x-matroska",
        fileSize = 100L,
        modifiedAt = 1.0,
        sourceFolder = "/library",
        absoluteNumber = absoluteNumber,
        relativePath = "episode-$id.mkv",
        volumeId = null,
        volumeUuid = null,
        episodeType = type,
        episodeTitle = null,
        identificationSource = null,
        identificationConfidence = null,
        manualOverride = false,
        missing = missing,
        lastPlayedAt = null,
        mediaIdentity = "episode:$id",
        availabilityState = availability,
    )
}
