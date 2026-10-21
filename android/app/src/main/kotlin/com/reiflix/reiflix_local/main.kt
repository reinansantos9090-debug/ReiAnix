package com.reiflix.reiflix_local

import androidx.activity.ComponentActivity
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.backup.ReiAnixDiagnosticsService
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsFileService
import java.util.UUID
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsContract
import java.io.File
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeLibraryHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import java.util.PriorityQueue
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.scanner.NativeScanController
import android.os.Environment
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.ScanProgressSnapshot
import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Native Android entry-point orchestration shared by the launcher Activity.
 *
 * ReiAnixApplication remains the single owner of the process dependency graph.
 * MainActivity keeps the explicit, idempotent bootstrap request and its routing
 * contract; this object observes that existing bootstrap and owns the policy
 * deciding when normal native operations may run.
 */
internal object MainEntryPoint {
    private const val TAG = "ReiAnixEntryPoint"

    private data class BootstrapObservation(
        val phase: ReiAnixBootstrapPhase,
        val stage: ReiAnixBootstrapStage,
        val persistenceStatus: ReiAnixBootstrapPersistenceStatus,
        val recoveryRequired: Boolean,
        val warningsCount: Int,
        val normalOperationsReady: Boolean,
    )

    fun onActivityCreated(activity: ComponentActivity) {
        // Resolve only the Application-owned graph; never instantiate a second graph.
        val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
        // This is the single Kotlin entrypoint for process bootstrap from an Activity.
        // ReiAnixApplication may already have started it; start() is idempotent.
        dependencies.bootstrap.start()
        PerformanceDiagnostics.attach(activity)
        PerformanceDiagnostics.sampleMemory(activity, "main_on_create")
        observeBootstrapForActivity(activity, dependencies)

        Log.i(TAG, "NATIVE_ACTIVITY_ENTRYPOINT_READY activity=${activity::class.java.simpleName}")
    }



    /** Normalized Activity lifecycle notifications replace the legacy Flet lifecycle callback. */
    fun onActivityLifecycleEvent(eventOrState: Any?) {
        NativeLifecycleCoordinator.onEvent(eventOrState)
    }

    /** Route selection is one native policy, made only after database validation. */
    internal fun initialRoute(recoveryRequired: Boolean): String =
        if (recoveryRequired) ReiAnixRoutes.RECOVERY else ReiAnixRoutes.HOME

    /**
     * Native replacement for main.py's composition-root startup block.
     *
     * The process-owned Application already created the service graph. This
     * entry point waits for canonical SQLite validation before it constructs a
     * Compose host or exposes the library/settings ViewModels to a route. The
     * caller receives a host only after the recovery decision is definitive.
     */
    fun launchNativeRuntime(
        activity: MainActivity,
        libraryViewModel: ReiAnixLibraryViewModel,
        settingsViewModel: ReiAnixSettingsViewModel,
        onReady: (ReiAnixComposeLibraryHost, Boolean) -> Unit,
    ) {
        val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
        activity.lifecycleScope.launch {
            val recoveryRequired = dependencies.bootstrap.validateCanonicalDatabase()
            if (activity.isFinishing || activity.isDestroyed) return@launch

            val host = ReiAnixComposeLibraryHost(
                activity = activity,
                libraryViewModel = libraryViewModel,
                settingsViewModel = settingsViewModel,
            )
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_RUNTIME_COMPOSITION_READY",
                severity = if (recoveryRequired) "WARN" else "INFO",
                source = "MainEntryPoint",
                result = if (recoveryRequired) "RECOVERY_REQUIRED" else "HOME_READY",
            )
            onReady(host, recoveryRequired)
        }
    }

    /**
     * Single policy for the Activity's native-command/lifecycle gates.
     *
     * This gate intentionally depends on the completed bootstrap stage and the
     * canonical persistence decision, not on [ReiAnixBootstrapPhase] alone:
     * DEGRADED may mean that the app is usable with non-fatal warnings. Recovery,
     * a pending stage, or unchecked persistence must always keep native work blocked.
     *
     * Activity routing/validation is a separate required gate and must be complete
     * before process readiness can release native commands.
     */
    internal fun allowsNativeOperations(
        activityDecisionComplete: Boolean,
        bootstrapState: ReiAnixBootstrapState,
    ): Boolean = activityDecisionComplete && bootstrapStateAllowsNativeOperations(bootstrapState)

    private fun bootstrapStateAllowsNativeOperations(state: ReiAnixBootstrapState): Boolean =
        !state.recoveryRequired &&
            state.stage == ReiAnixBootstrapStage.READY &&
            (state.persistenceStatus == ReiAnixBootstrapPersistenceStatus.HEALTHY ||
                state.persistenceStatus == ReiAnixBootstrapPersistenceStatus.NOT_CREATED)

    /**
     * Observe the existing process state for this Activity's lifetime. The
     * lifecycleScope cancels collection on destruction; distinct snapshots avoid
     * registering callbacks repeatedly or recording every timestamp-only update.
     */
    private fun observeBootstrapForActivity(
        activity: ComponentActivity,
        dependencies: ReiAnixAppDependencies,
    ) {
        activity.lifecycleScope.launch {
            dependencies.bootstrap.state
                .map { state ->
                    BootstrapObservation(
                        phase = state.phase,
                        stage = state.stage,
                        persistenceStatus = state.persistenceStatus,
                        recoveryRequired = state.recoveryRequired,
                        warningsCount = state.warnings.size,
                        normalOperationsReady = bootstrapStateAllowsNativeOperations(state),
                    )
                }
                .distinctUntilChanged()
                .collect { observation ->
                    val result = when {
                        observation.recoveryRequired -> "RECOVERY_REQUIRED"
                        observation.normalOperationsReady && observation.phase == ReiAnixBootstrapPhase.READY -> "READY"
                        observation.normalOperationsReady -> "READY_WITH_WARNINGS"
                        else -> observation.phase.name
                    }
                    ReiAnixDiagnosticTimeline.record(
                        name = "APP_NATIVE_ENTRYPOINT_BOOTSTRAP_STATE",
                        severity = if (observation.recoveryRequired) "WARN" else "INFO",
                        source = "MainEntryPoint",
                        result = result,
                        context = mapOf(
                            "phase" to observation.phase.name,
                            "stage" to observation.stage.name,
                            "persistence_status" to observation.persistenceStatus.name,
                            "recovery_required" to observation.recoveryRequired,
                            "normal_operations_ready" to observation.normalOperationsReady,
                            "warnings_count" to observation.warningsCount,
                        ),
                    )
                    if (observation.recoveryRequired) {
                        Log.w(TAG, "Native startup remains gated: canonical database recovery is required.")
                    } else {
                        Log.i(TAG, "Native bootstrap observed stage=${observation.stage} result=$result")
                    }
                }
        }
    }
}



internal data class NativeLifecycleSnapshot(
    val state: String = "unknown",
    val resumed: Boolean = false,
    val generation: Long = 0L,
    val changedAtMillis: Long = 0L,
)

/** App-lifecycle state formerly normalized inside the Flet main(page) callback. */
internal object NativeLifecycleCoordinator {
    private val lock = Any()
    private val mutableState = MutableStateFlow(NativeLifecycleSnapshot())
    val state: StateFlow<NativeLifecycleSnapshot> = mutableState.asStateFlow()

    fun onEvent(eventOrState: Any?) {
        val normalized = normalizeAppLifecycleState(eventOrState)
        val nextState = when (normalized) {
            "create", "created" -> "created"
            "start", "started", "restart" -> "started"
            "resume", "show" -> "resumed"
            "pause", "hide" -> "paused"
            "stop", "stopped" -> "stopped"
            "destroy", "destroyed" -> "destroyed"
            else -> return
        }
        val updated = synchronized(lock) {
            val previous = mutableState.value
            if (previous.state == nextState) return
            NativeLifecycleSnapshot(
                state = nextState,
                resumed = nextState == "resumed",
                generation = previous.generation + 1L,
                changedAtMillis = System.currentTimeMillis(),
            ).also { mutableState.value = it }
        }
        ReiAnixDiagnosticTimeline.record(
            name = "APP_NATIVE_LIFECYCLE_STATE_CHANGED",
            severity = if (updated.state == "destroyed") "DEBUG" else "INFO",
            source = "MainEntryPoint",
            result = updated.state,
            context = mapOf(
                "resumed" to updated.resumed,
                "generation" to updated.generation,
                "changed_at_ms" to updated.changedAtMillis,
            ),
        )
    }
}

/**
 * Typed Kotlin port of the legacy main.py Home-refresh state machine.
 *
 * This coordinator owns only refresh lifecycle/diagnostics. It deliberately
 * does not scan storage, write SQLite, or decide whether a partial scan can
 * reconcile missing files; those operations remain owned by the existing
 * native scanner and canonical SQLite services.
 */
internal enum class HomeRefreshPhase {
    IDLE,
    REQUESTED,
    RUNNING,
    CATALOG_UPDATING,
    UI_COMMIT,
    SUCCESS,
    ERROR,
    CANCELLED,
}

internal data class HomeRefreshSnapshot(
    val active: Boolean = false,
    val phase: HomeRefreshPhase = HomeRefreshPhase.IDLE,
    val refreshId: String? = null,
    val requestId: String? = null,
    val startedAtMillis: Long? = null,
    val scanStarted: Boolean = false,
    val scanStartedAtMillis: Long? = null,
    val scanTerminal: Boolean = false,
    val catalogUpdated: Boolean = false,
    val source: String? = null,
    val terminalReason: String? = null,
)

internal object HomeRefreshCoordinator {
    private val lock = Any()
    private val mutableState = MutableStateFlow(HomeRefreshSnapshot())
    val state: StateFlow<HomeRefreshSnapshot> = mutableState.asStateFlow()

    private val allowedTransitions = mapOf(
        HomeRefreshPhase.REQUESTED to setOf(
            HomeRefreshPhase.RUNNING, HomeRefreshPhase.ERROR, HomeRefreshPhase.CANCELLED,
        ),
        HomeRefreshPhase.RUNNING to setOf(
            HomeRefreshPhase.CATALOG_UPDATING, HomeRefreshPhase.ERROR, HomeRefreshPhase.CANCELLED,
        ),
        HomeRefreshPhase.CATALOG_UPDATING to setOf(
            HomeRefreshPhase.UI_COMMIT, HomeRefreshPhase.ERROR, HomeRefreshPhase.CANCELLED,
        ),
        HomeRefreshPhase.UI_COMMIT to setOf(
            HomeRefreshPhase.SUCCESS, HomeRefreshPhase.ERROR, HomeRefreshPhase.CANCELLED,
        ),
    )

    /** Starts exactly one refresh generation; terminal generations may be replaced. */
    fun begin(requestId: String, refreshId: String = requestId, source: String): Boolean {
        val request = requestId.trim()
        val refresh = refreshId.trim()
        val normalizedSource = source.trim().ifBlank { "native" }
        if (request.isBlank() || refresh.isBlank()) return false
        synchronized(lock) {
            val current = mutableState.value
            if (current.active) return current.requestId == request
            val now = System.currentTimeMillis()
            mutableState.value = HomeRefreshSnapshot(
                active = true,
                phase = HomeRefreshPhase.REQUESTED,
                refreshId = refresh,
                requestId = request,
                startedAtMillis = now,
                source = normalizedSource,
            )
            recordTransition(null, HomeRefreshPhase.REQUESTED, request, refresh, normalizedSource, "begin")
            return true
        }
    }

    fun markScanStarted(requestId: String): Boolean = synchronized(lock) {
        val current = currentFor(requestId) ?: return false
        if (current.scanStarted || current.phase != HomeRefreshPhase.REQUESTED) return false
        val now = System.currentTimeMillis()
        transition(current.copy(scanStarted = true, scanStartedAtMillis = now), HomeRefreshPhase.RUNNING, "scan_started")
    }

    /** A terminal scanner callback does not itself certify a complete scan. */
    fun markScanTerminal(requestId: String): Boolean = synchronized(lock) {
        val current = currentFor(requestId) ?: return false
        if (!current.scanStarted || current.scanTerminal || current.phase != HomeRefreshPhase.RUNNING) return false
        transition(current.copy(scanTerminal = true), HomeRefreshPhase.CATALOG_UPDATING, "scan_terminal")
    }

    fun markCatalogUpdated(requestId: String): Boolean = synchronized(lock) {
        val current = currentFor(requestId) ?: return false
        if (!current.scanTerminal || current.catalogUpdated || current.phase != HomeRefreshPhase.CATALOG_UPDATING) return false
        transition(current.copy(catalogUpdated = true), HomeRefreshPhase.UI_COMMIT, "catalog_updated")
    }

    fun complete(requestId: String): Boolean = synchronized(lock) {
        val current = currentFor(requestId) ?: return false
        if (!current.catalogUpdated || current.phase != HomeRefreshPhase.UI_COMMIT) return false
        transition(current.copy(active = false, terminalReason = null), HomeRefreshPhase.SUCCESS, "success")
    }

    fun fail(requestId: String, reason: String): Boolean =
        terminate(requestId, HomeRefreshPhase.ERROR, reason)

    fun cancel(requestId: String, reason: String = "cancelled"): Boolean =
        terminate(requestId, HomeRefreshPhase.CANCELLED, reason)

    private fun terminate(requestId: String, phase: HomeRefreshPhase, reason: String): Boolean =
        synchronized(lock) {
            val current = currentFor(requestId) ?: return false
            if (phase !in setOf(HomeRefreshPhase.ERROR, HomeRefreshPhase.CANCELLED)) return false
            if (!current.active) return false
            transition(current.copy(active = false, terminalReason = reason.trim().take(120).ifBlank { phase.name }), phase, reason)
        }

    private fun currentFor(requestId: String): HomeRefreshSnapshot? {
        val current = mutableState.value
        if (!current.active || current.requestId != requestId.trim()) return null
        return current
    }

    private fun transition(
        next: HomeRefreshSnapshot,
        phase: HomeRefreshPhase,
        reason: String,
    ): Boolean {
        val previous = mutableState.value
        if (phase !in allowedTransitions[previous.phase].orEmpty()) return false
        val updated = next.copy(phase = phase)
        mutableState.value = updated
        recordTransition(previous.phase, phase, updated.requestId, updated.refreshId, updated.source, reason)
        return true
    }

    private fun recordTransition(
        previous: HomeRefreshPhase?,
        next: HomeRefreshPhase,
        requestId: String?,
        refreshId: String?,
        source: String?,
        reason: String,
    ) {
        if (previous == next) return
        ReiAnixDiagnosticTimeline.record(
            name = "HOME_REFRESH_PHASE_CHANGED",
            severity = if (next == HomeRefreshPhase.ERROR || next == HomeRefreshPhase.CANCELLED) "WARN" else "INFO",
            source = source,
            result = next.name,
            requestId = requestId,
            refreshId = refreshId,
            context = mapOf(
                "previous_phase" to (previous?.name ?: "NONE"),
                "phase" to next.name,
                "transition_reason" to reason.take(80),
            ),
        )
    }
}


/**
 * Kotlin equivalent of main.py's normalize_app_lifecycle_state helper.
 *
 * Accepts plain values, enum-like objects and event-like objects that expose
 * state/data, value or name properties. Reflection is intentionally read-only;
 * no Android/Flet event is retained by this helper.
 */
internal fun normalizeAppLifecycleState(eventOrState: Any?): String {
    data class PropertyResult(val exists: Boolean, val value: Any?)

    fun property(target: Any?, name: String): PropertyResult {
        if (target == null) return PropertyResult(false, null)
        if (target is Map<*, *>) {
            return if (target.containsKey(name)) {
                PropertyResult(true, target[name])
            } else {
                PropertyResult(false, null)
            }
        }
        val cap = name.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.ROOT) else character.toString()
        }
        val method = target.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf(name, "get$cap", "is$cap")
        }
        if (method != null) {
            return try {
                // Test fixtures and application event wrappers may be nested/private
                // classes even when their bean getters are public.
                method.isAccessible = true
                PropertyResult(true, method.invoke(target))
            } catch (_: ReflectiveOperationException) {
                PropertyResult(false, null)
            } catch (_: SecurityException) {
                PropertyResult(false, null)
            }
        }
        // Only public bean-style properties are read. Inspecting private JDK fields
        // (for example String.value) is both unnecessary and blocked on modern Android/JVMs.
        return PropertyResult(false, null)
    }

    val stateProperty = property(eventOrState, "state")
    val raw = if (stateProperty.exists) {
        stateProperty.value
    } else {
        val dataProperty = property(eventOrState, "data")
        if (dataProperty.exists) dataProperty.value else eventOrState
    }
    val valueProperty = property(raw, "value")
    val normalizedRaw = if (valueProperty.exists && valueProperty.value is String) {
        valueProperty.value
    } else {
        val nameProperty = property(raw, "name")
        if (nameProperty.exists && nameProperty.value is String) nameProperty.value else raw
    }
    val text = when (normalizedRaw) {
        null, false -> ""
        is Number -> if (normalizedRaw.toDouble() == 0.0) "" else normalizedRaw.toString()
        is String -> normalizedRaw
        is Collection<*> -> if (normalizedRaw.isEmpty()) "" else normalizedRaw.toString()
        is Map<*, *> -> if (normalizedRaw.isEmpty()) "" else normalizedRaw.toString()
        else -> normalizedRaw.toString()
    }.trim().lowercase(Locale.ROOT)
    return text.substringAfterLast('.')
}


/**
 * Kotlin orchestration port for the legacy main.py composition root.
 *
 * The Android implementation already owns rendering and persistence in the
 * Compose host, ViewModels, Activity, repositories, scanners, and player. This
 * class preserves the old entrypoint's coordination contract while delegating
 * each operation to those canonical Kotlin owners. It intentionally does not
 * create a second database, scanner, player, navigation stack, or command queue.
 */
internal data class NativeMainRuntimeSnapshot(
    val initialized: Boolean = false,
    val recoveryRequired: Boolean = false,
    val route: String = ReiAnixRoutes.HOME,
    val accountState: String = "disconnected",
    val selectedAnimeId: Long? = null,
    val generation: Long = 0L,
    val pendingTasks: Int = 0,
    val lastAction: String? = null,
    val lastError: String? = null,
    val lastArtifactPath: String? = null,
    val lastResultJson: String? = null,
    val playerSessionActive: Boolean = false,
    val activePlayerRequestId: String? = null,
    val activePlayerSessionId: String? = null,
    val activePlayerActivityInstanceId: String? = null,
    val activeEpisodeId: Long? = null,
    val activeAnimeId: Long? = null,
    val activeMediaUri: String? = null,
    val activePlayerGeneration: Long = 0L,
    val transitionGeneration: Long = 0L,
    val queuedThumbnailCount: Int = 0,
    val activeThumbnailCount: Int = 0,
)

internal data class NativePlayerCallbackValidation(val isCurrent: Boolean, val reason: String = "")

internal data class NativeMainStorageSnapshot(
    val mediaStoreAccess: String,
    val broadStorageAccess: Boolean,
    val broadStorageManagerEnabled: Boolean,
    val configuredSafRoots: List<String>,
    val persistedSafRootCount: Int,
    val scanSnapshots: List<ScanProgressSnapshot>,
    val onboardingPhase: String,
    val onboardingDismissed: Boolean,
    val capturedAtMillis: Long,
) {
    val hasAnyAuthorizedSource: Boolean
        get() = mediaStoreAccess.equals("full", ignoreCase = true) ||
            mediaStoreAccess.equals("partial", ignoreCase = true) ||
            broadStorageAccess || configuredSafRoots.isNotEmpty()
}

private data class NativeThumbnailRequest(
    val key: String,
    val uri: Uri,
    val size: Long,
    val modifiedAt: Long,
    val mediaIdentity: String?,
    val priority: Int,
    val enqueuedAtMillis: Long,
    val completion: CompletableDeferred<VideoThumbnailExtractor.Result?>,
)

internal data class NativeMainUiPreferences(
    val homeState: Map<String, Any?> = emptyMap(),
    val organizeState: Map<String, Any?> = emptyMap(),
    val settingsState: Map<String, Any?> = emptyMap(),
)

internal class NativeMainRuntimePort(
    private val activity: MainActivity,
    private val libraryViewModel: ReiAnixLibraryViewModel,
    private val settingsViewModel: ReiAnixSettingsViewModel,
    private val composeHost: ReiAnixComposeLibraryHost,
) {
    private val lock = Any()
    private val jobs = linkedSetOf<Job>()
    private val jobLanes = mutableMapOf<Job, String>()
    private val maxBackgroundTasksPerLane = 8
    private val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
    private val mutableSnapshot = MutableStateFlow(NativeMainRuntimeSnapshot())
    val snapshot: StateFlow<NativeMainRuntimeSnapshot> = mutableSnapshot.asStateFlow()
    private val preferenceLock = Any()
    private val preferenceFile = File(File(activity.filesDir, "data"), "navigation_state.json")
    private val preferenceWriteMutex = Mutex()
    private val thumbnailQueueLock = Any()
    private val thumbnailQueue = PriorityQueue<NativeThumbnailRequest>(
        compareByDescending<NativeThumbnailRequest> { it.priority }
            .thenBy { it.enqueuedAtMillis }
            .thenBy { it.key },
    )
    private val thumbnailRequests = ConcurrentHashMap<String, NativeThumbnailRequest>()
    private val thumbnailInFlight = ConcurrentHashMap.newKeySet<String>()
    private val thumbnailSemaphore = Semaphore(2)
    @Volatile private var thumbnailPumpActive = false
    @Volatile private var homeRefreshJob: Job? = null
    private val mutableUiPreferences = MutableStateFlow(loadDurableUiPreferences())
    val uiPreferences: StateFlow<NativeMainUiPreferences> = mutableUiPreferences.asStateFlow()

    private fun publish(
        action: String? = mutableSnapshot.value.lastAction,
        error: String? = null,
        transform: (NativeMainRuntimeSnapshot) -> NativeMainRuntimeSnapshot = { it },
    ) {
        synchronized(lock) {
            val old = mutableSnapshot.value
            mutableSnapshot.value = transform(
                old.copy(
                    generation = old.generation + 1L,
                    pendingTasks = jobs.size,
                    lastAction = action,
                    lastError = error,
                ),
            )
        }
    }

    /**
     * The previous Flet runtime persisted durable Home/Organize/Settings preferences
     * only. Route and back-stack state is deliberately not restored; each process
     * starts at Home and a recovery decision remains authoritative.
     */
    private fun loadDurableUiPreferences(): NativeMainUiPreferences {
        val file = preferenceFile
        if (!file.isFile || file.length() !in 1..1_048_576L) return NativeMainUiPreferences()
        return runCatching {
            val root = org.json.JSONObject(file.readText(Charsets.UTF_8))
            fun values(key: String): Map<String, Any?> {
                val source = root.optJSONObject(key) ?: return emptyMap()
                val target = linkedMapOf<String, Any?>()
                val keys = source.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val value = source.opt(name)
                    if (value != null && value !== org.json.JSONObject.NULL) target[name] = value
                }
                return target
            }
            NativeMainUiPreferences(
                homeState = values("home_state"),
                organizeState = values("organize_state"),
                settingsState = values("settings_state"),
            )
        }.getOrElse { error ->
            Log.w("ReiAnixMainPort", "Ignoring invalid durable UI preference snapshot", error)
            NativeMainUiPreferences()
        }
    }

    private fun preferencePayload(state: NativeMainUiPreferences): org.json.JSONObject =
        org.json.JSONObject()
            .put("version", 3)
            .put("home_state", org.json.JSONObject(state.homeState))
            .put("organize_state", org.json.JSONObject(state.organizeState))
            .put("settings_state", org.json.JSONObject(state.settingsState))

    private fun writeDurableUiPreferences(state: NativeMainUiPreferences) {
        synchronized(preferenceLock) {
            val directory = preferenceFile.parentFile
            check(directory != null && (directory.isDirectory || directory.mkdirs())) {
                "Could not create durable UI preference directory"
            }
            val temporary = File(directory, preferenceFile.name + ".tmp")
            try {
                val bytes = preferencePayload(state).toString().toByteArray(Charsets.UTF_8)
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        preferenceFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        preferenceFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (failure: Throwable) {
                temporary.delete()
                throw failure
            }
        }
    }

    private fun updateUiPreference(
        area: String,
        key: String,
        value: Any?,
        remove: Boolean,
    ): Boolean {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return false
        val current = mutableUiPreferences.value
        fun nextMap(source: Map<String, Any?>): Map<String, Any?> = source.toMutableMap().apply {
            if (remove) remove(normalizedKey) else put(normalizedKey, value)
        }
        val next = when (area) {
            "home" -> current.copy(homeState = nextMap(current.homeState))
            "organize" -> current.copy(organizeState = nextMap(current.organizeState))
            "settings" -> current.copy(settingsState = nextMap(current.settingsState))
            else -> return false
        }
        mutableUiPreferences.value = next
        return launchTracked("persist_ui_preferences", lane = "settings") {
            preferenceWriteMutex.withLock {
                withContext(Dispatchers.IO) {
                    writeDurableUiPreferences(mutableUiPreferences.value)
                }
            }
        }
    }

    fun setHomeUiPreference(key: String, value: Any?): Boolean =
        updateUiPreference("home", key, value, remove = false)

    fun removeHomeUiPreference(key: String): Boolean =
        updateUiPreference("home", key, null, remove = true)

    fun setOrganizeUiPreference(key: String, value: Any?): Boolean =
        updateUiPreference("organize", key, value, remove = false)

    fun removeOrganizeUiPreference(key: String): Boolean =
        updateUiPreference("organize", key, null, remove = true)

    fun setSettingsUiPreference(key: String, value: Any?): Boolean =
        updateUiPreference("settings", key, value, remove = false)

    fun removeSettingsUiPreference(key: String): Boolean =
        updateUiPreference("settings", key, null, remove = true)

    fun flushUiPreferences(): Boolean = launchTracked("flush_ui_preferences", lane = "settings") {
        preferenceWriteMutex.withLock {
            withContext(Dispatchers.IO) {
                writeDurableUiPreferences(mutableUiPreferences.value)
            }
        }
    }

    fun clearPersistedNavigationState() {
        // Runtime route and back stack are not durable UI preferences and must not be replayed.
        publish(action = "navigation_state_process_local")
    }

    /**
     * Bounded lifecycle-scoped work replaces the old main.py task registries.
     * A task is registered before it starts, and completion always releases the
     * slot; failures are observable rather than silently lost.
     */
    private fun launchTracked(
        label: String,
        lane: String = "general",
        block: suspend () -> Unit,
    ): Boolean {
        val normalizedLane = lane.trim().ifBlank { "general" }
        val job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
                publish(action = label)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e("ReiAnixMainPort", "Native main operation failed: $label", failure)
                ReiAnixDiagnosticTimeline.record(
                    name = "NATIVE_MAIN_OPERATION_FAILED",
                    severity = "ERROR",
                    source = "NativeMainRuntimePort",
                    result = label,
                    error = failure,
                    context = mapOf("operation" to label, "lane" to normalizedLane),
                )
                publish(action = label, error = failure.message ?: failure.javaClass.simpleName)
            }
        }

        val accepted = synchronized(lock) {
            val laneSize = jobLanes.values.count { it == normalizedLane }
            if (
                laneSize >= maxBackgroundTasksPerLane ||
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                false
            } else {
                jobs.add(job)
                jobLanes[job] = normalizedLane
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    generation = mutableSnapshot.value.generation + 1L,
                    pendingTasks = jobs.size,
                    lastAction = label,
                    lastError = null,
                )
                true
            }
        }
        if (!accepted) {
            job.cancel()
            return false
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                jobs.remove(job)
                jobLanes.remove(job)
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    generation = mutableSnapshot.value.generation + 1L,
                    pendingTasks = jobs.size,
                )
            }
        }
        job.start()
        return true
    }

    /** Port of the original startup/recovery decision, guarded by canonical SQLite validation. */
    fun start(startDestination: String = ReiAnixRoutes.HOME): Boolean =
        launchTracked("startup") {
            val recoveryRequired = dependencies.bootstrap.validateCanonicalDatabase()
            if (activity.isFinishing || activity.isDestroyed) return@launchTracked
            val destination = if (recoveryRequired) {
                ReiAnixRoutes.RECOVERY
            } else if (ReiAnixRoutes.isSupportedShellDestination(startDestination)) {
                startDestination
            } else {
                ReiAnixRoutes.HOME
            }
            composeHost.show(destination, resetBackStack = true)
            if (!recoveryRequired) {
                libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
                settingsViewModel.refresh()
            }
            publish(action = "startup", transform = {
                it.copy(initialized = true, recoveryRequired = recoveryRequired, route = destination)
            })
        }

    /** Process-local navigation replaces Python's persisted Flet route stack. */
    fun navigate(route: String, reason: String = "navigate"): Boolean {
        val destination = route.trim()
        if (!ReiAnixRoutes.isSupportedShellDestination(destination)) {
            publish(
                action = reason,
                error = "Unsupported native route: ${destination.take(100)}",
            )
            return false
        }
        composeHost.show(destination)
        publish(action = reason, transform = { it.copy(route = destination) })
        return true
    }

    fun navigateHome(): Boolean = navigate(ReiAnixRoutes.HOME, "navigate_home")
    fun navigateLibrary(): Boolean = navigate(ReiAnixRoutes.LIBRARY, "navigate_library")
    fun navigateOrganize(): Boolean = navigate(ReiAnixRoutes.ORGANIZE, "navigate_organize")
    fun navigateCollector(): Boolean = navigate(ReiAnixRoutes.COLLECTOR, "navigate_collector")
    fun navigateSettings(): Boolean = navigate(ReiAnixRoutes.SETTINGS, "navigate_settings")
    fun navigateStorage(): Boolean = navigate(ReiAnixRoutes.STORAGE, "navigate_storage")
    fun navigateSearch(): Boolean = navigate(ReiAnixRoutes.SEARCH, "navigate_search")
    fun navigateMyList(): Boolean = navigate(ReiAnixRoutes.MY_LIST, "navigate_my_list")

    fun navigateBack(): Boolean {
        val handled = composeHost.handleBack()
        publish(action = "navigate_back")
        return handled
    }

    fun hideComposeLibrary() {
        composeHost.hide()
        publish(action = "hide_compose_library")
    }

    fun showComposeLibrary(route: String = ReiAnixRoutes.LIBRARY, resetBackStack: Boolean = false): Boolean {
        if (!ReiAnixRoutes.isSupportedShellDestination(route)) return false
        composeHost.show(route, resetBackStack = resetBackStack)
        publish(action = "show_compose_library", transform = { it.copy(route = route) })
        return true
    }

    /** The Compose UI is StateFlow-driven, so the former Flet safe_update is intentionally a no-op. */
    fun safeUpdate() = Unit

    fun setAccountState(value: String, reason: String = "account_state_changed") {
        val normalized = value.trim().lowercase(Locale.ROOT).ifBlank { "disconnected" }
        publish(action = reason, transform = { it.copy(accountState = normalized) })
    }
    suspend fun accountProfile() = dependencies.sqliteAccountRepository.loadProfile()

    fun openHomeSearch() {
        libraryViewModel.setSearchQuery("")
        publish(action = "open_home_search")
    }

    fun closeHomeSearch() {
        libraryViewModel.setSearchQuery("")
        publish(action = "close_home_search")
    }

    fun updateHomeSearchQuery(value: String) = libraryViewModel.setSearchQuery(value)

    fun navigateSettingsCategory(category: String): Boolean {
        val normalized = category.trim().lowercase(Locale.ROOT)
        val destination = when (normalized) {
            "storage", "folders", "permissions", "armazenamento", "pastas" -> ReiAnixRoutes.STORAGE
            else -> ReiAnixRoutes.SETTINGS
        }
        return navigate(destination, "navigate_settings_category")
    }

    fun navigateBackFromSettings(): Boolean = navigateBack()

    fun login(): Boolean = requestAccountActionTracked("login")
    fun logout(): Boolean = requestAccountActionTracked("logout")
    fun switchAccount(): Boolean = requestAccountActionTracked("switch_account")

    fun refreshLibrary(): Boolean = launchTracked("refresh_library") {
        libraryViewModel.refresh()
        settingsViewModel.refresh()
    }

    fun refreshCanonicalCatalog(): Boolean = launchTracked("refresh_canonical_catalog") {
        val refreshed = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
        if (!refreshed) {
            throw IllegalStateException("O catálogo nativo ainda não pode ser atualizado antes da validação do banco.")
        }
    }

    fun refreshCurrentDetails(animeId: Long): Boolean {
        if (animeId <= 0L) return false
        publish(action = "refresh_details", transform = { it.copy(selectedAnimeId = animeId) })
        libraryViewModel.refreshDetails(animeId)
        return true
    }

    fun selectDetailsAnime(animeId: Long): Boolean {
        if (animeId <= 0L) return false
        publish(action = "navigate_details", transform = { it.copy(selectedAnimeId = animeId) })
        libraryViewModel.refreshDetails(animeId)
        return true
    }

    fun clearSelectedAnime() {
        publish(action = "clear_details", transform = { it.copy(selectedAnimeId = null) })
    }

    fun setLibraryPageSize(value: Int) = libraryViewModel.applyLibraryPageSize(value)
    fun setLibrarySortDefault(value: String) = libraryViewModel.applyLibrarySortDefault(value)
    fun loadNextLibraryPage() = libraryViewModel.loadNextLibraryPage()
    fun setLibrarySearchQuery(value: String) = libraryViewModel.setLibrarySearchQuery(value)
    fun setLibrarySort(value: String) = libraryViewModel.setLibrarySort(value)
    fun setLibraryGenreFilter(value: String?) = libraryViewModel.setLibraryGenreFilter(value)
    fun toggleLibraryFavoritesFilter() = libraryViewModel.toggleLibraryFavoritesFilter()
    fun toggleLibraryWatchingFilter() = libraryViewModel.toggleLibraryWatchingFilter()
    fun toggleLibraryCompletedFilter() = libraryViewModel.toggleLibraryCompletedFilter()
    fun clearLibraryFilters() = libraryViewModel.clearLibraryFilters()

    fun setSearchQuery(value: String) = libraryViewModel.setSearchQuery(value)
    fun setSearchGenreFilter(value: String?) = libraryViewModel.setSearchGenreFilter(value)
    fun toggleSearchFavoritesFilter() = libraryViewModel.toggleSearchFavoritesFilter()
    fun toggleSearchWatchingFilter() = libraryViewModel.toggleSearchWatchingFilter()
    fun toggleSearchCompletedFilter() = libraryViewModel.toggleSearchCompletedFilter()
    fun setSearchSort(value: String?) = libraryViewModel.setSearchSort(value)
    fun clearSearchFilters() = libraryViewModel.clearSearchFilters()

    fun setOrganizeQuery(value: String) = libraryViewModel.setOrganizeQuery(value)
    fun setOrganizeState(value: String) = libraryViewModel.setOrganizeState(value)
    fun setOrganizeGenre(key: String?) = libraryViewModel.setOrganizeGenre(key)
    fun setOrganizeSort(value: String) = libraryViewModel.setOrganizeSort(value)
    fun clearOrganizeFilters() = libraryViewModel.clearOrganizeFilters()
    fun openOrganizeCollection(value: String) = libraryViewModel.openOrganizeCollection(value)
    fun openOrganizeOverview() = libraryViewModel.openOrganizeOverview()

    fun toggleFavorite(animeId: Long) {
        if (animeId > 0L) libraryViewModel.toggleFavorite(animeId)
    }

    fun markEpisodeWatched(episodeId: Long, watched: Boolean) {
        if (episodeId > 0L) libraryViewModel.setEpisodeWatched(episodeId, watched)
    }
    suspend fun playbackProgress(episodeId: Long) =
        dependencies.consumptionService.getProgress(episodeId)

    suspend fun playbackHistory(limit: Int = 36) =
        dependencies.consumptionService.playbackHistory(limit.coerceIn(1, 200))

    suspend fun continueWatching(limit: Int = 36) =
        dependencies.consumptionService.continueWatching(limit.coerceIn(1, 200))

    suspend fun watchedRecently(limit: Int = 36) =
        dependencies.consumptionService.watchedRecently(limit.coerceIn(1, 200))

    fun savePlaybackProgress(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long? = null,
        completed: Boolean = false,
    ): Boolean {
        if (episodeId <= 0L || positionMs < 0L || (durationMs != null && durationMs <= 0L)) return false
        return launchTracked("save_playback_progress", lane = "playback") {
            val progress = dependencies.consumptionService.saveProgress(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
            )
            libraryViewModel.refresh()
            publish(action = "save_playback_progress", transform = {
                it.copy(
                    lastResultJson = org.json.JSONObject()
                        .put("episodeId", progress.episodeId)
                        .put("positionMs", progress.positionMs)
                        .put("durationMs", progress.durationMs)
                        .put("watched", progress.watched)
                        .put("isCompleted", progress.isCompleted)
                        .toString(),
                )
            })
        }
    }

    fun markEpisodeWatchedTracked(episodeId: Long, watched: Boolean): Boolean {
        if (episodeId <= 0L) return false
        return launchTracked("mark_episode_watched", lane = "playback") {
            val progress = dependencies.consumptionService.setWatched(episodeId, watched)
            libraryViewModel.refresh()
            publish(action = "mark_episode_watched", transform = {
                it.copy(
                    lastResultJson = org.json.JSONObject()
                        .put("episodeId", progress.episodeId)
                        .put("positionMs", progress.positionMs)
                        .put("durationMs", progress.durationMs)
                        .put("watched", progress.watched)
                        .toString(),
                )
            })
        }
    }

    /**
     * Priority/deduplicated local-thumbnail queue corresponding to the Python
     * thumbnail dispatcher. The canonical extractor owns caching and artwork
     * registration; this queue only schedules work and never downloads video.
     */
    fun enqueueLocalThumbnail(
        reference: String,
        size: Long = 0L,
        modifiedAt: Long = 0L,
        mediaIdentity: String? = null,
        priority: Int = 0,
    ): Boolean {
        val raw = reference.trim()
        if (raw.isEmpty()) return false
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val uri = when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> parsed
            null -> if (raw.startsWith("/")) Uri.fromFile(File(raw)) else return false
            else -> return false
        }
        val boundedSize = size.coerceAtLeast(0L)
        val boundedModifiedAt = modifiedAt.coerceAtLeast(0L)
        val identity = mediaIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: uri.toString()
        val key = VideoThumbnailExtractor.cacheKey(identity, boundedSize, boundedModifiedAt)
        synchronized(thumbnailQueueLock) {
            val existing = thumbnailRequests[key]
            if (existing != null) {
                if (priority > existing.priority && key !in thumbnailInFlight) {
                    thumbnailQueue.remove(existing)
                    val promoted = existing.copy(priority = priority)
                    thumbnailRequests[key] = promoted
                    thumbnailQueue.add(promoted)
                }
                updateThumbnailQueueSnapshot()
                startThumbnailPumpLocked()
                return true
            }
            if (thumbnailRequests.size >= MAX_PENDING_THUMBNAIL_REQUESTS) {
                val lowest = thumbnailQueue.minWithOrNull(
                    compareBy<NativeThumbnailRequest> { it.priority }.thenByDescending { it.enqueuedAtMillis },
                )
                if (lowest == null || lowest.priority >= priority) {
                    recordDiagnostic(
                        name = "THUMBNAIL_REQUEST_REJECTED",
                        severity = "WARN",
                        result = "queue_full",
                        context = mapOf("pending_count" to thumbnailRequests.size),
                    )
                    return false
                }
                thumbnailQueue.remove(lowest)
                thumbnailRequests.remove(lowest.key, lowest)
                lowest.completion.complete(null)
            }
            val request = NativeThumbnailRequest(
                key = key,
                uri = uri,
                size = boundedSize,
                modifiedAt = boundedModifiedAt,
                mediaIdentity = identity,
                priority = priority,
                enqueuedAtMillis = System.currentTimeMillis(),
                completion = CompletableDeferred(),
            )
            thumbnailRequests[key] = request
            thumbnailQueue.add(request)
            updateThumbnailQueueSnapshot()
            startThumbnailPumpLocked()
        }
        return true
    }

    private fun updateThumbnailQueueSnapshot() {
        val pending = synchronized(thumbnailQueueLock) { thumbnailQueue.size }
        val active = thumbnailInFlight.size
        publish(action = "thumbnail_queue_updated", transform = {
            it.copy(queuedThumbnailCount = pending, activeThumbnailCount = active)
        })
    }

    private fun startThumbnailPumpLocked() {
        if (thumbnailPumpActive || thumbnailQueue.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        thumbnailPumpActive = true
        activity.lifecycleScope.launch {
            try {
                while (true) {
                    val request = synchronized(thumbnailQueueLock) {
                        thumbnailQueue.poll()?.also { thumbnailInFlight.add(it.key) }
                    } ?: break
                    updateThumbnailQueueSnapshot()
                    try {
                        val result = thumbnailSemaphore.withPermit {
                            withContext(Dispatchers.IO) {
                                VideoThumbnailExtractor.extract(
                                    activity.applicationContext,
                                    request.uri,
                                    request.size,
                                    request.modifiedAt,
                                    request.mediaIdentity,
                                )
                            }
                        }
                        request.completion.complete(result)
                        if (result == null) {
                            recordDiagnostic(
                                name = "THUMBNAIL_EXTRACTION_FAILED",
                                severity = "WARN",
                                result = "no_result",
                                context = mapOf("request_id" to request.key.take(20)),
                            )
                        } else {
                            publish(action = "thumbnail_extracted", transform = {
                                it.copy(lastArtifactPath = result.path)
                            })
                        }
                    } catch (cancelled: CancellationException) {
                        request.completion.cancel(cancelled)
                        throw cancelled
                    } catch (failure: Throwable) {
                        request.completion.completeExceptionally(failure)
                        Log.w("ReiAnixMainPort", "Local thumbnail extraction failed", failure)
                        recordDiagnostic(
                            name = "THUMBNAIL_EXTRACTION_FAILED",
                            severity = "WARN",
                            error = failure,
                            context = mapOf("request_id" to request.key.take(20)),
                        )
                    } finally {
                        synchronized(thumbnailQueueLock) {
                            thumbnailInFlight.remove(request.key)
                            thumbnailRequests.remove(request.key)
                        }
                        updateThumbnailQueueSnapshot()
                    }
                }
            } finally {
                synchronized(thumbnailQueueLock) {
                    thumbnailPumpActive = false
                    if (thumbnailQueue.isNotEmpty()) startThumbnailPumpLocked()
                }
            }
        }
    }

    suspend fun awaitLocalThumbnail(reference: String, size: Long = 0L, modifiedAt: Long = 0L, mediaIdentity: String? = null): VideoThumbnailExtractor.Result? {
        val raw = reference.trim()
        if (raw.isEmpty()) return null
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val uri = when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> parsed
            null -> if (raw.startsWith("/")) Uri.fromFile(File(raw)) else return null
            else -> return null
        }
        val identity = mediaIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: uri.toString()
        val key = VideoThumbnailExtractor.cacheKey(identity, size.coerceAtLeast(0L), modifiedAt.coerceAtLeast(0L))
        val accepted = enqueueLocalThumbnail(raw, size, modifiedAt, identity, priority = 100)
        if (!accepted) return null
        return thumbnailRequests[key]?.completion?.await()
    }

    fun cancelThumbnailRequest(reference: String, size: Long = 0L, modifiedAt: Long = 0L, mediaIdentity: String? = null): Boolean {
        val raw = reference.trim()
        if (raw.isEmpty()) return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val identity = mediaIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: uri.toString()
        val key = VideoThumbnailExtractor.cacheKey(identity, size.coerceAtLeast(0L), modifiedAt.coerceAtLeast(0L))
        synchronized(thumbnailQueueLock) {
            val request = thumbnailRequests.remove(key) ?: return false
            thumbnailQueue.remove(request)
            request.completion.cancel()
            updateThumbnailQueueSnapshot()
            return true
        }
    }

    fun extractLocalThumbnail(
        reference: String,
        size: Long = 0L,
        modifiedAt: Long = 0L,
        mediaIdentity: String? = null,
    ): Boolean {
        val raw = reference.trim()
        if (raw.isEmpty()) return false
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val uri = when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> parsed
            null -> if (raw.startsWith("/")) Uri.fromFile(File(raw)) else return false
            else -> return false
        }
        return launchTracked("extract_local_thumbnail", lane = "thumbnail") {
            val result = withContext(Dispatchers.IO) {
                VideoThumbnailExtractor.extract(
                    activity.applicationContext,
                    uri,
                    size.coerceAtLeast(0L),
                    modifiedAt.coerceAtLeast(0L),
                    mediaIdentity,
                )
            } ?: throw IllegalStateException("Não foi possível extrair uma miniatura da mídia local.")
            publish(action = "extract_local_thumbnail", transform = {
                it.copy(
                    lastArtifactPath = result.path,
                    lastResultJson = org.json.JSONObject()
                        .put("path", result.path)
                        .put("durationMs", result.durationMs)
                        .put("width", result.width)
                        .put("height", result.height)
                        .put("rotation", result.rotation)
                        .put("mimeType", result.mimeType)
                        .put("canonicalArtworkRegistered", result.canonicalArtworkRegistered)
                        .toString(),
                )
            })
            libraryViewModel.queryNativeArtwork()
        }
    }


    fun openEpisode(episodeId: Long, expectedAnimeId: Long? = null): String? {
        if (episodeId <= 0L) return null
        return runCatching { libraryViewModel.openEpisode(episodeId, expectedAnimeId) }
            .onFailure { failure ->
                Log.e("ReiAnixMainPort", "Unable to open local episode id=$episodeId", failure)
                ReiAnixDiagnosticTimeline.record(
                    name = "NATIVE_MAIN_OPEN_EPISODE_FAILED",
                    severity = "ERROR",
                    source = "NativeMainRuntimePort",
                    error = failure,
                    context = mapOf("episode_id" to episodeId),
                )
            }
            .getOrNull()
    }


    /** Session identity helpers preserve the stale-callback fencing from the former player callbacks. */
    fun revokePlayerTransition(originRequestId: String, originPlayerSessionId: String? = null) {
        MainActivity.revokePlayerTransition(originRequestId, originPlayerSessionId)
        publish(action = "revoke_player_transition", transform = {
            it.copy(transitionGeneration = it.transitionGeneration + 1L)
        })
    }

    @Volatile private var lifecycleIsAlive: Boolean = true

    fun cancelPlayerTransition(reason: String = "unknown") {
        publish(action = "player_transition_cancelled", transform = {
            it.copy(transitionGeneration = it.transitionGeneration + 1L)
        })
        recordDiagnostic(
            name = "PLAYER_TRANSITION_CANCELLED",
            severity = "INFO",
            result = reason,
            context = mapOf("transition_generation" to mutableSnapshot.value.transitionGeneration),
        )
    }

    fun invalidatePlayerSession(
        reason: String = "unknown",
        expectedSessionId: String? = null,
        expectedRequestId: String? = null,
    ): Boolean {
        val current = mutableSnapshot.value
        if (!expectedSessionId.isNullOrBlank() && current.activePlayerSessionId != null &&
            current.activePlayerSessionId != expectedSessionId) return false
        if (!expectedRequestId.isNullOrBlank() && current.activePlayerRequestId != null &&
            current.activePlayerRequestId != expectedRequestId) return false
        cancelBackgroundTasksForLane("playback")
        publish(action = "invalidate_player_session", transform = {
            it.copy(
                playerSessionActive = false,
                activePlayerRequestId = null,
                activePlayerSessionId = null,
                activePlayerActivityInstanceId = null,
                activeEpisodeId = null,
                activeAnimeId = null,
                activeMediaUri = null,
                activePlayerGeneration = 0L,
                transitionGeneration = it.transitionGeneration + 1L,
            )
        })
        recordDiagnostic(
            name = "PLAYER_SESSION_INVALIDATED",
            severity = "INFO",
            result = reason,
            context = mapOf("had_session" to !current.activePlayerSessionId.isNullOrBlank()),
        )
        return true
    }


    fun playerCallbackIdentityIsComplete(payload: org.json.JSONObject?): Boolean {
        if (payload == null) return false
        val session = payload.optString("playerSessionId", payload.optString("player_session_id", "")).trim()
        val episode = payload.optString("episodeId", "").trim()
        val media = payload.optString("mediaId", "").trim()
        val uri = payload.optString("uri", "").trim()
        return session.isNotEmpty() && episode.isNotEmpty() && media.isNotEmpty() && uri.isNotEmpty()
    }

    fun validatePlayerCallback(
        eventRequestId: String?,
        payload: org.json.JSONObject,
        requireActive: Boolean = true,
        expectedEpisodeId: Long? = null,
        expectedMediaId: String? = null,
        expectedAnimeId: Long? = null,
    ): NativePlayerCallbackValidation {
        val current = mutableSnapshot.value
        fun reject(reason: String) = NativePlayerCallbackValidation(false, reason)
        val sessionId = payload.optString("playerSessionId", payload.optString("player_session_id", "")).trim()
        val activityId = payload.optString("activityInstanceId", payload.optString("activity_instance_id", "")).trim()
        val requestId = eventRequestId?.trim().orEmpty().ifBlank { payload.optString("requestId", "").trim() }
        val playerGeneration = payload.optLong("generation", payload.optLong("playerGeneration", 0L))
        val transition = payload.optLong("transitionGeneration", 0L)
        val episodeId = expectedEpisodeId ?: payload.optLong("episodeId", 0L).takeIf { it > 0L }
        val animeId = expectedAnimeId ?: payload.optLong("animeId", 0L).takeIf { it > 0L }
        val mediaId = expectedMediaId?.trim().orEmpty().ifBlank { payload.optString("mediaId", "").trim() }

        if (requireActive && !current.playerSessionActive) return reject("stale_session")
        if (sessionId.isNotBlank() && current.activePlayerSessionId != null && current.activePlayerSessionId != sessionId) return reject("stale_session")
        if (activityId.isNotBlank() && current.activePlayerActivityInstanceId != null && current.activePlayerActivityInstanceId != activityId) return reject("stale_activity_instance")
        if (requestId.isNotBlank() && current.activePlayerRequestId != null && current.activePlayerRequestId != requestId) return reject("stale_request")
        if (playerGeneration > 0L && current.activePlayerGeneration > 0L && playerGeneration != current.activePlayerGeneration) return reject("stale_player_generation")
        if (transition > 0L && current.transitionGeneration > 0L && transition < current.transitionGeneration) return reject("stale_transition_generation")
        if (episodeId != null && current.activeEpisodeId != null && episodeId != current.activeEpisodeId) return reject("stale_episode")
        if (mediaId.isNotBlank()) {
            val activeEpisode = current.activeEpisodeId
            if (activeEpisode != null && mediaId != "episode:" + activeEpisode) return reject("stale_media_identity")
            if (activeEpisode == null && !current.activeMediaUri.isNullOrBlank() && mediaId != current.activeMediaUri) return reject("stale_media_identity")
        }
        if (animeId != null && current.activeAnimeId != null && animeId != current.activeAnimeId) return reject("stale_anime")
        return NativePlayerCallbackValidation(true)
    }

    fun playerTransitionIsCurrent(
        generation: Long,
        requestId: String,
        playerSessionId: String? = null,
    ): Boolean {
        val current = mutableSnapshot.value
        val sessionMatches = playerSessionId == null ||
            (!current.activePlayerSessionId.isNullOrBlank() && current.activePlayerSessionId == playerSessionId)
        return generation == current.transitionGeneration &&
            current.activePlayerRequestId in setOf(null, requestId) &&
            current.playerSessionActive && sessionMatches && lifecycleIsAlive
    }

    fun setActivePlayerMedia(episodeId: Long?, animeId: Long?, mediaUri: String?) {
        publish(action = "player_media_selected", transform = {
            it.copy(
                activeEpisodeId = episodeId?.takeIf { value -> value > 0L },
                activeAnimeId = animeId?.takeIf { value -> value > 0L },
                activeMediaUri = mediaUri?.trim()?.takeIf { value -> value.isNotEmpty() },
            )
        })
    }

    fun isPlayerTransitionRevoked(originRequestId: String, originPlayerSessionId: String? = null): Boolean =
        MainActivity.isPlayerTransitionRevoked(originRequestId, originPlayerSessionId)

    fun isCurrentPlayerHandoff(
        requestId: String,
        originRequestId: String,
        originCreatedAtMs: Long,
        playerSessionId: String,
        originPlayerSessionId: String,
        originTransitionGeneration: Long,
    ): Boolean = MainActivity.isCurrentPlayerHandoff(
        requestId,
        originRequestId,
        originCreatedAtMs,
        playerSessionId,
        originPlayerSessionId,
        originTransitionGeneration,
    )

    fun notePlayerSession(requestId: String, sessionId: String, transitionGeneration: Long = 0L) {
        MainActivity.notePlayerSession(requestId, sessionId, transitionGeneration)
        publish(action = "player_session_active", transform = {
            it.copy(
                playerSessionActive = true,
                activePlayerRequestId = requestId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerSessionId = sessionId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerGeneration = transitionGeneration.coerceAtLeast(0L),
                transitionGeneration = maxOf(it.transitionGeneration, transitionGeneration.coerceAtLeast(0L)),
            )
        })
    }

    fun notePlayerActivityCreated(
        instanceId: String,
        requestId: String,
        sessionId: String,
        transitionGeneration: Long = 0L,
    ) {
        MainActivity.notePlayerActivityCreated(instanceId, requestId, sessionId, transitionGeneration)
        publish(action = "player_activity_created", transform = {
            it.copy(
                playerSessionActive = true,
                activePlayerActivityInstanceId = instanceId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerRequestId = requestId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerSessionId = sessionId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerGeneration = transitionGeneration.coerceAtLeast(0L),
                transitionGeneration = maxOf(it.transitionGeneration, transitionGeneration.coerceAtLeast(0L)),
            )
        })
    }

    fun notePlayerActivityDestroyed(instanceId: String, sessionId: String) {
        MainActivity.notePlayerActivityDestroyed(instanceId, sessionId)
        publish(action = "player_activity_destroyed", transform = {
            if (it.activePlayerActivityInstanceId == instanceId.trim() &&
                (sessionId.isBlank() || it.activePlayerSessionId == sessionId.trim())
            ) it.copy(activePlayerActivityInstanceId = null) else it
        })
    }

    fun notePlayerExit(
        requestId: String,
        atMs: Long = System.currentTimeMillis(),
        sessionId: String? = null,
        activityInstanceId: String? = null,
    ) {
        MainActivity.notePlayerExit(requestId, atMs, sessionId, activityInstanceId)
        publish(action = "player_exit", transform = {
            val requestMatches = it.activePlayerRequestId == null || it.activePlayerRequestId == requestId.trim()
            val sessionMatches = sessionId.isNullOrBlank() || it.activePlayerSessionId == null ||
                it.activePlayerSessionId == sessionId.trim()
            val instanceMatches = activityInstanceId.isNullOrBlank() || it.activePlayerActivityInstanceId == null ||
                it.activePlayerActivityInstanceId == activityInstanceId.trim()
            if (requestMatches && sessionMatches && instanceMatches) it.copy(
                playerSessionActive = false,
                activePlayerRequestId = null,
                activePlayerSessionId = null,
                activePlayerActivityInstanceId = null,
                activeEpisodeId = null,
                activeAnimeId = null,
                activeMediaUri = null,
                activePlayerGeneration = 0L,
                transitionGeneration = it.transitionGeneration + 1L,
            ) else it
        })
    }


    fun togglePinned(animeId: Long): Boolean {
        if (animeId <= 0L) return false
        return launchTracked("toggle_pinned") {
            libraryViewModel.toggleAnimePinnedNative(animeId)
        }
    }

    fun savePersonalNote(animeId: Long, note: String?): Boolean {
        if (animeId <= 0L) return false
        return launchTracked("save_personal_note") {
            libraryViewModel.saveAnimePersonalNoteNative(animeId, note)
        }
    }

    fun saveUserTags(animeId: Long, tags: List<String>): Boolean {
        if (animeId <= 0L) return false
        val normalized = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return launchTracked("save_user_tags") {
            libraryViewModel.saveAnimeUserTagsNative(animeId, normalized)
        }
    }

    fun refreshMetadata(animeId: Long): Boolean = refreshCurrentDetails(animeId)

    fun queryMarathonPlan(animeId: Long, currentPath: String? = null): Boolean {
        if (animeId <= 0L) return false
        return launchTracked("query_marathon_plan") {
            libraryViewModel.queryNativeMarathonPlan(animeId, currentPath)
        }
    }

    fun setSetting(key: String, value: String) {
        settingsViewModel.setSetting(key, value)
        publish(action = "set_setting")
    }

    fun requestSettingsAction(action: String) {
        settingsViewModel.requestAction(action)
        publish(action = "settings_action")
    }

    fun requestAccountAction(action: String) {
        settingsViewModel.requestAccountAction(action)
        publish(action = "account_action")
    }

    fun requestStorageAction(action: String): Boolean {
        val accepted = activity.requestNativeStorageAction(action)
        publish(action = "storage_action", error = if (accepted) null else "Native storage action was rejected.")
        return accepted
    }
    fun requestMediaAccess(): Boolean = requestStorageAction("request_media_access")
    fun checkStorageAccess(): Boolean = requestStorageAction("check_storage_access")
    fun openBroadStorageSettings(): Boolean = requestStorageAction("open_broad_storage_settings")
    fun requestLibraryScan(): Boolean = requestStorageAction("scan_library")
    fun selectSafFolder(): Boolean = requestStorageAction("select_tree")

    fun storageSnapshot(): NativeMainStorageSnapshot {
        val context = activity.applicationContext
        val roots = runCatching {
            libraryViewModel.authorizedSafTreeUris()
                .map { it.toString() }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
        val access = runCatching { MediaStoreScanner.accessLevel(context) }.getOrDefault("denied")
        val broad = runCatching { BroadStorageScanner.hasAccess(context) }.getOrDefault(false)
        val manager = runCatching {
            android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
        }.getOrDefault(false)
        val onboarding = activity.nativeStorageOnboardingState.value
        val scans: List<ScanProgressSnapshot> = NativeScanController.progress.value.values.toList()
            .sortedByDescending { it.startedAtMillis }
        return NativeMainStorageSnapshot(
            mediaStoreAccess = access,
            broadStorageAccess = broad,
            broadStorageManagerEnabled = manager,
            configuredSafRoots = roots,
            persistedSafRootCount = roots.size,
            scanSnapshots = scans,
            onboardingPhase = onboarding.phase,
            onboardingDismissed = onboarding.dismissed,
            capturedAtMillis = System.currentTimeMillis(),
        )
    }

    fun hasConfiguredSafFolder(): Boolean = configuredSafTreeUris().isNotEmpty()

    fun hasAvailableMediaSource(): Boolean = storageSnapshot().hasAnyAuthorizedSource

    fun isStorageOnboardingRequired(): Boolean {
        val snapshot = storageSnapshot()
        return !snapshot.hasAnyAuthorizedSource &&
            !snapshot.onboardingPhase.equals("ready", ignoreCase = true) &&
            !snapshot.onboardingDismissed
    }

    fun storageSourceStates(): Map<String, String> {
        val snapshot = storageSnapshot()
        return linkedMapOf(
            "mediastore" to snapshot.mediaStoreAccess,
            "broad_storage" to if (snapshot.broadStorageAccess) "granted" else "denied",
            "saf" to if (snapshot.configuredSafRoots.isNotEmpty()) "configured" else "missing",
        )
    }

    fun scanUiSnapshot(): org.json.JSONObject {
        val json = org.json.JSONObject()
        val scans = org.json.JSONArray()
        storageSnapshot().scanSnapshots.forEach { scan ->
            scans.put(
                org.json.JSONObject()
                    .put("operationId", scan.operationId)
                    .put("source", scan.source)
                    .put("state", scan.state.name)
                    .put("startedAtMillis", scan.startedAtMillis)
                    .put("finishedAtMillis", scan.finishedAtMillis ?: 0L)
                    .put("filesFound", scan.filesFound)
                    .put("filesProcessed", scan.filesProcessed)
                    .put("failures", scan.failures)
                    .put("directoriesCompleted", scan.directoriesCompleted)
                    .put("totalFiles", scan.totalFiles ?: org.json.JSONObject.NULL)
                    .put("partial", scan.partial)
                    .put("message", scan.message)
                    .put("errorCategory", scan.errorCategory ?: ""),
            )
        }
        json.put("scans", scans)
        val storage = storageSnapshot()
        json.put("mediaStoreAccess", storage.mediaStoreAccess)
            .put("broadStorageAccess", storage.broadStorageAccess)
            .put("broadStorageManagerEnabled", storage.broadStorageManagerEnabled)
            .put("configuredSafRootCount", storage.persistedSafRootCount)
            .put("onboardingPhase", storage.onboardingPhase)
            .put("onboardingDismissed", storage.onboardingDismissed)
            .put("capturedAtMillis", storage.capturedAtMillis)
        return json
    }


    fun onRuntimeDisconnect() {
        cancelBackgroundTasks()
        publish(action = "runtime_disconnect")
    }

    fun onRuntimeReconnect() {
        resumeRuntimeTasks()
        ensureNativeCommandDispatcher()
        settingsViewModel.refresh()
        libraryViewModel.refresh()
        publish(action = "runtime_reconnect")
    }

    /** In Compose these compatibility hooks invalidate observed state, not a hidden Flet view tree. */
    fun markUiDirty(reason: String = "state_changed") {
        publish(action = reason)
    }

    fun renderCurrent(route: String, force: Boolean = false, reason: String = "render_current"): Boolean {
        val currentRoute = route.trim().ifBlank { ReiAnixRoutes.HOME }
        if (!ReiAnixRoutes.isSupportedShellDestination(currentRoute)) return false
        if (force || currentRoute != mutableSnapshot.value.route) {
            return navigate(currentRoute, reason)
        }
        return true
    }

    fun buildScreen(route: String, force: Boolean = false): Boolean =
        renderCurrent(route, force, "build_screen")

    fun routeForScreen(screen: String): String {
        val normalized = screen.trim().lowercase(Locale.ROOT)
        return when (normalized) {
            "home" -> ReiAnixRoutes.HOME
            "library" -> ReiAnixRoutes.LIBRARY
            "organize" -> ReiAnixRoutes.ORGANIZE
            "collector" -> ReiAnixRoutes.COLLECTOR
            "settings" -> ReiAnixRoutes.SETTINGS
            "storage" -> ReiAnixRoutes.STORAGE
            "search" -> ReiAnixRoutes.SEARCH
            "my_list", "my-list", "my list" -> ReiAnixRoutes.MY_LIST
            else -> ""
        }
    }

    fun navigateScreen(screen: String, reason: String = "navigate_screen"): Boolean {
        val route = routeForScreen(screen)
        return route.isNotBlank() && navigate(route, reason)
    }

    fun refreshSettingsIfActive(): Boolean = launchTracked("refresh_settings", lane = "settings") {
        settingsViewModel.refresh()
    }

    fun applySettingRuntime(key: String, value: String): Boolean = setSettingSerialized(key, value)
    fun openSettingsAction(action: String): Boolean = requestSettingsActionTracked(action)


    fun selectSafTree() = libraryViewModel.selectSafTree()
    fun dismissStorageOnboarding() = libraryViewModel.dismissStorageOnboarding()
    fun removeSafTree(reference: String) = libraryViewModel.removeSafTree(reference)

    fun queryNativeAnime(animeId: Long): Boolean = launchTracked("query_anime") {
        libraryViewModel.queryNativeAnime(animeId)
    }

    fun queryNativeEpisodes(animeId: Long): Boolean = launchTracked("query_episodes") {
        libraryViewModel.queryNativeEpisodes(animeId)
    }

    fun queryNativeEpisode(episodeId: Long): Boolean = launchTracked("query_episode") {
        libraryViewModel.queryNativeEpisode(episodeId)
    }

    fun queryNativeRecentlyPlayed(): Boolean = launchTracked("query_recently_played") {
        libraryViewModel.queryNativeRecentlyPlayed()
    }

    fun queryNativeFolders(): Boolean = launchTracked("query_folders") {
        libraryViewModel.queryNativeFolders()
    }

    fun queryNativeArtwork(): Boolean = launchTracked("query_artwork") {
        libraryViewModel.queryNativeArtwork()
    }

    /** The process Application owns dispatch; start is idempotent and creates no second queue. */
    fun ensureNativeCommandDispatcher() {
        NativeCommandDispatcher.start(activity.applicationContext)
        publish(action = "ensure_native_command_dispatcher")
    }

    private val backupService: ReiAnixBackupService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixBackupService(activity.applicationContext)
    }
    private val diagnosticsService: ReiAnixDiagnosticsService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixDiagnosticsService(activity.applicationContext)
    }
    private val settingsFileService: ReiAnixSettingsFileService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReiAnixSettingsFileService(dependencies.sqliteSettingsRepository)
    }

    /** Versioned settings snapshots are validated and committed atomically in canonical SQLite. */
    suspend fun exportSettingsJson(): String = settingsFileService.exportJson()

    suspend fun importSettingsJson(raw: String): ReiAnixSettingsFileService.ImportResult {
        require(raw.length <= 1_048_576) { "O arquivo de configurações excede o limite suportado." }
        val result = settingsFileService.importJson(raw)
        settingsViewModel.refresh()
        publish(action = "import_settings", transform = {
            it.copy(
                lastResultJson = org.json.JSONObject()
                    .put("imported", result.imported)
                    .put("unknownKeys", org.json.JSONArray(result.unknownKeys))
                    .toString(),
            )
        })
        return result
    }

    fun exportSettingsToPrivateFile(): Boolean =
        launchTracked("export_settings", lane = "settings") {
            val raw = settingsFileService.exportJson()
            val target = File(File(activity.filesDir, "data"), "settings-export.json")
            val parent = target.parentFile
            check(parent != null && (parent.isDirectory || parent.mkdirs())) {
                "Não foi possível criar a pasta de exportação de configurações."
            }
            val temporary = File(parent, target.name + ".tmp")
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(raw.toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (failure: Throwable) {
                temporary.delete()
                throw failure
            }
            publish(action = "export_settings", transform = { it.copy(lastArtifactPath = target.absolutePath) })
        }

    fun importSettingsFromPrivateFile(file: File): Boolean {
        val sourcePath = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return false
        val allowedRoots = listOf(activity.filesDir, activity.cacheDir).mapNotNull {
            runCatching { it.canonicalFile.toPath() }.getOrNull()
        }
        if (allowedRoots.none { sourcePath.startsWith(it) } || !file.isFile || file.length() !in 1..1_048_576L) {
            publish(action = "import_settings", error = "O arquivo de configurações não está em uma área local permitida.")
            return false
        }
        return launchTracked("import_settings", lane = "settings") {
            importSettingsJson(file.readText(Charsets.UTF_8))
            publish(action = "import_settings", transform = { it.copy(lastArtifactPath = file.absolutePath) })
        }
    }

    /** Build the existing canonical backup format; no alternate catalogue or database is created. */
    fun createBackup(): Boolean = launchTracked("create_backup", lane = "backup") {
        val archive = backupService.createBackupFile()
        require(archive.isFile && archive.length() > 0L) {
            "O serviço de backup não produziu um arquivo válido."
        }
        publish(action = "create_backup", transform = {
            it.copy(lastArtifactPath = archive.absolutePath, lastResultJson = null)
        })
    }

    /** Inspect archive structure and integrity before an explicit restore request. */
    fun inspectBackup(archive: File): Boolean {
        if (!archive.isFile || archive.length() <= 0L) {
            publish(action = "inspect_backup", error = "O arquivo de backup está ausente ou vazio.")
            return false
        }
        return launchTracked("inspect_backup", lane = "backup") {
            val inspection = backupService.inspectBackup(archive)
            val summary = org.json.JSONObject()
                .put("manifest", inspection.manifest)
                .put("counts", inspection.counts)
                .put("invalidOptionalEntries", org.json.JSONArray(inspection.invalidOptionalEntries.toList()))
                .put("warnings", org.json.JSONArray(inspection.warnings))
            publish(action = "inspect_backup", transform = {
                it.copy(lastArtifactPath = archive.absolutePath, lastResultJson = summary.toString())
            })
        }
    }

    /** Restore through the canonical Kotlin service, including its pre-restore safety backup. */
    fun restoreBackup(archive: File): Boolean {
        if (!archive.isFile || archive.length() <= 0L) {
            publish(action = "restore_backup", error = "O arquivo de backup está ausente ou vazio.")
            return false
        }
        return launchTracked("restore_backup", lane = "backup") {
            val result = backupService.restoreBackup(archive)
            libraryViewModel.refresh()
            settingsViewModel.refresh()
            val summary = org.json.JSONObject()
                .put("importedRows", result.importedRows)
                .put("missingFiles", result.missingFiles)
                .put("manualArtworkReconnected", result.manualArtworkReconnected)
                .put("safetyBackup", result.safetyBackup.absolutePath)
                .put("warnings", org.json.JSONArray(result.warnings))
                .put("accountStatePreserved", result.accountStatePreserved)
                .put("reauthenticationRequired", result.reauthenticationRequired)
            publish(action = "restore_backup", transform = {
                it.copy(lastArtifactPath = result.safetyBackup.absolutePath, lastResultJson = summary.toString())
            })
        }
    }

    fun createRecoverySafetySnapshot(): Boolean =
        launchTracked("create_recovery_safety_snapshot", lane = "backup") {
            val file = backupService.createRecoverySafetySnapshotForUser()
            publish(action = "create_recovery_safety_snapshot", transform = {
                it.copy(lastArtifactPath = file.absolutePath)
            })
        }

    fun restoreRecoveryBackup(archive: File): Boolean {
        if (!archive.isFile || archive.length() <= 0L) {
            publish(action = "restore_recovery_backup", error = "O arquivo de recuperação está ausente ou vazio.")
            return false
        }
        return launchTracked("restore_recovery_backup", lane = "backup") {
            val result = backupService.restoreRecoveryBackup(archive)
            libraryViewModel.refresh()
            settingsViewModel.refresh()
            val summary = org.json.JSONObject()
                .put("importedRows", result.importedRows)
                .put("missingFiles", result.missingFiles)
                .put("manualArtworkReconnected", result.manualArtworkReconnected)
                .put("safetyBackup", result.safetyBackup.absolutePath)
                .put("warnings", org.json.JSONArray(result.warnings))
                .put("reauthenticationRequired", result.reauthenticationRequired)
            publish(action = "restore_recovery_backup", transform = {
                it.copy(
                    initialized = true,
                    recoveryRequired = false,
                    route = ReiAnixRoutes.HOME,
                    lastArtifactPath = result.safetyBackup.absolutePath,
                    lastResultJson = summary.toString(),
                )
            })
            composeHost.show(ReiAnixRoutes.HOME, resetBackStack = true)
        }
    }

    fun checkDatabaseIntegrity(): Boolean = launchTracked("database_integrity", lane = "backup") {
        val report = backupService.integrityReport().toJson()
        publish(action = "database_integrity", transform = {
            it.copy(lastResultJson = report.toString())
        })
    }

    fun reconcileRestoredFileAvailability(): Boolean =
        launchTracked("reconcile_restored_file_availability", lane = "backup") {
            val count = backupService.reconcileRestoredFileAvailability()
            publish(action = "reconcile_restored_file_availability", transform = {
                it.copy(lastResultJson = org.json.JSONObject().put("updatedRows", count).toString())
            })
            libraryViewModel.refresh()
        }

    /** Native privacy-filtered report mirrors the old diagnostic export action. */
    fun exportDiagnosticsJson(): Boolean = launchTracked("export_diagnostics", lane = "diagnostics") {
        val bytes = diagnosticsService.exportJson()
        publish(action = "export_diagnostics", transform = {
            it.copy(lastResultJson = bytes.toString(Charsets.UTF_8))
        })
    }

    fun exportDiagnostics(): String = ReiAnixDiagnosticTimeline.snapshotJson().toString()

    fun recordDiagnostic(
        name: String,
        severity: String = "INFO",
        result: String? = null,
        error: Throwable? = null,
        context: Map<String, Any?> = emptyMap(),
    ) {
        ReiAnixDiagnosticTimeline.record(
            name = name,
            severity = severity,
            source = "NativeMainRuntimePort",
            result = result,
            error = error,
            context = context,
        )
    }


    private val settingLocks = ConcurrentHashMap<String, Mutex>()

    /** Serialize writes to the same preference key, mirroring the old per-setting asyncio locks. */
    fun setSettingSerialized(key: String, value: String): Boolean {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return false
        val keyLock = settingLocks.getOrPut(normalizedKey) { Mutex() }
        return launchTracked("set_setting", lane = "settings") {
            keyLock.withLock {
                settingsViewModel.setSetting(normalizedKey, value)
            }
        }
    }

    /** Settings actions are delegated to the canonical Settings ViewModel/repository. */
    fun requestSettingsActionTracked(action: String): Boolean {
        val normalized = action.trim()
        if (normalized.isEmpty()) return false
        return launchTracked("settings_action", lane = "settings") {
            settingsViewModel.requestAction(normalized)
        }
    }
    /** Validate the entire update before committing any settings row. */
    fun applySettingsAtomically(values: Map<String, String>): Boolean {
        if (values.isEmpty() || values.size > ReiAnixSettingsContract.definitions.size) return false
        val normalized = linkedMapOf<String, String>()
        for ((rawKey, rawValue) in values) {
            val key = rawKey.trim()
            val definition = ReiAnixSettingsContract.definitions[key] ?: return false
            val value = runCatching { definition.normalize(rawValue) }.getOrNull() ?: return false
            normalized[key] = value
        }
        if (normalized.isEmpty()) return false
        return launchTracked("settings_bulk_applied", lane = "settings") {
            dependencies.sqliteSettingsRepository.setPreferencesAtomically(normalized)
            settingsViewModel.refresh()
            publish(
                action = "settings_bulk_applied",
                transform = {
                    it.copy(
                        lastResultJson = org.json.JSONObject()
                            .put("applied", normalized.size)
                            .put("keys", org.json.JSONArray(normalized.keys.toList()))
                            .toString(),
                    )
                },
            )
        }
    }

    fun resetSettingToDefault(key: String): Boolean {
        val definition = ReiAnixSettingsContract.definitions[key.trim()] ?: return false
        return setSettingSerialized(definition.key, definition.defaultValue)
    }

    fun updateSettingsRuntime(key: String, value: String): Boolean =
        setSettingSerialized(key, value)

    fun accountChanged(state: String, reason: String = "account_changed"): Boolean =
        launchTracked("account_changed", lane = "settings") {
            setAccountState(state, reason)
            settingsViewModel.refresh()
            libraryViewModel.refresh()
        }

    fun requestRecoveryDiagnostic(): Boolean = exportDiagnosticsJson()

    fun createRecoverySnapshot(): Boolean = createRecoverySafetySnapshot()

    fun restoreRecoveryFromPrivateFile(file: File, previewOnly: Boolean = false): Boolean {
        val candidate = resolvePrivateBackupFile(file.absolutePath) ?: return false
        return if (previewOnly) inspectBackup(candidate) else restoreRecoveryBackup(candidate)
    }

    fun integrityReport(): Boolean = checkDatabaseIntegrity()

    fun refreshCatalogAfterRestore(): Boolean = reconcileRestoredFileAvailability()

    fun refreshCurrentMetadata(animeId: Long): Boolean = refreshAniListMetadata(animeId)

    fun scheduleMetadataHydration(): Boolean = reconcileCatalogMetadata()

    fun scheduleThumbnailReconciliation(): Boolean = reconcileMissingThumbnails()


    fun requestAccountActionTracked(action: String): Boolean {
        val normalized = action.trim()
        if (normalized.isEmpty()) return false
        return launchTracked("account_action", lane = "settings") {
            settingsViewModel.requestAccountAction(normalized)
        }
    }

    /** Native scanner snapshots replace the old Python ScanCoordinator projection. */
    fun nativeScanSnapshots() = NativeScanController.progress.value

    fun nativeScanSnapshot(scanId: String) = NativeScanController.snapshot(scanId)

    fun beginNativeScan(scanId: String, sourceKey: String? = null): Boolean =
        NativeScanController.begin(scanId, sourceKey)

    fun launchNativeScan(scanId: String, block: suspend CoroutineScope.() -> Unit): Boolean =
        NativeScanController.launch(scanId, block)

    fun cancelNativeScan(scanId: String, reason: String = "Solicitado pelo usuário"): Boolean =
        NativeScanController.requestCancel(scanId, reason)

    fun cancelAllNativeScans(): List<String> = NativeScanController.cancelAll()

    fun finishNativeScan(scanId: String) = NativeScanController.finish(scanId)
    fun updateNativeScanProgress(scanId: String, payload: org.json.JSONObject) =
        NativeScanController.updateProgress(scanId, payload)

    fun completeNativeScan(
        scanId: String,
        status: String,
        payload: org.json.JSONObject = org.json.JSONObject(),
    ) = NativeScanController.complete(scanId, status, payload)

    fun nativeScanIsRunning(sourceKey: String): Boolean =
        NativeScanController.isRunning(sourceKey)

    fun activeNativeScanId(sourceKey: String): String? =
        NativeScanController.activeScanId(sourceKey)


    /** Catalog metadata and artwork reconciliation are owned by the canonical Kotlin ViewModel/services. */
    fun reconcileCatalogMetadata(): Boolean = launchTracked("reconcile_catalog_metadata", lane = "library") {
        val refreshed = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
        if (!refreshed) {
            throw IllegalStateException("A reconciliação de metadados foi bloqueada pela validação do banco.")
        }
        libraryViewModel.queryNativeArtwork()
    }

    fun reconcileMissingThumbnails(): Boolean = launchTracked("reconcile_thumbnails", lane = "library") {
        libraryViewModel.queryNativeArtwork()
    }

    /** Port of the explicit SAF actions; selecting or removing a root remains user-driven. */
    fun requestSafFolderSelection() {
        libraryViewModel.selectSafTree()
        publish(action = "saf_folder_selection")
    }

    fun removeConfiguredSafFolder(reference: String) {
        val normalized = reference.trim()
        if (normalized.isNotEmpty()) {
            libraryViewModel.removeSafTree(normalized)
            publish(action = "remove_saf_folder")
        }
    }

    fun dismissNativeStorageOnboarding() {
        libraryViewModel.dismissStorageOnboarding()
        activity.dismissNativeStorageOnboarding()
        publish(action = "dismiss_storage_onboarding")
    }

    fun nativeStorageAction(action: String): Boolean =
        requestStorageAction(action)

    fun performanceSnapshot(): String = PerformanceDiagnostics.snapshotJson().toString()

    /** Metadata-only AniList access delegates to the existing native integration service. */
    suspend fun searchAniList(term: String, limit: Int = 10) =
        dependencies.aniListIntegrationService.search(term, limit.coerceIn(1, 50))

    suspend fun lookupAniList(anilistId: Int) =
        dependencies.aniListIntegrationService.lookupAnimeById(anilistId)

    suspend fun associateAniList(animeId: Long, anilistId: Int) =
        dependencies.aniListIntegrationService.associateManually(animeId, anilistId)

    suspend fun autoMatchAniList(animeId: Long) =
        dependencies.aniListIntegrationService.autoMatch(animeId)

    fun refreshAniListMetadata(animeId: Long): Boolean {
        if (animeId <= 0L) return false
        return launchTracked("refresh_anilist_metadata", lane = "metadata") {
            val result = dependencies.aniListIntegrationService.refreshMetadata(animeId)
            publish(action = "refresh_anilist_metadata", transform = {
                it.copy(lastResultJson = result.toString())
            })
        }
    }

    fun reconcileUnmatchedAniListMetadata(): Boolean =
        launchTracked("reconcile_unmatched_metadata", lane = "metadata") {
            val updated = dependencies.aniListIntegrationService.reconcileUnmatchedLibraryMetadata()
            publish(action = "reconcile_unmatched_metadata", transform = {
                it.copy(lastResultJson = org.json.JSONObject().put("updated", updated).toString())
            })
        }

    fun requestAnimeArtwork(animeId: Long, force: Boolean = false): Boolean {
        if (animeId <= 0L) return false
        val accepted = dependencies.artworkService.requestAnimeArtwork(animeId, force)
        publish(action = "request_anime_artwork", error = if (accepted) null else "Solicitação de artwork não aceita.")
        return accepted
    }

    fun requestPendingArtwork(): Boolean {
        val accepted = dependencies.artworkService.requestPendingArtwork()
        publish(action = "request_pending_artwork", error = if (accepted) null else "Não há solicitação pendente aceita.")
        return accepted
    }

    fun clearManagedArtworkCache(): Boolean =
        launchTracked("clear_managed_artwork_cache", lane = "metadata") {
            val removed = dependencies.artworkService.clearManagedCache()
            libraryViewModel.refresh()
            publish(action = "clear_managed_artwork_cache", transform = {
                it.copy(lastResultJson = org.json.JSONObject().put("removed", removed).toString())
            })
        }

    suspend fun timelineGroups() = libraryViewModel.queryNativeTimelineGroups()
    suspend fun durationAnomalies(animeId: Long? = null) =
        libraryViewModel.queryNativeDurationAnomalies(animeId)
    suspend fun marathonPlan(animeId: Long, currentPath: String? = null) =
        libraryViewModel.queryNativeMarathonPlan(animeId, currentPath)

    suspend fun personalListItems() = libraryViewModel.personalListItems()
    suspend fun addToPersonalList(animeId: Long) = libraryViewModel.addToPersonalList(animeId)
    suspend fun removeFromPersonalList(animeId: Long) = libraryViewModel.removeFromPersonalList(animeId)

    fun classifyMissingLocalGenres(): Boolean =
        launchTracked("classify_missing_local_genres", lane = "library") {
            libraryViewModel.classifyMissingLocalGenres()
        }

    fun refreshNativeFolders() {
        libraryViewModel.refreshNativeFolders()
        publish(action = "refresh_native_folders")
    }

    suspend fun listSafDirectory(uri: android.net.Uri) = libraryViewModel.listSafDirectory(uri)
    suspend fun createSafDirectory(parentUri: android.net.Uri, name: String) =
        libraryViewModel.createSafDirectory(parentUri, name)
    suspend fun renameSafItem(uri: android.net.Uri, newName: String) =
        libraryViewModel.renameSafItem(uri, newName)
    suspend fun moveSafItem(uri: android.net.Uri, destinationUri: android.net.Uri) =
        libraryViewModel.moveSafItem(uri, destinationUri)
    suspend fun deleteSafItem(uri: android.net.Uri) = libraryViewModel.deleteSafItem(uri)

    /** Home refresh transitions are separated from scanner truth; a callback alone never implies a complete scan. */
    /**
     * Explicit Home refresh coordinator. A refresh is successful only after a new
     * native scan reaches a terminal result and the canonical catalog refresh is
     * accepted. Lifecycle resume alone never invokes this method.
     */
    fun requestHomeRefresh(source: String = "button"): Boolean {
        if (HomeRefreshCoordinator.state.value.active) return false
        val requestId = UUID.randomUUID().toString()
        val refreshId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val knownOperations = NativeScanController.progress.value.keys.toSet()
        if (!HomeRefreshCoordinator.begin(requestId, refreshId, source)) return false
        if (!requestLibraryScan()) {
            HomeRefreshCoordinator.fail(requestId, "native_scan_request_rejected")
            return false
        }
        synchronized(lock) {
            homeRefreshJob?.cancel()
            homeRefreshJob = activity.lifecycleScope.launch {
                try {
                    val firstBatch = withTimeoutOrNull(HOME_REFRESH_SCAN_START_TIMEOUT_MS) {
                        NativeScanController.progress
                            .map { snapshots ->
                                snapshots.values.filter {
                                    it.operationId !in knownOperations && it.startedAtMillis >= startedAt
                                }
                            }
                            .first { it.isNotEmpty() }
                    }
                    if (firstBatch.isNullOrEmpty()) {
                        HomeRefreshCoordinator.fail(requestId, "native_scan_start_timeout")
                        recordDiagnostic("HOME_REFRESH_FAILED", result = "native_scan_start_timeout",
                            requestId = requestId, context = mapOf("source" to source))
                        return@launch
                    }
                    HomeRefreshCoordinator.markScanStarted(requestId)
                    recordDiagnostic("HOME_REFRESH_SCAN_STARTED", requestId = requestId, refreshId = refreshId,
                        context = mapOf("source" to source, "operation_count" to firstBatch.size))
                    var stableTerminalBatch: List<ScanProgressSnapshot> = emptyList()
                    while (true) {
                        val current = NativeScanController.progress.value.values.filter {
                            it.operationId !in knownOperations && it.startedAtMillis >= startedAt
                        }
                        if (current.isNotEmpty() && current.all { it.state.isTerminal }) {
                            delay(HOME_REFRESH_TERMINAL_SETTLE_MS)
                            val afterSettle = NativeScanController.progress.value.values.filter {
                                it.operationId !in knownOperations && it.startedAtMillis >= startedAt
                            }
                            if (afterSettle.isNotEmpty() && afterSettle.all { it.state.isTerminal }) {
                                stableTerminalBatch = afterSettle
                                break
                            }
                        }
                        delay(HOME_REFRESH_MONITOR_INTERVAL_MS)
                    }
                    if (stableTerminalBatch.any { it.state == ScanLifecycleState.FAILED }) {
                        HomeRefreshCoordinator.fail(requestId, "native_scan_failed")
                        recordDiagnostic("HOME_REFRESH_FAILED", severity = "WARN", requestId = requestId,
                            refreshId = refreshId, result = "native_scan_failed",
                            context = mapOf("terminal_states" to stableTerminalBatch.joinToString { it.state.name }))
                        return@launch
                    }
                    if (stableTerminalBatch.any { it.state == ScanLifecycleState.CANCELLED }) {
                        HomeRefreshCoordinator.cancel(requestId, "native_scan_cancelled")
                        recordDiagnostic("HOME_REFRESH_CANCELLED", severity = "WARN", requestId = requestId,
                            refreshId = refreshId, result = "native_scan_cancelled")
                        return@launch
                    }
                    if (!HomeRefreshCoordinator.markScanTerminal(requestId)) {
                        throw IllegalStateException("Home refresh refused a non-running terminal scan transition.")
                    }
                    recordDiagnostic("HOME_REFRESH_SCAN_COMPLETED", requestId = requestId, refreshId = refreshId,
                        context = mapOf(
                            "operation_count" to stableTerminalBatch.size,
                            "partial_count" to stableTerminalBatch.count { it.state == ScanLifecycleState.PARTIAL },
                        ))
                    val catalogAccepted = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
                    if (!catalogAccepted) {
                        HomeRefreshCoordinator.fail(requestId, "canonical_catalog_refresh_rejected")
                        return@launch
                    }
                    libraryViewModel.refresh()
                    if (!HomeRefreshCoordinator.markCatalogUpdated(requestId)) {
                        throw IllegalStateException("Home refresh refused catalog update before scan completion.")
                    }
                    composeHost.show(ReiAnixRoutes.HOME)
                    if (!HomeRefreshCoordinator.complete(requestId)) {
                        throw IllegalStateException("Home refresh refused completion before UI commit.")
                    }
                    recordDiagnostic("HOME_REFRESH_COMPLETED", requestId = requestId, refreshId = refreshId,
                        context = mapOf(
                            "elapsed_ms" to (System.currentTimeMillis() - startedAt),
                            "partial_count" to stableTerminalBatch.count { it.state == ScanLifecycleState.PARTIAL },
                        ))
                } catch (cancelled: CancellationException) {
                    HomeRefreshCoordinator.cancel(requestId, "runtime_cancelled")
                    throw cancelled
                } catch (failure: Throwable) {
                    HomeRefreshCoordinator.fail(requestId, failure.message ?: "runtime_failure")
                    recordDiagnostic("HOME_REFRESH_FAILED", severity = "ERROR", requestId = requestId,
                        refreshId = refreshId, error = failure)
                } finally {
                    synchronized(lock) {
                        if (homeRefreshJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                            homeRefreshJob = null
                        }
                    }
                }
            }
        }
        return true
    }

    fun cancelHomeRefreshOperation(reason: String = "cancelled"): Boolean {
        val current = HomeRefreshCoordinator.state.value
        if (!current.active || current.requestId.isNullOrBlank()) return false
        homeRefreshJob?.cancel()
        val cancelled = HomeRefreshCoordinator.cancel(current.requestId, reason)
        if (cancelled) recordDiagnostic("HOME_REFRESH_CANCELLED", severity = "WARN",
            requestId = current.requestId, refreshId = current.refreshId, result = reason)
        return cancelled
    }

    private companion object HomeRefreshTiming {
        const val HOME_REFRESH_SCAN_START_TIMEOUT_MS = 15_000L
        const val HOME_REFRESH_TERMINAL_SETTLE_MS = 250L
        const val HOME_REFRESH_MONITOR_INTERVAL_MS = 100L
        const val MAX_PENDING_THUMBNAIL_REQUESTS = 512
    }

    fun beginHomeRefresh(requestId: String, refreshId: String = requestId, source: String = "native"): Boolean =
        HomeRefreshCoordinator.begin(requestId, refreshId, source)

    fun markHomeRefreshScanStarted(requestId: String): Boolean =
        HomeRefreshCoordinator.markScanStarted(requestId)

    fun markHomeRefreshScanTerminal(requestId: String): Boolean =
        HomeRefreshCoordinator.markScanTerminal(requestId)

    fun markHomeRefreshCatalogUpdated(requestId: String): Boolean =
        HomeRefreshCoordinator.markCatalogUpdated(requestId)

    fun completeHomeRefresh(requestId: String): Boolean =
        HomeRefreshCoordinator.complete(requestId)

    fun failHomeRefresh(requestId: String, reason: String): Boolean =
        HomeRefreshCoordinator.fail(requestId, reason)

    fun cancelHomeRefresh(requestId: String, reason: String = "cancelled"): Boolean =
        HomeRefreshCoordinator.cancel(requestId, reason)

    fun homeRefreshSnapshot(): HomeRefreshSnapshot = HomeRefreshCoordinator.state.value

    /** StateFlow-driven Android screens invalidate by publishing catalog state, not rebuilding a Flet view tree. */
    fun onCatalogChanged(refreshDetailsForAnimeId: Long? = null): Boolean {
        if (refreshDetailsForAnimeId != null && refreshDetailsForAnimeId > 0L) {
            libraryViewModel.refreshDetails(refreshDetailsForAnimeId)
        }
        return refreshCanonicalCatalog()
    }

    fun onMetadataChanged(animeId: Long, requestArtwork: Boolean = true): Boolean {
        if (animeId <= 0L) return false
        return launchTracked("metadata_changed", lane = "metadata") {
            val metadata = dependencies.aniListIntegrationService.refreshMetadata(animeId)
            if (requestArtwork) dependencies.artworkService.requestAnimeArtwork(animeId)
            publish(action = "metadata_changed", transform = { it.copy(lastResultJson = metadata.toString()) })
        }
    }

    fun clearRuntimeSelection() {
        clearSelectedAnime()
        publish(action = "clear_runtime_selection", transform = { it.copy(route = ReiAnixRoutes.HOME) })
    }

    fun setManualEpisodeIdentification(
        episodeId: Long,
        season: Int?,
        number: Double?,
        episodeType: String,
        title: String?,
    ): Boolean {
        if (episodeId <= 0L) return false
        return launchTracked("manual_episode_identification", lane = "library") {
            libraryViewModel.setEpisodeManualIdentificationNative(episodeId, season, number, episodeType, title)
        }
    }

    fun dismissPlayerTransition(originRequestId: String, originPlayerSessionId: String? = null) =
        revokePlayerTransition(originRequestId, originPlayerSessionId)

    private fun resolvePrivateBackupFile(reference: String): File? {
        val raw = reference.trim()
        if (raw.isEmpty()) return null
        val candidate = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        val roots = listOf(
            File(activity.filesDir, "data/backups"),
            File(activity.filesDir, "data"),
            activity.cacheDir,
        ).mapNotNull { runCatching { it.canonicalFile.toPath() }.getOrNull() }
        val path = runCatching { candidate.toPath() }.getOrNull() ?: return null
        if (roots.none { path.startsWith(it) }) return null
        if (!candidate.isFile || candidate.length() <= 0L) return null
        return candidate
    }

    /**
     * Typed replacement for the legacy main.py action/event branching. Values
     * are validated at this boundary, and domain writes still run in their
     * canonical Kotlin service. Unknown actions are rejected, not acknowledged.
     */
    fun dispatchMainAction(action: String, payload: org.json.JSONObject): Boolean {
        val normalized = action.trim().lowercase(Locale.ROOT)
        fun positiveLong(key: String): Long? =
            payload.optLong(key, 0L).takeIf { it > 0L }
        fun nonBlank(key: String): String? =
            payload.optString(key, "").trim().takeIf { it.isNotEmpty() }
        return when (normalized) {
            "navigate_home" -> navigateHome()
            "navigate_library" -> navigateLibrary()
            "navigate_organize" -> navigateOrganize()
            "navigate_collector" -> navigateCollector()
            "navigate_settings" -> navigateSettings()
            "navigate_storage" -> navigateStorage()
            "navigate_search" -> navigateSearch()
            "navigate_my_list" -> navigateMyList()
            "navigate_back", "back" -> navigateBack()
            "navigate_settings_category" ->
                navigateSettingsCategory(nonBlank("category") ?: return false)

            "refresh_library" -> refreshLibrary()
            "refresh_catalog", "refresh_canonical_catalog" -> refreshCanonicalCatalog()
            "refresh_settings" -> refreshSettingsIfActive()
            "close_home_search" -> {
                closeHomeSearch()
                true
            }
            "home_search_query" -> {
                updateHomeSearchQuery(payload.optString("query", ""))
                true
            }

            "login" -> login()
            "logout" -> logout()
            "switch_account", "switchaccount" -> switchAccount()
            "account_state_changed" -> {
                setAccountState(payload.optString("state", "disconnected"), "native_event")
                true
            }

            "select_saf", "select_tree", "add_folder" -> selectSafFolder()
            "request_media_access", "request_video_access" -> requestMediaAccess()
            "check_storage_access", "check_video_access" -> checkStorageAccess()
            "open_broad_storage_settings", "request_storage_access" -> openBroadStorageSettings()
            "scan_library" -> requestLibraryScan()
            "refresh_home_library", "request_home_refresh" -> requestHomeRefresh(
                source = nonBlank("source") ?: "native_action",
            )
            "remove_folder", "remove_saf_tree", "release_tree" ->
                (nonBlank("treeUri") ?: nonBlank("tree_uri") ?: nonBlank("reference"))?.let {
                    removeConfiguredSafFolder(it)
                    true
                } ?: false

            "set_setting", "compose_settings_set" -> {
                val key = nonBlank("key") ?: nonBlank("setting_key") ?: return false
                val value = if (payload.has("value")) payload.opt("value")?.toString().orEmpty()
                    else payload.optString("setting_value", "")
                setSettingSerialized(key, value)
            }
            "settings_action", "compose_settings_action" ->
                (nonBlank("action") ?: nonBlank("setting_action"))?.let(::requestSettingsActionTracked) ?: false
            "account_action", "compose_account_action", "compose_settings_account_action" ->
                (nonBlank("accountAction") ?: nonBlank("account_action"))?.let(::requestAccountActionTracked) ?: false
            "set_theme" -> setSettingSerialized("appearance.theme", payload.optString("theme", "dark"))

            "open_episode", "play_episode" -> {
                val episodeId = positiveLong("episodeId") ?: positiveLong("episode_id") ?: return false
                val animeId = positiveLong("animeId") ?: positiveLong("anime_id")
                openEpisode(episodeId, animeId) != null
            }
            "toggle_favorite", "toggle_personal_list" ->
                positiveLong("animeId")?.let {
                    libraryViewModel.toggleFavorite(it)
                    true
                } ?: false
            "mark_episode_watched", "set_episode_watched" -> {
                val id = positiveLong("episodeId") ?: positiveLong("episode_id") ?: return false
                markEpisodeWatched(id, payload.optBoolean("watched", true))
                true
            }
            "toggle_pinned", "toggle_pin" ->
                positiveLong("animeId")?.let(::togglePinned) ?: false
            "save_personal_note", "set_note" -> {
                val id = positiveLong("animeId") ?: positiveLong("anime_id") ?: return false
                savePersonalNote(id, payload.optString("note", ""))
            }
            "save_user_tags", "set_tags" -> {
                val id = positiveLong("animeId") ?: positiveLong("anime_id") ?: return false
                val raw = payload.optJSONArray("tags") ?: return false
                val tags = (0 until raw.length()).mapNotNull { index ->
                    raw.optString(index, "").trim().takeIf { it.isNotEmpty() }
                }
                saveUserTags(id, tags)
            }
            "set_episode_manual_identification" -> {
                val id = positiveLong("episodeId") ?: positiveLong("episode_id") ?: return false
                val season = if (payload.has("season")) payload.optInt("season") else null
                val number = if (payload.has("number")) payload.optDouble("number") else null
                val kind = payload.optString("episodeType", payload.optString("episode_type", "regular"))
                val title = nonBlank("title")
                setManualEpisodeIdentification(id, season, number, kind, title)
            }
            "query_anime" -> positiveLong("animeId")?.let(::queryNativeAnime) ?: false
            "query_episodes" -> positiveLong("animeId")?.let(::queryNativeEpisodes) ?: false
            "query_episode" -> positiveLong("episodeId")?.let(::queryNativeEpisode) ?: false
            "query_recently_played" -> queryNativeRecentlyPlayed()
            "query_folders" -> queryNativeFolders()
            "query_artwork" -> queryNativeArtwork()
            "query_marathon_plan" -> positiveLong("animeId")?.let {
                queryMarathonPlan(it, nonBlank("currentPath") ?: nonBlank("current_path"))
            } ?: false
            "refresh_details" -> positiveLong("animeId")?.let(::refreshCurrentDetails) ?: false
            "refresh_anilist_metadata", "refresh_current_metadata", "refresh_metadata" ->
                positiveLong("animeId")?.let(::refreshAniListMetadata) ?: false
            "resolve_match", "associate_anilist" -> {
                val animeId = positiveLong("animeId") ?: positiveLong("anime_id") ?: return false
                val remoteId = positiveLong("anilistId") ?: positiveLong("anilist_id") ?: return false
                launchTracked("associate_anilist", lane = "metadata") {
                    val result = dependencies.aniListIntegrationService.associateManually(animeId, remoteId.toInt())
                    publish(action = "associate_anilist", transform = { it.copy(lastResultJson = result.toString()) })
                }
            }
            "reconcile_unmatched_metadata" -> reconcileUnmatchedAniListMetadata()
            "request_anime_artwork" -> positiveLong("animeId")?.let {
                requestAnimeArtwork(it, payload.optBoolean("force", false))
            } ?: false
            "request_pending_artwork" -> requestPendingArtwork()
            "clear_artwork_cache" -> clearManagedArtworkCache()
            "request_missing_thumbnail", "request_thumbnail" ->
                (nonBlank("uri") ?: nonBlank("path") ?: nonBlank("mediaUri"))?.let {
                    enqueueLocalThumbnail(
                        reference = it,
                        size = payload.optLong("size", 0L),
                        modifiedAt = payload.optLong("modifiedAt", payload.optLong("modified_at", 0L)),
                        mediaIdentity = nonBlank("mediaIdentity") ?: nonBlank("media_identity"),
                        priority = payload.optInt("priority", 0),
                    )
                } ?: false
            "extract_local_thumbnail" ->
                (nonBlank("uri") ?: nonBlank("path") ?: nonBlank("mediaUri"))?.let {
                    extractLocalThumbnail(
                        reference = it,
                        size = payload.optLong("size", 0L),
                        modifiedAt = payload.optLong("modifiedAt", payload.optLong("modified_at", 0L)),
                        mediaIdentity = nonBlank("mediaIdentity") ?: nonBlank("media_identity"),
                    )
                } ?: false
            "classify_missing_local_genres" -> classifyMissingLocalGenres()
            "open_marathon" -> positiveLong("animeId")?.let {
                queryMarathonPlan(it, nonBlank("currentPath") ?: nonBlank("current_path"))
            } ?: false
            "clear_runtime_selection" -> {
                clearRuntimeSelection()
                true
            }
            "request_restore_reconciliation", "reconcile_after_restore" -> reconcileRestoredFileAvailability()

            "create_backup" -> createBackup()
            "inspect_backup" -> nonBlank("archivePath")?.let(::resolvePrivateBackupFile)?.let(::inspectBackup) ?: false
            "restore_backup" -> nonBlank("archivePath")?.let(::resolvePrivateBackupFile)?.let(::restoreBackup) ?: false
            "create_recovery_safety_snapshot" -> createRecoverySafetySnapshot()
            "restore_recovery_backup" -> nonBlank("archivePath")?.let(::resolvePrivateBackupFile)?.let(::restoreRecoveryBackup) ?: false
            "integrity_check" -> checkDatabaseIntegrity()
            "reconcile_after_restore" -> reconcileRestoredFileAvailability()
            "export_diagnostics" -> exportDiagnosticsJson()

            "cancel_scan", "cancel_native_scan", "scan_cancel" -> {
                val id = nonBlank("scanId") ?: nonBlank("scan_id") ?: return false
                cancelNativeScan(id, nonBlank("reason") ?: "cancelled")
            }
            "native_scan_progress" -> {
                val id = nonBlank("scanId") ?: nonBlank("scan_id") ?: return false
                val stats = payload.optJSONObject("payload") ?: payload
                updateNativeScanProgress(id, stats)
                true
            }
            "native_scan_terminal" -> {
                val id = nonBlank("scanId") ?: nonBlank("scan_id") ?: return false
                val status = nonBlank("status") ?: return false
                val stats = payload.optJSONObject("payload") ?: payload
                completeNativeScan(id, status, stats)
                true
            }
            "cancel_player_transition" -> {
                val origin = nonBlank("originRequestId") ?: nonBlank("origin_request_id") ?: return false
                revokePlayerTransition(origin, nonBlank("originPlayerSessionId") ?: nonBlank("origin_player_session_id"))
                true
            }
            "diagnostic" -> {
                recordDiagnostic(
                    name = nonBlank("name") ?: "NATIVE_MAIN_DIAGNOSTIC",
                    severity = payload.optString("severity", "INFO"),
                    result = nonBlank("result"),
                    context = payload.optJSONObject("context")?.let { context ->
                        buildMap {
                            val keys = context.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, context.opt(key))
                            }
                        }
                    } ?: emptyMap(),
                )
                true
            }
            else -> {
                Log.w("ReiAnixMainPort", "Rejected unknown main action='$normalized'")
                false
            }
        }
    }

    fun onLifecycleEvent(eventOrState: Any?) {
        NativeLifecycleCoordinator.onEvent(eventOrState)
    }

    fun cancelBackgroundTasksForLane(lane: String) {
        val normalized = lane.trim().ifBlank { "general" }
        val copy = synchronized(lock) { jobs.filter { jobLanes[it] == normalized } }
        copy.forEach { it.cancel() }
        publish(action = "cancel_background_tasks_" + normalized)
    }

    fun cancelBackgroundTasks() {
        val copy = synchronized(lock) { jobs.toList() }
        copy.forEach { it.cancel() }
        lifecycleIsAlive = false
        publish(action = "cancel_background_tasks")
    }

    fun resumeRuntimeTasks() {
        lifecycleIsAlive = true
        publish(action = "resume_runtime_tasks")
    }
}
