package com.reiflix.reiflix_local.data.settings

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import org.json.JSONObject
import java.util.UUID
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
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

class ReiAnixSettingsRepository(context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "ReiAnixSettingsRepo"

        /**
         * A projection/IPC error must not erase the last known settings/account
         * state. The error remains observable, while persisted values stay visible
         * until a valid newer snapshot arrives.
         */
        internal fun mergeSnapshotState(
            decoded: ReiAnixSettingsUiState,
            previous: ReiAnixSettingsUiState,
        ): ReiAnixSettingsUiState {
            val preserveKnownState =
                decoded.status == ReiAnixSettingsLoadStatus.ERROR &&
                    previous.status == ReiAnixSettingsLoadStatus.READY
            return if (!preserveKnownState) {
                decoded
            } else {
                previous.copy(
                    status = ReiAnixSettingsLoadStatus.ERROR,
                    revision = decoded.revision,
                    error = decoded.error,
                )
            }
        }
    }
    private val appContext = context.applicationContext
    private val dataDirectory = File(appContext.filesDir, "data")
    private val bridgeDirectory = File(dataDirectory, "reianix-compose")
    private val snapshotFile = File(bridgeDirectory, "settings.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ReiAnixSettingsUiState())
    val state: StateFlow<ReiAnixSettingsUiState> = _state.asStateFlow()
    /** Prevents overlapping FileObserver callbacks from applying snapshots out of order. */
    private val stateMutex = Mutex()

    private val snapshotObserver = object : FileObserver(
        bridgeDirectory.path,
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.CREATE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path == snapshotFile.name) {
                scope.launch { loadSnapshot() }
            }
        }
    }

    init {
        bridgeDirectory.mkdirs()
        snapshotObserver.startWatching()
        scope.launch { loadSnapshot() }
    }

    fun refresh() {
        scope.launch { loadSnapshot() }
    }

    /**
     * Persists a supported setting through the existing Python SettingsStore.
     * Compose does not keep a second preference store or optimistic local value.
     */
    fun setSetting(key: String, value: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) return
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val event = JSONObject()
                .put("type", "compose_settings_set")
                .put("requestId", requestId)
                .put(
                    "payload",
                    JSONObject()
                        .put("key", normalizedKey)
                        .put("value", value),
                )
            val published = runCatching {
                NativeMailbox.write(appContext, event)
            }.getOrElse { false }
            if (!published) {
                Log.e(TAG, "Failed to publish Compose setting update requestId=$requestId key=$normalizedKey")
            }
        }
    }

    /**
     * Dispatch an existing settings action through the same Python bridge used
     * by the legacy Settings UI. This is a command, not a second preference store.
     */
    fun requestAction(action: String) {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf("reset_player")) return
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val event = JSONObject()
                .put("type", "compose_settings_action")
                .put("requestId", requestId)
                .put(
                    "payload",
                    JSONObject()
                        .put("action", normalizedAction)
                        .put("requestId", requestId),
                )
            val published = runCatching {
                NativeMailbox.write(appContext, event)
            }.getOrElse { false }
            if (!published) {
                Log.e(
                    TAG,
                    "Failed to publish Compose settings action requestId=$requestId action=$normalizedAction",
                )
            }
        }
    }

    fun requestAccountAction(action: String) {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf("login", "logout", "switch")) return
        scope.launch {
            val requestId = UUID.randomUUID().toString()
            val event = JSONObject()
                .put("type", "compose_account_action")
                .put("requestId", requestId)
                .put(
                    "payload",
                    JSONObject()
                        .put("action", normalizedAction)
                        .put("requestId", requestId),
                )
            val published = runCatching {
                NativeMailbox.write(appContext, event)
            }.getOrElse { false }
            if (!published) {
                Log.e(
                    TAG,
                    "Failed to publish Compose account action requestId=$requestId action=$normalizedAction",
                )
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
                    status = ReiAnixSettingsLoadStatus.ERROR,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            return
        }

        stateMutex.withLock {
            runCatching {
                ReiAnixSettingsSnapshotCodec.decode(raw, _state.value.revision)
            }.onSuccess { decoded ->
                _state.value = mergeSnapshotState(
                    ReiAnixSettingsUiState.fromSnapshot(decoded),
                    _state.value,
                )
            }.onFailure { error ->
                val message = error.message.orEmpty()
                if (message.startsWith("Stale Compose settings snapshot")) return@onFailure
                _state.value = _state.value.copy(
                    status = ReiAnixSettingsLoadStatus.ERROR,
                    error = message.ifBlank { error::class.java.simpleName },
                )
            }
        }
    }

    override fun close() {
        snapshotObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
    }
}