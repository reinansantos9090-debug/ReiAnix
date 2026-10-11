package com.reiflix.reiflix_local.data.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReiAnixSqliteModelsTest {
    @Test
    fun playbackUpdatePreservesOptionalFieldsAsNull() {
        val update = ReiAnixPlaybackProgressUpdate(
            episodeId = 42L,
            progress = 12.5,
            duration = null,
            watched = null,
            playedAtEpochSeconds = 1_700_000_000.0,
        )

        assertEquals(42L, update.episodeId)
        assertEquals(12.5, update.progress, 0.0)
        assertNull(update.duration)
        assertNull(update.watched)
    }


    @Test
    fun metadataPatchCanUpdateCatalogFieldsWithoutModelingUserOwnedFields() {
        val patch = ReiAnixAnimeMetadataPatch(
            animeId = 17L,
            title = "  Naruto  ",
            coverUrl = "https://example.invalid/cover.jpg",
        )

        assertEquals(17L, patch.animeId)
        assertEquals("  Naruto  ", patch.title)
        assertNull(patch.metadataFetchedAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun metadataPatchRejectsBlankTitle() {
        ReiAnixAnimeMetadataPatch(animeId = 17L, title = "   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun metadataPatchRejectsEmptyPatch() {
        ReiAnixAnimeMetadataPatch(animeId = 17L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackUpdateRejectsNonPositiveIds() {
        ReiAnixPlaybackProgressUpdate(episodeId = 0L, progress = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackUpdateRejectsNegativeProgress() {
        ReiAnixPlaybackProgressUpdate(episodeId = 1L, progress = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackUpdateRejectsNonFiniteProgress() {
        ReiAnixPlaybackProgressUpdate(episodeId = 1L, progress = Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun playbackUpdateRejectsInvalidDuration() {
        ReiAnixPlaybackProgressUpdate(episodeId = 1L, progress = 0.0, duration = Double.POSITIVE_INFINITY)
    }
}
