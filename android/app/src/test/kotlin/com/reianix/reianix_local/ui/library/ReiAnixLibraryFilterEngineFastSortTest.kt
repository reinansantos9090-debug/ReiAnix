package com.reianix.reianix_local.ui.library

import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import com.reianix.reianix_local.ui.model.ReiAnixMetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class ReiAnixLibraryFilterEngineFastSortTest {
    private fun anime(
        id: Long,
        title: String,
        addedAt: Double,
        playedAt: Double,
        favorite: Boolean = false,
        pinned: Boolean = false,
    ) = ReiAnixAnimeUiModel(
        id = id,
        title = title,
        year = null,
        genres = emptyList(),
        favorite = favorite,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = null,
        metadataAvailability = ReiAnixMetadataAvailability.UNKNOWN,
        seasons = emptyList(),
        specials = emptyList(),
        mediaFiles = emptyList(),
        addedAt = addedAt,
        lastPlayedAt = playedAt,
        pinned = pinned,
    )

    @Test
    fun commonSortsKeepPreviousOrderingWithoutEpisodeMetricScans() {
        val beta = anime(1L, "Beta", addedAt = 8.0, playedAt = 1.0, favorite = true)
        val alpha = anime(2L, "Alpha", addedAt = 8.0, playedAt = 9.0, pinned = true)
        val zeta = anime(3L, "Zeta", addedAt = 10.0, playedAt = 2.0, favorite = true)
        val input = listOf(beta, zeta, alpha)

        fun sortedIds(sort: ReiAnixLibrarySort) =
            ReiAnixLibraryFilterEngine.sortForLibrary(input, sort.label).map { it.id }

        assertEquals(listOf(3L, 2L, 1L), sortedIds(ReiAnixLibrarySort.RECENT))
        assertEquals(listOf(2L, 3L, 1L), sortedIds(ReiAnixLibrarySort.RECENTLY_WATCHED))
        assertEquals(listOf(1L, 3L, 2L), sortedIds(ReiAnixLibrarySort.FAVORITES_FIRST))
        assertEquals(listOf(2L, 1L, 3L), sortedIds(ReiAnixLibrarySort.PINNED_FIRST))
        assertEquals(listOf(2L, 1L, 3L), sortedIds(ReiAnixLibrarySort.TITLE_ASC))
        assertEquals(listOf(3L, 1L, 2L), sortedIds(ReiAnixLibrarySort.TITLE_DESC))
    }

    @Test
    fun titleSortStillBreaksTiesUsingIdInTheSameDirection() {
        val a = anime(7L, "Re:Zero", 1.0, 1.0)
        val b = anime(9L, "re:zero", 1.0, 1.0)
        val input = listOf(b, a)
        assertEquals(
            listOf(7L, 9L),
            ReiAnixLibraryFilterEngine.sortForLibrary(input, ReiAnixLibrarySort.TITLE_ASC.label).map { it.id },
        )
        assertEquals(
            listOf(9L, 7L),
            ReiAnixLibraryFilterEngine.sortForLibrary(input, ReiAnixLibrarySort.TITLE_DESC.label).map { it.id },
        )
    }
}
