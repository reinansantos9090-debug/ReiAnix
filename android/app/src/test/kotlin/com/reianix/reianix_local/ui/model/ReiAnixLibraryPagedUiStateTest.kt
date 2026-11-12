package com.reianix.reianix_local.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryPagedUiStateTest {

    @Test
    fun firstPageProjectionRefreshDoesNotShowPaginationSpinner() {
        val state = ReiAnixLibraryPagedUiState(
            loadedPage = -1,
            isLoading = true,
        )

        assertFalse(state.isLoadingMore)
    }

    @Test
    fun actualNextPageRequestShowsPaginationSpinner() {
        val state = ReiAnixLibraryPagedUiState(
            loadedPage = 0,
            isLoading = true,
        )

        assertTrue(state.isLoadingMore)
    }

    @Test
    fun completedPageDoesNotShowPaginationSpinner() {
        val state = ReiAnixLibraryPagedUiState(
            loadedPage = 0,
            isLoading = false,
        )

        assertFalse(state.isLoadingMore)
    }

    @Test
    fun firstPageWithoutAnEarlierPageNeverLooksLikeLoadMore() {
        val state = ReiAnixLibraryPagedUiState(
            loadedPage = -1,
            isLoading = false,
            hasMore = true,
        )

        assertFalse(state.isLoadingMore)
    }
}
