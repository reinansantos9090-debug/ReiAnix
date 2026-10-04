package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reiflix.reiflix_local.ui.model.ReiAnixSearchUiState
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilterEngine
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilters
import com.reiflix.reiflix_local.ui.search.ReiAnixSearchEngine
@Keep
class ReiAnixLibraryViewModel(context: Context) :
    ReiAnixViewModel<ReiAnixLibraryUiState>() {
    
    companion object {
        internal fun projectHomeState(state: ReiAnixLibraryUiState): ReiAnixHomeLibraryUiState =
            ReiAnixHomeLibraryUiState.from(state)

        internal fun projectDetailsState(
            state: ReiAnixLibraryUiState,
            animeId: Long,
        ): ReiAnixDetailsUiState =
            ReiAnixDetailsUiStateProjection.from(state, animeId)
    }

    private val repository = ReiAnixLibraryRepository(context)
    override val uiState: StateFlow<ReiAnixLibraryUiState> = repository.state

    /**
     * Home observes only the fields that can affect its persistent catalog sections.
     * Episode-level progress is deliberately excluded so a playback tick cannot
     * invalidate the whole Home tree.
     */
    val homeState: StateFlow<ReiAnixHomeLibraryUiState> = uiState
        .map { state ->
            projectHomeState(state)
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ReiAnixHomeLibraryUiState(),
        )

    private val _libraryFilters = MutableStateFlow(ReiAnixLibraryFilters())

    val libraryFilters: StateFlow<ReiAnixLibraryFilters> = _libraryFilters.asStateFlow()

    val libraryGenres: StateFlow<List<ReiAnixGenreUiModel>> = uiState
        .map { state ->
            state.animes
                .flatMap { anime -> anime.genres }
                .distinctBy(ReiAnixGenreUiModel::stableKey)
                .sortedBy { it.name.lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    val filteredLibraryAnimes: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>> =
        combine(
            uiState.map { it.animes },
            libraryFilters,
        ) { animes, filters ->
            ReiAnixLibraryFilterEngine.filter(animes, filters)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Canonical Details projection. Episode state is included so progress and
     * watched changes are reflected by the same real-library snapshot.
     */
    fun detailsState(animeId: Long): kotlinx.coroutines.flow.Flow<ReiAnixDetailsUiState> =
        uiState
            .map { state -> projectDetailsState(state, animeId) }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    /** The canonical Continue Watching projection; this is the only Home subtree that observes it. */
    val continueWatching: StateFlow<List<ReiAnixContinueWatchingUiModel>> = uiState
        .map { it.continueWatching }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            uiState.value.continueWatching,
        )

    /** Parent Home only needs this flag to add/remove the section item. */
    val hasContinueWatching: StateFlow<Boolean> = continueWatching
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            uiState.value.continueWatching.isNotEmpty(),
        )

    /**
     * Search index is rebuilt only when the canonical library snapshot changes.
     * Query changes operate against the in-memory index and never touch SQLite
     * or remote services from the UI layer.
     */
    private val searchIndex: StateFlow<com.reiflix.reiflix_local.ui.search.ReiAnixSearchIndex> = uiState
        .map { state -> ReiAnixSearchEngine.buildIndex(state.animes) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ReiAnixSearchEngine.buildIndex(emptyList()),
        )

    private val _searchQuery = MutableStateFlow("")

    val searchState: StateFlow<ReiAnixSearchUiState> = combine(
        searchIndex,
        _searchQuery,
    ) { index, query ->
        ReiAnixSearchUiState(
            query = query,
            results = index.search(query),
        )
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ReiAnixSearchUiState(
                query = "",
                results = emptyList(),
            ),
        )

    fun setSearchQuery(value: String) {
        _searchQuery.value = value
    }

    fun refresh() = repository.refresh()

    fun selectSafTree() = repository.selectSafTree()

    fun setLibrarySearchQuery(value: String) {
        _libraryFilters.value = _libraryFilters.value.copy(query = value)
    }

    fun setLibraryGenreFilter(key: String?) {
        _libraryFilters.value = _libraryFilters.value.copy(selectedGenreKey = key)
    }

    fun toggleLibraryFavoritesFilter() {
        _libraryFilters.value = _libraryFilters.value.copy(
            favoritesOnly = !_libraryFilters.value.favoritesOnly,
        )
    }

    fun toggleLibraryWatchingFilter() {
        _libraryFilters.value = _libraryFilters.value.copy(
            watchingOnly = !_libraryFilters.value.watchingOnly,
        )
    }

    fun toggleLibraryCompletedFilter() {
        _libraryFilters.value = _libraryFilters.value.copy(
            completedOnly = !_libraryFilters.value.completedOnly,
        )
    }

    fun clearLibraryFilters() {
        _libraryFilters.value = ReiAnixLibraryFilters()
    }

    fun toggleFavorite(animeId: Long) = repository.toggleFavorite(animeId)

    fun setEpisodeWatched(episodeId: Long, watched: Boolean) =
        repository.setEpisodeWatched(episodeId, watched)

    fun openEpisode(episodeId: Long): String = repository.openEpisode(episodeId)

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
