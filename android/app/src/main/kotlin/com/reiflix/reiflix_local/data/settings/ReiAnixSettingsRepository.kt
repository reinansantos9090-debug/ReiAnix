package com.reiflix.reiflix_local.data.settings

import android.content.Context
import android.os.FileObserver
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ReiAnixSettingsRepository(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val dataDirectory = File(appContext.filesDir, "data")
    private val bridgeDirectory = File(dataDirectory, "reianix-compose")
    private val snapshotFile = File(bridgeDirectory, "settings.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ReiAnixSettingsUiState())
    val state: StateFlow<ReiAnixSettingsUiState> = _state.asStateFlow()

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

    private suspend fun loadSnapshot() {
        val raw = runCatching {
            if (!snapshotFile.isFile) return
            snapshotFile.readText(Charsets.UTF_8)
        }.getOrElse { error ->
            _state.value = _state.value.copy(
                status = ReiAnixSettingsLoadStatus.ERROR,
                error = error.message ?: error::class.java.simpleName,
            )
            return
        }

        runCatching {
            ReiAnixSettingsSnapshotCodec.decode(raw, _state.value.revision)
        }.onSuccess { decoded ->
            _state.value = ReiAnixSettingsUiState.fromSnapshot(decoded)
        }.onFailure { error ->
            val message = error.message.orEmpty()
            if (message.startsWith("Stale Compose settings snapshot")) return@onFailure
            _state.value = _state.value.copy(
                status = ReiAnixSettingsLoadStatus.ERROR,
                error = message.ifBlank { error::class.java.simpleName },
            )
        }
    }

    override fun close() {
        snapshotObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
    }
}
