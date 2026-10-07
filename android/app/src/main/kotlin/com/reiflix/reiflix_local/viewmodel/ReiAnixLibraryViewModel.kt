package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPagedUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPresentationUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.reiflix.reiflix_local.ui.model.ReiAnixHomeLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixContinueWatchingUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixGenreUiModel
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixDetailsUiStateProjection
import com.reiflix.reiflix_local.ui.model.ReiAnixSearchFilters
import com.reiflix.reiflix_local.ui.model.ReiAnixSearchUiState
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilterEngine
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilters
import com.reiflix.reiflix_local.ui.library.ReiAnixLibrarySort
import com.reiflix.reiflix_local.ui.mylist.ReiAnixMyListFilter
import com.reiflix.reiflix_local.ui.organize.ReiAnixOrganizeFilters
import com.reiflix.reiflix_local.ui.organize.ReiAnixOrganizeCategory
import com.reiflix.reiflix_local.ui.organize.ReiAnixOrganizeMode
import com.reiflix.reiflix_local.ui.organize.buildOrganizeCategories
import com.reiflix.reiflix_local.ui.organize.filterOrganizeAnimes
import com.reiflix.reiflix_local.ui.search.ReiAnixSearchEngine
@Keep
class ReiAnixLibraryViewModel(context: Context) :
    ReiAnixViewModel<ReiAnixLibraryUiState>() {
    
    companion object {
        const val DERIVED_FLOW_STOP_TIMEOUT_MS = 5_000L
        const val SEARCH_DEBOUNCE_MS = 180L

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

    /** Bounded Library pages; the canonical domain remains owned by the repository/SQLite. */
    val pagedLibraryState: StateFlow<ReiAnixLibraryPagedUiState> = repository.pagedLibraryState

    private var libraryFilterJob: Job? = null

    /**
     * Canonical immutable catalog projection.
     *
     * All catalog-derived screens consume this flow instead of re-running
     * against the full UiState for unrelated scan/command/error emissions.
     * The underlying entities still come exclusively from the repository's
     * single canonical snapshot.
     */
    private val canonicalCatalog: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>> =
        uiState
            .map { it.animes }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
                uiState.value.animes,
            )

    private val homeAvailabilityState: StateFlow<ReiAnixLibraryUiState> = uiState
        .map { state ->
            state.copy(
                animes = emptyList(),
                continueWatching = emptyList(),
                storage = com.reiflix.reiflix_local.ui.model.ReiAnixStorageUiState(),
                scanInProgress = false,
                scanState = "IDLE",
                lastCommandId = null,
                lastCommandAction = null,
                lastCommandStatus = null,
                lastCommandError = null,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            uiState.value.copy(
                animes = emptyList(),
                continueWatching = emptyList(),
                storage = com.reiflix.reiflix_local.ui.model.ReiAnixStorageUiState(),
                scanInProgress = false,
                scanState = "IDLE",
                lastCommandId = null,
                lastCommandAction = null,
                lastCommandStatus = null,
                lastCommandError = null,
            ),
        )

    /**
     * Home catalog content is driven only by canonicalCatalog, while source
     * availability/error metadata remains reactive through a narrow state flow.
     */
    val homeState: StateFlow<ReiAnixHomeLibraryUiState> = combine(
        canonicalCatalog,
        homeAvailabilityState,
    ) { animes, availability ->
        projectHomeState(
            availability.copy(animes = animes),
        )
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            projectHomeState(uiState.value),
        )

    private val _libraryFilters = MutableStateFlow(ReiAnixLibraryFilters())

    val libraryFilters: StateFlow<ReiAnixLibraryFilters> = _libraryFilters.asStateFlow()

    val libraryGenres: StateFlow<List<ReiAnixGenreUiModel>> = pagedLibraryState
        .map { page ->
            page.animes
                .flatMap { anime -> anime.genres }
                .distinctBy(ReiAnixGenreUiModel::stableKey)
                .sortedBy { it.name.lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            emptyList(),
        )

    /** True while an existing refresh command is queued or the canonical scanner reports activity. */
    val isRefreshing: StateFlow<Boolean> = uiState
        .map { state ->
            state.scanInProgress || (
                state.lastCommandAction.equals("refresh", ignoreCase = true) &&
                    state.lastCommandStatus?.uppercase() in setOf("QUEUED", "RUNNING")
                )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            false,
        )

    private val libraryAvailabilitySource: StateFlow<ReiAnixLibraryPresentationUiState> = uiState
        .map { state ->
            ReiAnixLibraryPresentationUiState(
                status = state.status,
                sourceAvailable = state.sourceAvailable,
                sourceState = state.sourceState,
                scanInProgress = state.scanInProgress,
                scanState = state.scanState,
                error = state.error,
            )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixLibraryPresentationUiState(),
        )

    private data class CatalogCounts(
        val animeCount: Int,
        val availableEpisodeCount: Int,
        val favoriteCount: Int,
    )

    private val catalogCounts: StateFlow<CatalogCounts> = canonicalCatalog
        .map { animes ->
            CatalogCounts(
                animeCount = animes.size,
                availableEpisodeCount = animes.sumOf { it.availableContentCount },
                favoriteCount = animes.count { it.favorite },
            )
        }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            CatalogCounts(animeCount = 0, availableEpisodeCount = 0, favoriteCount = 0),
        )

    val libraryPresentationState: StateFlow<ReiAnixLibraryPresentationUiState> = combine(
        libraryAvailabilitySource,
        pagedLibraryState,
    ) { source, page ->
        val availableEpisodes = page.animes.sumOf { it.availableContentCount }
        val favorites = page.animes.count { it.favorite }
        source.copy(
            status = when {
                page.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR -> page.status
                page.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE -> page.status
                page.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY -> page.status
                page.animes.isNotEmpty() -> com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
                else -> com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING
            },
            error = page.error ?: source.error,
            animeCount = page.totalCount.coerceAtLeast(page.animes.size),
            availableEpisodeCount = availableEpisodes,
            favoriteCount = favorites,
        )
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixLibraryPresentationUiState(),
        )

    val filteredLibraryAnimes: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>> =
        pagedLibraryState
            .map { it.animes }
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
                emptyList(),
            )

    private val _myListFilter = MutableStateFlow(ReiAnixMyListFilter.ALL)

    val myListFilter: StateFlow<ReiAnixMyListFilter> = _myListFilter.asStateFlow()

    /**
     * Minha Lista is a presentation projection over the canonical library.
     * Membership remains the existing favorite flag; the remaining filters
     * only derive subsets from that same immutable snapshot.
     */
    val myListAnimes: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>> =
        combine(
            canonicalCatalog,
            myListFilter,
        ) { animes, selectedFilter ->
            val saved = animes.asSequence().filter { it.favorite }
            when (selectedFilter) {
                ReiAnixMyListFilter.ALL,
                ReiAnixMyListFilter.FAVORITES,
                -> saved
                ReiAnixMyListFilter.WATCHING ->
                    saved.filter { it.isWatching }
                ReiAnixMyListFilter.COMPLETED ->
                    saved.filter { it.isCompleted }
            }
                .sortedBy { it.title.trim().lowercase() }
                .toList()
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
                emptyList(),
            )

    fun setMyListFilter(filter: ReiAnixMyListFilter) {
        _myListFilter.value = filter
    }

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
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            uiState.value.continueWatching,
        )

    /** Parent Home only needs this flag to add/remove the section item. */
    val hasContinueWatching: StateFlow<Boolean> = continueWatching
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            uiState.value.continueWatching.isNotEmpty(),
        )

    /**
     * Search index is rebuilt only when the canonical library snapshot changes.
     * Query changes operate against the in-memory index and never touch SQLite
     * or remote services from the UI layer.
     */
    /**
     * Search consumes the same canonical catalog instance used by Home, Library,
     * Details and Organize. Exposing this flow does not create a second source
     * of truth or duplicate the catalog in memory.
     */
    val searchCatalog: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>>
        get() = canonicalCatalog

    /**
     * Search filters must see genres from the entire canonical catalog, not
     * only the currently paged Library window.
     */
    val searchGenres: StateFlow<List<ReiAnixGenreUiModel>> = canonicalCatalog
        .map { animes ->
            animes
                .asSequence()
                .flatMap { anime -> anime.genres.asSequence() }
                .filter { it.name.isNotBlank() }
                .distinctBy(ReiAnixGenreUiModel::stableKey)
                .sortedBy { it.name.trim().lowercase() }
                .toList()
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            emptyList(),
        )

    private val _organizeFilters = MutableStateFlow(ReiAnixOrganizeFilters())

    val organizeFilters: StateFlow<ReiAnixOrganizeFilters> = _organizeFilters.asStateFlow()

    val organizeGenres: StateFlow<List<ReiAnixGenreUiModel>> = canonicalCatalog
        .map { animes ->
            animes
                .flatMap { anime -> anime.genres }
                .distinctBy(ReiAnixGenreUiModel::stableKey)
                .sortedBy { it.name.lowercase() }
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            emptyList(),
        )

    val organizeCategories: StateFlow<List<ReiAnixOrganizeCategory>> = canonicalCatalog
        .map { animes -> buildOrganizeCategories(animes) }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            emptyList(),
        )

    val organizeVisibleAnimes: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>> =
        combine(
            canonicalCatalog,
            searchIndex,
            organizeFilters,
        ) { animes, index, filters ->
            filterOrganizeAnimes(
                animes = animes,
                searchIndex = index,
                filters = filters,
            )
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
                emptyList(),
            )

    fun setOrganizeQuery(value: String) {
        _organizeFilters.value = _organizeFilters.value.copy(
            query = value,
            mode = ReiAnixOrganizeMode.COLLECTION,
        )
    }

    fun setOrganizeState(value: String) {
        val normalized = value.trim().takeIf { it.isNotEmpty() }
            ?: ReiAnixOrganizeFilters.DEFAULT_STATE
        _organizeFilters.value = _organizeFilters.value.copy(
            state = normalized,
            mode = ReiAnixOrganizeMode.COLLECTION,
        )
    }

    fun setOrganizeGenre(key: String?) {
        _organizeFilters.value = _organizeFilters.value.copy(
            genreKey = key?.trim()?.takeIf { it.isNotEmpty() },
            mode = ReiAnixOrganizeMode.COLLECTION,
        )
    }

    fun setOrganizeSort(label: String) {
        val normalized = ReiAnixLibrarySort.fromLabel(label).label
        _organizeFilters.value = _organizeFilters.value.copy(
            sort = normalized,
            mode = ReiAnixOrganizeMode.COLLECTION,
        )
    }

    fun clearOrganizeFilters() {
        _organizeFilters.value = ReiAnixOrganizeFilters(
            mode = ReiAnixOrganizeMode.COLLECTION,
        )
    }

    fun openOrganizeCollection(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) return
        _organizeFilters.value = when {
            ReiAnixOrganizeFilters.states.contains(normalized) ->
                _organizeFilters.value.copy(
                    state = normalized,
                    genreKey = null,
                    mode = ReiAnixOrganizeMode.COLLECTION,
                )
            else ->
                _organizeFilters.value.copy(
                    genreKey = normalized,
                    mode = ReiAnixOrganizeMode.COLLECTION,
                )
        }
    }

    fun openOrganizeOverview() {
        _organizeFilters.value = _organizeFilters.value.copy(
            mode = ReiAnixOrganizeMode.OVERVIEW,
        )
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val debouncedSearchQuery: StateFlow<String> = _searchQuery
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            "",
        )

    private val _searchFilters = MutableStateFlow(ReiAnixSearchFilters())

    val searchState: StateFlow<ReiAnixSearchUiState> = combine(
        searchIndex,
        debouncedSearchQuery,
        _searchFilters,
    ) { index, query, filters ->
        Triple(index, query, filters)
    }
        .mapLatest { (index, query, filters) ->
            runCatching {
                val indexedResults = if (query.isBlank()) {
                    index.all()
                } else {
                    index.search(query)
                }
                val hasSearchRefinement = query.isNotBlank() || filters.hasAnyFilter
                val filteredResults = if (!hasSearchRefinement) {
                    emptyList()
                } else {
                    // Text matching has already been normalized and ranked by
                    // ReiAnixSearchEngine. Do not feed the raw query back into
                    // the generic Library matcher, otherwise aliases and
                    // accent-insensitive matches would be rejected again.
                    ReiAnixLibraryFilterEngine.filter(
                        animes = indexedResults,
                        filters = ReiAnixLibraryFilters(
                            query = "",
                            selectedGenreKey = filters.selectedGenreKey,
                            favoritesOnly = filters.favoritesOnly,
                            watchingOnly = filters.watchingOnly,
                            completedOnly = filters.completedOnly,
                            sort = filters.sort ?: "",
                        ),
                    )
                }
                ReiAnixSearchUiState(
                    query = query,
                    results = filteredResults,
                    filters = filters,
                )
            }.getOrElse { error ->
                ReiAnixSearchUiState(
                    query = query,
                    results = emptyList(),
                    filters = filters,
                    error = error.message?.takeIf { it.isNotBlank() }
                        ?: "Não foi possível pesquisar na biblioteca local.",
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixSearchUiState(
                query = "",
                results = emptyList(),
            ),
        )

    fun setSearchQuery(value: String) {
        _searchQuery.value = value
    }

    fun setSearchGenreFilter(key: String?) {
        _searchFilters.value = _searchFilters.value.copy(
            selectedGenreKey = key?.takeIf { it.isNotBlank() },
        )
    }

    fun toggleSearchFavoritesFilter() {
        _searchFilters.value = _searchFilters.value.copy(
            favoritesOnly = !_searchFilters.value.favoritesOnly,
        )
    }

    fun toggleSearchWatchingFilter() {
        _searchFilters.value = _searchFilters.value.copy(
            watchingOnly = !_searchFilters.value.watchingOnly,
        )
    }

    fun toggleSearchCompletedFilter() {
        _searchFilters.value = _searchFilters.value.copy(
            completedOnly = !_searchFilters.value.completedOnly,
        )
    }

    fun setSearchSort(label: String?) {
        val normalized = label?.trim().orEmpty()
        _searchFilters.value = _searchFilters.value.copy(
            sort = normalized.takeIf { it.isNotEmpty() },
        )
    }

    fun clearSearchFilters() {
        _searchFilters.value = ReiAnixSearchFilters()
    }

    fun refresh() {
        val filters = libraryFilters.value
        repository.refresh(
            query = filters.query,
            genre = filters.selectedGenreKey ?: "Todos",
            sort = filters.sort,
            favoritesOnly = filters.favoritesOnly,
            watchingOnly = filters.watchingOnly,
            completedOnly = filters.completedOnly,
        )
    }

    init {
        libraryFilterJob?.cancel()
        libraryFilterJob = viewModelScope.launch {
            libraryFilters
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { filters ->
                    repository.loadLibraryPage(
                        page = 0,
                        pageSize = 36,
                        query = filters.query,
                        genre = filters.selectedGenreKey ?: "Todos",
                        sort = filters.sort,
                        favoritesOnly = filters.favoritesOnly,
                        watchingOnly = filters.watchingOnly,
                        completedOnly = filters.completedOnly,
                        reset = true,
                    )
                }
        }
    }

    fun loadNextLibraryPage() {
        val page = pagedLibraryState.value
        if (!page.hasMore || page.isLoading || page.loadedPage < 0) return
        val filters = libraryFilters.value
        repository.loadLibraryPage(
            page = page.loadedPage + 1,
            pageSize = 36,
            query = filters.query,
            genre = filters.selectedGenreKey ?: "Todos",
            sort = filters.sort,
            favoritesOnly = filters.favoritesOnly,
            watchingOnly = filters.watchingOnly,
            completedOnly = filters.completedOnly,
            reset = false,
        )
    }

    fun selectSafTree() = repository.selectSafTree()

    fun removeSafTree(reference: String) = repository.removeSafTree(reference)

    fun setLibrarySearchQuery(value: String) {
        _libraryFilters.value = _libraryFilters.value.copy(query = value)
    }

    fun setLibrarySort(label: String) {
        val normalized = ReiAnixLibrarySort.fromLabel(label).label
        _libraryFilters.value = _libraryFilters.value.copy(sort = normalized)
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
