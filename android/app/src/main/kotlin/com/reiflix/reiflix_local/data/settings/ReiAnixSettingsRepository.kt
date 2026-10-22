package com.reiflix.reiflix_local.data.settings

import android.content.Context
import android.content.Intent
import com.reiflix.reiflix_local.MainActivity
import android.os.FileObserver
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.artwork.ReiAnixArtworkService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSettingsRepository
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteAccountRepository
import com.reiflix.reiflix_local.ui.model.ReiAnixSettingsAccountUiState
import android.net.Uri
import com.reiflix.reiflix_local.R
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
    private val sharedArtworkService: ReiAnixArtworkService? = null,
    private val sharedAccountRepository: ReiAnixSqliteAccountRepository? = null,
) : AutoCloseable {
    companion object {
        private const val TAG = "ReiAnixSettingsRepo"
        private val NATIVE_STORAGE_SETTINGS_ACTIONS = setOf(
            "select_saf",
            "request_media_access",
            "check_storage_access",
            "open_broad_storage_settings",
        )
        private val NATIVE_BACKUP_DIAGNOSTIC_ACTIONS = setOf(
            "backup_create",
            "backup_restore",
            "backup_recovery_restore",
            "backup_recovery_snapshot",
            "backup_integrity",
            "backup_reconcile",
            "diagnostic_export",
        )

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
    // The application dependency graph supplies its shared artwork worker in production.
    // The fallback exists for isolated repository construction and targets the same canonical DB.
    private val nativeArtworkService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        sharedArtworkService ?: ReiAnixArtworkService(appContext, ReiAnixSqliteDatabase(appContext))
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

    /** Serializes mutations by key without blocking unrelated preferences. */
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
        if (normalizedAction == "reset_player" || normalizedAction == "reset_all_settings") {
            resetSettingsNatively(normalizedAction)
            return
        }
        if (normalizedAction == "clear_anilist_cache") {
            clearArtworkCacheNatively()
            return
        }
        if (normalizedAction == "settings_export" || normalizedAction == "settings_import") {
            launchNativeSettingsFileAction(normalizedAction)
            return
        }
        if (normalizedAction in NATIVE_STORAGE_SETTINGS_ACTIONS) {
            launchNativeStorageSettingsAction(normalizedAction)
            return
        }
        if (normalizedAction in NATIVE_BACKUP_DIAGNOSTIC_ACTIONS) {
            launchNativeSettingsFileAction(normalizedAction)
            return
        }
        // Unknown actions are intentionally ignored here. The legacy Flet
        // Settings view owns its independent Python action handlers; Compose
        // actions routed above must never fall back to a second owner.
    }
    private fun launchNativeStorageSettingsAction(action: String) {
        val requestId = UUID.randomUUID().toString()
        val nativeAction = if (action == "select_saf") "select_tree" else action
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = action,
                        state = ReiAnixSettingsOperationState.RUNNING,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        scope.launch {
            try {
                val intent = MainActivity.createNativeStorageActionIntent(
                    context = appContext,
                    action = nativeAction,
                    requestId = requestId,
                ).apply {
                    data = data?.buildUpon()
                        ?.appendQueryParameter("settings_action", action)
                        ?.build()
                }
                appContext.startActivity(intent)
                Log.i(
                    TAG,
                    "SETTINGS_STORAGE_ACTION_LAUNCHED requestId=" + requestId +
                        " action=" + action + " nativeAction=" + nativeAction,
                )
            } catch (cancelled: CancellationException) {
                updateSettingsFileOperation(
                    requestId,
                    ReiAnixSettingsOperationState.CANCELLED,
                    error = "A operação de armazenamento foi cancelada.",
                )
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "SETTINGS_STORAGE_ACTION_LAUNCH_FAILED requestId=" + requestId + " action=" + action, error)
                updateSettingsFileOperation(
                    requestId,
                    ReiAnixSettingsOperationState.ERROR,
                    error = "Não foi possível iniciar a operação nativa de armazenamento.",
                )
            }
        }
    }

    private fun launchNativeSettingsFileAction(action: String) {
        val requestId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = action,
                        state = ReiAnixSettingsOperationState.QUEUED,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        scope.launch {
            try {
                val intent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ReiAnixSettingsFileService.EXTRA_ACTION, action)
                    putExtra(ReiAnixSettingsFileService.EXTRA_REQUEST_ID, requestId)
                }
                appContext.startActivity(intent)
                Log.i(TAG, "SETTINGS_FILE_ACTION_LAUNCHED requestId=" + requestId + " action=" + action)
            } catch (cancelled: CancellationException) {
                updateSettingsFileOperation(
                    requestId,
                    ReiAnixSettingsOperationState.CANCELLED,
                    error = "A operação de arquivo foi cancelada.",
                )
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "SETTINGS_FILE_ACTION_LAUNCH_FAILED requestId=" + requestId + " action=" + action, error)
                updateSettingsFileOperation(
                    requestId,
                    ReiAnixSettingsOperationState.ERROR,
                    error = "Não foi possível abrir a operação nativa de arquivo.",
                )
            }
        }
    }

    private fun updateSettingsFileOperation(
        requestId: String,
        state: ReiAnixSettingsOperationState,
        message: String? = null,
        error: String? = null,
    ) {
        synchronized(stateLock) {
            val current = _state.value
            val existing = current.operations[requestId] ?: return
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    existing.copy(
                        state = state,
                        message = message,
                        error = error,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    private fun clearArtworkCacheNatively() {
        val requestId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = "clear_anilist_cache",
                        state = ReiAnixSettingsOperationState.QUEUED,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        Log.i(TAG, "SETTINGS_ACTION_START requestId=" + requestId + " action=clear_anilist_cache owner=kotlin")
        scope.launch {
            try {
                val removed = nativeArtworkService.clearManagedCache()
                synchronized(stateLock) {
                    val current = _state.value
                    val existing = current.operations[requestId]
                    if (existing != null) {
                        _state.value = current.copy(
                            operations = upsertOperationLocked(
                                current.operations,
                                existing.copy(
                                    state = ReiAnixSettingsOperationState.SUCCESS,
                                    message = "Cache de capas limpo.",
                                    error = null,
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            ),
                        )
                    }
                }
                // Python remains a legacy Flet consumer. This post-commit notification
                // refreshes its projection but marks the clear itself as already native.
                val notified = runCatching {
                    NativeMailbox.write(
                        appContext,
                        JSONObject()
                            .put("type", "compose_settings_action")
                            .put("requestId", requestId)
                            .put(
                                "payload",
                                JSONObject()
                                    .put("action", "clear_anilist_cache")
                                    .put("requestId", requestId)
                                    .put("nativeHandled", true)
                                    .put("removed", removed),
                            ),
                    )
                }.getOrElse { false }
                Log.i(
                    TAG,
                    "SETTINGS_CACHE_CLEAR_COMMITTED requestId=" + requestId +
                        " removed=" + removed +
                        " legacyRuntimeNotificationPublished=" + notified,
                )
            } catch (cancelled: CancellationException) {
                updateCacheClearOperation(
                    requestId,
                    ReiAnixSettingsOperationState.CANCELLED,
                    "A limpeza do cache foi cancelada.",
                    "A limpeza do cache foi cancelada.",
                )
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "SETTINGS_CACHE_CLEAR_FAILED requestId=" + requestId, error)
                updateCacheClearOperation(
                    requestId,
                    ReiAnixSettingsOperationState.ERROR,
                    error = "Não foi possível limpar o cache de capas.",
                )
            }
        }
    }

    private fun updateCacheClearOperation(
        requestId: String,
        state: ReiAnixSettingsOperationState,
        message: String? = null,
        error: String? = null,
    ) {
        synchronized(stateLock) {
            val current = _state.value
            val existing = current.operations[requestId] ?: return
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    existing.copy(
                        state = state,
                        message = message,
                        error = error,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
    }

    fun requestAccountAction(action: String, requestId: String? = null) {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in setOf("login", "logout", "switch")) return
        val correlationId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (sharedAccountRepository == null) {
            // Preserve the caller's request identity across the compatibility
            // mailbox; a generated replacement would leave the originating
            // Compose/Python operation waiting for a terminal result forever.
            dispatchCommand(
                normalizedAction,
                "account:" + normalizedAction,
                correlationId,
            ) { dispatchedRequestId ->
                JSONObject()
                    .put("type", "compose_account_action")
                    .put("requestId", dispatchedRequestId)
                    .put(
                        "payload",
                        JSONObject()
                            .put("action", normalizedAction)
                            .put("requestId", dispatchedRequestId),
                    )
            }
            return
        }
        launchNativeAccountAction(normalizedAction, correlationId)
    }

    private fun launchNativeAccountAction(action: String, requestedRequestId: String? = null) {
        val requestId = requestedRequestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        val busyState = if (action == "login") "connecting" else "disconnecting"
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                account = current.account.copy(
                    integrationAvailable = isNativeGoogleConfigured(),
                    state = busyState,
                ),
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = action,
                        state = ReiAnixSettingsOperationState.RUNNING,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        scope.launch {
            try {
                val nativeAction = when (action) {
                    "login" -> "google_sign_in"
                    "logout" -> "google_sign_out"
                    else -> "google_account_switch"
                }
                val target = Uri.parse("reiflix://native").buildUpon()
                    .appendQueryParameter("action", nativeAction)
                    .appendQueryParameter("request_id", requestId)
                    .appendQueryParameter("native_account_owner", "true")
                    .appendQueryParameter("settings_action", action)
                    .build()
                val intent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    data = target
                }
                appContext.startActivity(intent)
                Log.i(TAG, "SETTINGS_ACCOUNT_ACTION_LAUNCHED requestId=" + requestId + " action=" + action + " owner=kotlin")
            } catch (cancelled: CancellationException) {
                restoreNativeAccountProjection()
                updateSettingsFileOperation(
                    requestId,
                    ReiAnixSettingsOperationState.CANCELLED,
                    error = "A operação da conta foi cancelada.",
                )
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "SETTINGS_ACCOUNT_ACTION_LAUNCH_FAILED requestId=" + requestId + " action=" + action, error)
                restoreNativeAccountProjection()
                updateSettingsFileOperation(
                    requestId,
                    ReiAnixSettingsOperationState.ERROR,
                    error = "Não foi possível iniciar a operação nativa da conta.",
                )
            }
        }
    }

    private fun isNativeGoogleConfigured(): Boolean = runCatching {
        val clientId = appContext.getString(R.string.reiflix_google_web_client_id).trim()
        clientId.endsWith(".apps.googleusercontent.com") &&
            clientId.substringBefore(".apps.googleusercontent.com").isNotBlank()
    }.getOrDefault(false)

    private suspend fun readNativeAccountUiState(): ReiAnixSettingsAccountUiState? {
        val repository = sharedAccountRepository ?: return null
        return try {
            val profile = repository.loadProfile()
            val operation = synchronized(stateLock) {
                _state.value.operations.values
                    .filter {
                        it.action in setOf("login", "logout", "switch") &&
                            it.state in setOf(ReiAnixSettingsOperationState.QUEUED, ReiAnixSettingsOperationState.RUNNING)
                    }
                    .maxByOrNull { it.timestampMs }
            }
            val operationState = when (operation?.action) {
                "login" -> "connecting"
                "logout", "switch" -> "disconnecting"
                else -> if (profile == null) "disconnected" else "connected"
            }
            ReiAnixSettingsAccountUiState(
                integrationAvailable = isNativeGoogleConfigured(),
                connected = profile != null,
                name = profile?.name.orEmpty(),
                email = profile?.email.orEmpty(),
                picture = profile?.picture.orEmpty(),
                state = operationState,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Canonical SQLite account profile could not be read.", error)
            null
        }
    }

    private suspend fun restoreNativeAccountProjection() {
        val account = readNativeAccountUiState() ?: return
        synchronized(stateLock) {
            _state.value = _state.value.copy(account = account)
        }
    }

    private fun resetSettingsNatively(action: String) {
        val keys = ReiAnixSettingsContract.definitions.keys
            .filter { action == "reset_all_settings" || it.startsWith("player.") }
            .sorted()
        val defaults = keys.associateWith { key ->
            ReiAnixSettingsContract.defaultFor(key)
                ?: throw IllegalStateException("Missing default for known setting: $key")
        }
        val requestId = UUID.randomUUID().toString()
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = requestId,
                        action = action,
                        state = ReiAnixSettingsOperationState.QUEUED,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }

        scope.launch {
            val locks = keys.map { settingLocks.computeIfAbsent(it) { Mutex() } }
            try {
                withSettingLocks(locks) {
                    val persisted = sqliteSettingsRepository.setPreferencesAtomically(defaults)
                        .mapValues { (_, record) -> record.value }
                    synchronized(stateLock) {
                        confirmedSettings.putAll(persisted)
                        val current = _state.value
                        _state.value = current.copy(
                            settings = current.settings + persisted,
                            operations = upsertOperationLocked(
                                current.operations,
                                (current.operations[requestId] ?: ReiAnixSettingsOperationUiState(
                                    requestId, action,
                                )).copy(
                                    state = ReiAnixSettingsOperationState.SUCCESS,
                                    message = "Configurações restauradas aos valores padrão.",
                                    error = null,
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            ),
                        )
                    }
                    val notification = JSONObject().put("settings", JSONObject().apply {
                        persisted.forEach { (key, value) -> put(key, value) }
                    })
                    val published = runCatching {
                        NativeMailbox.write(
                            appContext,
                            JSONObject()
                                .put("type", "compose_settings_bulk_applied")
                                .put("requestId", requestId)
                                .put("payload", notification.put("requestId", requestId)),
                        )
                    }.getOrElse { false }
                    Log.i(
                        TAG,
                        "SETTINGS_RESET_PERSISTED requestId=" + requestId +
                            " action=" + action +
                            " count=" + persisted.size +
                            " runtimeNotificationPublished=" + published,
                    )
                }
            } catch (cancelled: CancellationException) {
                synchronized(stateLock) {
                    val current = _state.value
                    val existing = current.operations[requestId]
                    if (existing != null) {
                        _state.value = current.copy(
                            operations = upsertOperationLocked(
                                current.operations,
                                existing.copy(
                                    state = ReiAnixSettingsOperationState.CANCELLED,
                                    error = "A restauração das configurações foi cancelada.",
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            ),
                        )
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "SETTINGS_RESET_FAILED requestId=" + requestId + " action=" + action, error)
                synchronized(stateLock) {
                    val current = _state.value
                    val existing = current.operations[requestId]
                    if (existing != null) {
                        _state.value = current.copy(
                            operations = upsertOperationLocked(
                                current.operations,
                                existing.copy(
                                    state = ReiAnixSettingsOperationState.ERROR,
                                    error = "Não foi possível restaurar as configurações no SQLite.",
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun <T> withSettingLocks(
        locks: List<Mutex>,
        index: Int = 0,
        block: suspend () -> T,
    ): T {
        if (index >= locks.size) return block()
        val lock = locks[index]
        lock.lock()
        return try {
            withSettingLocks(locks, index + 1, block)
        } finally {
            lock.unlock()
        }
    }

    private fun dispatchCommand(
        action: String,
        operationKey: String,
        requestId: String? = null,
        builder: (String) -> JSONObject,
    ) {
        val resolvedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        synchronized(stateLock) {
            val current = _state.value
            _state.value = current.copy(
                operations = upsertOperationLocked(
                    current.operations,
                    ReiAnixSettingsOperationUiState(
                        requestId = resolvedRequestId,
                        action = operationKey,
                        state = ReiAnixSettingsOperationState.QUEUED,
                        timestampMs = System.currentTimeMillis(),
                    ),
                ),
            )
        }
        Log.i(TAG, "SETTINGS_ACTION_START requestId=" + resolvedRequestId + " action=" + action)
        scope.launch {
            val writeStartedNs = System.nanoTime()
            val published = runCatching { NativeMailbox.write(appContext, builder(resolvedRequestId)) }.getOrElse { false }
            val durationMs = (System.nanoTime() - writeStartedNs) / 1_000_000.0
            if (published) {
                Log.i(TAG, "SETTINGS_COMMAND_WRITTEN requestId=" + resolvedRequestId + " action=" + action + " success=true writeDurationMs=" + durationMs)
            } else {
                synchronized(stateLock) {
                    val current = _state.value
                    _state.value = current.copy(
                        operations = upsertOperationLocked(
                            current.operations,
                            (current.operations[resolvedRequestId] ?: ReiAnixSettingsOperationUiState(resolvedRequestId, operationKey)).copy(
                                state = ReiAnixSettingsOperationState.ERROR,
                                error = "Não foi possível enviar o comando ao backend.",
                                timestampMs = System.currentTimeMillis(),
                            ),
                        ),
                    )
                }
                Log.e(TAG, "SETTINGS_COMMAND_WRITTEN requestId=" + resolvedRequestId + " action=" + action + " success=false writeDurationMs=" + durationMs)
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
        val canonicalAccountState = readNativeAccountUiState()
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
            if (persistedPreferences != null) {
                publishCanonicalPreferencesAfterBridgeFailure(
                    persistedPreferences,
                    canonicalAccountState,
                )
                Log.w(
                    TAG,
                    "Optional settings projection could not be read; canonical SQLite preferences remain available.",
                    error,
                )
            } else {
                synchronized(stateLock) {
                    _state.value = _state.value.copy(
                        status = ReiAnixSettingsLoadStatus.ERROR,
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            }
            return
        }
        if (raw == null) {
            if (persistedPreferences != null) {
                publishCanonicalPreferencesAfterBridgeFailure(
                    persistedPreferences,
                    canonicalAccountState,
                )
            } else {
                synchronized(stateLock) {
                    _state.value = _state.value.copy(
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
                    // SQLite preferences remain authoritative even when the account/storage
                    // projection is stale or reports an error.
                    val base = snapshotState.copy(
                        // Native SQLite is authoritative for preferences. An unavailable
                        // legacy projection must not make valid native settings unavailable.
                        status = if (persistedPreferences != null) {
                            ReiAnixSettingsLoadStatus.READY
                        } else {
                            snapshotState.status
                        },
                        categories = if (persistedPreferences != null && snapshotState.categories.isEmpty()) {
                            com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsCategoryUiModel.defaultCategories()
                        } else {
                            snapshotState.categories
                        },
                        settings = if (persistedPreferences != null) {
                            snapshotState.settings + persistedPreferences
                        } else {
                            snapshotState.settings
                        },
                        account = canonicalAccountState ?: snapshotState.account,
                        error = if (persistedPreferences != null) null else snapshotState.error,
                    )
                    if (base.status == ReiAnixSettingsLoadStatus.READY) {
                        // The SQLite snapshot is authoritative. Do not let a pending
                        // write leak into Compose before the writer confirms its commit,
                        // and do not remove its request token from a projection callback.
                        confirmedSettings.clear()
                        confirmedSettings.putAll(base.settings)
                        val current = _state.value
                        _state.value = base.copy(settings = base.settings, operations = current.operations)
                        Log.i(TAG, "SETTINGS_SNAPSHOT_CONSUMED revision=" + decoded.revision + " pending=" + pendingSettings.size)
                    } else {
                        _state.value = mergeSnapshotState(base, _state.value)
                    }
                }.onFailure { error ->
                    val message = error.message.orEmpty()
                    if (message.startsWith("Stale Compose settings snapshot")) {
                        if (persistedPreferences != null) {
                            publishCanonicalPreferencesAfterBridgeFailure(
                                persistedPreferences,
                                canonicalAccountState,
                            )
                        }
                        return@onFailure
                    }
                    if (persistedPreferences != null) {
                        publishCanonicalPreferencesAfterBridgeFailure(
                            persistedPreferences,
                            canonicalAccountState,
                        )
                        Log.w(
                            TAG,
                            "Optional settings projection is invalid; canonical SQLite preferences remain available.",
                            error,
                        )
                    } else {
                        _state.value = _state.value.copy(
                            status = ReiAnixSettingsLoadStatus.ERROR,
                            account = canonicalAccountState ?: _state.value.account,
                            error = message.ifBlank { error::class.java.simpleName },
                        )
                    }
                }
        }
    }

    /**
     * Updates the observed preference state from the canonical SQLite store without
     * depending on Python's settings.json projection. Keep the projection's other
     * observed fields and pending operation results, because they still include
     * legacy-owned account/storage state and command acknowledgements.
     */
    private fun publishCanonicalPreferencesAfterBridgeFailure(
        persistedPreferences: Map<String, String>,
        canonicalAccountState: ReiAnixSettingsAccountUiState?,
    ) {
        synchronized(stateLock) {
            val current = _state.value
            confirmedSettings.clear()
            confirmedSettings.putAll(persistedPreferences)
            _state.value = current.copy(
                status = ReiAnixSettingsLoadStatus.READY,
                account = canonicalAccountState ?: current.account,
                categories = current.categories.ifEmpty {
                    com.reiflix.reiflix_local.ui.settings.ReiAnixSettingsCategoryUiModel.defaultCategories()
                },
                settings = persistedPreferences,
                error = null,
            )
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
        if (
            action in setOf("login", "logout", "switch") &&
            operationState in setOf(
                ReiAnixSettingsOperationState.SUCCESS,
                ReiAnixSettingsOperationState.ERROR,
                ReiAnixSettingsOperationState.CANCELLED,
            )
        ) {
            restoreNativeAccountProjection()
        }
    }
    override fun close() {
        snapshotObserver.stopWatching()
        commandResultObserver.stopWatching()
        scope.coroutineContext[Job]?.cancel()
    }
}