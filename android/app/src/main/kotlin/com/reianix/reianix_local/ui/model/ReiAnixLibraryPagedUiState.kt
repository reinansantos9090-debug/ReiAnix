package com.reianix.reianix_local.ui.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable

/**
 * Incremental presentation state for the Library route.
 *
 * This is not a second persistence source: SQLite/Python remain canonical.
 * It retains the current Library result set. A pageSize of 0 represents all matching items.
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
    val pageSize: Int = 0,
    val requestId: String? = null,
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
) {
    /**
     * True only while requesting a page after the first one.
     *
     * Page-zero reloads are projection refreshes (for example after a cover or
     * episode thumbnail is published), not pagination. They must not show the
     * grid's blue "load more" spinner.
     */
    val isLoadingMore: Boolean
        get() = isLoading && loadedPage >= 0
}
