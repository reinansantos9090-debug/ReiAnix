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
    val error: String? = null,
)
