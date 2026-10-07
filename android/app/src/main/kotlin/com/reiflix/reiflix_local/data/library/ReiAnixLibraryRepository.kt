package com.reiflix.reiflix_local.data.library

import android.content.Context
import android.os.FileObserver
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPagedUiState
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
    private var inFlightPage: Pair<Long, Int>? = null

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

    fun refresh() {
        if (!refreshInFlight.compareAndSet(false, true)) return
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

        synchronized(pageRequestGuard) {
            if (!reset && inFlightPage?.first == generation) return
            inFlightPage = generation to requestedPage
        }

        scope.launch {
            stateMutex.withLock {
                val current = _pagedLibraryState.value
                _pagedLibraryState.value = if (reset) {
                    current.copy(
                        status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.LOADING,
                        animes = emptyList(),
                        totalCount = 0,
                        hasMore = false,
                        loadedPage = -1,
                        isLoading = true,
                        generation = generation,
                        error = null,
                    )
                } else if (current.generation == generation) {
                    current.copy(isLoading = true, error = null)
                } else current
            }
        }

        send(
            ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE,
            page = requestedPage,
            pageSize = pageSize.coerceIn(12, 48),
            query = query,
            genre = genre,
            sort = sort,
            favoritesOnly = favoritesOnly,
            watchingOnly = watchingOnly,
            completedOnly = completedOnly,
            generation = generation,
        )
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
                _state.value = mergeSnapshotState(decoded, _state.value)
                val currentPaged = _pagedLibraryState.value
                if (currentPaged.animes.isNotEmpty() && currentPaged.generation > 0L) {
                    val latestById = _state.value.animes.associateBy { it.id }
                    val patched = currentPaged.animes.mapNotNull { existing ->
                        latestById[existing.id]
                    }
                    if (patched != currentPaged.animes) {
                        _pagedLibraryState.value = currentPaged.copy(animes = patched)
                    }
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
        }

        if (action == ReiAnixLibraryCommandCodec.Action.LOAD_LIBRARY_PAGE.value) {
            val page = result.libraryPage
            stateMutex.withLock {
                val current = _pagedLibraryState.value
                if (page != null &&
                    result.status == "COMPLETED" &&
                    page.generation == current.generation
                ) {
                    val seenIds = current.animes.asSequence().map { it.id }.toHashSet()
                    val merged = if (page.page == 0) {
                        page.items
                    } else {
                        buildList(current.animes.size + page.items.size) {
                            addAll(current.animes)
                            page.items.forEach { item ->
                                if (seenIds.add(item.id)) add(item)
                            }
                        }
                    }
                    _pagedLibraryState.value = current.copy(
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
                } else if (result.status in setOf("FAILED", "BLOCKED", "CANCELLED")) {
                    _pagedLibraryState.value = current.copy(
                        isLoading = false,
                        error = result.error ?: "Não foi possível carregar a biblioteca.",
                    )
                }
            }
            synchronized(pageRequestGuard) {
                if (page != null && inFlightPage?.first == page.generation) {
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
        synchronized(pageRequestGuard) { inFlightPage = null }
    }
}
