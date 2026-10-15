package com.reiflix.reiflix_local.data.settings

import android.content.Context
import android.os.FileObserver
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import org.json.JSONObject
import java.util.UUID
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsLoadStatus
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsOperationState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsOperationUiState
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsUiState
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.ConcurrentHashMap
import java.io.File

class ReiAnixSettingsRepository(
    context: Context,
    private val sharedSqliteSettingsRepository: ReiAnixSqliteSettingsRepository? = null,
) : AutoCloseable {
    companion object {
        private const val TAG = "ReiAnixSettingsRepo"

        /**
         * A projection/IPC error must not erase the last known settings/account
         * state. The error remains observable, while persisted values stay visible
         * until a valid newer snapshot arrives.
         */
        internal fun rollbackFailedSetting(
            currentSettings: Map<String, String>,
            key: String,
            failedRequestId: String,
            currentPendingRequestId: String?,
            confirmedValue: String,
        ): Map<String, String> {
            if (currentPendingRequestId != failedRequestId) return currentSettings
            return currentSettings + (key to confirmedValue)
        }

        internal fun mergeSnapshotState(
            decoded: ReiAnixSettingsUiState,
            previous: ReiAnixSettingsUiState,
        ): ReiAnixSettingsUiState {
            val preserveKnownState =
                decoded.status == ReiAnixSettingsLoadStatus.ERROR &&
                    previous.status != ReiAnixSettingsLoadStatus.LOADING
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
    // Reads and writes the canonical LibraryStore.preferences table. The Python bridge is
    // retained only to notify still-Python consumers after native persistence succeeds.
    private val sqliteSettingsRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        sharedSqliteSettingsRepository ?: ReiAnixSqliteSettingsRepository(appContext)
    }
    private val dataDirectory = File(appContext.filesDir, "data")
    private val bridgeDirectory = File(dataDirectory, "reianix-compose")
    private val snapshotFile = File(bridgeDirectory, "settings.json")
    private val commandResultDirectory = File(bridgeDirectory, "command-results")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ReiAnixSettingsUiState())
    val state: StateFlow<ReiAnixSettingsUiState> = _state.asStateFlow()
    private val stateLock = Any()

    private data class PendingSetting(
        val requestId: String,
        val key: String,
        val value: String,
        val confirmedValue: String,
    )

    private val confirmedSettings = linkedMapOf<String, String>()
    private val pendingSettings = linkedMapOf<String, PendingSetting>()

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

    private val commandResultObserver = object : FileObserver(
        commandResultDirectory.path,
        FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.CREATE,
    ) {
        override fun onEvent(event: Int, path: String?) {
            if (path?.startsWith("command-") == true && path.endsWith(".json")) {
                scope.launch { loadCommandResult(path) }
            }
        }
    }

    init {
        bridgeDirectory.mkdirs()
        commandResultDirectory.mkdirs()
        snapshotObserver.startWatching()
        commandResultObserver.startWatching()
        scope.launch {
            loadSnapshot()
            loadExistingCommandResults()
        }
    }

    fun refresh() {
        scope.launch { loadSnapshot() }
    }

    /**
     * Optimistically updates Compose, then sends the mutation through the existing
     * SettingsStore writer. Compose does not keep a second preference store: canonical
     * preference reads come from the existing SQLite table, while command results and the
     * snapshot provide acknowledgement/account/storage state.
     */
    private val settingLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Persist each preference directly to the canonical SQLite table. Compose state
     * changes only after SQLite confirms the write; NativeMailbox is a post-commit
     * notification for legacy consumers, never the persistence mechanism.
     */
    fun setSetting(key: String, value: String) {
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) return
        val normalizedValue = try {
            ReiAnixSettingsContract.normalize(normalizedKey, value)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Rejected invalid setting value for key=" + normalizedKey)
            synchronized(stateLock) {
                val current = _state.value
                val requestId = UUID.randomUUID().toString()
                _state.value = current.copy(
                    operations = upsertOperationLocked(
                        current.operations,
                        ReiAnixSettingsOperationUiState(
                            requestId = requestId,
                            action = "set:" + normalizedKey,
                            key = normalizedKey,
                            state = ReiAnixSettingsOperationState.ERROR,
                            error = "Valor inválido para esta configuração.",
                            timestampMs = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            return
        }
        val requestId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            val current = _state.value
            val confirmedValue = confirmedSettings[normalizedKey]
                ?: current.settings[normalizedKey]
                ?: ReiAnixSettingsContract.defaultFor(normalizedKey)
                ?: normalizedValue
            confirmedSettings.putIfAbsent(normalizedKey, confirmedValue)
            pendingSettings[normalizedKey] = PendingSetting(
                requestId, normalizedKey, normalizedValue, confirmedValue,
            )
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = "set:" + normalizedKey,
                        key = normalizedKey,
                        state = ReiAnixSettingsOperationState.QUEUED,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        Log.i(TAG, "SETTINGS_ACTION_START requestId=" + requestId + " action=set key=" + normalizedKey)
        scope.launch {
            settingLocks.computeIfAbsent(normalizedKey) { Mutex() }.withLock {
                val isLatest = synchronized(stateLock) {
                    pendingSettings[normalizedKey]?.requestId == requestId
                }
                if (!isLatest) {
                    synchronized(stateLock) {
                        val current = _state.value
                        val existing = current.operations[requestId] ?: return@synchronized
                        _state.value = current.copy(
                            operations = upsertOperationLocked(
                                current.operations,
                                existing.copy(
                                    state = ReiAnixSettingsOperationState.CANCELLED,
                                    message = "Substituída por uma alteração mais recente.",
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            ),
                        )
                    }
                    return@withLock
                }

                try {
                    val persistedValue = sqliteSettingsRepository
                        .setPreference(normalizedKey, normalizedValue)
                        .value
                    val shouldNotifyRuntime = synchronized(stateLock) {
                        val current = _state.value
                        val pending = pendingSettings[normalizedKey]
                        confirmedSettings[normalizedKey] = persistedValue
                        if (pending?.requestId != requestId) {
                            false
                        } else {
                            pendingSettings.remove(normalizedKey)
                            _state.value = current.copy(
                                settings = current.settings + (normalizedKey to persistedValue),
                                operations = upsertOperationLocked(
                                    current.operations,
                                    (current.operations[requestId] ?: ReiAnixSettingsOperationUiState(
                                        requestId, "set:" + normalizedKey, normalizedKey,
                                    )).copy(
                                        state = ReiAnixSettingsOperationState.SUCCESS,
                                        message = "Configuração salva.",
                                        error = null,
                                        timestampMs = System.currentTimeMillis(),
                                    ),
                                ),
                            )
                            true
                        }
                    }

                    if (shouldNotifyRuntime) {
                        val published = runCatching {
                            NativeMailbox.write(
                                appContext,
                                JSONObject()
                                    .put("type", "compose_settings_set")
                                    .put("requestId", requestId)
                                    .put(
                                        "payload",
                                        JSONObject()
                                            .put("key", normalizedKey)
                                            .put("value", persistedValue)
                                            .put("requestId", requestId)
                                            .put("nativePersisted", true),
                                    ),
                            )
                        }.getOrElse { false }
                        Log.i(
                            TAG,
                            "SETTINGS_PERSISTED requestId=" + requestId +
                                " key=" + normalizedKey +
                                " runtimeNotificationPublished=" + published,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(stateLock) {
                        val current = _state.value
                        val pending = pendingSettings[normalizedKey]
                        if (pending?.requestId == requestId) {
                            pendingSettings.remove(normalizedKey)
                            _state.value = current.copy(
                                settings = current.settings + (
                                    normalizedKey to (confirmedSettings[normalizedKey] ?: pending.confirmedValue)
                                ),
                                operations = upsertOperationLocked(
                                    current.operations,
                                    (current.operations[requestId] ?: ReiAnixSettingsOperationUiState(
                                        requestId, "set:" + normalizedKey, normalizedKey,
                                    )).copy(
                                        state = ReiAnixSettingsOperationState.CANCELLED,
                                        error = "A gravação da configuração foi cancelada.",
                                        timestampMs = System.currentTimeMillis(),
                                    ),
                                ),
                            )
                        }
                    }
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "SETTINGS_PERSIST_FAILED requestId=" + requestId + " key=" + normalizedKey, error)
                    synchronized(stateLock) {
                        val current = _state.value
                        val pending = pendingSettings[normalizedKey]
                        if (pending?.requestId == requestId) {
                            pendingSettings.remove(normalizedKey)
                            _state.value = current.copy(
                                settings = current.settings + (
                                    normalizedKey to (confirmedSettings[normalizedKey] ?: pending.confirmedValue)
                                ),
                                operations = upsertOperationLocked(
                                    current.operations,
                                    (current.operations[requestId] ?: ReiAnixSettingsOperationUiState(
                                        requestId, "set:" + normalizedKey, normalizedKey,
                                    )).copy(
                                        state = ReiAnixSettingsOperationState.ERROR,
                                        error = "Não foi possível salvar a configuração no SQLite.",
                                        timestampMs = System.currentTimeMillis(),
                                    ),
                                ),
                            )
                        } else {
                            val existing = current.operations[requestId]
                            if (existing != null) {
                                _state.value = current.copy(
                                    operations = upsertOperationLocked(
                                        current.operations,
                                        existing.copy(
                                            state = ReiAnixSettingsOperationState.ERROR,
                                            error = "Não foi possível salvar a configuração no SQLite.",
                                            timestampMs = System.currentTimeMillis(),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Dispatch an existing settings action through the same Python bridge used
     * by the legacy Settings UI. This is a command, not a second preference store.
     */
    fun requestAction(action: String) {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf(
            "reset_player", "reset_all_settings", "clear_anilist_cache", "settings_export",
            "settings_import", "select_saf", "request_media_access", "check_storage_access",
            "open_broad_storage_settings", "backup_create", "backup_restore", "backup_integrity",
            "backup_reconcile", "diagnostic_export",
        )) return
        dispatchCommand(normalizedAction, normalizedAction) { requestId ->
            JSONObject()
                .put("type", "compose_settings_action")
                .put("requestId", requestId)
                .put("payload", JSONObject().put("action", normalizedAction).put("requestId", requestId))
        }
    }
    fun requestAccountAction(action: String) {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf("login", "logout", "switch")) return
        dispatchCommand(normalizedAction, "account:" + normalizedAction) { requestId ->
            JSONObject()
                .put("type", "compose_account_action")
                .put("requestId", requestId)
                .put("payload", JSONObject().put("action", normalizedAction).put("requestId", requestId))
        }
    }

    private fun dispatchCommand(
        action: String,
        operationKey: String,
        builder: (String) -> JSONObject,
    ) {
        val requestId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = operationKey,
                        state = ReiAnixSettingsOperationState.QUEUED,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        Log.i(TAG, "SETTINGS_ACTION_START requestId=" + requestId + " action=" + action)
        scope.launch {
            val writeStartedNs = System.nanoTime()
            val published = runCatching { NativeMailbox.write(appContext, builder(requestId)) }.getOrElse { false }
            val durationMs = (System.nanoTime() - writeStartedNs) / 1_000_000.0
            if (published) {
                Log.i(TAG, "SETTINGS_COMMAND_WRITTEN requestId=" + requestId + " action=" + action + " success=true writeDurationMs=" + durationMs)
            } else {
                synchronized(stateLock) {
                    val current = _state.value
                    _state.value = current.copy(
                        operations = upsertOperationLocked(
                            current.operations,
                            (current.operations[requestId] ?: ReiAnixSettingsOperationUiState(requestId, operationKey)).copy(
                                state = ReiAnixSettingsOperationState.ERROR,
                                error = "Não foi possível enviar o comando ao backend.",
                                timestampMs = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
                Log.e(TAG, "SETTINGS_COMMAND_WRITTEN requestId=" + requestId + " action=" + action + " success=false writeDurationMs=" + durationMs)
            }
        }
    }

    private fun upsertOperationLocked(
        current: Map<String, ReiAnixSettingsOperationUiState>,
        operation: ReiAnixSettingsOperationUiState,
    ): Map<String, ReiAnixSettingsOperationUiState> {
        val result = LinkedHashMap(current)
        result[operation.requestId] = operation
        while (result.size > 48) result.remove(result.keys.first())
        return result
    }

    private suspend fun loadSnapshot() {
        // The JSON file is a projection for account/storage/action state, not a
        // second settings database. Prefer the canonical preferences table when it
        // is available, and retain snapshot compatibility if the database cannot be read.
        val persistedPreferences = try {
            sqliteSettingsRepository.allPreferences()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Canonical SQLite settings are unavailable; using the last valid settings projection.", error)
            null
        }
        val raw = runCatching {
            if (!snapshotFile.isFile) null else snapshotFile.readText(Charsets.UTF_8)
        }.getOrElse { error ->
            synchronized(stateLock) {
                _state.value = _state.value.copy(
                    status = ReiAnixSettingsLoadStatus.ERROR,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
            return
        }
        if (raw == null) {
            synchronized(stateLock) {
                val current = _state.value
                if (persistedPreferences != null) {
                    confirmedSettings.clear()
                    confirmedSettings.putAll(persistedPreferences)
                    _state.value = current.copy(
                        status = ReiAnixSettingsLoadStatus.READY,
                        settings = persistedPreferences,
                        error = null,
                    )
                } else {
                    _state.value = current.copy(
                        status = ReiAnixSettingsLoadStatus.ERROR,
                        error = "Não foi possível carregar as preferências do SQLite canônico.",
                    )
                }
            }
            return
        }
        synchronized(stateLock) {
            runCatching { ReiAnixSettingsSnapshotCodec.decode(raw, _state.value.revision) }
                .onSuccess { decoded ->
                    val snapshotState = ReiAnixSettingsUiState.fromSnapshot(decoded)
                    val base = if (snapshotState.status == ReiAnixSettingsLoadStatus.READY && persistedPreferences != null) {
                        snapshotState.copy(settings = snapshotState.settings + persistedPreferences)
                    } else {
                        snapshotState
                    }
                    if (base.status == ReiAnixSettingsLoadStatus.READY) {
                        confirmedSettings.clear()
                        confirmedSettings.putAll(base.settings)
                        val effective = base.settings.toMutableMap()
                        val confirmedPending = mutableListOf<String>()
                        for ((key, pending) in pendingSettings) {
                            if (base.settings[key] == pending.value) {
                                confirmedSettings[key] = pending.value
                                confirmedPending += key
                            } else {
                                effective[key] = pending.value
                            }
                        }
                        confirmedPending.forEach { pendingSettings.remove(it) }
                        val current = _state.value
                        _state.value = base.copy(settings = effective, operations = current.operations)
                        Log.i(TAG, "SETTINGS_SNAPSHOT_CONSUMED revision=" + decoded.revision + " pending=" + pendingSettings.size)
                    } else {
                        _state.value = mergeSnapshotState(base, _state.value)
                    }
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

    private suspend fun loadExistingCommandResults() {
        val files = commandResultDirectory.listFiles { file ->
            file.isFile && file.name.startsWith("command-") && file.name.endsWith(".json")
        } ?: return
        for (file in files.sortedBy { it.lastModified() }) {
            loadCommandResult(file.name)
        }
    }

    private suspend fun loadCommandResult(fileName: String) {
        val file = File(commandResultDirectory, fileName)
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { return }
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val requestId = root.optString("requestId").trim()
        if (requestId.isBlank()) return
        val commandBridge = root.optString("commandBridge").trim().lowercase()
        if (commandBridge.isNotBlank() && commandBridge != "settings") return
        if (commandBridge.isBlank()) {
            val tracked = synchronized(stateLock) {
                _state.value.operations.containsKey(requestId)
            }
            if (!tracked) return
        }
        val action = root.optString("action").trim()
        val status = root.optString("status").trim().uppercase()
        val operationState = when (root.optString("operationState").trim().uppercase()) {
            "RUNNING" -> ReiAnixSettingsOperationState.RUNNING
            "SUCCESS", "COMPLETED" -> ReiAnixSettingsOperationState.SUCCESS
            "ERROR", "FAILED" -> ReiAnixSettingsOperationState.ERROR
            "CANCELLED" -> ReiAnixSettingsOperationState.CANCELLED
            else -> when (status) {
                "ACK", "QUEUED" -> ReiAnixSettingsOperationState.QUEUED
                "SUCCESS", "COMPLETED" -> ReiAnixSettingsOperationState.SUCCESS
                "ERROR", "FAILED" -> ReiAnixSettingsOperationState.ERROR
                "CANCELLED" -> ReiAnixSettingsOperationState.CANCELLED
                else -> ReiAnixSettingsOperationState.RUNNING
            }
        }
        val message = root.optString("message").trim().takeIf { it.isNotBlank() && it != "null" }
        val error = root.optString("error").trim().takeIf { it.isNotBlank() && it != "null" }
        val key = root.optString("key").trim().takeIf { it.isNotBlank() && it != "null" }
        val value = if (root.has("value") && !root.isNull("value")) root.optString("value") else null
        synchronized(stateLock) {
            val current = _state.value
            val existing = current.operations[requestId]
            if (existing == null) {
                return@synchronized
            }
            var settings = current.settings
            if (operationState == ReiAnixSettingsOperationState.SUCCESS && key != null) {
                val pending = pendingSettings[key]
                // Even an older successful write becomes the last confirmed value.
                // Keep a newer optimistic value visible, but roll it back to this
                // confirmed value if the newer request later fails.
                if (value != null) confirmedSettings[key] = value
                if (pending != null && pending.requestId == requestId) {
                    val confirmed = value ?: pending.value
                    confirmedSettings[key] = confirmed
                    pendingSettings.remove(key)
                    settings = settings + (key to confirmed)
                }
            } else if (operationState == ReiAnixSettingsOperationState.ERROR && key != null) {
                val pending = pendingSettings[key]
                if (pending != null && pending.requestId == requestId) {
                    pendingSettings.remove(key)
                    val rollback = confirmedSettings[key] ?: pending.confirmedValue
                    settings = settings + (key to rollback)
                }
            }
            _state.value = current.copy(
                settings = settings,
                operations = upsertOperationLocked(
                    current.operations,
                    existing.copy(
                        action = action.ifBlank { existing.action },
                        key = key ?: existing.key,
                        state = operationState,
                        message = message,
                        error = error,
                        timestampMs = root.optLong("timestamp", System.currentTimeMillis()),
                    ),
                ),
            )
        }
        file.delete()
    }
    override fun close() {
        snapshotObserver.stopWatching()
        commandResultObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
    }
}