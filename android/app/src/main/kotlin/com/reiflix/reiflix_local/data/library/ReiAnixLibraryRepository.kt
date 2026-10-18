package com.reiflix.reiflix_local.data.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileObserver
import com.reiflix.reiflix_local.MainActivity
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
                decoded.animes.isEmpty() &&
                previous.animes.isNotEmpty()
            val preserveCatalog = preserveCatalogOnError || preserveCatalogDuringScan
            return decoded.copy(
                // Keep the last known-good catalog visible during a transient
                // scan projection. Scan state still comes from the new snapshot,
                // so reconciliation remains observable without visual deletion.
                status = if (preserveCatalogDuringScan) previous.status else decoded.status,
                animes = if (preserveCatalog) previous.animes else decoded.animes,
                continueWatching = if (preserveCatalog) previous.continueWatching else decoded.continueWatching,
                // Storage authorization/onboarding is independent of catalog retention.
                // A scan may temporarily publish an empty catalog while SAF selection
                // has already transitioned onboarding to READY; never freeze that newer
                // state just because the previous anime list is retained.
                storage = decoded.storage,
                sourceAvailable = if (preserveCatalog) previous.sourceAvailable else decoded.sourceAvailable,
                sourceState = if (preserveCatalog) previous.sourceState else decoded.sourceState,
                scanInProgress = decoded.scanInProgress,
                scanState = decoded.scanState,
                lastScanStatus = decoded.lastScanStatus ?: previous.lastScanStatus,
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

    /** Serializes snapshot/result application so stale callbacks cannot replace newer state. */
    private val stateMutex = Mutex()
    private val refreshInFlight = AtomicBoolean(false)
    /** Keeps duplicate scan requests from being started concurrently. */
    private val refreshRequestGuard = Any()
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

    /**
     * Starts the native scan lifecycle only. NativeLibraryViewModel owns catalog
     * refresh and page queries after the scan finishes; do not bounce the same
     * page request through Python's ComposeLibraryBridge a second time.
     */
    fun refresh() {
        val requestId = UUID.randomUUID().toString()
        synchronized(refreshRequestGuard) {
            if (!refreshInFlight.compareAndSet(false, true)) return
        }
        val commandUri = Uri.parse("reiflix://native").buildUpon()
            .appendQueryParameter("action", "scan_library")
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("protocol_version", "2")
            .appendQueryParameter("created_at", System.currentTimeMillis().toString())
            .appendQueryParameter("created_monotonic_ns", System.nanoTime().toString())
            .build()
        try {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, commandUri)
                    .setClass(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            scope.launch {
                stateMutex.withLock {
                    _state.value = _state.value.copy(
                        lastCommandId = requestId,
                        lastCommandAction = ReiAnixLibraryCommandCodec.Action.REFRESH.value,
                        lastCommandStatus = "QUEUED",
                        lastCommandError = null,
                    )
                }
            }
        } catch (exception: Exception) {
            synchronized(refreshRequestGuard) {
                refreshInFlight.set(false)
            }
            scope.launch {
                stateMutex.withLock {
                    _state.value = _state.value.copy(
                        lastCommandId = requestId,
                        lastCommandAction = ReiAnixLibraryCommandCodec.Action.REFRESH.value,
                        lastCommandStatus = "FAILED",
                        lastCommandError = exception.message ?: "Não foi possível iniciar a varredura nativa.",
                    )
                }
            }
        }
    }

    /**
     * Publishes the terminal result of a native Compose-to-player handoff to the
     * existing UI state flow. This is status reporting, not domain persistence.
     */
    fun reportNativePlayerHandoff(
        requestId: String,
        status: String,
        error: String? = null,
    ) {
        val normalizedRequestId = requestId.trim()
        val normalizedStatus = status.trim().uppercase()
        if (normalizedRequestId.isBlank() || normalizedStatus !in setOf("COMPLETED", "FAILED")) return
        scope.launch {
            stateMutex.withLock {
                _state.value = _state.value.copy(
                    lastCommandId = normalizedRequestId,
                    lastCommandAction = "native_player_handoff",
                    lastCommandStatus = normalizedStatus,
                    lastCommandError = error?.trim()?.takeIf { it.isNotEmpty() }?.take(500),
                )
            }
        }
    }

    /**
     * Publish the committed native catalog to the compatibility projection only.
     * Domain and storage mutations are performed by native Kotlin services/Activity.
     */
    fun publishCatalog() {
        sendCatalogPublication()
    }

    private fun sendCatalogPublication() {
        val requestId = UUID.randomUUID().toString()
        val action = ReiAnixLibraryCommandCodec.Action.PUBLISH_CATALOG
        val command = ReiAnixLibraryCommandCodec.create(
            requestId = requestId,
            action = action,
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
                        lastCommandError = "Não foi possível enviar o comando de republicação do catálogo.",
                    )
                }
            }
        }
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
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { return }
        val commandBridge = runCatching {
            org.json.JSONObject(raw).optString("commandBridge").trim().lowercase()
        }.getOrNull().orEmpty()
        if (commandBridge.isNotBlank() && commandBridge != "library") return

        val result = runCatching {
            ReiAnixLibrarySnapshotCodec.decodeCommandResult(raw)
        }.getOrElse { decodeError ->
            val requestId = file.name
                .removePrefix("command-")
                .removeSuffix(".json")
            stateMutex.withLock {
                val current = _state.value
                if (current.lastCommandId == requestId) {
                    _state.value = current.copy(
                        lastCommandStatus = "ERROR",
                        lastCommandError = decodeError.message ?: "Resultado IPC inválido.",
                    )
                }
            }
            file.delete()
            return
        }

        // Older installed versions may have left a durable page request in the mailbox.
        // Consume its response without reconstructing a second IPC-backed state: current
        // Compose pages are queried directly from canonical SQLite.
        if (result.action == "load_library_page") {
            file.delete()
            return
        }

        stateMutex.withLock {
            _state.value = _state.value.copy(
                lastCommandId = result.requestId,        stateMutex.withLock {
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
            // Only release the scan guard here. The ViewModel observes the
            // terminal native scan event and queries canonical SQLite directly.
            synchronized(refreshRequestGuard) {
                refreshInFlight.set(false)
            }
        }

        file.delete()
    }

    override fun close() {
        snapshotObserver.stopWatching()
        commandResultObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
        synchronized(refreshRequestGuard) {
            refreshInFlight.set(false)
        }
    }
}
