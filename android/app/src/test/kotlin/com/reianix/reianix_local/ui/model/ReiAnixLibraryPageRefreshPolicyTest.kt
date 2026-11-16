package com.reianix.reianix_local.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReiAnixLibraryPageRefreshPolicyTest {
    private fun anime(id: Long) = ReiAnixAnimeUiModel(
        id = id,
        title = "Anime $id",
        year = null,
        genres = emptyList(),
        favorite = false,
        mediaKind = ReiAnixMediaKind.SERIES,
        artwork = null,
        metadataAvailability = ReiAnixMetadataAvailability.UNKNOWN,
        seasons = emptyList(),
        specials = emptyList(),
        mediaFiles = emptyList(),
    )

    @Test
    fun backgroundRefreshMayKeepTheVisiblePageOnlyForIdenticalRequest() {
        val previous = ReiAnixLibraryPagedUiState(
            status = ReiAnixLibraryLoadStatus.READY,
            animes = listOf(anime(1), anime(2)),
            loadedPage = 0,
            pageSize = 24,
            query = "",
            genreKey = null,
            favoritesOnly = false,
            sort = "Mais recentes",
        )
        assertTrue(
            previous.matchesRefreshRequest(
                pageSize = 24,
                query = "",
                genreKey = null,
                favoritesOnly = false,
                watchingOnly = false,
                completedOnly = false,
                sort = "Mais recentes",
            ),
        )
        assertFalse(
            previous.matchesRefreshRequest(
                pageSize = 24,
                query = "Re:Zero",
                genreKey = null,
                favoritesOnly = false,
                watchingOnly = false,
                completedOnly = false,
                sort = "Mais recentes",
            ),
        )
        assertFalse(
            previous.matchesRefreshRequest(
                pageSize = 72,
                query = "",
                genreKey = null,
                favoritesOnly = false,
                watchingOnly = false,
                completedOnly = false,
                sort = "Mais recentes",
            ),
        )
        assertFalse(
            previous.matchesRefreshRequest(
                pageSize = 24,
                query = "",
                genreKey = null,
                favoritesOnly = true,
                watchingOnly = false,
                completedOnly = false,
                sort = "Mais recentes",
            ),
        )
    }

    @Test
    fun visiblePageCountPreservesPartialLastPagesAndAllItemsMode() {
        val previous = ReiAnixLibraryPagedUiState(
            animes = (1L..73L).map(::anime),
        )
        assertEquals(2, previous.visiblePageCount(72))
        assertEquals(4, previous.visiblePageCount(24))
        assertEquals(0, previous.visiblePageCount(0))
        assertEquals(0, previous.copy(animes = emptyList()).visiblePageCount(24))
    }

    @Test
    fun reloadOfPageZeroMustNeverShowLoadMoreIndicator() {
        val refresh = ReiAnixLibraryPagedUiState(
            animes = listOf(anime(1)),
            loadedPage = -1,
            isLoading = true,
            hasMore = false,
        )
        assertFalse(refresh.isLoadingMore)
        assertTrue(refresh.copy(loadedPage = 0).isLoadingMore)
    }
}
