package com.reiflix.reiflix_local.ui.model

import androidx.annotation.Keep

@Keep
data class ReiAnixSearchFilters(
    val selectedGenreKey: String? = null,
    val favoritesOnly: Boolean = false,
    val watchingOnly: Boolean = false,
    val completedOnly: Boolean = false,
    val sort: String? = null,
) {
    val hasAnyFilter: Boolean
        get() = selectedGenreKey != null ||
            favoritesOnly ||
            watchingOnly ||
            completedOnly ||
            !sort.isNullOrBlank()
}

@Keep
data class ReiAnixSearchUiState(
    val query: String = "",
    val results: List<ReiAnixAnimeUiModel> = emptyList(),
    val filters: ReiAnixSearchFilters = ReiAnixSearchFilters(),
    val error: String? = null,
)
