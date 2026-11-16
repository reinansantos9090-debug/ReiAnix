package com.reianix.reianix_local.ui.model

/**
 * Keeps the current visible window during a background SQLite page refresh only
 * if the requested filters and sort are unchanged. A new search/filter still
 * clears the old results immediately rather than showing unrelated titles.
 *
 * Non-all-results queries are bounded by the SQLite query API's maximum batch;
 * preserve whole pages so the next pagination offset never skips any items.
 */
internal object ReiAnixLibraryRefreshWindow {
    fun retainedPageIndex(
        previous: ReiAnixLibraryPagedUiState,
        pageSize: Int,
        query: String,
        genreKey: String?,
        sort: String,
        favoritesOnly: Boolean,
        watchingOnly: Boolean,
        completedOnly: Boolean,
        maxQueryRows: Int,
    ): Int? {
        if (previous.status != ReiAnixLibraryLoadStatus.READY ||
            previous.animes.isEmpty() ||
            previous.loadedPage < 0 ||
            previous.pageSize != pageSize ||
            previous.query != query.trim() ||
            previous.genreKey != genreKey ||
            previous.sort != sort.trim().ifEmpty { "Mais recentes" } ||
            previous.favoritesOnly != favoritesOnly ||
            previous.watchingOnly != watchingOnly ||
            previous.completedOnly != completedOnly
        ) return null

        if (pageSize == 0) return 0 // all-results queries already fetch the entire window
        val maxWholePages = maxQueryRows / pageSize
        if (maxWholePages <= 0) return null
        return previous.loadedPage.coerceIn(0, maxWholePages - 1)
    }
}
