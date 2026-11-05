package com.reiflix.reiflix_local.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraceMoeEpisodeOrderTest {
    @Test
    fun episodeOneIsSelectedBeforeOtherEpisodesEvenWhenTheyAppearFirstInTheList() {
        val selected = selectInitialTraceMoeEpisodeId(
            listOf(
                TraceMoeEpisodeOrderKey(episodeId = 30L, seasonNumber = 1, episodeNumber = 3.0),
                TraceMoeEpisodeOrderKey(episodeId = 21L, seasonNumber = 2, episodeNumber = 1.0),
                TraceMoeEpisodeOrderKey(episodeId = 10L, seasonNumber = 1, episodeNumber = 1.0),
                TraceMoeEpisodeOrderKey(episodeId = 20L, seasonNumber = 1, episodeNumber = 2.0),
            ),
        )

        assertEquals(10L, selected)
    }

    @Test
    fun fallsBackToTheEarliestAvailableEpisodeWhenEpisodeOneIsMissing() {
        val selected = selectInitialTraceMoeEpisodeId(
            listOf(
                TraceMoeEpisodeOrderKey(episodeId = 31L, seasonNumber = 2, episodeNumber = 1.0),
                TraceMoeEpisodeOrderKey(episodeId = 12L, seasonNumber = 1, episodeNumber = 3.0),
                TraceMoeEpisodeOrderKey(episodeId = 11L, seasonNumber = 1, episodeNumber = 2.0),
            ),
        )

        assertEquals(11L, selected)
    }

    @Test
    fun ordersUnnumberedSeasonsByEpisodeNumberAndHandlesEmptyInput() {
        val selected = selectInitialTraceMoeEpisodeId(
            listOf(
                TraceMoeEpisodeOrderKey(episodeId = 2L, seasonNumber = null, episodeNumber = 2.0),
                TraceMoeEpisodeOrderKey(episodeId = 1L, seasonNumber = null, episodeNumber = 1.0),
            ),
        )

        assertEquals(1L, selected)
        assertNull(selectInitialTraceMoeEpisodeId(emptyList()))
    }
}
