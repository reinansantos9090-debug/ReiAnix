package com.reiflix.reiflix_local.data.library

import android.content.Context
import android.os.FileObserver
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPagedUiState
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilterEngine
import com.reiflix.reiflix_local.ui.library.ReiAnixLibraryFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin boundary over the real Python/SQLite library.
 *
 * The JSON files are a derived IPC projection, not a second source of truth.
 * Commands stay deliberately small; the existing Python LibraryService,
 * LibraryStore, ScanCoordinator and AndroidBridge keep ownership of domain and
 * player behavior.
 */
class ReiAnixLibraryRepository(context: Context) : AutoCloseable {
    companion object {
        /**
         * Snapshot reconciliation belongs to the repository boundary: a new
         * library projection must replace domain state while preserving the
         * command status that belongs to the in-flight IPC request.
         *
         * Kept pure so the repository's state-transition contract can be
         * exercised by JVM unit tests without touching Android/SQLite.
         */
        internal fun mergeSnapshotState(
            decoded: ReiAnixLibraryUiState,
            previous: ReiAnixLibraryUiState,
        ): ReiAnixLibraryUiState {
            // A transient projection/IPC failure is not permission to erase the
            // last known canonical catalog from the UI. Keep the snapshot that was
            // last known-good while surfacing the new ERROR state so Compose can
            // show the failure without making the library visually disappear.
            val preserveCatalogOnError = decoded.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR &&
                decoded.animes.isEmpty() &&
                previous.animes.isNotEmpty()
            val preserveCatalogDuringScan = decoded.scanInProgress &&
                decoded.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY &&
                decoded.animes.isEmpty() &&
                decoded.sourceState !in setOf("UNAVAILABLE", "ERROR") &&
                previous.animes.isNotEmpty()
            val preserveCatalog = preserveCatalogOnError || preserveCatalogDuringScan
            return decoded.copy(
                // Keep the last known-good catalog visible during a transient
                // scan projection. Scan state still comes from the new snapshot,
                // so reconciliation remains observable without visual deletion.
                status = if (preserveCatalogDuringScan) previous.status else decoded.status,
                animes = if (preserveCatalog) previous.animes else decoded.animes,
                continueWatching = if (preserveCatalog) previous.continueWatching else decoded.continueWatching,
                storage = if (preserveCatalog) previous.storage else decoded.storage,
                sourceAvailable = if (preserveCatalog) previous.sourceAvailable else decoded.sourceAvailable,
                sourceState = if (preserveCatalog) previous.sourceState else decoded.sourceState,
                scanInProgress = decoded.scanInProgress,
                scanState = decoded.scanState,
                lastCommandId = previous.lastCommandId,
                lastCommandAction = previous.lastCommandAction,
                lastCommandStatus = previous.lastCommandStatus,
                lastCommandError = previous.lastCommandError,
            )
        }

        internal fun reconcilePagedState(
            current: ReiAnixLibraryPagedUiState,
            canonicalAnimes: List<com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel>,
            recountTotal: Boolean = false,
            includeNewCandidates: Boolean = false,
        ): ReiAnixLibraryPagedUiState {
            if (current.generation <= 0L || current.animes.isEmpty()) return current

            val filters = ReiAnixLibraryFilters(
                query = current.query,
                selectedGenreKey = current.genreKey,
                favoritesOnly = current.favoritesOnly,
                watchingOnly = current.watchingOnly,
                completedOnly = current.completedOnly,
                sort = current.sort,
            )
            val loadedIds = current.animes.asSequence().map { it.id }.toHashSet()

            // Replace only already-loaded entries. Items removed from canonical
            // state disappear; items outside the window are not materialized.
            val retained = buildList(current.animes.size) {
                for (anime in canonicalAnimes) {
                    if (anime.id in loadedIds &&
                        ReiAnixLibraryFilterEngine.matches(anime, filters)
                    ) {
                        add(anime)
                    }
                }
            }
            val retainedIds = retained.asSequence().map { it.id }.toHashSet()

            // Discover a bounded set of new candidates from the snapshot that is
            // already in memory. Never materialize another full catalog/page set.
            val candidates = if (includeNewCandidates) {
                val candidateLimit = maxOf(36, current.animes.size * 2)
                buildList(candidateLimit) {
                    for (anime in canonicalAnimes) {
                        if (size >= candidateLimit) break
                        if (anime.id !in loadedIds &&
                            ReiAnixLibraryFilterEngine.matches(anime, filters)
                        ) {
                            add(anime)
                        }
                    }
                }
            } else {
                emptyList()
            }

            // Keep the same bounded window size. New compatible items enter in
            // the selected order while the oldest tail item is displaced.
            val merged = ReiAnixLibraryFilterEngine
                .sortForLibrary(retained + candidates, current.sort)
                .distinctBy { it.id }
                .take(current.animes.size)

            val removedLoaded = current.animes.size - retained.size
            val admittedNew = merged.count { it.id !in retainedIds }
            val nextTotal = if (recountTotal) {
                canonicalAnimes.count { ReiAnixLibraryFilterEngine.matches(it, filters) }
            } else {
                (current.totalCount - removedLoaded + admittedNew).coerceAtLeast(merged.size)
            }

            return current.copy(
                animes = merged,
                totalCount = nextTotal,
                loadedPage = current.loadedPage,
                hasMore = current.hasMore || nextTotal > merged.size,
            )
        }

        internal fun resetPagedState(
            current: ReiAnixLibraryPagedUiState,
            generation: Long,
            query: String,
            genre: String,
            sort: String,
            favoritesOnly: Boolean,
            watchingOnly: Boolean,
            completedOnly: Boolean,
        ): ReiAnixLibraryPagedUiState =
            current.copy(
                status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING,
                animes = emptyList(),
                totalCount = 0,
                hasMore = false,
                loadedPage = -1,
                isLoading = true,
                pageSize = 36,
                requestId = null,
                generation = generation,
                query = query.trim(),
                genreKey = genre.takeIf { it != "Todos" }?.trim(),
                favoritesOnly = favoritesOnly,
                watchingOnly = watchingOnly,
                completedOnly = completedOnly,
                sort = sort.trim().ifEmpty { "Mais recentes" },
                error = null,
            )

        internal fun applyLibraryPage(
            current: ReiAnixLibraryPagedUiState,
            page: ReiAnixLibrarySnapshotCodec.LibraryPageResult,
        ): ReiAnixLibraryPagedUiState? {
            if (page.generation != current.generation) return null
            val currentDistinct = current.animes.distinctBy { it.id }
            val seenIds = currentDistinct.asSequence().map { it.id }.toHashSet()
            val merged = if (page.page == 0) {
                page.items.distinctBy { it.id }
            } else {
                buildList(currentDistinct.size + page.items.size) {
                    addAll(currentDistinct)
                    page.items.forEach { item ->
                        if (seenIds.add(item.id)) add(item)
                    }
                }
            }
            return current.copy(
                status = if (page.total > 0) {
                    com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.READY
                } else {
                    com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
                },
                animes = merged,
                totalCount = page.total,
                hasMore = page.hasMore,
                loadedPage = maxOf(current.loadedPage, page.page),
                isLoading = false,
                error = null,
            )
        }
    }
    private val appContext = context.applicationContext
    private val dataDirectory = File(appContext.filesDir, "data")
    private val bridgeDirectory = File(dataDirectory, "reianix-compose")
    private val snapshotFile = File(bridgeDirectory, "library.json")
    private val commandResultDirectory = File(bridgeDirectory, "command-results")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ReiAnixLibraryUiState())
    val state: StateFlow<ReiAnixLibraryUiState> = _state.asStateFlow()

    private val _pagedLibraryState = MutableStateFlow(ReiAnixLibraryPagedUiState())
    val pagedLibraryState: StateFlow<ReiAnixLibraryPagedUiState> = _pagedLibraryState.asStateFlow()

    /** Serializes snapshot/result application so stale callbacks cannot replace newer state. */
    private val stateMutex = Mutex()
    private val pageGeneration = AtomicLong(0L)
    private val refreshInFlight = AtomicBoolean(false)
    private val pageRequestGuard = Any()
    private data class InFlightPage(
        val generation: Long,
        val page: Int,
        val requestId: String,
    )
    private var inFlightPage: InFlightPage? = null
    private data class RefreshPageRequest(
        val query: String,
        val genre: String,
        val sort: String,
        val favoritesOnly: Boolean,
        val watchingOnly: Boolean,
        val completedOnly: Boolean,
    )
    private var pendingRefreshPage: RefreshPageRequest? = null

    private val snapshotObserver = object : FileObserver(bridgeDirectory.path, FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.CREATE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == snapshotFile.name) {
                scope.launch { loadSnapshot() }
            }
        }
    }

    private val commandResultObserver = object : FileObserver(commandResultDirectory.path, FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.CREATE) {
        override fun onEvent(event: Int, path: String?) {
            if (path?.startsWith("command-") == true && path.endsWith(".json")) {
                scope.launch { loadCommandResult(File(commandResultDirectory, path)) }
            }
        }
    }

    init {
        bridgeDirectory.mkdirs()
        commandResultDirectory.mkdirs()
        snapshotObserver.startWatching()
        commandResultObserver.startWatching()
        scope.launch { loadSnapshot() }
    }

    fun refresh(
        query: String = "",
        genre: String = "Todos",
        sort: String = "Mais recentes",
        favoritesOnly: Boolean = false,
        watchingOnly: Boolean = false,
        completedOnly: Boolean = false,
    ) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        pendingRefreshPage = RefreshPageRequest(
            query = query,
            genre = genre,
            sort = sort,
            favoritesOnly = favoritesOnly,
            watchingOnly = watchingOnly,
            completedOnly = completedOnly,
        )
        send(ReiAnixLibraryCommandCodec.Action.REFRESH)
    }

    fun loadLibraryPage(
        page: Int = 0,
        pageSize: Int = 36,
        query: String = "",
        genre: String = "Todos",
        sort: String = "Mais recentes",
        favoritesOnly: Boolean = false,
        watchingOnly: Boolean = false,
        completedOnly: Boolean = false,
        reset: Boolean = page == 0,
    ) {
        val requestedPage = page.coerceAtLeast(0)
        val generation = if (reset) pageGeneration.incrementAndGet()
        else pageGeneration.get().coerceAtLeast(1L)

        val normalizedPageSize = pageSize.coerceIn(12, 48)
        val requestId = UUID.randomUUID().toString()
        synchronized(pageRequestGuard) {
            val existing = inFlightPage
            if (!reset && existing != null &&
                existing.generation == generation && existing.page == requestedPage
            ) return
            inFlightPage = InFlightPage(generation, requestedPage, requestId)
        }

        scope.launch {
            val command = ReiAnixLibraryCommandCodec.create(
                requestId = requestId,
                action = ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE,
                page = requestedPage,
                pageSize = normalizedPageSize,
                query = query,
                genre = genre,
                sort = sort,
                favoritesOnly = favoritesOnly,
                watchingOnly = watchingOnly,
                completedOnly = completedOnly,
                generation = generation,
            )
            stateMutex.withLock {
                val current = _pagedLibraryState.value
                _pagedLibraryState.value = if (reset) {
                    resetPagedState(
                        current = current,
                        generation = generation,
                        query = query,
                        genre = genre,
                        sort = sort,
                        favoritesOnly = favoritesOnly,
                        watchingOnly = watchingOnly,
                        completedOnly = completedOnly,
                    ).copy(
                        pageSize = normalizedPageSize,
                        requestId = requestId,
                    )
                } else if (current.generation == generation) {
                    current.copy(
                        isLoading = true,
                        pageSize = normalizedPageSize,
                        requestId = requestId,
                        error = null,
                        query = query.trim(),
                        genreKey = genre.takeIf { it != "Todos" }?.trim(),
                        favoritesOnly = favoritesOnly,
                        watchingOnly = watchingOnly,
                        completedOnly = completedOnly,
                        sort = sort.trim().ifEmpty { "Mais recentes" },
                    )
                } else current
            }

            val written = runCatching { NativeMailbox.write(appContext, command) }
                .getOrElse { false }
            if (!written) {
                stateMutex.withLock {
                    val current = _pagedLibraryState.value
                    if (current.requestId == requestId && current.generation == generation) {
                        _state.value = _state.value.copy(
                            lastCommandId = requestId,
                            lastCommandAction = command.action.value,
                            lastCommandStatus = "FAILED",
                            lastCommandError = "Não foi possível enviar o comando ao serviço local.",
                        )
                        _pagedLibraryState.value = current.copy(
                            isLoading = false,
                            requestId = null,
                            error = "Não foi possível carregar a biblioteca.",
                        )
                    }
                }
                synchronized(pageRequestGuard) {
                    if (inFlightPage?.requestId == requestId) inFlightPage = null
                }
            }
        }
    }

    fun selectSafTree() {
        send(ReiAnixLibraryCommandCodec.Action.SELECT_SAF)
    }

    fun removeSafTree(reference: String) {
        val normalized = reference.trim()
        if (normalized.isBlank()) return
        send(
            ReiAnixLibraryCommandCodec.Action.REMOVE_SAF,
            source = normalized,
        )
    }

    fun toggleFavorite(animeId: Long) {
        send(ReiAnixLibraryCommandCodec.Action.TOGGLE_FAVORITE, animeId = animeId)
    }

    fun setEpisodeWatched(episodeId: Long, watched: Boolean) {
        send(
            ReiAnixLibraryCommandCodec.Action.SET_WATCHED,
            episodeId = episodeId,
            watched = watched,
        )
    }

    fun openEpisode(episodeId: Long): String =
        send(ReiAnixLibraryCommandCodec.Action.OPEN_MEDIA, episodeId = episodeId)

    private fun send(
        action: ReiAnixLibraryCommandCodec.Action,
        animeId: Long? = null,
        episodeId: Long? = null,
        watched: Boolean? = null,
        source: String? = null,
        page: Int? = null,
        pageSize: Int? = null,
        query: String? = null,
        genre: String? = null,
        sort: String? = null,
        favoritesOnly: Boolean? = null,
        watchingOnly: Boolean? = null,
        completedOnly: Boolean? = null,
        generation: Long? = null,
    ): String {
        val requestId = UUID.randomUUID().toString()
        val command = ReiAnixLibraryCommandCodec.create(
            requestId = requestId,
            action = action,
            animeId = animeId,
            episodeId = episodeId,
            watched = watched,
            source = source,
            page = page,
            pageSize = pageSize,
            query = query,
            genre = genre,
            sort = sort,
            favoritesOnly = favoritesOnly,
            watchingOnly = watchingOnly,
            completedOnly = completedOnly,
            generation = generation,
        )
        scope.launch {
            val written = runCatching { NativeMailbox.write(appContext, command) }
                .getOrElse { false }
            if (!written) {
                stateMutex.withLock {
                    _state.value = _state.value.copy(
                        lastCommandId = requestId,
                        lastCommandAction = action.value,
                        lastCommandStatus = "FAILED",
                        lastCommandError = "Não foi possível enviar o comando ao serviço local.",
                    )
                    if (action == ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE) {
                        _pagedLibraryState.value = _pagedLibraryState.value.copy(
                            isLoading = false,
                            error = "Não foi possível carregar a biblioteca.",
                        )
                    }
                }
                if (action == ReiAnixLibraryCommandCodec.Action.REFRESH) {
                    refreshInFlight.set(false)
                    pendingRefreshPage = null
                }
                if (action == ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE) {
                    synchronized(pageRequestGuard) { inFlightPage = null }
                }
            }
        }
        return requestId
    }

    private suspend fun loadSnapshot() {
        val raw = runCatching {
            if (!snapshotFile.isFile) return
            snapshotFile.readText(Charsets.UTF_8)
        }.getOrElse { error ->
            stateMutex.withLock {
                _state.value = _state.value.copy(
                    status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            return
        }

        stateMutex.withLock {
            runCatching {
                ReiAnixLibrarySnapshotCodec.decode(raw, _state.value.revision)
            }.onSuccess { decoded ->
                val previousState = _state.value
                _state.value = mergeSnapshotState(decoded, previousState)
                val currentPaged = _pagedLibraryState.value
                if (currentPaged.generation > 0L) {
                    val structuralChange = decoded.animes.size != previousState.animes.size
                    val reconciled = reconcilePagedState(
                        current = currentPaged,
                        canonicalAnimes = _state.value.animes,
                        recountTotal = structuralChange,
                        includeNewCandidates = structuralChange,
                    )
                    _pagedLibraryState.value = reconciled.copy(
                        status = when {
                            reconciled.animes.isEmpty() &&
                                _state.value.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY ->
                                com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.EMPTY
                            else -> reconciled.status
                        },
                        error = null,
                    )
                }
            }.onFailure { error ->
                val message = error.message.orEmpty()
                if (message.startsWith("Stale Compose library snapshot")) return@onFailure
                _state.value = _state.value.copy(
                    status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR,
                    error = message.ifBlank { error::class.java.simpleName },
                )
            }
        }
    }

    private suspend fun loadCommandResult(file: File) {
        val result = runCatching {
            ReiAnixLibrarySnapshotCodec.decodeCommandResult(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: run {
            file.delete()
            return
        }
        stateMutex.withLock {
            _state.value = _state.value.copy(
                lastCommandId = result.requestId,
                lastCommandAction = result.action,
                lastCommandStatus = result.status,
                lastCommandError = result.error,
            )
        }

        val action = result.action.orEmpty()
        if (action == ReiAnixLibraryCommandCodec.Action.REFRESH.value &&
            result.status !in setOf("QUEUED", "RUNNING")
        ) {
            refreshInFlight.set(false)
            val request = pendingRefreshPage
            pendingRefreshPage = null
            if (result.status == "COMPLETED" && request != null) {
                loadLibraryPage(
                    page = 0,
                    pageSize = 36,
                    query = request.query,
                    genre = request.genre,
                    sort = request.sort,
                    favoritesOnly = request.favoritesOnly,
                    watchingOnly = request.watchingOnly,
                    completedOnly = request.completedOnly,
                    reset = true,
                )
            }
        }

        if (action == ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE.value) {
            val page = result.libraryPage
            stateMutex.withLock {
                val current = _pagedLibraryState.value
                if (page != null && result.status == "COMPLETED") {
                    applyLibraryPage(current, page)?.let { applied ->
                        _pagedLibraryState.value = applied
                    }
                } else if (result.status in setOf("FAILED", "BLOCKED", "CANCELLED")) {
                    _pagedLibraryState.value = current.copy(
                        isLoading = false,
                        error = result.error ?: "Não foi possível carregar a biblioteca.",
                    )
                }
            }
            synchronized(pageRequestGuard) {
                if (action == ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE.value) {
                    inFlightPage = null
                }
            }
        } else if (result.status in setOf("COMPLETED", "QUEUED")) {
            loadSnapshot()
        }
        file.delete()
    }

    override fun close() {
        snapshotObserver.stopWatching()
        commandResultObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
        refreshInFlight.set(false)
        pendingRefreshPage = null
        synchronized(pageRequestGuard) { inFlightPage = null }
    }
}
