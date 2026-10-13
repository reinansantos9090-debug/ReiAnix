package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryResult
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixFolderRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixArtworkRecord
import com.reiflix.reiflix_local.storage.SafFileOperations
import com.reiflix.reiflix_local.bridge.NativeMailbox
import org.json.JSONObject
import kotlinx.coroutines.withContext
import android.net.Uri
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPagedUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPresentationUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryDataState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
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
enum class ReiAnixNativeFoldersStatus {
    IDLE,
    LOADING,
    READY,
    EMPTY,
    NOT_FOUND,
    ERROR,
}

data class ReiAnixNativeFolderUiModel(
    val name: String?,
    val path: String,
)

data class ReiAnixNativeFoldersUiState(
    val status: ReiAnixNativeFoldersStatus = ReiAnixNativeFoldersStatus.IDLE,
    val folders: List<ReiAnixNativeFolderUiModel> = emptyList(),
)

/**
 * ViewModel coordinating the real read-only SQLite folder query for Storage.
 * The existing projection still owns SAF commands and permission state.
 */
@Keep
class ReiAnixLibraryViewModel(context: Context) :
    ReiAnixViewModel<ReiAnixLibraryUiState>() {
    private val appContext = context.applicationContext

    
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

        internal fun projectMyListAnimes(
            animes: List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>,
            selectedFilter: ReiAnixMyListFilter,
        ): List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel> {
            val saved = animes.asSequence().filter { it.favorite }
            val filtered = when (selectedFilter) {
                ReiAnixMyListFilter.ALL,
                ReiAnixMyListFilter.FAVORITES,
                -> saved
                ReiAnixMyListFilter.WATCHING ->
                    saved.filter { it.isWatching }
                ReiAnixMyListFilter.COMPLETED ->
                    saved.filter { it.isCompleted }
            }
            return filtered
                .sortedWith(
                    compareBy<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel> {
                        it.title.trim().lowercase()
                    }.thenBy { it.id },
                )
                .toList()
        }
    }

    private val repository = ReiAnixLibraryRepository(context)
    /**
     * Direct, read-only queries against the canonical SQLite database. The legacy
     * repository remains responsible for command/IPC flows until each mutation
     * is migrated with parity and single-writer guarantees.
     */
    private val nativeLibraryQueries = ReiAnixLibraryQueryService(context)
    /** Favorite membership uses the canonical SQLite store; playback remains in the consumption service. */
    private val personalListService = com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService(context)
    private val _personalListMutationError = MutableStateFlow<String?>(null)
    val personalListMutationError: StateFlow<String?> = _personalListMutationError.asStateFlow()
    /** SAF mutations operate only on persisted, user-authorized content URIs. */
    private val nativeFileOperations = SafFileOperations(context.applicationContext)

    fun authorizedSafTreeUris(): List<Uri> = nativeFileOperations.authorizedTreeUris()
    fun safTreeDisplayName(uri: Uri): String = nativeFileOperations.treeDisplayName(uri)

    suspend fun listSafDirectory(uri: Uri): SafFileOperations.ResultOrItems =
        nativeFileOperations.list(uri)

    suspend fun createSafDirectory(parentUri: Uri, name: String): SafFileOperations.Result =
        nativeFileOperations.createDirectory(parentUri, name)

    private suspend fun enqueueSafIdentityReconciliation(
        oldUri: Uri,
        result: SafFileOperations.Result,
        operation: String,
    ): SafFileOperations.Result {
        if (result !is SafFileOperations.Result.Success) return result
        val treeUri = result.treeUri
            ?: return SafFileOperations.Result.Failure(
                SafFileOperations.FailureKind.PARTIAL,
                "A mutação SAF foi confirmada, mas não foi possível identificar a árvore para reconciliar o catálogo. Verifique a biblioteca antes de repetir.",
                oldUri,
                result.uri,
                recoverable = true,
            )
        val event = JSONObject()
            .put("type", "saf_document_identity_change")
            .put(
                "payload",
                JSONObject()
                    .put("operation", operation)
                    .put("treeUri", treeUri.toString())
                    .put("oldUri", oldUri.toString())
                    .put("newUri", result.uri.toString())
                    .put("name", result.name),
            )
        val queued = withContext(Dispatchers.IO) {
            NativeMailbox.write(appContext, event)
        }
        return if (queued) {
            result.copy(catalogReconciliationQueued = true)
        } else {
            SafFileOperations.Result.Failure(
                SafFileOperations.FailureKind.PARTIAL,
                "A mutação SAF foi confirmada pelo provedor, mas a reconciliação canônica não pôde ser enfileirada. O catálogo foi preservado; verifique a biblioteca antes de repetir a operação.",
                oldUri,
                result.uri,
                recoverable = true,
            )
        }
    }

    suspend fun renameSafItem(uri: Uri, newName: String): SafFileOperations.Result {
        val result = nativeFileOperations.rename(uri, newName)
        return enqueueSafIdentityReconciliation(uri, result, "rename")
    }

    suspend fun moveSafItem(uri: Uri, destinationDirectoryUri: Uri): SafFileOperations.Result {
        val result = nativeFileOperations.move(uri, destinationDirectoryUri)
        return enqueueSafIdentityReconciliation(uri, result, "move")
    }

    suspend fun deleteSafItem(uri: Uri): SafFileOperations.Result =
        nativeFileOperations.delete(uri)

    suspend fun queryNativeAnime(id: Long): ReiAnixLibraryQueryResult<ReiAnixAnimeRecord> =
        nativeLibraryQueries.animeById(id)

    suspend fun queryNativeEpisodes(animeId: Long): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> =
        nativeLibraryQueries.episodesForAnime(animeId)

    suspend fun queryNativeEpisode(id: Long): ReiAnixLibraryQueryResult<ReiAnixEpisodeRecord> =
        nativeLibraryQueries.episodeById(id)

    suspend fun queryNativeRecentlyPlayed(
        limit: Int = ReiAnixLibraryQueryService.DEFAULT_PAGE_SIZE,
    ): ReiAnixLibraryQueryResult<List<ReiAnixEpisodeRecord>> =
        nativeLibraryQueries.recentlyPlayed(limit)

    suspend fun queryNativeFolders(): ReiAnixLibraryQueryResult<List<ReiAnixFolderRecord>> =
        nativeLibraryQueries.folders()

    private val _nativeFoldersState = MutableStateFlow(ReiAnixNativeFoldersUiState())
    val nativeFoldersState: StateFlow<ReiAnixNativeFoldersUiState> = _nativeFoldersState.asStateFlow()
    private var nativeFoldersJob: Job? = null

    /** Refreshes the read-only SQLite folder view without changing folder ownership or SAF grants. */
    fun refreshNativeFolders() {
        nativeFoldersJob?.cancel()
        _nativeFoldersState.value = ReiAnixNativeFoldersUiState(
            status = ReiAnixNativeFoldersStatus.LOADING,
        )
        nativeFoldersJob = viewModelScope.launch {
            val result = queryNativeFolders()
            _nativeFoldersState.value = when (result) {
                is ReiAnixLibraryQueryResult.Success -> ReiAnixNativeFoldersUiState(
                    status = ReiAnixNativeFoldersStatus.READY,
                    folders = result.value.map { folder ->
                        ReiAnixNativeFolderUiModel(name = folder.name, path = folder.path)
                    },
                )
                is ReiAnixLibraryQueryResult.Empty -> ReiAnixNativeFoldersUiState(
                    status = ReiAnixNativeFoldersStatus.EMPTY,
                )
                is ReiAnixLibraryQueryResult.NotFound -> ReiAnixNativeFoldersUiState(
                    status = ReiAnixNativeFoldersStatus.NOT_FOUND,
                )
                is ReiAnixLibraryQueryResult.Failure -> ReiAnixNativeFoldersUiState(
                    status = ReiAnixNativeFoldersStatus.ERROR,
                )
            }
        }
    }

    suspend fun queryNativeArtwork(
        animeId: Long,
    ): ReiAnixLibraryQueryResult<List<ReiAnixArtworkRecord>> =
        nativeLibraryQueries.artworkForAnime(animeId)
    override val uiState: StateFlow<ReiAnixLibraryUiState> = repository.state

    /** Bounded Library pages; the canonical domain remains owned by the repository/SQLite. */
    val pagedLibraryState: StateFlow<ReiAnixLibraryPagedUiState> = repository.pagedLibraryState

    private var libraryFilterJob: Job? = null

    // These values are projections of the persisted SettingsStore preferences,
    // not another preference store. The settings StateFlow remains canonical.
    private var configuredLibraryPageSize: Int = 36
    private var appliedLibrarySortDefault: String = "added_desc"

    private fun librarySortLabelForSetting(value: String): String = when (value) {
        "title_asc" -> ReiAnixLibrarySort.TITLE_ASC.label
        "title_desc" -> ReiAnixLibrarySort.TITLE_DESC.label
        "recently_watched" -> ReiAnixLibrarySort.RECENTLY_WATCHED.label
        else -> ReiAnixLibrarySort.RECENT.label
    }

    fun applyLibraryPageSize(value: Int) {
        val normalized = value.takeIf { it in setOf(24, 36, 48, 72) } ?: 36
        if (configuredLibraryPageSize == normalized) return
        configuredLibraryPageSize = normalized
        val filters = libraryFilters.value
        repository.loadLibraryPage(
            page = 0,
            pageSize = configuredLibraryPageSize,
            query = filters.query,
            genre = filters.selectedGenreKey ?: "Todos",
            sort = filters.sort,
            favoritesOnly = filters.favoritesOnly,
            watchingOnly = filters.watchingOnly,
            completedOnly = filters.completedOnly,
            reset = true,
        )
    }

    fun applyLibrarySortDefault(value: String) {
        val normalized = value.takeIf {
            it in setOf("added_desc", "title_asc", "title_desc", "recently_watched")
        } ?: "added_desc"
        if (normalized == appliedLibrarySortDefault) return

        val previousDefaultLabel = librarySortLabelForSetting(appliedLibrarySortDefault)
        val nextDefaultLabel = librarySortLabelForSetting(normalized)
        appliedLibrarySortDefault = normalized

        // A changed default follows the preference only while the current order
        // still equals the previously applied default. A custom transient sort
        // selected by the user is not silently discarded.
        val current = libraryFilters.value
        if (current.sort == previousDefaultLabel && current.sort != nextDefaultLabel) {
            _libraryFilters.value = current.copy(sort = nextDefaultLabel)
        }
    }

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

    val libraryGenres: StateFlow<List<ReiAnixGenreUiModel>> = canonicalCatalog
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
        canonicalCatalog,
        pagedLibraryState,
    ) { source, canonical, page ->
        val canonicalReady = canonical.isNotEmpty()
        val dataState = when {
            canonicalReady && page.error != null ->
                ReiAnixLibraryDataState.CANONICAL_READY_PAGE_ERROR
            canonicalReady && page.isLoading ->
                ReiAnixLibraryDataState.CANONICAL_READY_PAGE_LOADING
            canonicalReady && page.animes.isNotEmpty() ->
                ReiAnixLibraryDataState.CANONICAL_READY_PAGE_READY
            canonicalReady ->
                ReiAnixLibraryDataState.CANONICAL_READY
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE ||
                source.sourceState.uppercase() in setOf("UNAVAILABLE", "NOT_CONFIGURED") ->
                ReiAnixLibraryDataState.SOURCE_UNAVAILABLE
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR ->
                ReiAnixLibraryDataState.CANONICAL_ERROR
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING ->
                ReiAnixLibraryDataState.INITIAL_LOADING
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY ->
                ReiAnixLibraryDataState.CANONICAL_EMPTY
            else ->
                ReiAnixLibraryDataState.INITIAL_LOADING
        }
        val status = when (dataState) {
            ReiAnixLibraryDataState.CANONICAL_ERROR ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR
            ReiAnixLibraryDataState.CANONICAL_EMPTY ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
            ReiAnixLibraryDataState.SOURCE_UNAVAILABLE ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE
            else -> if (canonicalReady) {
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
            } else {
                source.status
            }
        }
        source.copy(
            status = status,
            dataState = dataState,
            error = page.error ?: source.error,
            animeCount = canonical.size,
            availableEpisodeCount = canonical.sumOf { it.availableContentCount },
            favoriteCount = canonical.count { it.favorite },
        )
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixLibraryPresentationUiState(),
        )

    val filteredLibraryAnimes: StateFlow<List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>> =
        combine(
            canonicalCatalog,
            pagedLibraryState,
            libraryFilters,
        ) { canonical, page, filters ->
            if (page.animes.isNotEmpty()) {
                page.animes
            } else if (canonical.isNotEmpty()) {
                val requested = ReiAnixLibraryFilterEngine.filter(
                    animes = canonical,
                    filters = filters,
                )
                requested.take(page.pageSize.coerceIn(12, 72))
            } else {
                emptyList()
            }
        }
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
                emptyList(),
            )

    private val _myListFilter = MutableStateFlow(ReiAnixMyListFilter.ALL)

    val myListFilter: StateFlow<ReiAnixMyListFilter> = _myListFilter.asStateFlow()

    /**
     * Minha Lista derives its presentation state from the canonical local
     * snapshot instead of the paged Biblioteca window. This keeps opening the
     * screen independent from page loading and lets already-materialized local
     * favorites appear immediately, including while the source is offline or
     * temporarily unavailable.
     */
    val myListPresentationState: StateFlow<ReiAnixLibraryPresentationUiState> = combine(
        libraryAvailabilitySource,
        canonicalCatalog,
    ) { source, animes ->
        val savedCount = animes.count { it.favorite }
        val status = when {
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.SOURCE_UNAVAILABLE
            source.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING &&
                animes.isEmpty() ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING
            savedCount > 0 ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
            else ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
        }
        source.copy(
            status = status,
            animeCount = savedCount,
            availableEpisodeCount = animes
                .asSequence()
                .filter { it.favorite }
                .sumOf { it.availableContentCount },
            favoriteCount = savedCount,
        )
    }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixLibraryPresentationUiState(),
        )

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
            projectMyListAnimes(animes, selectedFilter)
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
     * Shared in-memory search index derived exclusively from the canonical
     * library snapshot. It is not a second source of truth and performs no
     * persistence or remote work.
     */
    private val searchIndex = canonicalCatalog
        .map { animes -> ReiAnixSearchEngine.buildIndex(animes) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixSearchEngine.buildIndex(canonicalCatalog.value),
        )

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

    private data class SearchRequest(
        val catalog: List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>,
        val requestedQuery: String,
        val settledQuery: String,
        val filters: ReiAnixSearchFilters,
    )

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

    /**
     * Search result identities come from the canonical SQLite query service.
     * The in-memory canonical projection is used only to provide existing Compose
     * presentation models for those persisted IDs; it does not decide membership.
     */
    val searchState: StateFlow<ReiAnixSearchUiState> = combine(
        canonicalCatalog,
        _searchQuery,
        debouncedSearchQuery,
        _searchFilters,
    ) { catalog, requestedQuery, settledQuery, filters ->
        SearchRequest(catalog, requestedQuery, settledQuery, filters)
    }
        .transformLatest { request ->
            val filters = request.filters
            if (request.requestedQuery != request.settledQuery) {
                emit(
                    ReiAnixSearchUiState(
                        query = request.settledQuery,
                        results = emptyList(),
                        filters = filters,
                    ),
                )
                return@transformLatest
            }
            if (request.settledQuery.isBlank() && !filters.hasAnyFilter) {
                emit(
                    ReiAnixSearchUiState(
                        query = request.settledQuery,
                        results = emptyList(),
                        filters = filters,
                    ),
                )
                return@transformLatest
            }

            emit(
                ReiAnixSearchUiState(
                    query = request.settledQuery,
                    results = emptyList(),
                    filters = filters,
                    isLoading = true,
                ),
            )

            val selectedIds = mutableListOf<Long>()
            var offset = 0
            var hasMore = true
            var queryError: String? = null
            while (hasMore && queryError == null) {
                when (val result = nativeLibraryQueries.searchAnime(
                    searchText = request.settledQuery,
                    genreKey = filters.selectedGenreKey,
                    favoritesOnly = filters.favoritesOnly,
                    watchingOnly = filters.watchingOnly,
                    completedOnly = filters.completedOnly,
                    sortLabel = filters.sort,
                    limit = ReiAnixLibraryQueryService.MAX_PAGE_SIZE,
                    offset = offset,
                )) {
                    is ReiAnixLibraryQueryResult.Success -> {
                        val page = result.value
                        selectedIds += page.animeIds
                        hasMore = page.hasMore
                        offset += page.limit
                    }
                    is ReiAnixLibraryQueryResult.Empty -> hasMore = false
                    is ReiAnixLibraryQueryResult.Failure -> {
                        queryError = result.cause.message?.takeIf(String::isNotBlank)
                            ?: "Não foi possível pesquisar no banco SQLite da biblioteca."
                    }
                    is ReiAnixLibraryQueryResult.NotFound -> {
                        queryError = "O banco SQLite não encontrou o item solicitado."
                    }
                }
            }

            val completedState = if (queryError != null) {
                ReiAnixSearchUiState(
                    query = request.settledQuery,
                    results = emptyList(),
                    filters = filters,
                    error = queryError,
                )
            } else {
                val byId = request.catalog.associateBy { it.id }
                val orderedIds = selectedIds.distinct()
                val missingIds = orderedIds.filterNot(byId::containsKey)
                if (missingIds.isNotEmpty()) {
                    ReiAnixSearchUiState(
                        query = request.settledQuery,
                        results = emptyList(),
                        filters = filters,
                        error = "A pesquisa SQLite retornou itens que ainda não estão disponíveis na projeção da biblioteca. Atualize a biblioteca e tente novamente.",
                    )
                } else {
                    ReiAnixSearchUiState(
                        query = request.settledQuery,
                        results = orderedIds.mapNotNull(byId::get),
                        filters = filters,
                    )
                }
            }
            emit(completedState)
        }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(DERIVED_FLOW_STOP_TIMEOUT_MS),
            ReiAnixSearchUiState(query = "", results = emptyList()),
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
                        pageSize = configuredLibraryPageSize,
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
            pageSize = configuredLibraryPageSize,
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

    fun dismissStorageOnboarding() = repository.dismissStorageOnboarding()

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
        _libraryFilters.value = ReiAnixLibraryFilters(
            sort = librarySortLabelForSetting(appliedLibrarySortDefault),
        )
    }

    /**
     * Persist personal-list membership in the canonical SQLite database. Do not
     * route this through episode watched/progress state. Refresh the shared
     * library projection only after the transaction has succeeded.
     */
    fun toggleFavorite(animeId: Long) {
        if (animeId <= 0L) {
            _personalListMutationError.value = "Não foi possível alterar Minha Lista: identificador inválido."
            return
        }
        viewModelScope.launch {
            _personalListMutationError.value = null
            when (val result = personalListService.toggle(animeId)) {
                is com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Success -> refresh()
                com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.NotFound ->
                    _personalListMutationError.value = "Este anime não existe mais na biblioteca local."
                com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Conflict ->
                    _personalListMutationError.value = "Minha Lista foi alterada por outra operação. Atualize e tente novamente."
                is com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Failure ->
                    _personalListMutationError.value = result.cause.message
                        ?.takeIf(String::isNotBlank)
                        ?.let { "Não foi possível salvar Minha Lista: $it" }
                        ?: "Não foi possível salvar Minha Lista."
            }
        }
    }

    suspend fun personalListItems() = personalListService.list()

    suspend fun addToPersonalList(animeId: Long) = updatePersonalListMembership {
        personalListService.add(animeId)
    }

    suspend fun removeFromPersonalList(animeId: Long) = updatePersonalListMembership {
        personalListService.remove(animeId)
    }

    private suspend fun updatePersonalListMembership(
        operation: suspend () -> com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result<Boolean>,
    ): com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result<Boolean> {
        _personalListMutationError.value = null
        val result = operation()
        when (result) {
            is com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Success -> refresh()
            com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.NotFound ->
                _personalListMutationError.value = "Este anime não existe mais na biblioteca local."
            com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Conflict ->
                _personalListMutationError.value = "Minha Lista foi alterada por outra operação. Atualize e tente novamente."
            is com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Failure ->
                _personalListMutationError.value = result.cause.message
                    ?.takeIf(String::isNotBlank)
                    ?.let { "Não foi possível salvar Minha Lista: $it" }
                    ?: "Não foi possível salvar Minha Lista."
        }
        return result
    }

    fun setEpisodeWatched(episodeId: Long, watched: Boolean) =
        repository.setEpisodeWatched(episodeId, watched)

    fun openEpisode(episodeId: Long): String = repository.openEpisode(episodeId)

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
 
