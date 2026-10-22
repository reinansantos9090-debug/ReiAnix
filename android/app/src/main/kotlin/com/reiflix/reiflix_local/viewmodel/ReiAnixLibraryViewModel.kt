package com.reiflix.reiflix_local.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import com.reiflix.reiflix_local.MainActivity
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import com.reiflix.reiflix_local.resolveNativePlaybackStartPosition
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.ReiAnixBootstrapState
import androidx.annotation.Keep
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryRepository
import com.reiflix.reiflix_local.data.library.ReiAnixLibraryDiscoveryService
import com.reiflix.reiflix_local.data.organize.ReiAnixGenreRegistry
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixConsumptionService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixLibraryQueryResult
import com.reiflix.reiflix_local.data.sqlite.ReiAnixAnimeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixEpisodeRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixFolderRecord
import com.reiflix.reiflix_local.data.sqlite.ReiAnixArtworkRecord
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsContract
import com.reiflix.reiflix_local.data.sqlite.toggleAnimePinned
import com.reiflix.reiflix_local.data.sqlite.setAnimePersonalNote
import com.reiflix.reiflix_local.data.sqlite.setAnimeUserTags
import com.reiflix.reiflix_local.data.sqlite.setEpisodeManualIdentification
import com.reiflix.reiflix_local.storage.SafFileOperations
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.scanner.NativeScanController
import com.reiflix.reiflix_local.scanner.ScanLifecycleState
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import kotlinx.coroutines.withContext
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.UUID
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

private const val REIANIX_PLAYER_ORDER_SENTINEL = 1_000_000.0
private val REIANIX_NON_REGULAR_PLAYER_EPISODE_TYPES =
    setOf("movie", "special", "ova", "oad", "ona", "extra")

/**
 * Mirrors LibraryStore._episode_order_key for the native Compose player.
 * Rows with relative episode numbers sort before absolute-number-only rows;
 * stable media identity, filename and local path are the final tie breakers.
 */
private data class ReiAnixNativePlayerEpisodeOrderKey(
    val season: Double,
    val orderGroup: Int,
    val primaryNumber: Double,
    val secondaryNumber: Double,
    val mediaIdentity: String,
    val fileName: String,
    val path: String,
) : Comparable<ReiAnixNativePlayerEpisodeOrderKey> {
    override fun compareTo(other: ReiAnixNativePlayerEpisodeOrderKey): Int {
        var result = season.compareTo(other.season)
        if (result != 0) return result
        result = orderGroup.compareTo(other.orderGroup)
        if (result != 0) return result
        result = primaryNumber.compareTo(other.primaryNumber)
        if (result != 0) return result
        if (orderGroup == 1) {
            result = secondaryNumber.compareTo(other.secondaryNumber)
            if (result != 0) return result
        }
        result = mediaIdentity.compareTo(other.mediaIdentity)
        if (result != 0) return result
        result = fileName.compareTo(other.fileName)
        if (result != 0) return result
        return path.compareTo(other.path)
    }
}

private fun nativePlayerOrderText(value: String): String =
    value.lowercase(Locale.ROOT).replace("ß", "ss").replace("ς", "σ")

private fun nativePlayerEpisodeOrderKey(
    episode: ReiAnixEpisodeRecord,
): ReiAnixNativePlayerEpisodeOrderKey {
    fun numeric(value: Double?): Double =
        value?.takeIf { it.isFinite() } ?: REIANIX_PLAYER_ORDER_SENTINEL

    val number = numeric(episode.number)
    val absolute = numeric(episode.absoluteNumber)
    val hasRelativeNumber = number < REIANIX_PLAYER_ORDER_SENTINEL
    return ReiAnixNativePlayerEpisodeOrderKey(
        season = episode.season.toDouble(),
        orderGroup = if (hasRelativeNumber) 1 else 0,
        primaryNumber = if (hasRelativeNumber) number else absolute,
        secondaryNumber = if (hasRelativeNumber) absolute else 0.0,
        mediaIdentity = episode.mediaIdentity.orEmpty(),
        fileName = nativePlayerOrderText(episode.fileName),
        path = nativePlayerOrderText(episode.path),
    )
}

enum class ReiAnixNativeFoldersStatus {
    IDLE,
    LOADING,
    READY,
    EMPTY,
    NOT_FOUND,
    ERROR,
}

data class ReiAnixOrganizerGenreOperationState(
    val isRunning: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

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
class ReiAnixLibraryViewModel(
    context: Context,
    dependencies: ReiAnixAppDependencies? = null,
) :
    ReiAnixViewModel<ReiAnixLibraryUiState>() {
    private val appContext = context.applicationContext
    private val appDependencies = dependencies ?: ReiAnixAppDependencies.existingOrNull(appContext)

    // The former main.py player_launch_inflight flag allowed only one handoff to
    // be pending at a time. Keep that guarantee while MainActivity validates and
    // acknowledges the URI request.
    private val nativePlayerHandoffLock = Any()
    private var pendingNativePlayerHandoffRequestId: String? = null

    /** Process-owned bootstrap state is observable by Compose without transferring lifecycle ownership. */
    val bootstrapState: StateFlow<ReiAnixBootstrapState> =
        appDependencies?.bootstrap?.state ?: MutableStateFlow(ReiAnixBootstrapState())

    
    companion object {
        private const val TAG = "ReiAnixLibraryViewModel"
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

    private data class NativeCatalogState(
        val status: com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus =
            com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING,
        val animes: List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel> = emptyList(),
        val continueWatching: List<ReiAnixContinueWatchingUiModel> = emptyList(),
        val error: String? = null,
    )

    private val nativeCatalogState = MutableStateFlow(NativeCatalogState())
    private val nativeCatalogReloadMutex = Mutex()
    // Legacy runtime callbacks can invalidate the same projection many times in a burst.
    // A conflated channel keeps one follow-up reload instead of launching dozens of
    // identical SQLite catalog reads while the previous reload is still in flight.
    private val projectionRefreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val nativePageGeneration = AtomicLong(0L)
    private val nativePagedLibraryState = MutableStateFlow(ReiAnixLibraryPagedUiState())

    private val repository = ReiAnixLibraryRepository(context)
    /**
     * All native library consumers below share one repository/database instance.
     * The legacy repository remains only for projection/IPC operations that have
     * not yet been migrated; it is not the writer for favorite/watched mutations.
     */
    private val nativeLibraryRepository = dependencies?.sqliteLibraryRepository
        ?: com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository(context)
    /** Shared native genre identity service; uses the same canonical SQLite object. */
    private val nativeGenreRegistry = dependencies?.genreRegistry
        ?: ReiAnixGenreRegistry(nativeLibraryRepository.database)
    private val nativeLibraryQueries = dependencies?.libraryQueryService
        ?: ReiAnixLibraryQueryService(context, nativeLibraryRepository)
    private val nativeLibraryService = dependencies?.libraryService
        ?: ReiAnixLibraryService(nativeLibraryRepository)
    private val personalListService = dependencies?.personalListService
        ?: com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService(
            nativeLibraryRepository.database,
            nativeLibraryRepository,
        )
    private val consumptionService = dependencies?.consumptionService
        ?: com.reiflix.reiflix_local.data.sqlite.ReiAnixConsumptionService(nativeLibraryRepository)
    /** Native projection-only discovery rules use the same canonical library repository. */
    private val nativeLibraryDiscovery = dependencies?.libraryDiscoveryService
        ?: ReiAnixLibraryDiscoveryService(nativeLibraryRepository)

    private val _personalListMutationError = MutableStateFlow<String?>(null)
    val personalListMutationError: StateFlow<String?> = _personalListMutationError.asStateFlow()

    /** Dismisses a Home-owned error after the user has seen the failed list mutation. */
    fun clearPersonalListMutationError() {
        _personalListMutationError.value = null
    }

    private val _consumptionMutationError = MutableStateFlow<String?>(null)
    val consumptionMutationError: StateFlow<String?> = _consumptionMutationError.asStateFlow()
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

        return try {
            // Do not re-key a row while a scan of this same tree can still publish
            // a snapshot containing the old provider URI.
            val scanKey = "saf:$treeUri"
            val activeScanId = NativeScanController.activeScanId(scanKey)
            if (activeScanId != null) {
                NativeScanController.progress.first { snapshots ->
                    snapshots[activeScanId]?.state?.isTerminal == true
                }
            }

            val reconciliation = withContext(Dispatchers.IO) {
                nativeLibraryService.reconcileSafDocumentIdentityChange(
                    oldUri = oldUri.toString(),
                    newUri = result.uri.toString(),
                    name = result.name,
                    treeUri = treeUri.toString(),
                )
            }
            val reconciliationStatus = reconciliation.optString("status", "unknown")
            if (reconciliationStatus !in setOf("reconciled", "already_reconciled")) {
                Log.w(
                    "ReiAnixStorage",
                    "SAF identity reconciliation not applied operation=$operation status=$reconciliationStatus",
                )
                return SafFileOperations.Result.Failure(
                    SafFileOperations.FailureKind.PARTIAL,
                    "A operação no provedor foi confirmada, mas não foi possível provar a correspondência no catálogo ($reconciliationStatus). O registro existente foi preservado; verifique a biblioteca antes de repetir.",
                    oldUri,
                    result.uri,
                    recoverable = true,
                )
            }

            // This event now informs Python/UI only. The canonical URI mutation has
            // already committed in Kotlin and must never be repeated by LibraryStore.
            val event = JSONObject()
                .put("type", "saf_document_identity_change")
                .put(
                    "payload",
                    JSONObject()
                        .put("operation", operation)
                        .put("treeUri", treeUri.toString())
                        .put("oldUri", oldUri.toString())
                        .put("newUri", result.uri.toString())
                        .put("name", result.name)
                        .put("status", reconciliationStatus)
                        .put("episodeId", reconciliation.optLong("episode_id", -1L))
                        .put("nativeIdentityOwner", true),
                )
            val queued = withContext(Dispatchers.IO) {
                NativeMailbox.write(appContext, event)
            }
            if (queued) {
                result.copy(catalogReconciliationQueued = true)
            } else {
                SafFileOperations.Result.Failure(
                    SafFileOperations.FailureKind.PARTIAL,
                    "A URI do catálogo foi atualizada, mas a notificação de atualização da interface não pôde ser enfileirada. A mídia não foi removida; abra a biblioteca novamente para atualizar a projeção.",
                    oldUri,
                    result.uri,
                    recoverable = true,
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ReiAnixStorage", "SAF identity reconciliation failed after provider mutation", error)
            SafFileOperations.Result.Failure(
                SafFileOperations.FailureKind.PARTIAL,
                "A operação no provedor foi confirmada, mas a reconciliação canônica falhou. O registro existente foi preservado; verifique a biblioteca antes de repetir.",
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

    /** Native replacement for legacy local release timeline calculations. */
    suspend fun queryNativeTimelineGroups() = nativeLibraryDiscovery.timelineGroups()

    /** Native replacement for the local duration anomaly report; does not mutate playback state. */
    suspend fun queryNativeDurationAnomalies(animeId: Long? = null) =
        nativeLibraryDiscovery.durationAnomalies(animeId)

    /** Native replacement for the remaining-time marathon projection. */
    suspend fun queryNativeMarathonPlan(animeId: Long, currentPath: String? = null) =
        nativeLibraryDiscovery.marathonPlan(animeId, currentPath)

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
    override val uiState: StateFlow<ReiAnixLibraryUiState> = combine(
        repository.state,
        nativeCatalogState,
        NativeScanController.progress,
    ) { bridgeState, catalog, scans ->
        val mergedStatus = when {
            catalog.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING ->
                if (bridgeState.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR) {
                    bridgeState.status
                } else {
                    com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING
                }
            catalog.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR &&
                catalog.animes.isNotEmpty() ->
                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
            else -> catalog.status
        }

        // Current Android scan progress and terminal status come from the native
        // coordinator. The compatibility snapshot still owns storage/onboarding,
        // but can no longer leave Compose in "scanning" after Kotlin has finished.
        val safScans = scans.values.filter { it.source.equals("saf", ignoreCase = true) }
        val activeSafScan = safScans
            .filter { !it.state.isTerminal }
            .maxByOrNull { it.startedAtMillis }
        val latestSafScan = safScans
            .maxByOrNull { it.finishedAtMillis ?: it.startedAtMillis }
        val selectedScan = activeSafScan ?: latestSafScan
        val nativeScanState = when (selectedScan?.state) {
            ScanLifecycleState.PREPARING -> "PREPARING"
            ScanLifecycleState.RUNNING -> "RUNNING"
            ScanLifecycleState.CANCELLATION_REQUESTED -> "CANCELLING"
            ScanLifecycleState.CANCELLED -> "CANCELLED"
            ScanLifecycleState.SUCCEEDED -> "COMPLETED"
            ScanLifecycleState.PARTIAL -> "PARTIAL"
            ScanLifecycleState.FAILED -> "FAILED"
            ScanLifecycleState.IDLE, null -> bridgeState.scanState
        }
        val nativeLastScanStatus = when (latestSafScan?.state) {
            ScanLifecycleState.CANCELLED -> "CANCELLED"
            ScanLifecycleState.SUCCEEDED -> "COMPLETED"
            ScanLifecycleState.PARTIAL -> "PARTIAL"
            ScanLifecycleState.FAILED -> "FAILED"
            else -> bridgeState.lastScanStatus
        }
        val nativeScanInProgress = when {
            latestSafScan == null -> bridgeState.scanInProgress
            else -> activeSafScan != null
        }

        bridgeState.copy(
            status = mergedStatus,
            animes = catalog.animes,
            continueWatching = catalog.continueWatching,
            scanInProgress = nativeScanInProgress,
            scanState = nativeScanState,
            lastScanStatus = nativeLastScanStatus,
            // Availability still describes the configured storage/source contract;
            // a successful SQLite read must not masquerade as granted storage access.
            sourceAvailable = bridgeState.sourceAvailable,
            sourceState = bridgeState.sourceState,
            error = catalog.error ?: bridgeState.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        repository.state.value.copy(
            status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING,
            animes = emptyList(),
            continueWatching = emptyList(),
        ),
    )

    /** Filtered Library pages are now projected from Kotlin records read from canonical SQLite. */
    val pagedLibraryState: StateFlow<ReiAnixLibraryPagedUiState> = nativePagedLibraryState.asStateFlow()

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
        loadNativeLibraryPage(
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

    private val _organizerGenreOperationState =
        MutableStateFlow(ReiAnixOrganizerGenreOperationState())
    val organizerGenreOperationState: StateFlow<ReiAnixOrganizerGenreOperationState> =
        _organizerGenreOperationState.asStateFlow()

    /**
     * Explicit local classification action for missing genres only. The writer is
     * Kotlin and commits to the canonical database; mailbox events below are
     * projection-refresh notifications only and never repeat the catalog mutation.
     */
    fun classifyMissingLocalGenres() {
        if (_organizerGenreOperationState.value.isRunning) return
        viewModelScope.launch {
            _organizerGenreOperationState.value = ReiAnixOrganizerGenreOperationState(
                isRunning = true,
                message = "Classificando gêneros locais ausentes…",
            )
            try {
                nativeGenreRegistry.initialize()
                val report = nativeGenreRegistry.classifyMissingLocalGenres()
                val notificationFailed = report.changedAnimeIds.isNotEmpty() &&
                    !withContext(Dispatchers.IO) {
                        NativeMailbox.write(
                            appContext,
                            JSONObject()
                                .put("type", "native_genres_committed")
                                .put(
                                    "payload",
                                    JSONObject().put("changedCount", report.changedAnimeIds.size),
                                ),
                        )
                    }
                val message = when {
                    report.classifiedAnime > 0 && notificationFailed ->
                        "Gêneros salvos para ${report.classifiedAnime} animes; reabra a biblioteca para atualizar a projeção."
                    report.classifiedAnime > 0 ->
                        "${report.assignedGenreCount} associação(ões) de gênero salvas em ${report.classifiedAnime} animes."
                    report.skippedAuthoritativeMetadata > 0 ->
                        "Nenhum gênero novo foi inferido. ${report.skippedAuthoritativeMetadata} registro(s) foram preservados por metadados remotos/manuais; títulos sem regra correspondente ficaram sem rótulos."
                    else ->
                        "Nenhum gênero ausente pôde ser inferido pelas regras locais."
                }
                _organizerGenreOperationState.value = ReiAnixOrganizerGenreOperationState(
                    message = message,
                )
            } catch (cancelled: CancellationException) {
                _organizerGenreOperationState.value = ReiAnixOrganizerGenreOperationState()
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Local genre classification failed", error)
                _organizerGenreOperationState.value = ReiAnixOrganizerGenreOperationState(
                    message = "Não foi possível classificar os gêneros locais: ${error.message ?: "erro desconhecido"}",
                    isError = true,
                )
            }
        }
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
            var pagesFetched = 0
            val maxPages = 2
            while (hasMore && queryError == null && pagesFetched < maxPages) {
                when (val result = nativeLibraryQueries.searchAnime(
                    searchText = request.settledQuery,
                    genreKey = filters.selectedGenreKey,
                    favoritesOnly = filters.favoritesOnly,
                    watchingOnly = filters.watchingOnly,
                    completedOnly = filters.completedOnly,
                    sortLabel = filters.sort,
                    limit = ReiAnixLibraryQueryService.MAX_PAGE_SIZE,
                    offset = offset,
                    libraryOnly = true,
                )) {
                    is ReiAnixLibraryQueryResult.Success -> {
                        val page = result.value
                        selectedIds += page.animeIds
                        hasMore = page.hasMore
                        offset += page.limit
                        pagesFetched++
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
        // Explicit user refresh is a scan request. After its terminal event,
        // refreshNativeCatalog() reloads both the canonical catalog and the active
        // filtered page directly from SQLite.
        repository.refresh()
    }

    /**
     * Reloads the canonical SQLite projection without requesting another filesystem scan.
     * Use after a completed local mutation, player checkpoint, permission-state update,
     * metadata/artwork commit, or catalog event.
     */
    fun refreshCatalogProjection() {
        projectionRefreshRequests.trySend(Unit)
    }

    /**
     * Refreshes the local Details projection directly from canonical SQLite and
     * asks the native artwork pipeline to repair this item's poster/backdrop.
     * This read path deliberately does not issue a legacy Python catalog command.
     */
    fun refreshDetails(animeId: Long) {
        if (animeId <= 0L) return
        viewModelScope.launch {
            refreshNativeCatalog()
            appDependencies?.artworkService?.requestAnimeArtwork(animeId)
        }
    }

    private var canonicalConsumersStarted = false

    init {
        val dependencies = appDependencies
        viewModelScope.launch {
            // Queue projection-only refreshes until the canonical database is healthy.
            // Recovery mode already has its own route and will release this worker only
            // after bootstrap has accepted the restored database.
            if (dependencies != null) {
                dependencies.bootstrap.state.first { state ->
                    !state.recoveryRequired &&
                        state.stage == com.reiflix.reiflix_local.ReiAnixBootstrapStage.READY &&
                        state.persistenceStatus in setOf(
                            com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.HEALTHY,
                            com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
                        )
                }
            }
            for (ignored in projectionRefreshRequests) {
                try {
                    if (dependencies == null) {
                        refreshNativeCatalog()
                    } else {
                        refreshCanonicalCatalogAfterBootstrapValidation()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Projection-only refresh failed", error)
                }
            }
        }
        if (dependencies == null) {
            // Isolated UI/test construction has no process bootstrap to await.
            startCanonicalConsumersAfterDatabaseValidation()
        } else {
            viewModelScope.launch {
                dependencies.bootstrap.state.first { state ->
                    !state.recoveryRequired &&
                        state.stage == com.reiflix.reiflix_local.ReiAnixBootstrapStage.READY &&
                        state.persistenceStatus in setOf(
                            com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.HEALTHY,
                            com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
                        )
                }
                startCanonicalConsumersAfterDatabaseValidation()
            }
        }
    }

    @Synchronized
    private fun startCanonicalConsumersAfterDatabaseValidation() {
        if (canonicalConsumersStarted) return
        canonicalConsumersStarted = true

        // Read the local canonical catalog only after bootstrap allows normal use.
        viewModelScope.launch { refreshNativeCatalog() }

        // Opening the app must not schedule missing/stale artwork downloads. Artwork is
        // requested only by explicit details/artwork actions or existing pipeline events.
        // Player notifications are emitted only after a successful SQLite commit.
        // Periodic notifications arrive at the existing 15-second checkpoint, not the
        // 250 ms UI clock, so Compose remains responsive without excessive full queries.
        viewModelScope.launch {
            ReiAnixConsumptionService.playbackChanges
                .conflate()
                .collectLatest { change ->
                    Log.d(
                        TAG,
                        "Native playback committed episodeId=${change.episodeId} " +
                            "event=${change.eventType} sessionId=${change.playerSessionId} " +
                            "playerGeneration=${change.playerGeneration} " +
                            "transitionGeneration=${change.transitionGeneration}",
                    )
                    refreshNativeCatalog()
                }
        }

        // Load catalog records directly from the canonical SQLite repository. The legacy
        // snapshot is retained only for storage/onboarding and diagnostic scan state.
        viewModelScope.launch {
            NativeScanController.progress
                .map { snapshots ->
                    snapshots.filterValues { it.state.isTerminal }
                        .mapValues { (_, snapshot) -> snapshot.finishedAtMillis }
                }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest {
                    // This collector emits only after a new terminal native-scan snapshot;
                    // its first saved value is dropped, so process startup alone does not
                    // trigger network metadata matching or artwork backfill.
                    refreshNativeCatalog()
                }
        }
        // Legacy commands can still mutate unrelated fields; their revision is only an
        // invalidation signal. Actual anime/episode values are always reread from SQLite.
        viewModelScope.launch {
            repository.state
                .map { it.revision }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { refreshNativeCatalog() }
        }

        libraryFilterJob?.cancel()
        libraryFilterJob = viewModelScope.launch {
            libraryFilters
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest { filters ->
                    loadNativeLibraryPage(
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

    /**
     * Recovery restore uses this path after the process bootstrap has revalidated the
     * canonical database. Unlike refresh(), this never starts a scan or Python catalog
     * page request; it reloads the committed SQLite catalog and its local filtered page.
     */
    suspend fun refreshCanonicalCatalogAfterBootstrapValidation(): Boolean {
        val state = appDependencies?.bootstrap?.state?.value ?: return false
        if (
            state.recoveryRequired ||
            state.stage != com.reiflix.reiflix_local.ReiAnixBootstrapStage.READY ||
            state.persistenceStatus !in setOf(
                com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.HEALTHY,
                com.reiflix.reiflix_local.ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
            )
        ) return false

        startCanonicalConsumersAfterDatabaseValidation()
        refreshNativeCatalog()
        return nativeCatalogState.value.status != com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR &&
            nativePagedLibraryState.value.status != com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR
    }

    private suspend fun refreshNativeCatalog() {
        nativeCatalogReloadMutex.withLock {
            try {
                val rows = nativeLibraryRepository.loadLibraryCatalogRows()
                val projected = com.reiflix.reiflix_local.data.library.ReiAnixNativeLibraryCatalogProjector
                    .project(rows)
                nativeCatalogState.value = NativeCatalogState(
                    status = if (projected.animes.isEmpty()) {
                        com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
                    } else {
                        com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
                    },
                    animes = projected.animes,
                    continueWatching = projected.continueWatching,
                )
                val filters = libraryFilters.value
                loadNativeLibraryPage(
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Native canonical library projection failed", error)
                val previous = nativeCatalogState.value
                nativeCatalogState.value = previous.copy(
                    status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR,
                    error = error.message ?: error::class.java.simpleName,
                )
                if (previous.animes.isEmpty()) {
                    nativePagedLibraryState.value = nativePagedLibraryState.value.copy(
                        status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR,
                        isLoading = false,
                        error = error.message ?: "Não foi possível ler a biblioteca SQLite.",
                    )
                } else {
                    nativePagedLibraryState.value = nativePagedLibraryState.value.copy(
                        isLoading = false,
                        error = error.message ?: "A atualização da biblioteca falhou; dados anteriores foram preservados.",
                    )
                }
            }
        }
    }

    private fun loadNativeLibraryPage(
        page: Int = 0,
        pageSize: Int = configuredLibraryPageSize,
        query: String = "",
        genre: String = "Todos",
        sort: String = "Mais recentes",
        favoritesOnly: Boolean = false,
        watchingOnly: Boolean = false,
        completedOnly: Boolean = false,
        reset: Boolean = page == 0,
    ) {
        val requestedPage = page.coerceAtLeast(0)
        val normalizedPageSize = pageSize.coerceIn(12, 72)
        val generation = if (reset) {
            nativePageGeneration.incrementAndGet()
        } else {
            nativePageGeneration.get().let { current ->
                if (current > 0L) current else nativePageGeneration.incrementAndGet()
            }
        }
        val normalizedGenre = genre.takeIf { it != "Todos" }?.trim()
        val previous = nativePagedLibraryState.value
        nativePagedLibraryState.value = if (reset) {
            ReiAnixLibraryPagedUiState(
                status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING,
                isLoading = true,
                pageSize = normalizedPageSize,
                generation = generation,
                query = query.trim(),
                genreKey = normalizedGenre,
                favoritesOnly = favoritesOnly,
                watchingOnly = watchingOnly,
                completedOnly = completedOnly,
                sort = sort.trim().ifEmpty { "Mais recentes" },
            )
        } else if (previous.generation == generation) {
            previous.copy(
                isLoading = true,
                pageSize = normalizedPageSize,
                query = query.trim(),
                genreKey = normalizedGenre,
                favoritesOnly = favoritesOnly,
                watchingOnly = watchingOnly,
                completedOnly = completedOnly,
                sort = sort.trim().ifEmpty { "Mais recentes" },
                error = null,
            )
        } else {
            previous
        }

        viewModelScope.launch(Dispatchers.Default) {
            val catalog = nativeCatalogState.value
            if (catalog.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING) return@launch
            if (generation != nativePageGeneration.get()) return@launch

            val offsetLong = requestedPage.toLong() * normalizedPageSize.toLong()
            val offset = offsetLong.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val current = nativePagedLibraryState.value
            if (current.generation != generation) return@launch
            when (val result = nativeLibraryQueries.searchAnime(
                searchText = query,
                genreKey = normalizedGenre,
                favoritesOnly = favoritesOnly,
                watchingOnly = watchingOnly,
                completedOnly = completedOnly,
                sortLabel = sort,
                limit = normalizedPageSize,
                offset = offset,
                libraryOnly = true,
            )) {
                is ReiAnixLibraryQueryResult.Success -> {
                    val pageResult = result.value
                    val byId = catalog.animes.associateBy { it.id }
                    val missingIds = pageResult.animeIds.filterNot(byId::containsKey)
                    if (missingIds.isNotEmpty()) {
                        nativePagedLibraryState.value = current.copy(
                            status = if (current.animes.isEmpty()) {
                                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR
                            } else current.status,
                            isLoading = false,
                            error = "A consulta SQLite encontrou alterações concorrentes; a biblioteca será relida.",
                        )
                        refreshNativeCatalog()
                        return@launch
                    }
                    val pageItems = pageResult.animeIds.mapNotNull(byId::get)
                    if (generation != nativePageGeneration.get()) return@launch
                    val currentState = nativePagedLibraryState.value
                    if (currentState.generation != generation) return@launch
                    val pageAnimes = if (reset || requestedPage == 0) {
                        pageItems
                    } else {
                        (currentState.animes + pageItems).distinctBy { it.id }
                    }
                    nativePagedLibraryState.value = currentState.copy(
                        status = if (pageResult.totalCount > 0) {
                            com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
                        } else {
                            com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
                        },
                        animes = pageAnimes,
                        totalCount = pageResult.totalCount,
                        hasMore = pageResult.hasMore,
                        loadedPage = requestedPage,
                        isLoading = false,
                        pageSize = normalizedPageSize,
                        requestId = null,
                        error = null,
                    )
                }
                is ReiAnixLibraryQueryResult.Empty -> {
                    if (generation != nativePageGeneration.get()) return@launch
                    val currentState = nativePagedLibraryState.value
                    if (currentState.generation != generation) return@launch
                    nativePagedLibraryState.value = currentState.copy(
                        status = if (requestedPage == 0) {
                            com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
                        } else currentState.status,
                        animes = if (requestedPage == 0) emptyList() else currentState.animes,
                        totalCount = if (requestedPage == 0) 0 else currentState.totalCount,
                        hasMore = false,
                        loadedPage = if (requestedPage == 0) 0 else currentState.loadedPage,
                        isLoading = false,
                        pageSize = normalizedPageSize,
                        requestId = null,
                        error = null,
                    )
                }
                is ReiAnixLibraryQueryResult.Failure -> {
                    val currentState = nativePagedLibraryState.value
                    if (currentState.generation == generation) {
                        nativePagedLibraryState.value = currentState.copy(
                            status = if (currentState.animes.isEmpty()) {
                                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR
                            } else currentState.status,
                            isLoading = false,
                            requestId = null,
                            error = result.cause.message
                                ?.takeIf(String::isNotBlank)
                                ?: "Não foi possível consultar a biblioteca SQLite.",
                        )
                    }
                }
                is ReiAnixLibraryQueryResult.NotFound -> {
                    val currentState = nativePagedLibraryState.value
                    if (currentState.generation == generation) {
                        nativePagedLibraryState.value = currentState.copy(
                            status = if (currentState.animes.isEmpty()) {
                                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR
                            } else currentState.status,
                            isLoading = false,
                            requestId = null,
                            error = "A consulta da biblioteca retornou um resultado inconsistente.",
                        )
                    }
                }
            }
        }
    }

    fun loadNextLibraryPage() {
        val page = pagedLibraryState.value
        if (!page.hasMore || page.isLoading || page.loadedPage < 0) return
        val filters = libraryFilters.value
        loadNativeLibraryPage(
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

    /** Start the platform SAF selector natively from the Compose storage surface. */
    fun selectSafTree() {
        appContext.startActivity(MainActivity.createNativeStorageActionIntent(appContext, "select_tree"))
    }

    fun dismissStorageOnboarding() = repository.dismissStorageOnboarding()

    /**
     * Requests native release first. MainActivity owns the permission -> canonical SQLite
     * removal transaction and calls onSafRemovalTerminal only after the durable result.
     */
    private val pendingSafRemovalReferences = ConcurrentHashMap.newKeySet<String>()

    fun removeSafTree(reference: String) {
        val normalized = reference.trim()
        if (normalized.isBlank()) return
        if (!pendingSafRemovalReferences.add(normalized)) {
            Log.i("ReiAnixLibraryViewModel", "Ignoring duplicate SAF removal request while pending")
            return
        }
        try {
            appContext.startActivity(
                MainActivity.createNativeStorageActionIntent(appContext, "release_tree", normalized),
            )
        } catch (error: Exception) {
            pendingSafRemovalReferences.remove(normalized)
            Log.e("ReiAnixLibraryViewModel", "Could not dispatch native SAF removal", error)
        }
    }

    /** Refresh Compose only after MainActivity publishes a terminal native result. */
    fun onSafRemovalTerminal(reference: String) {
        pendingSafRemovalReferences.remove(reference.trim())
        refreshNativeFolders()
        repository.publishCatalog()
    }

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
     * canonical catalog projection only after the transaction has succeeded.
     */
    fun toggleFavorite(animeId: Long) {
        if (animeId <= 0L) {
            _personalListMutationError.value = "Não foi possível alterar Minha Lista: identificador inválido."
            return
        }
        viewModelScope.launch {
            _personalListMutationError.value = null
            when (val result = personalListService.toggle(animeId)) {
                is com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Success -> {
                    refreshNativeCatalog()
                    repository.publishCatalog()
                }
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
            is com.reiflix.reiflix_local.data.sqlite.ReiAnixPersonalListService.Result.Success -> {
                refreshNativeCatalog()
                repository.publishCatalog()
            }
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

    fun setEpisodeWatched(episodeId: Long, watched: Boolean) {
        if (episodeId <= 0L) {
            _consumptionMutationError.value = "Não foi possível atualizar o episódio: identificador inválido."
            return
        }
        viewModelScope.launch {
            _consumptionMutationError.value = null
            try {
                consumptionService.setWatched(episodeId, watched)
                refreshNativeCatalog()
                // The projection bridge only republishes compatibility/diagnostic state;
                // the watched-state mutation itself is performed once by Kotlin.
                repository.publishCatalog()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _consumptionMutationError.value = error.message
                    ?.takeIf(String::isNotBlank)
                    ?.let { "Não foi possível atualizar o episódio: $it" }
                    ?: "Não foi possível atualizar o episódio."
            }
        }
    }

    /** Persist personal pin state in the canonical SQLite store and refresh native projections. */
    suspend fun toggleAnimePinnedNative(animeId: Long): Boolean {
        val pinned = nativeLibraryRepository.toggleAnimePinned(animeId)
        refreshNativeCatalog()
        repository.publishCatalog()
        return pinned
    }

    /** Save the private note without modifying remote editorial metadata or another store. */
    suspend fun saveAnimePersonalNoteNative(animeId: Long, note: String?): String? {
        val saved = nativeLibraryRepository.setAnimePersonalNote(animeId, note)
        refreshNativeCatalog()
        repository.publishCatalog()
        return saved
    }

    /** Persist normalized personal tags in the canonical anime.user_tags field. */
    suspend fun saveAnimeUserTagsNative(animeId: Long, tags: List<String>): List<String> {
        val saved = nativeLibraryRepository.setAnimeUserTags(animeId, tags)
        refreshNativeCatalog()
        repository.publishCatalog()
        return saved
    }

    /** Persist a manual episode identity while preserving its playback progress and file identity. */
    suspend fun setEpisodeManualIdentificationNative(
        episodeId: Long,
        season: Int?,
        number: Double?,
        episodeType: String,
        title: String?,
    ) {
        nativeLibraryRepository.setEpisodeManualIdentification(
            episodeId = episodeId,
            season = season,
            number = number,
            episodeType = episodeType,
            title = title,
        )
        refreshNativeCatalog()
        repository.publishCatalog()
    }

    /**
     * Resolve a Compose playback request from canonical SQLite and request the
     * existing native MainActivity handoff. This path does not send open_media
     * through NativeMailbox or invoke the Python worker.
     */
    /** Publish native player handoff status into the existing library UI state. */
    fun reportNativePlayerHandoff(
        requestId: String,
        status: String,
        error: String? = null,
    ) {
        repository.reportNativePlayerHandoff(requestId, status, error)
        val normalizedStatus = status.trim().uppercase(Locale.ROOT)
        val eventName = when (normalizedStatus) {
            "COMPLETED", "ACCEPTED", "SUCCESS" -> "PLAYER_HANDOFF_ACCEPTED"
            "CANCELLED", "CANCELED" -> "PLAYER_HANDOFF_CANCELLED"
            else -> "PLAYER_HANDOFF_FAILED"
        }
        ReiAnixDiagnosticTimeline.record(
            name = eventName,
            severity = if (eventName == "PLAYER_HANDOFF_FAILED") "WARN" else "INFO",
            source = "ReiAnixLibraryViewModel",
            result = normalizedStatus.ifBlank { "UNKNOWN" },
            requestId = requestId,
            context = mapOf(
                "has_error" to !error.isNullOrBlank(),
                "status" to normalizedStatus,
            ),
        )
        if (eventName == "PLAYER_HANDOFF_ACCEPTED") {
            ReiAnixDiagnosticTimeline.record(
                name = "ASSIST_REQUEST_ACCEPTED",
                source = "ReiAnixLibraryViewModel",
                result = "native_handoff_confirmed",
                requestId = requestId,
            )
            ReiAnixDiagnosticTimeline.record(
                name = "CONTINUE_REQUEST_ACCEPTED",
                source = "ReiAnixLibraryViewModel",
                result = "native_handoff_confirmed",
                requestId = requestId,
            )
        }
        synchronized(nativePlayerHandoffLock) {
            if (pendingNativePlayerHandoffRequestId == requestId) {
                pendingNativePlayerHandoffRequestId = null
            }
        }
    }

    /** Release the single-flight gate when the legacy runtime disconnects mid-handoff. */
    fun cancelPendingNativePlayerHandoff(reason: String = "runtime_disconnect") {
        val pending = synchronized(nativePlayerHandoffLock) {
            pendingNativePlayerHandoffRequestId
        } ?: return
        reportNativePlayerHandoff(
            requestId = pending,
            status = "CANCELLED",
            error = reason,
        )
    }

    fun openEpisode(episodeId: Long, expectedAnimeId: Long? = null): String? {
        val requestId = UUID.randomUUID().toString()
        val duplicateRequestId = synchronized(nativePlayerHandoffLock) {
            val existing = pendingNativePlayerHandoffRequestId
            if (existing != null) {
                existing
            } else {
                pendingNativePlayerHandoffRequestId = requestId
                null
            }
        }
        if (duplicateRequestId != null) {
            Log.i(TAG, "PLAYER_HANDOFF_DUPLICATE_IGNORED episodeId=$episodeId pendingRequestId=$duplicateRequestId")
            // The legacy player_launch_inflight guard ignored duplicate play taps.
            // Returning null prevents Compose from treating the old request ID as
            // a new launch and leaves the first handoff as the only player request.
            return null
        }

        viewModelScope.launch(Dispatchers.IO) {
            var nativeIntentStarted = false
            try {
                require(episodeId > 0L) { "O identificador do episódio é inválido." }
                val progressLookupStartedAtMs = System.currentTimeMillis()
                ReiAnixDiagnosticTimeline.record(
                    name = "CONTINUE_REQUEST_CREATED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    context = mapOf(
                        "episode_id" to episodeId,
                        "expected_anime_id" to (expectedAnimeId ?: 0L),
                    ),
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "PROGRESS_LOOKUP_STARTED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    context = mapOf("episode_id" to episodeId),
                )
                val episode = nativeLibraryRepository.getEpisode(episodeId)
                    ?: run {
                        ReiAnixDiagnosticTimeline.record(
                            name = "PROGRESS_LOOKUP_COMPLETED",
                            severity = "WARN",
                            source = "ReiAnixLibraryViewModel",
                            result = "not_found",
                            requestId = requestId,
                            context = mapOf(
                                "episode_id" to episodeId,
                                "found" to false,
                                "duration_ms" to (System.currentTimeMillis() - progressLookupStartedAtMs).coerceAtLeast(0L),
                            ),
                        )
                        throw IllegalArgumentException("Episódio não encontrado no catálogo local.")
                    }
                ReiAnixDiagnosticTimeline.record(
                    name = "PROGRESS_LOOKUP_COMPLETED",
                    source = "ReiAnixLibraryViewModel",
                    result = "found",
                    requestId = requestId,
                    context = mapOf(
                        "episode_id" to episodeId,
                        "found" to true,
                        "duration_ms" to (System.currentTimeMillis() - progressLookupStartedAtMs).coerceAtLeast(0L),
                    ),
                )
                require(expectedAnimeId == null || episode.animeId == expectedAnimeId) {
                    "O episódio não pertence ao anime solicitado."
                }
                require(episode.missing != true && episode.path.isNotBlank()) {
                    "Este episódio não possui uma mídia local disponível."
                }
                nativeLibraryRepository.getAnime(episode.animeId)
                    ?: throw IllegalStateException("O anime associado ao episódio não existe no catálogo.")

                val dependencies = appDependencies
                    ?: throw IllegalStateException("O grafo nativo ainda não está pronto para abrir episódios.")
                val persistedSettings = dependencies.sqliteSettingsRepository.allPreferences()
                fun preferenceValue(key: String): String =
                    persistedSettings[key] ?: ReiAnixSettingsContract.defaultFor(key).orEmpty()

                // Keep episode adjacency in one canonical SQLite query. This applies
                // the same availability predicate and ordering as the retained LibraryStore.
                val navigation = dependencies.sqliteLibraryRepository.playerNavigation(episodeId)
                    ?: throw IllegalArgumentException("O episódio não existe no catálogo local.")
                require(navigation.current.id == episodeId && navigation.current.animeId == episode.animeId) {
                    "O catálogo local mudou durante a abertura do episódio."
                }
                val canPrevious = navigation.previous != null
                val canNext = navigation.next != null
                ReiAnixDiagnosticTimeline.record(
                    name = "EPISODE_RESOLVED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    context = mapOf(
                        "episode_id" to episode.id,
                        "anime_id" to episode.animeId,
                        "can_previous" to canPrevious,
                        "can_next" to canNext,
                        "source" to "sqlite_player_navigation",
                    ),
                )

                val rawReference = episode.path.trim()
                val parsedReference = Uri.parse(rawReference)
                val mediaUri = when (parsedReference.scheme?.lowercase(Locale.ROOT)) {
                    "content", "file" -> parsedReference
                    null -> {
                        require(rawReference.startsWith("/")) {
                            "A referência de mídia local não é um caminho absoluto nem uma URI autorizada."
                        }
                        Uri.fromFile(File(rawReference))
                    }
                    else -> throw IllegalArgumentException(
                        "O ReiAnix aceita somente mídias locais autorizadas.",
                    )
                }

                val resumeEnabled = preferenceValue("player.resume").toBooleanStrictOrNull() ?: true
                val playbackStart = resolveNativePlaybackStartPosition(
                    progressSeconds = episode.progress,
                    durationSeconds = episode.duration,
                    watched = episode.watched,
                    resumeEnabled = resumeEnabled,
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "PROGRESS_VALIDATED",
                    severity = if (playbackStart.progressInvalid) "WARN" else "INFO",
                    source = "ReiAnixLibraryViewModel",
                    result = if (playbackStart.progressInvalid) "INVALID_PERSISTED_PROGRESS" else "valid",
                    requestId = requestId,
                    context = mapOf(
                        "episode_id" to episode.id,
                        "progress_seconds" to playbackStart.positionSeconds,
                        "duration_seconds" to (episode.duration ?: 0.0),
                        "resume_enabled" to resumeEnabled,
                        "watched" to (episode.watched == true),
                    ),
                )
                if (playbackStart.progressInvalid) {
                    Log.w(
                        TAG,
                        "PLAYER_PROGRESS_REJECTED episodeId=${episode.id} reason=INVALID_PERSISTED_PROGRESS",
                    )
                    ReiAnixDiagnosticTimeline.record(
                        name = "PLAYER_PROGRESS_REJECTED",
                        severity = "WARN",
                        source = "ReiAnixLibraryViewModel",
                        result = "INVALID_PERSISTED_PROGRESS",
                        context = mapOf(
                            "episode_id" to episode.id,
                            "progress_seconds" to (episode.progress ?: 0.0),
                            "duration_seconds" to (episode.duration ?: 0.0),
                        ),
                    )
                }
                val createdAtMs = System.currentTimeMillis()
                val playerSessionId = UUID.randomUUID().toString()
                ReiAnixDiagnosticTimeline.record(
                    name = "ASSIST_REQUEST_CREATED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    sessionId = playerSessionId,
                    context = mapOf(
                        "episode_id" to episode.id,
                        "anime_id" to episode.animeId,
                        "origin" to "details_or_episode_card",
                    ),
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "PLAYER_SESSION_CREATED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    sessionId = playerSessionId,
                    context = mapOf(
                        "episode_id" to episode.id,
                        "anime_id" to episode.animeId,
                        "origin" to "kotlin_launch",
                    ),
                )
                val title = episode.episodeTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: episode.fileName.trim().takeIf { it.isNotEmpty() }
                    ?: "Episódio"

                val commandUri = Uri.parse("reiflix://native").buildUpon()
                    .appendQueryParameter("action", "play")
                    .appendQueryParameter("request_id", requestId)
                    .appendQueryParameter("protocol_version", "2")
                    .appendQueryParameter("created_at", createdAtMs.toString())
                    .appendQueryParameter("uri", mediaUri.toString())
                    .appendQueryParameter("episode_id", episode.id.toString())
                    .appendQueryParameter("anime_id", episode.animeId.toString())
                    .appendQueryParameter("player_session_id", playerSessionId)
                    .appendQueryParameter("title", title)
                    .apply {
                        playbackStart.positionMsOverride?.let { positionMs ->
                            appendQueryParameter("position_ms", positionMs.toString())
                        }
                    }
                    .appendQueryParameter("can_next", canNext.toString())
                    .appendQueryParameter("can_previous", canPrevious.toString())
                    .appendQueryParameter(
                        "autoplay",
                        preferenceValue("player.autoplay_next").toBooleanStrictOrNull()?.toString() ?: "true",
                    )
                    .appendQueryParameter("native_compose", "true")
                ReiAnixDiagnosticTimeline.record(
                    name = "MEDIA_RESOLVED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    sessionId = playerSessionId,
                    context = mapOf(
                        "episode_id" to episode.id,
                        "anime_id" to episode.animeId,
                        "uri_source" to "canonical_sqlite_row",
                    ),
                )

                val playerSettings = listOf(
                    "player.default_speed" to "setting_player_default_speed",
                    "player.aspect_ratio" to "setting_player_aspect_ratio",
                    "player.zoom_enabled" to "setting_player_zoom_enabled",
                    "player.immersive" to "setting_player_immersive",
                    "player.rotation" to "setting_player_rotation",
                    "player.pip" to "setting_player_pip",
                    "player.lock_mode" to "setting_player_lock_mode",
                    "player.auto_hide_seconds" to "setting_player_auto_hide_seconds",
                    "player.double_tap_seek_seconds" to "setting_player_double_tap_seek_seconds",
                    "player.long_press_speed" to "setting_player_long_press_speed",
                    "player.max_video_resolution" to "setting_player_max_video_resolution",
                    "player.max_video_frame_rate" to "setting_player_max_video_frame_rate",
                    "player.max_audio_channels" to "setting_player_max_audio_channels",
                    "gestures.volume" to "setting_gestures_volume",
                    "gestures.brightness" to "setting_gestures_brightness",
                    "gestures.double_tap" to "setting_gestures_double_tap",
                    "gestures.long_press" to "setting_gestures_long_press",
                    "audio.preferred_language" to "setting_audio_preferred_language",
                    "audio.preferred_subtitle_language" to "setting_audio_preferred_subtitle_language",
                    "audio.subtitles" to "setting_audio_subtitles",
                    "audio.subtitle_scale" to "setting_audio_subtitle_scale",
                    "audio.subtitle_bottom_padding" to "setting_audio_subtitle_bottom_padding",
                    "audio.subtitle_embedded_style" to "setting_audio_subtitle_embedded_style",
                )
                playerSettings.forEach { (preferenceKey, queryKey) ->
                    commandUri.appendQueryParameter(queryKey, preferenceValue(preferenceKey))
                }

                val intent = Intent(
                    Intent.ACTION_VIEW,
                    commandUri.build(),
                    appContext,
                    MainActivity::class.java,
                ).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "PLAYER_HANDOFF_STARTED",
                    source = "ReiAnixLibraryViewModel",
                    requestId = requestId,
                    sessionId = playerSessionId,
                    context = mapOf(
                        "episode_id" to episode.id,
                        "anime_id" to episode.animeId,
                        "destination" to "MainActivity",
                    ),
                )
                withContext(Dispatchers.Main) {
                    appContext.startActivity(intent)
                }
                nativeIntentStarted = true
            } catch (cancelled: CancellationException) {
                synchronized(nativePlayerHandoffLock) {
                    if (pendingNativePlayerHandoffRequestId == requestId) {
                        pendingNativePlayerHandoffRequestId = null
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Native Compose player handoff failed requestId=$requestId episodeId=$episodeId",
                    error,
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "ASSIST_FAILED",
                    severity = "ERROR",
                    source = "ReiAnixLibraryViewModel",
                    result = "native_handoff_exception",
                    requestId = requestId,
                    error = error,
                    context = mapOf("episode_id" to episodeId),
                )
                ReiAnixDiagnosticTimeline.record(
                    name = "CONTINUE_FAILED",
                    severity = "ERROR",
                    source = "ReiAnixLibraryViewModel",
                    result = "native_handoff_exception",
                    requestId = requestId,
                    error = error,
                    context = mapOf("episode_id" to episodeId),
                )
                reportNativePlayerHandoff(
                    requestId = requestId,
                    status = "FAILED",
                    error = error.message?.takeIf(String::isNotBlank)
                        ?: "Não foi possível preparar a reprodução local.",
                )
            } finally {
                if (!nativeIntentStarted) {
                    synchronized(nativePlayerHandoffLock) {
                        if (pendingNativePlayerHandoffRequestId == requestId) {
                            pendingNativePlayerHandoffRequestId = null
                        }
                    }
                }
            }
        }
        return requestId
    }


    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
 
