package com.reiflix.reiflix_local.data.library

import android.content.Context
import android.os.FileObserver
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.ui.model.ReiAnixLibraryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
            val preserveCatalog = decoded.status == com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR &&
                decoded.animes.isEmpty() &&
                previous.animes.isNotEmpty()
            return decoded.copy(
                animes = if (preserveCatalog) previous.animes else decoded.animes,
                continueWatching = if (preserveCatalog) previous.continueWatching else decoded.continueWatching,
                storage = if (preserveCatalog) previous.storage else decoded.storage,
                sourceAvailable = if (preserveCatalog) previous.sourceAvailable else decoded.sourceAvailable,
                sourceState = if (preserveCatalog) previous.sourceState else decoded.sourceState,
                scanInProgress = if (preserveCatalog) previous.scanInProgress else decoded.scanInProgress,
                scanState = if (preserveCatalog) previous.scanState else decoded.scanState,
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
        send(ReiAnixLibraryCommandCodec.Action.REFRESH)
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
    ): String {
        val requestId = UUID.randomUUID().toString()
        val command = ReiAnixLibraryCommandCodec.create(
            requestId = requestId,
            action = action,
            animeId = animeId,
            episodeId = episodeId,
            watched = watched,
        )
        scope.launch {
            val written = runCatching { NativeMailbox.write(appContext, command) }
                .getOrElse { false }
            if (!written) {
                _state.value = _state.value.copy(
                    lastCommandId = requestId,
                    lastCommandAction = action.value,
                    lastCommandStatus = "FAILED",
                    lastCommandError = "Não foi possível enviar o comando ao serviço local.",
                )
            }
        }
        return requestId
    }

    private suspend fun loadSnapshot() {
        val raw = runCatching {
            if (!snapshotFile.isFile) return
            snapshotFile.readText(Charsets.UTF_8)
        }.getOrElse { error ->
            _state.value = _state.value.copy(
                status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR,
                error = error.message ?: error::class.java.simpleName,
            )
            return
        }

        runCatching {
            val currentRevision = _state.value.revision
            ReiAnixLibrarySnapshotCodec.decode(raw, currentRevision)
        }.onSuccess { decoded ->
            _state.value = mergeSnapshotState(decoded, _state.value)
        }.onFailure { error ->
            val message = error.message.orEmpty()
            if (message.startsWith("Stale Compose library snapshot")) return@onFailure
            _state.value = _state.value.copy(
                status = com.reiflix.reiflix_local.ui.model.ReiAnixLibraryLoadStatus.ERROR,
                error = message.ifBlank { error::class.java.simpleName },
            )
        }
    }

    private suspend fun loadCommandResult(file: File) {
        val result = runCatching {
            ReiAnixLibrarySnapshotCodec.decodeCommandResult(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: run {
            file.delete()
            return
        }
        _state.value = _state.value.copy(
            lastCommandId = result.requestId,
            lastCommandAction = result.action,
            lastCommandStatus = result.status,
            lastCommandError = result.error,
        )
        if (result.status in setOf("COMPLETED", "QUEUED")) {
            loadSnapshot()
        }
        file.delete()
    }

    override fun close() {
        snapshotObserver.stopWatching()
        commandResultObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
    }
}
