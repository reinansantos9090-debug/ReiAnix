package com.reianix.reianix_local

import com.reianix.reianix_local.ui.model.ReiAnixAnimeUiModel
import com.reianix.reianix_local.ui.model.ReiAnixLibraryLoadStatus
import com.reianix.reianix_local.ui.model.ReiAnixLibraryPagedUiState
import com.reianix.reianix_local.ui.model.ReiAnixLibraryRefreshWindow
import com.reianix.reianix_local.ui.model.ReiAnixMediaKind
import com.reianix.reianix_local.viewmodel.isExplicitLibraryRefresh
import com.reianix.reianix_local.ui.model.ReiAnixMetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryRefreshWindowTest {
    private fun state(
        pageSize: Int = 36,
        loadedPage: Int = 2,
        query: String = "",
        genre: String? = null,
        favorite: Boolean = false,
    ) = ReiAnixLibraryPagedUiState(
        status = ReiAnixLibraryLoadStatus.READY,
        animes = listOf(
            ReiAnixAnimeUiModel(
                id = 1L,
                title = "Anime",
                year = 2026,
                genres = emptyList(),
                favorite = false,
                mediaKind = ReiAnixMediaKind.SERIES,
                artwork = null,
                metadataAvailability = ReiAnixMetadataAvailability.UNRESOLVED,
                seasons = emptyList(),
                specials = emptyList(),
                mediaFiles = emptyList(),
            ),
        ),
        loadedPage = loadedPage,
        pageSize = pageSize,
        query = query,
        genreKey = genre,
        sort = "Mais recentes",
        favoritesOnly = favorite,
    )

    private fun retained(
        previous: ReiAnixLibraryPagedUiState,
        query: String = previous.query,
        genre: String? = previous.genreKey,
        favorite: Boolean = previous.favoritesOnly,
        pageSize: Int = previous.pageSize,
        maxRows: Int = 398,
    ) = ReiAnixLibraryRefreshWindow.retainedPageIndex(
        previous = previous,
        pageSize = pageSize,
        query = query,
        genreKey = genre,
        sort = previous.sort,
        favoritesOnly = favorite,
        watchingOnly = previous.watchingOnly,
        completedOnly = previous.completedOnly,
        maxQueryRows = maxRows,
    )

    @Test
    fun automaticStartupScanDoesNotTriggerManualRefreshIndicator() {
        // The scanner may be RUNNING while the existing SQLite library is ready;
        // no explicit refresh command exists in that state.
        assertFalse(isExplicitLibraryRefresh(null, "RUNNING"))
        assertFalse(isExplicitLibraryRefresh("scan_library", "RUNNING"))
        assertFalse(isExplicitLibraryRefresh("refresh", "COMPLETED"))
        assertTrue(isExplicitLibraryRefresh("refresh", "QUEUED"))
        assertTrue(isExplicitLibraryRefresh("REFRESH", "running"))
    }

    @Test
    fun backgroundRefreshKeepsScrolledPageWhenFiltersMatch() {
        assertEquals(2, retained(state()))
    }

    @Test
    fun filterOrPageSizeChangeCannotKeepStaleResults() {
        val original = state()
        assertNull(retained(original, query = "Naruto"))
        assertNull(retained(original, genre = "Action"))
        assertNull(retained(original, favorite = true))
        assertNull(retained(original, pageSize = 72))
    }

    @Test
    fun cappedRefreshKeepsWholePagesSoNextOffsetDoesNotSkipRows() {
        assertEquals(1, retained(state(loadedPage = 9), maxRows = 100))
        assertEquals(0, retained(state(pageSize = 72, loadedPage = 5), maxRows = 100))
    }

    @Test
    fun allResultsRefreshKeepsFullWindow() {
        assertEquals(0, retained(state(pageSize = 0)))
    }

    @Test
    fun pageZeroRefreshNeverShowsLoadMoreSpinner() {
        val refreshing = state().copy(isLoading = true, isRefreshingPage = true)
        assertFalse(refreshing.isLoadingMore)
        assertTrue(refreshing.copy(isRefreshingPage = false).isLoadingMore)
    }
}
