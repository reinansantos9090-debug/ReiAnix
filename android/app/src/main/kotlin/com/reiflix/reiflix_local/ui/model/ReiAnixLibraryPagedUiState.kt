package com.reiflix.reiflix_local.ui.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable

/**
 * Incremental presentation state for the Library route.
 *
 * This is not a second persistence source: SQLite/Python remain canonical.
 * It only retains the bounded pages currently requested by the visible Library.
 */
@Keep
@Immutable
data class ReiAnixLibraryPagedUiState(
    val status: ReiAnixLibraryLoadStatus = ReiAnixLibraryLoadStatus.LOADING,
    val animes: List<ReiAnixAnimeUiModel> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val loadedPage: Int = -1,
    val isLoading: Boolean = false,
    val generation: Long = 0L,
    /**
     * Exact paging inputs used for the current generation. Keeping them with
     * the paged projection prevents snapshot reconciliation from ever
     * falling back to a different filter/sort source.
     */
    val query: String = "",
    val genreKey: String? = null,
    val favoritesOnly: Boolean = false,
    val watchingOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val sort: String = "Mais recentes",
    val error: String? = null,
)
