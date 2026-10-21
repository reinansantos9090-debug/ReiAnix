package com.reiflix.reiflix_local

import androidx.activity.ComponentActivity
import android.util.Log
import android.graphics.BitmapFactory
import androidx.lifecycle.lifecycleScope
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.backup.ReiAnixDiagnosticsService
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsFileService
import java.util.UUID
import com.reiflix.reiflix_local.data.settings.ReiAnixSettingsContract
import com.reiflix.reiflix_local.data.sqlite.getAnimeByLookupTitle
import com.reiflix.reiflix_local.data.sqlite.findEpisodeByPathOrFileUri
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
import java.util.LinkedHashSet
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.scanner.NativeScanController
import com.reiflix.reiflix_local.scanner.SafScanner
import android.os.Environment
import com.reiflix.reiflix_local.scanner.MediaStoreScanner
import com.reiflix.reiflix_local.scanner.ScanProgressSnapshot
import com.reiflix.reiflix_local.scanner.ScanLifecycleState
import com.reiflix.reiflix_local.scanner.BroadStorageScanner
import com.reiflix.reiflix_local.storage.VideoThumbnailExtractor
import com.reiflix.reiflix_local.storage.SafAccessLevel
import com.reiflix.reiflix_local.storage.StorageAuthorization
import android.net.Uri
import android.provider.DocumentsContract
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
            if (!recoveryRequired) {
                val runtimePort = NativeMainRuntimePort(
                    activity = activity,
                    libraryViewModel = libraryViewModel,
                    settingsViewModel = settingsViewModel,
                    composeHost = host,
                )
                runtimePort.onRuntimeReconnect()
                runtimePort.scheduleThumbnailReconciliation("startup_ready")
            }
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

internal data class NativeMainViewShell(
    val route: String,
    val viewRoute: String,
    val key: String,
    val generation: Long,
    val stateSignature: String,
    val settingsPath: List<String> = emptyList(),
)

internal data class NativeMainOperationResult(
    val requestId: String,
    val action: String,
    val status: String,
    val operationState: String,
    val message: String? = null,
    val error: String? = null,
    val payloadJson: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

internal data class NativeMainScanUiState(
    val state: String = "IDLE",
    val source: String? = null,
    val scanId: String? = null,
    val found: Long = 0L,
    val files: Long = 0L,
    val directories: Long = 0L,
    val error: String? = null,
    val timestamp: Long? = null,
) {
    val isTerminal: Boolean
        get() = state in setOf("COMPLETED", "CANCELLED", "FAILED", "PARTIAL")
}

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
    val hasMediaStorePermission: Boolean
        get() = mediaStoreAccess.equals("full", ignoreCase = true) ||
            mediaStoreAccess.equals("partial", ignoreCase = true)

    /** The library index is SAF-root scoped; MediaStore/broad permission alone is not a configured library. */
    val hasConfiguredLibrarySource: Boolean
        get() = configuredSafRoots.isNotEmpty()

    val hasAnyAuthorizedSource: Boolean
        get() = hasMediaStorePermission || broadStorageAccess || hasConfiguredLibrarySource
}

private data class PendingNativeStorageOperation(
    val nativeAction: String,
    val resultAction: String,
)

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

private data class NativeThumbnailCandidateRow(
    val id: Long,
    val reference: String,
    val size: Long,
    val modifiedAt: Long,
    val mediaIdentity: String?,
    val durationSeconds: Double,
    val thumbnailPath: String?,
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
    private val seenProjectionEventIds = LinkedHashSet<String>()
    private val claimedRequestIds = mutableMapOf<String, LinkedHashSet<String>>()
    private val jobs = linkedSetOf<Job>()
    private val jobLanes = mutableMapOf<Job, String>()
    private val maxBackgroundTasksPerLane = 8
    private val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
    private val mutableSnapshot = MutableStateFlow(NativeMainRuntimeSnapshot())
    val snapshot: StateFlow<NativeMainRuntimeSnapshot> = mutableSnapshot.asStateFlow()
    private val mutableOperationResult = MutableStateFlow<NativeMainOperationResult?>(null)
    val operationResult: StateFlow<NativeMainOperationResult?> = mutableOperationResult.asStateFlow()
    private val mutableScanUiState = MutableStateFlow(NativeMainScanUiState())
    val scanUiState: StateFlow<NativeMainScanUiState> = mutableScanUiState.asStateFlow()
    private val mutableStorageState = MutableStateFlow<NativeMainStorageSnapshot?>(null)
    val storageState: StateFlow<NativeMainStorageSnapshot?> = mutableStorageState.asStateFlow()
    private val pendingStorageOperations = ConcurrentHashMap<String, PendingNativeStorageOperation>()
    private val metadataHydrationRequests = Channel<String>(Channel.CONFLATED)
    private val thumbnailReconciliationRequests = Channel<String>(Channel.CONFLATED)
    @Volatile private var lastStorageMutationAtMillis: Long = 0L
    @Volatile private var pendingSafSelectionStartedAtMillis: Long = 0L
    @Volatile private var pendingSafSelectionRequestId: String? = null
    @Volatile private var pendingSafSelectionBridge: String = "library"
    @Volatile private var pendingSafSelectionBaselineIdentities: Set<String> = emptySet()
    @Volatile private var pendingSafRemovalRequestId: String? = null
    @Volatile private var pendingSafRemovalReference: String? = null
    @Volatile private var pendingSafRemovalBridge: String = "library"
    private var scanObserverJob: Job? = null
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
    // Bounded retry state mirrors the legacy thumbnail retry_after_error task.
    private val thumbnailRetryCounts = ConcurrentHashMap<String, Int>()
    private val thumbnailRetryPendingKeys = ConcurrentHashMap.newKeySet<String>()
    // Older source-version requests for the same URI must not overtake the latest queued request.
    private val thumbnailLatestKeyByUri = ConcurrentHashMap<String, String>()
    private val thumbnailLatestAtMillis = ConcurrentHashMap<String, Long>()
    private val thumbnailInFlight = ConcurrentHashMap.newKeySet<String>()
    private val thumbnailSemaphore = Semaphore(2)
    @Volatile private var thumbnailPumpActive = false
    @Volatile private var thumbnailPumpJob: Job? = null
    @Volatile private var homeRefreshJob: Job? = null
    private val mutableUiPreferences = MutableStateFlow(loadDurableUiPreferences())
    val uiPreferences: StateFlow<NativeMainUiPreferences> = mutableUiPreferences.asStateFlow()

    init {
        scanObserverJob = activity.lifecycleScope.launch {
            NativeScanController.progress.collect { snapshots ->
                updateScanUiState(snapshots.values)
            }
        }
        activity.lifecycleScope.launch {
            for (reason in metadataHydrationRequests) {
                try {
                    val changed = dependencies.aniListIntegrationService.reconcileUnmatchedLibraryMetadata()
                    if (changed > 0) libraryViewModel.refresh()
                    recordDiagnostic(
                        name = "CATALOG_METADATA_HYDRATION_FINISHED",
                        result = if (changed > 0) "updated" else "no_unmatched_metadata",
                        context = mapOf("reason" to reason, "updated_count" to changed),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    recordDiagnostic(
                        name = "CATALOG_METADATA_HYDRATION_FAILED",
                        severity = "WARN",
                        result = reason,
                        error = failure,
                    )
                }
            }
        }
        activity.lifecycleScope.launch {
            for (reason in thumbnailReconciliationRequests) {
                try {
                    reconcileMissingLocalThumbnails(reason)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    recordDiagnostic(
                        name = "THUMBNAIL_RECONCILIATION_FAILED",
                        severity = "WARN",
                        result = reason,
                        error = failure,
                    )
                }
            }
        }
    }

    /** Coalesced native replacement for schedule_catalog_metadata_hydration(). */
    fun scheduleCatalogMetadataHydration(reason: String = "catalog_changed"): Boolean {
        val normalizedReason = reason.trim().take(160).ifEmpty { "catalog_changed" }
        val result = metadataHydrationRequests.trySend(normalizedReason)
        if (result.isFailure) {
            recordDiagnostic(
                name = "CATALOG_METADATA_HYDRATION_NOT_QUEUED",
                severity = "WARN",
                result = normalizedReason,
            )
        }
        return result.isSuccess
    }

    /** Native replacement for the bounded thumbnail-candidate reconciliation worker. */
    fun scheduleThumbnailReconciliation(reason: String): Boolean {
        val normalizedReason = reason.trim().take(160).ifEmpty { "catalog_changed" }
        val result = thumbnailReconciliationRequests.trySend(normalizedReason)
        if (result.isFailure) {
            recordDiagnostic(
                name = "THUMBNAIL_RECONCILIATION_NOT_QUEUED",
                severity = "WARN",
                result = normalizedReason,
            )
        }
        return result.isSuccess
    }

    /**
     * Page the canonical SQLite catalog, not the currently visible Compose page.
     * This preserves the old thumbnail_candidates(after_id, limit=128) behavior
     * and includes episodes whose cached frame exists but duration still needs repair.
     */
    private suspend fun loadThumbnailCandidateRows(
        afterId: Long,
        limit: Int = 128,
    ): List<NativeThumbnailCandidateRow> = withContext(Dispatchers.IO) {
        val pageLimit = limit.coerceIn(1, 128)
        dependencies.sqliteDatabase.read { database ->
            database.rawQuery(
                """
                SELECT
                    e.id,
                    e.path,
                    COALESCE(e.file_size, 0) AS file_size,
                    COALESCE(e.modified_at, 0) AS modified_at,
                    COALESCE(e.media_identity, '') AS media_identity,
                    COALESCE(e.duration, 0) AS duration,
                    (
                        SELECT art.local_path
                        FROM artwork art
                        WHERE art.entity_type = 'episode'
                          AND art.entity_id = CAST(e.id AS TEXT)
                          AND art.artwork_type = 'episode_thumbnail'
                          AND art.local_path IS NOT NULL
                          AND trim(art.local_path) != ''
                          AND COALESCE(art.status, '') != 'failed'
                        ORDER BY art.priority DESC, art.updated_at DESC, art.id DESC
                        LIMIT 1
                    ) AS thumbnail_path
                FROM episodes e
                WHERE e.id > ?
                  AND e.missing = 0
                  AND COALESCE(e.availability_state, 'available') = 'available'
                  AND e.path IS NOT NULL
                  AND trim(e.path) != ''
                ORDER BY e.id
                LIMIT ?
                """.trimIndent(),
                arrayOf(afterId.toString(), pageLimit.toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                        val reference = cursor.getString(cursor.getColumnIndexOrThrow("path"))
                            ?.trim()
                            .orEmpty()
                        if (id <= 0L || reference.isEmpty()) continue
                        val mediaIdentity = cursor.getString(cursor.getColumnIndexOrThrow("media_identity"))
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        add(
                            NativeThumbnailCandidateRow(
                                id = id,
                                reference = reference,
                                size = cursor.getLong(cursor.getColumnIndexOrThrow("file_size")).coerceAtLeast(0L),
                                modifiedAt = cursor.getLong(cursor.getColumnIndexOrThrow("modified_at")).coerceAtLeast(0L),
                                mediaIdentity = mediaIdentity,
                                durationSeconds = cursor.getDouble(cursor.getColumnIndexOrThrow("duration"))
                                    .takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
                                thumbnailPath = cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_path"))
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun hasValidLocalThumbnail(path: String?): Boolean {
        val normalized = path?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val file = File(normalized)
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        }.getOrDefault(false)
    }

    private suspend fun reconcileMissingLocalThumbnails(reason: String) {
        val state = withTimeoutOrNull(20_000L) {
            libraryViewModel.uiState.first { it.status.name != "LOADING" }
        } ?: run {
            recordDiagnostic(
                name = "THUMBNAIL_RECONCILIATION_SKIPPED",
                severity = "WARN",
                result = "catalog_not_ready",
                context = mapOf("reason" to reason),
            )
            return
        }
        if (state.status.name == "ERROR") {
            recordDiagnostic(
                name = "THUMBNAIL_RECONCILIATION_SKIPPED",
                severity = "WARN",
                result = "catalog_read_failed",
                context = mapOf("reason" to reason),
            )
            return
        }

        var cursor = 0L
        var scanned = 0
        var queued = 0
        var skipped = 0
        try {
            while (lifecycleIsAlive && !activity.isFinishing && !activity.isDestroyed) {
                val rows = loadThumbnailCandidateRows(afterId = cursor, limit = 128)
                if (rows.isEmpty()) break
                for (row in rows) {
                    cursor = maxOf(cursor, row.id)
                    scanned += 1
                    if (!lifecycleIsAlive || activity.isFinishing || activity.isDestroyed) return
                    val thumbnailReady = hasValidLocalThumbnail(row.thumbnailPath)
                    if (thumbnailReady && row.durationSeconds > 0.0) continue

                    val identity = row.mediaIdentity ?: "episode:${row.id}"
                    while (lifecycleIsAlive && !activity.isFinishing && !activity.isDestroyed) {
                        if (
                            enqueueLocalThumbnail(
                                reference = row.reference,
                                size = row.size,
                                modifiedAt = row.modifiedAt,
                                mediaIdentity = identity,
                                priority = 100,
                            )
                        ) {
                            queued += 1
                            break
                        }
                        val queueHasRoom = synchronized(thumbnailQueueLock) {
                            thumbnailRequests.size < MAX_PENDING_THUMBNAIL_REQUESTS
                        }
                        if (queueHasRoom) {
                            skipped += 1
                            break
                        }
                        delay(100L)
                    }
                }
                if (rows.size < 128) break
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            recordDiagnostic(
                name = "THUMBNAIL_RECONCILIATION_FAILED",
                severity = "WARN",
                result = reason,
                error = failure,
                context = mapOf("last_episode_id" to cursor),
            )
            return
        }
        recordDiagnostic(
            name = "THUMBNAIL_RECONCILIATION_FINISHED",
            result = "completed",
            context = mapOf(
                "reason" to reason,
                "catalog_episode_count" to scanned,
                "queued" to queued,
                "skipped" to skipped,
            ),
        )
    }

    private fun updateScanUiState(snapshots: Collection<ScanProgressSnapshot>) {
        if (snapshots.isEmpty()) {
            if (!mutableScanUiState.value.isTerminal) mutableScanUiState.value = NativeMainScanUiState()
            return
        }
        val current = mutableScanUiState.value
        val sameOperation = current.scanId?.let { id -> snapshots.firstOrNull { it.operationId == id } }
        if (sameOperation != null && current.isTerminal && !sameOperation.state.isTerminal) {
            recordDiagnostic(
                name = "SCAN_STALE_PROGRESS_IGNORED",
                severity = "WARN",
                result = sameOperation.state.name,
                scanId = sameOperation.operationId,
                context = mapOf("current_state" to current.state),
            )
            return
        }
        val preferred = snapshots.filter { !it.state.isTerminal }.maxByOrNull { it.startedAtMillis }
            ?: snapshots.maxByOrNull { it.finishedAtMillis ?: it.startedAtMillis }
            ?: return
        val uiState = when (preferred.state) {
            ScanLifecycleState.IDLE -> "IDLE"
            ScanLifecycleState.PREPARING -> "CHECKING"
            ScanLifecycleState.RUNNING, ScanLifecycleState.CANCELLATION_REQUESTED -> "SCANNING"
            ScanLifecycleState.CANCELLED -> "CANCELLED"
            ScanLifecycleState.SUCCEEDED -> if (preferred.partial) "PARTIAL" else "COMPLETED"
            ScanLifecycleState.PARTIAL -> "PARTIAL"
            ScanLifecycleState.FAILED -> "FAILED"
        }
        mutableScanUiState.value = NativeMainScanUiState(
            state = uiState,
            source = preferred.source,
            scanId = preferred.operationId,
            found = preferred.filesFound,
            files = preferred.filesProcessed,
            directories = preferred.directoriesCompleted,
            error = preferred.message.takeIf { it.isNotBlank() } ?: preferred.errorCategory,
            timestamp = preferred.finishedAtMillis ?: preferred.startedAtMillis,
        )
        if (current.state != uiState || current.scanId != preferred.operationId) {
            recordDiagnostic(
                name = "SCAN_UI_STATE_CHANGED",
                result = uiState,
                scanId = preferred.operationId,
                context = mapOf(
                    "source" to preferred.source,
                    "files_found" to preferred.filesFound,
                    "files_processed" to preferred.filesProcessed,
                    "directories_completed" to preferred.directoriesCompleted,
                    "partial" to preferred.partial,
                ),
            )
        }
    }


    /**
     * Process-local mirror of LibraryStore.claim_native_request. Requests are
     * namespaced because Settings and Library use separate request contracts.
     * It avoids replay within one process without creating another database/table.
     */
    private fun claimRequestId(requestId: String, namespace: String, limit: Int = 1000): Boolean {
        val normalizedRequest = requestId.trim()
        val normalizedNamespace = namespace.trim().ifBlank { "default" }
        if (normalizedRequest.isEmpty()) return false
        return synchronized(lock) {
            val ids = claimedRequestIds.getOrPut(normalizedNamespace) { LinkedHashSet() }
            if (!ids.add(normalizedRequest)) return@synchronized false
            while (ids.size > limit.coerceAtLeast(1)) {
                val iterator = ids.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                } else break
            }
            true
        }
    }

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
     * Preserve the legacy queue identity: URI, source version and media identity
     * are all part of one pending request. The extractor's own cache key remains
     * identity/version based, but queue deduplication must not merge distinct URIs.
     */
    private fun thumbnailRequestKey(
        uri: Uri,
        size: Long,
        modifiedAt: Long,
        mediaIdentity: String,
    ): String = VideoThumbnailExtractor.cacheKey("${uri}|${mediaIdentity}", size.coerceAtLeast(0L), modifiedAt.coerceAtLeast(0L))

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
        val key = thumbnailRequestKey(uri, boundedSize, boundedModifiedAt, identity)
        synchronized(thumbnailQueueLock) {
            pruneThumbnailQueueStateLocked()
            val uriKey = uri.toString()
            val previousKey = thumbnailLatestKeyByUri.put(uriKey, key)
            thumbnailLatestAtMillis[uriKey] = System.currentTimeMillis()
            if (previousKey != null && previousKey != key) {
                thumbnailRetryCounts.remove(previousKey)
                thumbnailRetryPendingKeys.remove(previousKey)
                val previous = thumbnailRequests[previousKey]
                if (previous != null && thumbnailQueue.remove(previous)) {
                    thumbnailRequests.remove(previousKey, previous)
                    thumbnailRetryCounts.remove(previousKey)
                    previous.completion.complete(null)
                }
            }
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
                thumbnailRetryCounts.remove(lowest.key)
                thumbnailRetryPendingKeys.remove(lowest.key)
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

    /** Remove expired request bookkeeping and bound per-URI freshness metadata. Caller holds thumbnailQueueLock. */
    private fun pruneThumbnailQueueStateLocked(nowMillis: Long = System.currentTimeMillis()) {
        val expiredKeys = thumbnailRequests.values
            .filter { nowMillis - it.enqueuedAtMillis > 180_000L }
            .map { it.key }
        expiredKeys.forEach { key ->
            val request = thumbnailRequests[key] ?: return@forEach
            thumbnailQueue.remove(request)
            if (thumbnailInFlight.contains(key)) return@forEach
            thumbnailRequests.remove(key, request)
            thumbnailRetryCounts.remove(key)
            request.completion.complete(null)
            val uriKey = request.uri.toString()
            if (thumbnailLatestKeyByUri[uriKey] == key) {
                thumbnailLatestKeyByUri.remove(uriKey, key)
                thumbnailLatestAtMillis.remove(uriKey)
            }
        }
        if (thumbnailLatestAtMillis.size > 2_048) {
            thumbnailLatestAtMillis.entries
                .sortedBy { it.value }
                .take(512)
                .forEach { entry ->
                    thumbnailLatestAtMillis.remove(entry.key, entry.value)
                    thumbnailLatestKeyByUri.remove(entry.key)
                }
        }
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
        thumbnailPumpJob = activity.lifecycleScope.launch {
            try {
                while (true) {
                    val request = synchronized(thumbnailQueueLock) {
                        val next = thumbnailQueue.poll() ?: return@synchronized null
                        val currentKey = thumbnailLatestKeyByUri[next.uri.toString()]
                        if (currentKey != null && currentKey != next.key) {
                            thumbnailRequests.remove(next.key, next)
                            thumbnailRetryCounts.remove(next.key)
                            thumbnailRetryPendingKeys.remove(next.key)
                            next.completion.complete(null)
                            return@synchronized next.copy(key = "")
                        }
                        thumbnailInFlight.add(next.key)
                        next
                    } ?: break
                    if (request.key.isEmpty()) {
                        updateThumbnailQueueSnapshot()
                        continue
                    }
                    updateThumbnailQueueSnapshot()
                    var retryDelayMs: Long? = null
                    var retryAttempt: Int? = null
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
                            val previousAttempts = thumbnailRetryCounts[request.key] ?: 0
                            if (previousAttempts < 2 && !request.completion.isCancelled) {
                                val nextAttempt = previousAttempts + 1
                                thumbnailRetryCounts[request.key] = nextAttempt
                                retryAttempt = nextAttempt
                                retryDelayMs = if (previousAttempts == 0) 1_000L else 5_000L
                            } else {
                                thumbnailRetryCounts.remove(request.key)
                                thumbnailRetryPendingKeys.remove(request.key)
                                val uriKey = request.uri.toString()
                                if (thumbnailLatestKeyByUri.remove(uriKey, request.key)) {
                                    thumbnailLatestAtMillis.remove(uriKey)
                                }
                                recordDiagnostic(
                                    name = "THUMBNAIL_RETRY_EXHAUSTED",
                                    severity = "WARN",
                                    result = "extraction_failed",
                                    context = mapOf("attempts" to previousAttempts),
                                )
                            }
                        } else {
                            thumbnailRetryCounts.remove(request.key)
                            thumbnailRetryPendingKeys.remove(request.key)
                            publish(action = "thumbnail_extracted", transform = {
                                it.copy(lastArtifactPath = result.path)
                            })
                        }
                    } catch (cancelled: CancellationException) {
                        request.completion.cancel(cancelled)
                        thumbnailRetryCounts.remove(request.key)
                        thumbnailRetryPendingKeys.remove(request.key)
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
                        val previousAttempts = thumbnailRetryCounts[request.key] ?: 0
                        if (previousAttempts < 2 && !request.completion.isCancelled) {
                            val nextAttempt = previousAttempts + 1
                            thumbnailRetryCounts[request.key] = nextAttempt
                            retryAttempt = nextAttempt
                            retryDelayMs = if (previousAttempts == 0) 1_000L else 5_000L
                        } else {
                            thumbnailRetryCounts.remove(request.key)
                            thumbnailRetryPendingKeys.remove(request.key)
                            val uriKey = request.uri.toString()
                            if (thumbnailLatestKeyByUri.remove(uriKey, request.key)) {
                                thumbnailLatestAtMillis.remove(uriKey)
                            }
                            recordDiagnostic(
                                name = "THUMBNAIL_RETRY_EXHAUSTED",
                                severity = "WARN",
                                error = failure,
                                context = mapOf("attempts" to previousAttempts),
                            )
                        }
                    } finally {
                        synchronized(thumbnailQueueLock) {
                            thumbnailInFlight.remove(request.key)
                            thumbnailRequests.remove(request.key)
                        }
                        updateThumbnailQueueSnapshot()
                    }

                    val delayBeforeRetry = retryDelayMs
                    val attemptToRetry = retryAttempt
                    if (delayBeforeRetry != null && attemptToRetry != null) {
                        thumbnailRetryPendingKeys.add(request.key)
                        val retryScheduled = launchTracked("thumbnail_retry", lane = "thumbnail") {
                            try {
                                delay(delayBeforeRetry)
                                if (thumbnailRetryCounts[request.key] != attemptToRetry) return@launchTracked
                                val latestRequestKey = thumbnailLatestKeyByUri[request.uri.toString()]
                                if (latestRequestKey != null && latestRequestKey != request.key) {
                                    thumbnailRetryCounts.remove(request.key)
                                    return@launchTracked
                                }
                                if (!lifecycleIsAlive || activity.isFinishing || activity.isDestroyed) {
                                    thumbnailRetryCounts.remove(request.key)
                                    return@launchTracked
                                }
                                val queued = enqueueLocalThumbnail(
                                    reference = request.uri.toString(),
                                    size = request.size,
                                    modifiedAt = request.modifiedAt,
                                    mediaIdentity = request.mediaIdentity,
                                    priority = 200,
                                )
                                if (!queued) {
                                    if (thumbnailRetryCounts[request.key] == attemptToRetry) {
                                        thumbnailRetryCounts.remove(request.key)
                                    }
                                    recordDiagnostic(
                                        name = "THUMBNAIL_RETRY_REJECTED",
                                        severity = "WARN",
                                        result = "queue_rejected",
                                        context = mapOf("attempt" to attemptToRetry),
                                    )
                                } else {
                                    recordDiagnostic(
                                        name = "THUMBNAIL_RETRY_SCHEDULED",
                                        result = "retry_$attemptToRetry",
                                        context = mapOf(
                                            "attempt" to attemptToRetry,
                                            "delay_ms" to delayBeforeRetry,
                                        ),
                                    )
                                }
                            } finally {
                                thumbnailRetryPendingKeys.remove(request.key)
                            }
                        }
                        if (!retryScheduled && thumbnailRetryCounts[request.key] == attemptToRetry) {
                            thumbnailRetryCounts.remove(request.key)
                            thumbnailRetryPendingKeys.remove(request.key)
                        }
                    }
                }
            } finally {
                synchronized(thumbnailQueueLock) {
                    thumbnailPumpActive = false
                    thumbnailPumpJob = null
                    if (thumbnailQueue.isNotEmpty() && lifecycleIsAlive) startThumbnailPumpLocked()
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
        val key = thumbnailRequestKey(uri, size.coerceAtLeast(0L), modifiedAt.coerceAtLeast(0L), identity)
        val accepted = enqueueLocalThumbnail(raw, size, modifiedAt, identity, priority = 100)
        if (!accepted) return null
        return thumbnailRequests[key]?.completion?.await()
    }

    fun cancelThumbnailRequest(reference: String, size: Long = 0L, modifiedAt: Long = 0L, mediaIdentity: String? = null): Boolean {
        val raw = reference.trim()
        if (raw.isEmpty()) return false
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val identity = mediaIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: uri.toString()
        val key = thumbnailRequestKey(uri, size.coerceAtLeast(0L), modifiedAt.coerceAtLeast(0L), identity)
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

    fun requestStorageAction(
        action: String,
        requestId: String? = null,
        resultAction: String? = null,
    ): Boolean {
        val normalizedAction = action.trim()
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        val operationAction = resultAction?.trim()?.takeIf { it.isNotEmpty() } ?: normalizedAction
        val pendingOperation = correlatedRequestId?.let {
            PendingNativeStorageOperation(normalizedAction, operationAction)
        }
        if (correlatedRequestId != null && pendingOperation != null &&
            pendingStorageOperations.putIfAbsent(correlatedRequestId, pendingOperation) != null
        ) {
            recordDiagnostic(
                name = "NATIVE_STORAGE_DUPLICATE_REQUEST_IGNORED",
                severity = "WARN",
                requestId = correlatedRequestId,
                result = operationAction,
                context = mapOf("native_action" to normalizedAction),
            )
            return false
        }

        // Publish the non-terminal state before dispatch. The native operation may
        // complete immediately; nothing below may overwrite its terminal result.
        if (correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = operationAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Operação nativa aceita para processamento.",
            )
        }

        var launchError: String? = null
        val accepted = try {
            if (correlatedRequestId == null) {
                activity.requestNativeStorageAction(normalizedAction)
            } else {
                activity.startActivity(
                    MainActivity.createNativeStorageActionIntent(
                        context = activity,
                        action = normalizedAction,
                        requestId = correlatedRequestId,
                    )
                )
                true
            }
        } catch (failure: Exception) {
            launchError = failure.message?.takeIf { it.isNotBlank() }
                ?: "Native storage action was rejected."
            false
        }
        if (!accepted && launchError == null) {
            launchError = "Native storage action was rejected."
        }
        if (!accepted && correlatedRequestId != null) {
            if (pendingOperation != null) pendingStorageOperations.remove(correlatedRequestId, pendingOperation)
            publishOperationResult(
                requestId = correlatedRequestId,
                action = operationAction,
                status = "FAILED",
                operationState = "FAILED",
                message = launchError,
                error = launchError,
            )
            recordDiagnostic(
                name = "NATIVE_STORAGE_ACTION_LAUNCH_FAILED",
                severity = "WARN",
                requestId = correlatedRequestId,
                result = normalizedAction,
                error = IllegalStateException(launchError),
            )
        }
        publish(
            action = "storage_action",
            error = if (accepted) null else launchError,
        )
        return accepted
    }
    fun requestMediaAccess(
        requestId: String? = null,
        resultAction: String = "request_media_access",
    ): Boolean = requestStorageAction("request_media_access", requestId, resultAction)
    fun checkStorageAccess(
        requestId: String? = null,
        resultAction: String = "check_storage_access",
    ): Boolean = requestStorageAction("check_storage_access", requestId, resultAction)
    fun openBroadStorageSettings(
        requestId: String? = null,
        resultAction: String = "open_broad_storage_settings",
    ): Boolean = requestStorageAction("open_broad_storage_settings", requestId, resultAction)
    fun requestLibraryScan(
        requestId: String? = null,
        resultAction: String = "scan_library",
    ): Boolean {
        if (!canStartLibraryScan("saf")) {
            val message = "Não há uma pasta SAF autorizada para atualizar a biblioteca."
            recordDiagnostic(
                name = "LIBRARY_SCAN_REJECTED",
                severity = "WARN",
                requestId = requestId,
                result = "no_authorized_library_saf_source",
                context = mapOf("configured_saf_root_count" to authorizedScanTargets("saf").size),
            )
            requestId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "BLOCKED",
                    operationState = "BLOCKED",
                    message = message,
                    error = "no_authorized_library_saf_source",
                )
            }
            return false
        }
        return requestStorageAction("scan_library", requestId, resultAction)
    }
    fun selectSafFolder(): Boolean = requestStorageAction("select_tree")

    /** Return only the SAF roots already registered by the canonical native library repository. */
    fun configuredSafTreeUris(): List<Uri> =
        libraryViewModel.authorizedSafTreeUris().distinctBy { it.toString() }

    private fun recordStorageMutation(reason: String, treeUri: String? = null) {
        val timestamp = System.currentTimeMillis()
        lastStorageMutationAtMillis = timestamp
        recordDiagnostic(
            name = "NATIVE_STORAGE_MUTATION",
            result = reason,
            context = mapOf(
                "tree_uri_present" to !treeUri.isNullOrBlank(),
                "at_ms" to timestamp,
            ),
        )
    }

    fun isStorageSnapshotStale(publishedAtMillis: Long): Boolean =
        publishedAtMillis > 0L &&
            lastStorageMutationAtMillis > 0L &&
            publishedAtMillis < lastStorageMutationAtMillis

    /**
     * Capability messages are hints, not proof of Android grants. Re-read the
     * PackageManager permission state, persisted SAF grants and canonical folder rows.
     */
    fun refreshAuthoritativeStorageSnapshot(
        payload: org.json.JSONObject? = null,
        eventCreatedAtMillis: Long = 0L,
    ): Boolean {
        val publishedAt = when {
            eventCreatedAtMillis > 0L -> eventCreatedAtMillis
            payload == null -> 0L
            else -> payload.optLong(
                "publishedAtMs",
                payload.optLong("published_at_ms", payload.optLong("createdAt", 0L)),
            )
        }
        if (isStorageSnapshotStale(publishedAt)) {
            mutableStorageState.value = storageSnapshot()
            recordDiagnostic(
                name = "STORAGE_STALE_CAPABILITY_SNAPSHOT_IGNORED",
                severity = "WARN",
                result = "older_than_last_storage_mutation",
                context = mapOf(
                    "published_at_ms" to publishedAt,
                    "last_mutation_at_ms" to lastStorageMutationAtMillis,
                ),
            )
            return false
        }

        val treeUri = payload?.let {
            it.optString("treeUri", it.optString("tree_uri", "")).trim()
        }.orEmpty()
        val claimsGranted = payload?.optBoolean("granted", false) == true ||
            payload?.optString("status", "")?.equals("GRANTED", ignoreCase = true) == true
        if (claimsGranted && treeUri.isNotBlank()) {
            val persisted = activity.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
            if (StorageAuthorization.safAccess(treeUri, persisted) == SafAccessLevel.AVAILABLE) {
                val validSource = configuredSafTreeUris().any {
                    StorageAuthorization.safIdentity(it.toString()) != null &&
                        StorageAuthorization.safIdentity(it.toString()) == StorageAuthorization.safIdentity(treeUri)
                }
                if (validSource) {
                    // Projection snapshots are not terminal picker results. Keep the
                    // original request correlation until a matching selected/cancelled/error
                    // event arrives; an unrelated grant must not clear a newer request.
                    recordStorageMutation("saf_grant_confirmed", treeUri)
                }
            } else {
                recordDiagnostic(
                    name = "SAF_GRANT_EVENT_REJECTED",
                    severity = "WARN",
                    requestId = pendingSafSelectionRequestId,
                    result = "android_persisted_read_grant_missing",
                )
            }
        }

        val snapshot = storageSnapshot()
        mutableStorageState.value = snapshot
        settingsViewModel.refresh()
        libraryViewModel.refreshNativeFolders()
        libraryViewModel.refresh()
        recordDiagnostic(
            name = "NATIVE_STORAGE_CAPABILITIES_APPLIED",
            requestId = pendingSafSelectionRequestId,
            result = "authoritative_android_snapshot",
            context = mapOf(
                "media_store_access" to snapshot.mediaStoreAccess,
                "broad_storage_access" to snapshot.broadStorageAccess,
                "configured_saf_root_count" to snapshot.configuredSafRoots.size,
                "onboarding_phase" to snapshot.onboardingPhase,
            ),
        )
        return true
    }

    fun updateSafCapabilities(currentUris: Collection<String>): NativeMainStorageSnapshot {
        val configured = configuredSafTreeUris().map { it.toString() }.toSet()
        val persisted = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val valid = currentUris.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in configured }
            .filter { StorageAuthorization.safAccess(it, persisted) == SafAccessLevel.AVAILABLE }
            .distinctBy { StorageAuthorization.safIdentity(it) ?: it }
            .toList()
        val result = storageSnapshot().copy(
            configuredSafRoots = valid,
            persistedSafRootCount = valid.size,
        )
        mutableStorageState.value = result
        return result
    }

    /** Do not add arbitrary persisted grants as library roots; only confirm configured sources. */
    fun mergeAuthoritativeSafRoot(treeUri: String): Boolean {
        val normalized = treeUri.trim()
        if (normalized.isEmpty() || !DocumentsContract.isTreeUri(Uri.parse(normalized))) return false
        val persisted = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
        if (StorageAuthorization.safAccess(normalized, persisted) != SafAccessLevel.AVAILABLE) return false
        val identity = StorageAuthorization.safIdentity(normalized) ?: return false
        val match = configuredSafTreeUris().any { existing ->
            StorageAuthorization.safIdentity(existing.toString()) == identity
        }
        if (!match) {
            recordDiagnostic(
                name = "SAF_ROOT_MERGE_REJECTED",
                severity = "WARN",
                result = "not_a_configured_library_source",
            )
            return false
        }
        mutableStorageState.value = storageSnapshot()
        return true
    }

    private fun clearPendingSafSelectionContext() {
        pendingSafSelectionRequestId = null
        pendingSafSelectionStartedAtMillis = 0L
        pendingSafSelectionBridge = "library"
        pendingSafSelectionBaselineIdentities = emptySet()
        publish(action = "clear_pending_saf_selection")
    }

    private fun clearPendingSafRemovalContext() {
        pendingSafRemovalRequestId = null
        pendingSafRemovalReference = null
        pendingSafRemovalBridge = "library"
        publish(action = "clear_pending_saf_removal")
    }

    private fun finishPendingSafRemoval(
        eventRequestId: String?,
        treeUri: String?,
        status: String,
        message: String? = null,
        error: String? = null,
        payload: org.json.JSONObject? = null,
    ): Boolean {
        val pendingRequestId = pendingSafRemovalRequestId?.trim().orEmpty()
        val incomingRequestId = eventRequestId?.trim().orEmpty()
        if (pendingRequestId.isEmpty() || incomingRequestId.isEmpty() ||
            pendingRequestId != incomingRequestId
        ) return false

        val expectedReference = pendingSafRemovalReference?.trim().orEmpty()
        val actualReference = treeUri?.trim().orEmpty()
        if (expectedReference.isNotEmpty() && actualReference.isNotEmpty() &&
            expectedReference != actualReference
        ) {
            val expectedIdentity = StorageAuthorization.safIdentity(expectedReference)
            val actualIdentity = StorageAuthorization.safIdentity(actualReference)
            if (expectedIdentity == null || actualIdentity == null || expectedIdentity != actualIdentity) {
                recordDiagnostic(
                    name = "SAF_REMOVAL_RESULT_REJECTED",
                    severity = "WARN",
                    requestId = pendingRequestId,
                    result = "tree_uri_mismatch",
                )
                return false
            }
        }

        val terminalStatus = status.trim().uppercase(Locale.ROOT)
        if (terminalStatus !in setOf("COMPLETED", "ERROR", "FAILED", "CANCELLED")) return false
        val resultPayload = org.json.JSONObject(payload?.toString() ?: "{}")
            .put("removalBridge", pendingSafRemovalBridge)
            .put("treeUri", actualReference.ifBlank { expectedReference })
        val published = publishOperationResult(
            requestId = pendingRequestId,
            action = "remove_saf",
            status = terminalStatus,
            operationState = terminalStatus,
            message = message,
            error = error,
            payload = resultPayload,
        )
        clearPendingSafRemovalContext()
        return published
    }

    private fun finishPendingSafSelection(
        eventRequestId: String?,
        status: String,
        message: String? = null,
        error: String? = null,
        payload: org.json.JSONObject? = null,
    ): Boolean {
        val pendingRequestId = pendingSafSelectionRequestId?.trim().orEmpty()
        val incomingRequestId = eventRequestId?.trim().orEmpty()
        if (pendingRequestId.isEmpty() || incomingRequestId.isEmpty() ||
            pendingRequestId != incomingRequestId
        ) return false

        val terminalStatus = status.trim().uppercase(Locale.ROOT)
        if (terminalStatus !in setOf("COMPLETED", "ERROR", "FAILED", "CANCELLED")) return false
        val resultPayload = org.json.JSONObject(payload?.toString() ?: "{}")
            .put("selectionBridge", pendingSafSelectionBridge)
        val published = publishOperationResult(
            requestId = pendingRequestId,
            action = "select_saf",
            status = terminalStatus,
            operationState = terminalStatus,
            message = message,
            error = error,
            payload = resultPayload,
        )
        clearPendingSafSelectionContext()
        return published
    }

    /**
     * Recover a lost picker callback only from a complete inventory that began
     * after the explicit selection request and proves exactly one newly persisted
     * SAF root. Unrelated grants are never promoted into library sources.
     */
    private fun recoverPendingSafSelectionFromInventory(payload: org.json.JSONObject): Boolean {
        val request = pendingSafSelectionRequestId?.trim().orEmpty()
        val selectionStarted = pendingSafSelectionStartedAtMillis
        val inventoryStarted = payload.optLong(
            "inventoryStartedAtMs",
            payload.optLong("inventory_started_at_ms", 0L),
        )
        val inventoryComplete = payload.optBoolean(
            "inventoryComplete",
            payload.optBoolean("inventory_complete", payload.optBoolean("complete", false)),
        )
        if (request.isEmpty() || selectionStarted <= 0L || inventoryStarted < selectionStarted ||
            inventoryStarted <= 0L || !inventoryComplete
        ) return false

        val trees = payload.optJSONArray("trees") ?: return false
        val candidates = linkedMapOf<String, org.json.JSONObject>()
        for (index in 0 until trees.length()) {
            val tree = trees.optJSONObject(index) ?: continue
            if (!tree.optBoolean("persisted", false)) continue
            val status = tree.optString("status", "").trim().uppercase(Locale.ROOT)
            if (status !in setOf("COMPLETED", "EMPTY_COMPLETE")) continue
            val treeUri = tree.optString("treeUri", tree.optString("tree_uri", "")).trim()
            if (treeUri.isEmpty()) continue
            val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: continue
            if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) continue
            val identity = StorageAuthorization.safIdentity(treeUri)
                ?: tree.optString("identity", "").trim().takeIf { it.isNotEmpty() }
                ?: continue
            if (identity in pendingSafSelectionBaselineIdentities) continue
            candidates[identity] = org.json.JSONObject(tree.toString())
                .put("treeUri", treeUri)
                .put("identity", identity)
        }
        if (candidates.size != 1) return false
        val candidate = candidates.values.single()
        val treeUri = candidate.optString("treeUri", "").trim()
        val identity = candidate.optString("identity", "").trim()
        if (treeUri.isEmpty() || identity.isEmpty()) return false
        val persistedReadUris = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
        if (StorageAuthorization.safAccess(treeUri, persistedReadUris) != SafAccessLevel.AVAILABLE) {
            recordDiagnostic(
                name = "SAF_INVENTORY_RECOVERY_REJECTED",
                severity = "WARN",
                requestId = request,
                result = "persisted_read_grant_not_available",
                context = mapOf("identity" to identity),
            )
            return false
        }
        if (!claimRequestId(request, "saf_inventory_recovery")) {
            recordDiagnostic(
                name = "SAF_INVENTORY_RECOVERY_DUPLICATE_IGNORED",
                severity = "WARN",
                requestId = request,
                result = "already_claimed",
            )
            return true
        }

        val accepted = launchTracked("recover_saf_selection_from_inventory", lane = "storage") {
            if (pendingSafSelectionRequestId != request) return@launchTracked
            try {
                val treeIdentity = SafScanner.treeIdentity(Uri.parse(treeUri))
                val accountId = dependencies.sqliteAccountRepository.loadProfile()?.id
                dependencies.sqliteLibraryRepository.upsertSafFolder(
                    path = treeUri,
                    name = candidate.optString("name", "").trim()
                        .takeIf { it.isNotEmpty() }
                        ?: SafScanner.displayName(activity.applicationContext, Uri.parse(treeUri)),
                    authorization = "granted",
                    accountId = accountId,
                    safAuthority = candidate.optString("authority", treeIdentity.authority),
                    safDocumentId = candidate.optString("documentId", treeIdentity.documentId),
                    safVolumeId = candidate.optString("volumeId", treeIdentity.volumeId),
                    safIdentity = identity,
                )
                recordStorageMutation("saf_selection_recovered_from_inventory", treeUri)
                libraryViewModel.refreshNativeFolders()
                settingsViewModel.refresh()
                libraryViewModel.refresh()
                mutableStorageState.value = storageSnapshot()
                scheduleCatalogMetadataHydration("storage_ready_inventory_recovery")
                scheduleThumbnailReconciliation("storage_ready_inventory_recovery")

                finishPendingSafSelection(
                    eventRequestId = request,
                    status = "COMPLETED",
                    message = "Pasta autorizada e configurada.",
                    payload = org.json.JSONObject()
                        .put("treeUri", treeUri)
                        .put("identity", identity)
                        .put("recoveredFromInventory", true),
                )
                recordDiagnostic(
                    name = "SAF_SELECTION_RECOVERED_FROM_INVENTORY",
                    requestId = request,
                    result = "completed",
                    context = mapOf("identity" to identity),
                )
                try {
                    activity.startActivity(
                        MainActivity.createNativeStorageActionIntent(
                            context = activity,
                            action = "scan_tree",
                            treeUri = treeUri,
                            requestId = UUID.randomUUID().toString(),
                            reason = "saf_inventory_selection_recovery",
                        )
                    )
                } catch (scanFailure: Exception) {
                    recordDiagnostic(
                        name = "SAF_RECOVERY_RESCAN_LAUNCH_FAILED",
                        severity = "WARN",
                        result = "scan_tree",
                        error = scanFailure,
                        context = mapOf("identity" to identity),
                    )
                }
            } catch (cancelled: CancellationException) {
                finishPendingSafSelection(
                    eventRequestId = request,
                    status = "CANCELLED",
                    message = "Recuperação da seleção SAF cancelada.",
                    payload = org.json.JSONObject().put("treeUri", treeUri),
                )
                throw cancelled
            } catch (failure: Throwable) {
                recordDiagnostic(
                    name = "SAF_SELECTION_INVENTORY_RECOVERY_FAILED",
                    severity = "WARN",
                    requestId = request,
                    result = "failed",
                    error = failure,
                    context = mapOf("identity" to identity),
                )
                finishPendingSafSelection(
                    eventRequestId = request,
                    status = "ERROR",
                    message = failure.message ?: "Não foi possível recuperar a seleção de pasta.",
                    error = failure.javaClass.simpleName,
                    payload = org.json.JSONObject().put("treeUri", treeUri),
                )
            }
        }
        if (!accepted) {
            finishPendingSafSelection(
                eventRequestId = request,
                status = "ERROR",
                message = "Não foi possível agendar a recuperação da seleção SAF.",
                error = "recovery_operation_rejected",
            )
        }
        return accepted
    }

    fun beginSafSelection(requestId: String? = null, bridge: String = "library"): Boolean {
        if (pendingSafSelectionRequestId?.isNotBlank() == true || !canMutateSafRoots()) return false
        pendingSafSelectionRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        pendingSafSelectionBridge = bridge.trim().lowercase(Locale.ROOT).takeIf {
            it == "library" || it == "settings"
        } ?: "library"
        pendingSafSelectionBaselineIdentities = configuredSafTreeUris()
            .mapNotNull { StorageAuthorization.safIdentity(it.toString()) }
            .toSet()
        pendingSafSelectionStartedAtMillis = System.currentTimeMillis()
        publish(action = "begin_saf_selection")
        return true
    }

    suspend fun configuredValidLibrarySafRoots(): List<String> {
        val allowed = authorizedScanTargets("saf").toSet()
        if (allowed.isEmpty()) return emptyList()
        return dependencies.sqliteLibraryRepository.listFolders()
            .asSequence()
            .filter { it.kind.equals("saf", ignoreCase = true) }
            .filter { it.authorization.equals("granted", ignoreCase = true) }
            .map { it.path.trim() }
            .filter { it in allowed }
            .distinct()
            .toList()
    }

    suspend fun repairConfiguredSafSourceIdentities(): Int {
        val folders = dependencies.sqliteLibraryRepository.listFolders()
        val persistedReadUris = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
        val persistedIdentities = persistedReadUris.mapNotNull { StorageAuthorization.safIdentity(it) }.toSet()
        var repaired = 0
        for (folder in folders) {
            if (!folder.kind.equals("saf", ignoreCase = true) ||
                !folder.authorization.equals("granted", ignoreCase = true)
            ) continue
            val reference = folder.path.trim()
            if (reference.isEmpty()) continue
            val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: continue
            if (!DocumentsContract.isTreeUri(uri)) continue
            val identity = StorageAuthorization.safIdentity(reference) ?: continue
            if (identity !in persistedIdentities || identity == folder.safIdentity) continue
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri).trim() }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val volumeId = documentId.substringBefore(':').takeIf { it.isNotBlank() && it != documentId }
            dependencies.sqliteLibraryRepository.upsertSafFolder(
                reference = reference,
                name = folder.name,
                authority = uri.authority,
                documentId = documentId,
                volumeId = volumeId,
                identity = identity,
            )
            repaired += 1
        }
        if (repaired > 0) {
            libraryViewModel.refreshNativeFolders()
            libraryViewModel.refresh()
            mutableStorageState.value = storageSnapshot()
        }
        return repaired
    }

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

    fun hasAvailableMediaSource(): Boolean = storageSnapshot().hasConfiguredLibrarySource

    fun hasAnyNativeScanInProgress(): Boolean =
        NativeScanController.progress.value.values.any { !it.state.isTerminal }

    fun canMutateSafRoots(): Boolean =
        pendingSafSelectionRequestId.isNullOrBlank() &&
            pendingSafRemovalRequestId.isNullOrBlank() &&
            !hasAnyNativeScanInProgress() &&
            !activity.nativeStorageOnboardingState.value.phase.equals("folder_picker_open", ignoreCase = true)

    fun isStorageOnboardingRequired(): Boolean {
        val snapshot = storageSnapshot()
        return !snapshot.hasConfiguredLibrarySource &&
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
    fun renderSignature(): String {
        val current = mutableSnapshot.value
        val scan = mutableScanUiState.value
        val input = listOf(
            current.route,
            current.selectedAnimeId?.toString().orEmpty(),
            current.generation.toString(),
            current.initialized.toString(),
            current.recoveryRequired.toString(),
            scan.state,
            scan.scanId.orEmpty(),
            mutableUiPreferences.value.homeState.hashCode().toString(),
            mutableUiPreferences.value.organizeState.hashCode().toString(),
            mutableUiPreferences.value.settingsState.hashCode().toString(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Compose owns view instances; invalidation publishes source state rather than caching Flet controls. */
    fun dropScreenCache(route: String) {
        val normalized = routeForScreen(route).ifBlank { route.trim() }
        if (normalized == mutableSnapshot.value.route) {
            if (normalized == ReiAnixRoutes.SETTINGS) settingsViewModel.refresh()
            else libraryViewModel.refresh()
        }
        publish(action = "drop_screen_cache", transform = { it.copy(generation = it.generation + 1L) })
    }

    fun clearScreenCache() {
        libraryViewModel.refresh()
        settingsViewModel.refresh()
        publish(action = "clear_screen_cache", transform = { it.copy(generation = it.generation + 1L) })
    }

    fun invalidateCatalogViews(animeId: Long? = null) {
        if (animeId != null && animeId > 0L) libraryViewModel.refreshDetails(animeId)
        libraryViewModel.refresh()
        publish(action = "invalidate_catalog_views", transform = { it.copy(generation = it.generation + 1L) })
    }

    fun invalidateCachedView(route: String, animeId: Long? = null) {
        dropScreenCache(route)
        if (animeId != null && animeId > 0L) libraryViewModel.refreshDetails(animeId)
    }

    fun showComposeHome(resetBackStack: Boolean = false): Boolean =
        showComposeLibrary(ReiAnixRoutes.HOME, resetBackStack)

    fun showComposeOrganize(resetBackStack: Boolean = false): Boolean =
        showComposeLibrary(ReiAnixRoutes.ORGANIZE, resetBackStack)

    fun showComposeSettings(resetBackStack: Boolean = false): Boolean =
        showComposeLibrary(ReiAnixRoutes.SETTINGS, resetBackStack)

    fun showComposeStorage(resetBackStack: Boolean = false): Boolean =
        showComposeLibrary(ReiAnixRoutes.STORAGE, resetBackStack)

    fun hideComposeSettings() = hideComposeLibrary()

    fun handleNativeBack(): Boolean = navigateBack()

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
        requestId: String? = null,
        scanId: String? = null,
        refreshId: String? = null,
        sessionId: String? = null,
        source: String = "NativeMainRuntimePort",
    ) {
        ReiAnixDiagnosticTimeline.record(
            name = name,
            severity = severity,
            source = source,
            result = result,
            requestId = requestId,
            scanId = scanId,
            refreshId = refreshId,
            sessionId = sessionId,
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

    fun scheduleMetadataHydration(): Boolean =
        scheduleCatalogMetadataHydration("legacy_schedule_metadata_hydration")

    fun scheduleThumbnailReconciliation(): Boolean =
        scheduleThumbnailReconciliation("legacy_schedule_thumbnail_reconciliation")


    fun requestAccountActionTracked(action: String): Boolean {
        val normalized = action.trim()
        if (normalized.isEmpty()) return false
        return launchTracked("account_action", lane = "settings") {
            settingsViewModel.requestAccountAction(normalized)
        }
    }

    /** Native scanner snapshots replace the old Python ScanCoordinator projection. */
    fun currentScanUiState(): NativeMainScanUiState = mutableScanUiState.value

    fun canStartLibraryScan(source: String? = null): Boolean {
        val normalized = source?.trim()?.lowercase(Locale.ROOT)
        if (normalized != null && normalized !in setOf("saf", "all", "library")) return false
        return !hasAnyNativeScanInProgress() && authorizedScanTargets("saf").isNotEmpty()
    }

    /** Only persisted, still-authorized SAF roots are valid native library scan targets. */
    fun authorizedScanTargets(source: String? = null, scopeReference: String? = null): List<String> {
        val normalized = source?.trim()?.lowercase(Locale.ROOT)
        if (normalized != null && normalized != "saf") return emptyList()
        val persisted = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()
        val roots = configuredSafTreeUris().map { it.toString() }
            .filter { StorageAuthorization.safAccess(it, persisted) == SafAccessLevel.AVAILABLE }
            .distinctBy { StorageAuthorization.safIdentity(it) ?: it }
        val scope = scopeReference?.trim().orEmpty()
        return if (scope.isEmpty()) roots else roots.filter {
            it == scope || (
                StorageAuthorization.safIdentity(it) != null &&
                StorageAuthorization.safIdentity(it) == StorageAuthorization.safIdentity(scope)
            )
        }
    }

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
    fun requestSafFolderSelection(requestId: String? = null, bridge: String = "library"): Boolean {
        val resolvedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        if (pendingSafSelectionRequestId == resolvedRequestId) {
            recordDiagnostic(
                name = "SAF_SELECTION_DUPLICATE_REQUEST_IGNORED",
                severity = "WARN",
                requestId = resolvedRequestId,
                result = "request_already_pending",
            )
            return false
        }
        if (!beginSafSelection(resolvedRequestId, bridge)) {
            val message = "Aguarde a varredura ou a seleção de pasta atual terminar."
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "select_saf",
                status = "BLOCKED",
                operationState = "BLOCKED",
                message = message,
                error = message,
            )
            publish(action = "saf_folder_selection", error = message)
            return false
        }
        return try {
            activity.startActivity(
                MainActivity.createNativeStorageActionIntent(
                    context = activity,
                    action = "select_tree",
                    requestId = resolvedRequestId,
                )
            )
            publish(action = "saf_folder_selection")
            true
        } catch (failure: Exception) {
            val message = failure.message?.takeIf { it.isNotBlank() }
                ?: "Não foi possível abrir o seletor de pastas."
            clearPendingSafSelectionContext()
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "select_saf",
                status = "ERROR",
                operationState = "ERROR",
                message = message,
                error = message,
            )
            publish(action = "saf_folder_selection", error = message)
            false
        }
    }

    fun requestSafFolderRemoval(
        reference: String,
        requestId: String? = null,
        bridge: String = "library",
    ): Boolean {
        val requestedReference = reference.trim()
        val resolvedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        if (pendingSafRemovalRequestId == resolvedRequestId) {
            recordDiagnostic(
                name = "SAF_REMOVAL_DUPLICATE_REQUEST_IGNORED",
                severity = "WARN",
                requestId = resolvedRequestId,
                result = "request_already_pending",
            )
            return false
        }
        val requestedIdentity = StorageAuthorization.safIdentity(requestedReference)
        val configuredReference = configuredSafTreeUris().firstOrNull { configured ->
            val configuredText = configured.toString()
            configuredText == requestedReference ||
                (requestedIdentity != null && StorageAuthorization.safIdentity(configuredText) == requestedIdentity)
        }?.toString()

        val blockedReason = when {
            requestedReference.isEmpty() -> "A referência da pasta SAF não foi informada."
            configuredReference.isNullOrBlank() -> "A pasta SAF não está configurada na biblioteca."
            !canMutateSafRoots() -> "A remoção da pasta não pode acontecer enquanto outra operação de armazenamento está em andamento."
            else -> null
        }
        if (blockedReason != null) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "remove_saf",
                status = "BLOCKED",
                operationState = "BLOCKED",
                message = blockedReason,
                error = blockedReason,
            )
            publish(action = "remove_saf_folder", error = blockedReason)
            return false
        }

        pendingSafRemovalRequestId = resolvedRequestId
        pendingSafRemovalReference = configuredReference
        pendingSafRemovalBridge = bridge.trim().lowercase(Locale.ROOT).takeIf {
            it == "library" || it == "settings"
        } ?: "library"
        return try {
            activity.startActivity(
                MainActivity.createNativeStorageActionIntent(
                    context = activity,
                    action = "release_tree",
                    treeUri = configuredReference,
                    requestId = resolvedRequestId,
                )
            )
            publish(action = "remove_saf_folder")
            true
        } catch (failure: Exception) {
            val message = failure.message?.takeIf { it.isNotBlank() }
                ?: "Não foi possível remover a pasta SAF."
            clearPendingSafRemovalContext()
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "remove_saf",
                status = "ERROR",
                operationState = "ERROR",
                message = message,
                error = message,
            )
            publish(action = "remove_saf_folder", error = message)
            false
        }
    }

    fun removeConfiguredSafFolder(reference: String): Boolean {
        val normalized = reference.trim()
        if (normalized.isEmpty() || !canMutateSafRoots()) {
            publish(action = "remove_saf_folder", error = "Aguarde a varredura ou a seleção de pasta atual terminar.")
            return false
        }
        libraryViewModel.removeSafTree(normalized)
        publish(action = "remove_saf_folder")
        return true
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
        if (!canStartLibraryScan("saf")) {
            HomeRefreshCoordinator.fail(requestId, "no_authorized_library_saf_source")
            recordDiagnostic(
                name = "HOME_REFRESH_FAILED",
                severity = "WARN",
                requestId = requestId,
                refreshId = refreshId,
                result = "no_authorized_library_saf_source",
            )
            return false
        }
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
        const val MAX_SEEN_PROJECTION_EVENTS = 512
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
        val accepted = refreshCanonicalCatalog()
        if (accepted) {
            scheduleCatalogMetadataHydration("catalog_changed")
            scheduleThumbnailReconciliation("catalog_changed")
        }
        return accepted
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

    private fun projectNativeLibraryPageEpisode(
        episode: com.reiflix.reiflix_local.ui.model.ReiAnixEpisodeUiModel,
    ): org.json.JSONObject {
        val artwork = episode.artwork
        return org.json.JSONObject()
            .put("id", episode.id)
            .put("anime_id", episode.animeId)
            .put("season", episode.seasonNumber ?: org.json.JSONObject.NULL)
            .put("number", episode.number ?: org.json.JSONObject.NULL)
            .put("episode_title", episode.title ?: org.json.JSONObject.NULL)
            .put("file_name", episode.fileName)
            .put("progress", episode.progressSeconds ?: org.json.JSONObject.NULL)
            .put("duration", episode.durationSeconds ?: org.json.JSONObject.NULL)
            .put("watched", episode.watched ?: org.json.JSONObject.NULL)
            .put("consumption_state", episode.consumptionState?.name?.lowercase(Locale.ROOT) ?: org.json.JSONObject.NULL)
            .put("last_played_at", episode.lastPlayedAt ?: org.json.JSONObject.NULL)
            .put("modified_at", episode.modifiedAt ?: org.json.JSONObject.NULL)
            .put("file_size", episode.fileSizeBytes ?: org.json.JSONObject.NULL)
            .put("episode_type", episode.episodeType ?: org.json.JSONObject.NULL)
            .put("manual_override", episode.manualOverride)
            .put("cover_cache", artwork?.localPath ?: org.json.JSONObject.NULL)
            .put("cover_url", artwork?.externalUrl ?: org.json.JSONObject.NULL)
            .put("banner_url", artwork?.backdropExternalUrl ?: org.json.JSONObject.NULL)
            .put("path", episode.media.path ?: org.json.JSONObject.NULL)
            .put("uri", episode.media.uri ?: org.json.JSONObject.NULL)
            .put("media_identity", episode.media.mediaIdentity ?: org.json.JSONObject.NULL)
            .put(
                "availability_state",
                episode.media.sourceAvailabilityState ?: episode.media.availability.name.lowercase(Locale.ROOT),
            )
            .put("missing", episode.media.availability.name == "MISSING")
            .put("artwork_local_path", artwork?.localPath ?: org.json.JSONObject.NULL)
            .put("artwork_external_url", artwork?.externalUrl ?: org.json.JSONObject.NULL)
    }

    private fun projectNativeLibraryPageAnime(
        anime: com.reiflix.reiflix_local.ui.model.ReiAnixAnimeUiModel,
    ): org.json.JSONObject {
        val artwork = anime.artwork
        val meta = org.json.JSONObject()
            .put("title", anime.title)
            .put("year", anime.year ?: org.json.JSONObject.NULL)
            .put("media_kind", anime.mediaKind.name.lowercase(Locale.ROOT))
            .put("presentation_state", anime.presentationState.name.lowercase(Locale.ROOT))
            .put("metadata_status", anime.metadataAvailability.name.lowercase(Locale.ROOT))
            .put("description", anime.description ?: org.json.JSONObject.NULL)
            .put("romaji", anime.romajiTitle ?: org.json.JSONObject.NULL)
            .put("english", anime.englishTitle ?: org.json.JSONObject.NULL)
            .put("native", anime.nativeTitle ?: org.json.JSONObject.NULL)
            .put("aliases", org.json.JSONArray().apply { anime.aliases.forEach { put(it) } })
            .put("score", anime.score ?: org.json.JSONObject.NULL)
            .put("added_at", anime.addedAt ?: org.json.JSONObject.NULL)
            .put("status", anime.status ?: org.json.JSONObject.NULL)
            .put("episodes_count", anime.announcedEpisodeCount ?: org.json.JSONObject.NULL)
            .put("format", anime.format ?: org.json.JSONObject.NULL)
            .put("duration", anime.durationMinutes ?: org.json.JSONObject.NULL)
            .put("studio", anime.studio ?: org.json.JSONObject.NULL)
            .put("season", anime.seasonLabel ?: org.json.JSONObject.NULL)
            .put("cover_cache", artwork?.localPath ?: org.json.JSONObject.NULL)
            .put("cover_url", artwork?.externalUrl ?: org.json.JSONObject.NULL)
            .put("banner_url", artwork?.backdropExternalUrl ?: org.json.JSONObject.NULL)
        val seasons = org.json.JSONArray().apply {
            anime.seasons.forEach { season ->
                put(
                    org.json.JSONObject()
                        .put("season", season.number ?: org.json.JSONObject.NULL)
                        .put("season_name", season.title)
                        .put(
                            "episodes",
                            org.json.JSONArray().apply {
                                season.episodes.forEach { put(projectNativeLibraryPageEpisode(it)) }
                            },
                        ),
                )
            }
        }
        val specials = org.json.JSONArray().apply {
            anime.specials
                .groupBy { it.seasonNumber }
                .forEach { (seasonNumber, episodes) ->
                    put(
                        org.json.JSONObject()
                            .put("season", seasonNumber ?: org.json.JSONObject.NULL)
                            .put("season_name", seasonNumber?.let { "Temporada $it" } ?: "Especiais")
                            .put(
                                "episodes",
                                org.json.JSONArray().apply {
                                    episodes.forEach { put(projectNativeLibraryPageEpisode(it)) }
                                },
                            ),
                    )
                }
        }
        return org.json.JSONObject()
            .put("id", anime.id)
            .put("main_title", anime.title)
            .put("lookup_title", anime.romajiTitle ?: anime.englishTitle ?: anime.title)
            .put("favorite", anime.favorite)
            .put("is_pinned", anime.pinned)
            .put("last_played_at", anime.lastPlayedAt ?: org.json.JSONObject.NULL)
            .put("added_at", anime.addedAt ?: org.json.JSONObject.NULL)
            .put("media_kind", anime.mediaKind.name.lowercase(Locale.ROOT))
            .put("year", anime.year ?: org.json.JSONObject.NULL)
            .put("presentation_state", anime.presentationState.name.lowercase(Locale.ROOT))
            .put("artwork_available", artwork?.isAvailable ?: false)
            .put("playback_target_episode_id", anime.playbackTargetEpisodeId ?: org.json.JSONObject.NULL)
            .put("genres", org.json.JSONArray().apply { anime.genres.forEach { put(it.name) } })
            .put("genre_ids", org.json.JSONArray().apply { anime.genres.forEach { put(it.id ?: "") } })
            .put("user_tags", org.json.JSONArray().apply { anime.userTags.forEach { put(it) } })
            .put("personal_note", anime.personalNote ?: org.json.JSONObject.NULL)
            .put("artwork_local_path", artwork?.localPath ?: org.json.JSONObject.NULL)
            .put("artwork_external_url", artwork?.externalUrl ?: org.json.JSONObject.NULL)
            .put("backdrop_local_path", artwork?.backdropLocalPath ?: org.json.JSONObject.NULL)
            .put("backdrop_external_url", artwork?.backdropExternalUrl ?: org.json.JSONObject.NULL)
            .put("meta", meta)
            .put("seasons", seasons)
            .put("specials", specials)
            .put(
                "media_files",
                org.json.JSONArray().apply {
                    anime.mediaFiles.forEach { put(projectNativeLibraryPageEpisode(it)) }
                },
            )
    }

    /**
     * Compatibility for a queued legacy page request without reviving the Python
     * catalog writer. Filters and rows are served by the canonical SQLite-backed
     * Compose ViewModel and the result is projected using its current generation.
     */
    private fun requestLegacyLibraryPage(
        payload: org.json.JSONObject,
        requestId: String?,
    ): Boolean {
        val requestedPage = payload.optInt("page", 0).coerceAtLeast(0)
        val pageSize = payload.optInt("pageSize", 36).coerceIn(12, 48)
        val requestedGeneration = payload.optLong("generation", 0L).coerceAtLeast(0L)
        val query = payload.optString("query", "").trim()
        val rawGenre = payload.optString("genre", "Todos").trim().ifEmpty { "Todos" }
        val genre = rawGenre.takeUnless { it.equals("Todos", ignoreCase = true) }
        val sort = payload.optString("sort", "Mais recentes").trim().ifEmpty { "Mais recentes" }
        val favoritesOnly = payload.optBoolean("favoritesOnly", false)
        val watchingOnly = payload.optBoolean("watchingOnly", false)
        val completedOnly = payload.optBoolean("completedOnly", false)
        val normalizedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }

        return launchTracked("load_library_page", lane = "library") {
            try {
                require(requestedPage <= 100) {
                    "A página solicitada excede o limite de compatibilidade nativa."
                }
                libraryViewModel.applyLibraryPageSize(pageSize)
                libraryViewModel.setLibrarySearchQuery(query)
                libraryViewModel.setLibraryGenreFilter(genre)
                libraryViewModel.setLibrarySort(sort)
                if (libraryViewModel.libraryFilters.value.favoritesOnly != favoritesOnly) {
                    libraryViewModel.toggleLibraryFavoritesFilter()
                }
                if (libraryViewModel.libraryFilters.value.watchingOnly != watchingOnly) {
                    libraryViewModel.toggleLibraryWatchingFilter()
                }
                if (libraryViewModel.libraryFilters.value.completedOnly != completedOnly) {
                    libraryViewModel.toggleLibraryCompletedFilter()
                }

                val expectedFilters = libraryViewModel.libraryFilters.value
                fun matchesRequestedFilters(
                    state: com.reiflix.reiflix_local.ui.model.ReiAnixLibraryPagedUiState,
                ): Boolean =
                    state.query == query &&
                        state.genreKey == expectedFilters.selectedGenreKey &&
                        state.sort == expectedFilters.sort &&
                        state.favoritesOnly == expectedFilters.favoritesOnly &&
                        state.watchingOnly == expectedFilters.watchingOnly &&
                        state.completedOnly == expectedFilters.completedOnly &&
                        state.pageSize == pageSize &&
                        !state.isLoading &&
                        state.status.name != "LOADING"

                var pageState = withTimeoutOrNull(20_000L) {
                    libraryViewModel.pagedLibraryState.first(::matchesRequestedFilters)
                } ?: error("A consulta da biblioteca nativa não terminou no tempo esperado.")
                if (pageState.status.name == "ERROR") {
                    error(pageState.error ?: "A consulta da biblioteca nativa falhou.")
                }

                while (pageState.loadedPage < requestedPage && pageState.hasMore) {
                    val previousGeneration = pageState.generation
                    val nextPage = pageState.loadedPage + 1
                    libraryViewModel.loadNextLibraryPage()
                    pageState = withTimeoutOrNull(20_000L) {
                        libraryViewModel.pagedLibraryState.first { state ->
                            state.generation != previousGeneration ||
                                (!state.isLoading &&
                                    (state.loadedPage >= nextPage ||
                                        state.status.name == "ERROR" ||
                                        !state.hasMore))
                        }
                    } ?: error("A página solicitada da biblioteca não foi carregada a tempo.")
                    if (pageState.generation != previousGeneration) {
                        error("Os filtros da biblioteca mudaram durante a consulta; o resultado antigo foi descartado.")
                    }
                    if (pageState.status.name == "ERROR") {
                        error(pageState.error ?: "A consulta da página da biblioteca falhou.")
                    }
                }

                val offset = requestedPage * pageSize
                val items = pageState.animes.drop(offset).take(pageSize)
                val itemPayload = org.json.JSONArray().apply {
                    items.forEach { put(projectNativeLibraryPageAnime(it)) }
                }
                val pagePayload = org.json.JSONObject()
                    .put("kind", "library_page")
                    .put("generation", requestedGeneration)
                    .put("page", requestedPage)
                    .put("page_size", pageSize)
                    .put("total", pageState.totalCount)
                    .put("has_more", offset + items.size < pageState.totalCount)
                    .put("items", itemPayload)

                if (normalizedRequestId != null) {
                    publishOperationResult(
                        requestId = normalizedRequestId,
                        action = "load_library_page",
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        payload = pagePayload,
                    )
                } else {
                    publish(
                        action = "load_library_page",
                        transform = { it.copy(lastResultJson = pagePayload.toString()) },
                    )
                }
            } catch (cancelled: CancellationException) {
                normalizedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = "load_library_page",
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "Operação da biblioteca cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                normalizedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = "load_library_page",
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível carregar a página da biblioteca.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
    }

    /**
     * Retry extraction_failed events from older bridge clients, while letting the
     * in-process queue own retries for requests it is already processing.
     */
    private fun retryLegacyThumbnailError(
        payload: org.json.JSONObject,
        requestId: String?,
    ): Boolean {
        val rawReference = payload.optString("uri", payload.optString("path", "")).trim()
        if (rawReference.isEmpty()) return false
        val parsed = runCatching { Uri.parse(rawReference) }.getOrNull() ?: return false
        val uri = when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> parsed
            null -> if (rawReference.startsWith("/")) Uri.fromFile(File(rawReference)) else return false
            else -> return false
        }
        val size = payload.optLong("size", payload.optLong("file_size", 0L)).coerceAtLeast(0L)
        val modifiedAt = payload.optLong("modifiedAt", payload.optLong("modified_at", 0L)).coerceAtLeast(0L)
        val rawIdentity = payload.optString("mediaIdentity", payload.optString("media_identity", "")).trim()
        val identity = rawIdentity.ifEmpty { uri.toString() }
        val key = thumbnailRequestKey(uri, size, modifiedAt, identity)
        val status = payload.optString("status", "FAILED").trim().uppercase(Locale.ROOT)
        var attempt: Int? = null
        var retryDelayMs: Long? = null

        synchronized(thumbnailQueueLock) {
            if (thumbnailLatestKeyByUri[uri.toString()] != key) {
                recordDiagnostic(
                    name = "THUMBNAIL_STALE_ERROR_IGNORED",
                    requestId = requestId,
                    result = status,
                    context = mapOf("uri_present" to true),
                )
                return true
            }
            if (
                key in thumbnailRetryPendingKeys ||
                key in thumbnailRequests ||
                key in thumbnailInFlight
            ) {
                recordDiagnostic(
                    name = "THUMBNAIL_DUPLICATE_ERROR_IGNORED",
                    requestId = requestId,
                    result = status,
                )
                return true
            }

            val previousAttempts = thumbnailRetryCounts[key] ?: 0
            if (status == "EXTRACTION_FAILED" && previousAttempts < 2 && lifecycleIsAlive) {
                val nextAttempt = previousAttempts + 1
                thumbnailRetryCounts[key] = nextAttempt
                thumbnailRetryPendingKeys.add(key)
                attempt = nextAttempt
                retryDelayMs = if (previousAttempts == 0) 1_000L else 5_000L
            } else {
                thumbnailRetryCounts.remove(key)
                thumbnailRetryPendingKeys.remove(key)
                thumbnailLatestKeyByUri.remove(uri.toString(), key)
                thumbnailLatestAtMillis.remove(uri.toString())
                if (status == "EXTRACTION_FAILED") {
                    recordDiagnostic(
                        name = "THUMBNAIL_RETRY_EXHAUSTED",
                        severity = "WARN",
                        requestId = requestId,
                        result = status,
                        context = mapOf("attempts" to previousAttempts),
                    )
                }
            }
        }

        val scheduledAttempt = attempt
        val delayMs = retryDelayMs
        if (scheduledAttempt == null || delayMs == null) return true
        val accepted = launchTracked("thumbnail_retry", lane = "thumbnail") {
            try {
                delay(delayMs)
                if (thumbnailRetryCounts[key] != scheduledAttempt) return@launchTracked
                if (thumbnailLatestKeyByUri[uri.toString()] != key) {
                    thumbnailRetryCounts.remove(key)
                    return@launchTracked
                }
                if (!lifecycleIsAlive || activity.isFinishing || activity.isDestroyed) {
                    thumbnailRetryCounts.remove(key)
                    return@launchTracked
                }
                val queued = enqueueLocalThumbnail(
                    reference = uri.toString(),
                    size = size,
                    modifiedAt = modifiedAt,
                    mediaIdentity = identity,
                    priority = 200,
                )
                if (!queued) {
                    if (thumbnailRetryCounts[key] == scheduledAttempt) thumbnailRetryCounts.remove(key)
                    recordDiagnostic(
                        name = "THUMBNAIL_RETRY_REJECTED",
                        severity = "WARN",
                        requestId = requestId,
                        result = "queue_rejected",
                        context = mapOf("attempt" to scheduledAttempt),
                    )
                } else {
                    recordDiagnostic(
                        name = "THUMBNAIL_RETRY_SCHEDULED",
                        requestId = requestId,
                        result = "retry_$scheduledAttempt",
                        context = mapOf("attempt" to scheduledAttempt, "delay_ms" to delayMs),
                    )
                }
            } finally {
                thumbnailRetryPendingKeys.remove(key)
            }
        }
        if (!accepted) {
            thumbnailRetryPendingKeys.remove(key)
            if (thumbnailRetryCounts[key] == scheduledAttempt) thumbnailRetryCounts.remove(key)
        }
        return accepted
    }

    /**
     * Normalize the legacy request_missing_thumbnail(item) payload without reviving
     * the Python bridge. Missing episodes are rejected and default priority still
     * favors previously played media.
     */
    private fun enqueueThumbnailFromLegacyPayload(payload: org.json.JSONObject): Boolean {
        val nestedEpisode = payload.optJSONObject("current_episode")
            ?: payload.optJSONObject("currentEpisode")
            ?: payload.optJSONObject("episode")
        val episode = if (payload.optString("path", "").trim().isNotEmpty()) payload else nestedEpisode ?: payload
        if (payload.optBoolean("missing", false) || episode.optBoolean("missing", false)) return false

        fun firstText(vararg keys: String): String? {
            for (key in keys) {
                val top = payload.optString(key, "").trim()
                if (top.isNotEmpty()) return top
                val nested = episode.optString(key, "").trim()
                if (nested.isNotEmpty()) return nested
            }
            return null
        }

        val reference = firstText("uri", "path", "mediaUri", "media_uri") ?: return false
        val size = payload.optLong(
            "size",
            episode.optLong("file_size", episode.optLong("fileSize", 0L)),
        ).coerceAtLeast(0L)
        val modifiedAt = payload.optLong(
            "modifiedAt",
            payload.optLong(
                "modified_at",
                episode.optLong("modifiedAt", episode.optLong("modified_at", 0L)),
            ),
        ).coerceAtLeast(0L)
        val mediaIdentity = firstText("mediaIdentity", "media_identity")
        val lastPlayedAt = payload.optDouble(
            "lastPlayedAt",
            payload.optDouble(
                "last_played_at",
                episode.optDouble("lastPlayedAt", episode.optDouble("last_played_at", 0.0)),
            ),
        )
        val priority = if (payload.has("priority") && !payload.isNull("priority")) {
            payload.optInt("priority", 100)
        } else if (lastPlayedAt.isFinite() && lastPlayedAt > 0.0) {
            150
        } else {
            100
        }
        return enqueueLocalThumbnail(
            reference = reference,
            size = size,
            modifiedAt = modifiedAt,
            mediaIdentity = mediaIdentity,
            priority = priority,
        )
    }

    /**
     * Compatibility for LibraryStore.set_watched(path, watched, episode_id):
     * the episode ID is authoritative, but a supplied path that resolves to
     * another episode must reject the write rather than mutating the wrong row.
     */
    private fun markEpisodeWatchedFromLegacyCommand(
        episodeId: Long,
        watched: Boolean,
        path: String?,
        requestId: String?,
        action: String,
    ): Boolean {
        if (episodeId <= 0L) return false
        val normalizedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedRequestId != null) {
            publishOperationResult(
                requestId = normalizedRequestId,
                action = action,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Atualização do estado do episódio enfileirada.",
            )
        }
        val accepted = launchTracked(action, lane = "playback") {
            try {
                val canonicalEpisode = dependencies.sqliteLibraryRepository.getEpisode(episodeId)
                    ?: throw IllegalArgumentException("Episódio não encontrado.")
                val suppliedPath = path?.trim().orEmpty()
                if (suppliedPath.isNotEmpty()) {
                    val pathEpisode = dependencies.sqliteLibraryRepository.findEpisodeByPathOrFileUri(suppliedPath)
                    require(pathEpisode == null || pathEpisode.id == canonicalEpisode.id) {
                        "Episódio não encontrado ou referência local incompatível."
                    }
                }
                val progress = dependencies.consumptionService.setWatched(episodeId, watched)
                libraryViewModel.refresh()
                scheduleCatalogMetadataHydration("catalog_changed")
                scheduleThumbnailReconciliation("catalog_changed")
                val result = org.json.JSONObject()
                    .put("episodeId", progress.episodeId)
                    .put("positionMs", progress.positionMs)
                    .put("durationMs", progress.durationMs)
                    .put("watched", progress.watched)
                if (normalizedRequestId != null) {
                    publishOperationResult(
                        requestId = normalizedRequestId,
                        action = action,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        payload = result,
                    )
                } else {
                    publish(action = action, transform = {
                        it.copy(lastResultJson = result.toString())
                    })
                }
            } catch (cancelled: CancellationException) {
                normalizedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = action,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "Atualização do estado do episódio cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                normalizedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = action,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível atualizar o estado do episódio.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && normalizedRequestId != null) {
            publishOperationResult(
                requestId = normalizedRequestId,
                action = action,
                status = "FAILED",
                operationState = "FAILED",
                message = "A atualização do estado do episódio não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    /**
     * Typed replacement for the legacy main.py action/event branching. Values
     * are validated at this boundary, and domain writes still run in their
     * canonical Kotlin service. Unknown actions are rejected, not acknowledged.
     */
    fun dispatchMainAction(action: String, payload: org.json.JSONObject, requestId: String? = null): Boolean {
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
            "navigate_back", "back", "previous", "settings_inner" -> navigateBack()
            "navigate_settings_category" ->
                navigateSettingsCategory(nonBlank("category") ?: return false)

            "load_library_page" -> requestLegacyLibraryPage(
                payload = payload,
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
            )
            "refresh" -> requestLibraryScan(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
            "refresh_library" -> refreshLibrary()
            "refresh_catalog", "refresh_canonical_catalog" -> refreshCanonicalCatalog()
            "publish_catalog" -> {
                // The canonical write was committed by Kotlin already; mirror on_catalog_changed
                // by updating projections and scheduling coalesced metadata/artwork reconciliation.
                invalidateCatalogViews()
                scheduleCatalogMetadataHydration("catalog_changed")
                scheduleThumbnailReconciliation("catalog_changed")
                val commandRequestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id")
                if (!commandRequestId.isNullOrBlank()) {
                    publishOperationResult(
                        requestId = commandRequestId,
                        action = normalized,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Projeção do catálogo nativo atualizada.",
                    )
                }
                true
            }
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
            "switch", "switch_account", "switchaccount" -> switchAccount()
            "account_state_changed" -> {
                setAccountState(payload.optString("state", "disconnected"), "native_event")
                true
            }

            "select_saf", "select_tree", "add_folder" -> requestSafFolderSelection(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                bridge = nonBlank("bridge") ?: "library",
            )
            "request_media_access", "request_video_access" ->
                requestMediaAccess(
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    resultAction = normalized,
                )
            "check_storage_access", "check_video_access" ->
                checkStorageAccess(
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    resultAction = normalized,
                )
            "open_broad_storage_settings", "request_storage_access" ->
                openBroadStorageSettings(
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    resultAction = normalized,
                )
            "scan_library" -> requestLibraryScan(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
            "refresh_home_library", "request_home_refresh" -> requestHomeRefresh(
                source = nonBlank("source") ?: "native_action",
            )
            "remove_folder", "remove_saf_tree", "remove_saf", "release_tree" ->
                (nonBlank("treeUri") ?: nonBlank("tree_uri") ?: nonBlank("reference") ?: nonBlank("source"))
                    ?.let { reference ->
                        requestSafFolderRemoval(
                            reference = reference,
                            requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                            bridge = nonBlank("bridge") ?: "library",
                        )
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
            "mark_episode_watched", "set_episode_watched", "set_watched" -> {
                val id = positiveLong("episodeId") ?: positiveLong("episode_id") ?: return false
                // Preserve the old Compose Library default (false for set_watched)
                // while enforcing path/ID agreement in the canonical SQLite catalog.
                val watched = payload.optBoolean("watched", normalized != "set_watched")
                markEpisodeWatchedFromLegacyCommand(
                    episodeId = id,
                    watched = watched,
                    path = nonBlank("uri") ?: nonBlank("path"),
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    action = normalized,
                )
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
                val remoteId = positiveLong("anilistId") ?: positiveLong("anilist_id") ?: return false
                val animeId = positiveLong("animeId") ?: positiveLong("anime_id")
                val lookupTitle = nonBlank("lookupTitle") ?: nonBlank("lookup_title")
                if (animeId == null && lookupTitle.isNullOrBlank()) return false
                launchTracked("associate_anilist", lane = "metadata") {
                    val resolvedAnimeId = animeId
                        ?: dependencies.sqliteLibraryRepository.getAnimeByLookupTitle(lookupTitle!!)
                            ?.id
                        ?: throw IllegalArgumentException("A chave local do anime não existe no catálogo SQLite.")
                    val result = dependencies.aniListIntegrationService.associateManually(
                        resolvedAnimeId,
                        remoteId.toInt(),
                    )
                    if (result is com.reiflix.reiflix_local.data.anilist.AniListResult.Failure) {
                        throw IllegalStateException(result.error.toString())
                    }
                    libraryViewModel.refreshDetails(resolvedAnimeId)
                    libraryViewModel.refresh()
                    settingsViewModel.refresh()
                    publish(
                        action = "associate_anilist",
                        transform = { it.copy(lastResultJson = result.toString(), selectedAnimeId = resolvedAnimeId) },
                    )
                    recordDiagnostic(
                        name = "ANILIST_MANUAL_ASSOCIATION_COMPLETED",
                        requestId = requestId,
                        result = "associated",
                        context = mapOf("anime_id" to resolvedAnimeId, "anilist_id" to remoteId),
                    )
                }
            }
            "reconcile_unmatched_metadata" -> reconcileUnmatchedAniListMetadata()
            "request_anime_artwork" -> positiveLong("animeId")?.let {
                requestAnimeArtwork(it, payload.optBoolean("force", false))
            } ?: false
            "request_pending_artwork" -> requestPendingArtwork()
            "clear_artwork_cache" -> clearManagedArtworkCache()
            "request_missing_thumbnail", "request_thumbnail" -> enqueueThumbnailFromLegacyPayload(payload)
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

    /**
     * Translates the legacy mailbox event switch to an idempotent Kotlin event
     * boundary. Canonical scan/index/SQLite writes are never replayed here; only
     * projection refreshes and explicitly non-native-persisted settings commands
     * can be applied. Duplicate event IDs are ignored within this process.
     */
    /**
     * Kotlin replacement for the old ComposeSettingsBridge result writer. A QUEUED
     * state is never upgraded to COMPLETED until a real operation callback arrives.
     */
    private fun finishPendingStorageOperation(
        eventRequestId: String?,
        expectedNativeActions: Set<String>,
        status: String,
        message: String? = null,
        error: String? = null,
        payload: org.json.JSONObject? = null,
    ): Boolean {
        val request = eventRequestId?.trim().orEmpty()
        if (request.isEmpty()) return false
        val pending = pendingStorageOperations[request] ?: return false
        if (pending.nativeAction !in expectedNativeActions) return false

        val terminalStatus = status.trim().uppercase(Locale.ROOT)
        if (terminalStatus !in setOf("COMPLETED", "BLOCKED", "FAILED", "CANCELLED", "ERROR")) return false
        if (!pendingStorageOperations.remove(request, pending)) return false
        return publishOperationResult(
            requestId = request,
            action = pending.resultAction,
            status = terminalStatus,
            operationState = terminalStatus,
            message = message,
            error = error,
            payload = payload,
        )
    }

    fun publishOperationResult(
        requestId: String,
        action: String,
        status: String,
        operationState: String = status,
        message: String? = null,
        error: String? = null,
        payload: org.json.JSONObject? = null,
    ): Boolean {
        val request = requestId.trim()
        val operation = action.trim()
        val normalizedStatus = status.trim().uppercase(Locale.ROOT)
        val normalizedState = operationState.trim().uppercase(Locale.ROOT)
        if (request.isEmpty() || operation.isEmpty()) return false
        if (normalizedStatus !in setOf("ACK", "QUEUED", "COMPLETED", "BLOCKED", "FAILED", "CANCELLED", "ERROR")) return false
        if (normalizedState !in setOf("RECEIVED", "QUEUED", "RUNNING", "COMPLETED", "BLOCKED", "FAILED", "CANCELLED", "ERROR")) return false
        val result = NativeMainOperationResult(
            requestId = request,
            action = operation,
            status = normalizedStatus,
            operationState = normalizedState,
            message = message?.take(500),
            error = error?.take(500),
            payloadJson = payload?.toString(),
        )
        mutableOperationResult.value = result
        publish(action = "operation_result", error = result.error, transform = {
            it.copy(lastResultJson = org.json.JSONObject()
                .put("requestId", result.requestId)
                .put("action", result.action)
                .put("status", result.status)
                .put("operationState", result.operationState)
                .put("message", result.message ?: org.json.JSONObject.NULL)
                .put("error", result.error ?: org.json.JSONObject.NULL)
                .put("payload", payload?.let { org.json.JSONObject(it.toString()) } ?: org.json.JSONObject.NULL)
                .put("updatedAtMillis", result.updatedAtMillis)
                .toString())
        })
        recordDiagnostic(
            name = "NATIVE_OPERATION_RESULT",
            severity = if (normalizedStatus in setOf("FAILED", "ERROR", "BLOCKED")) "WARN" else "INFO",
            requestId = request,
            result = normalizedState,
            context = mapOf("action" to operation, "status" to normalizedStatus, "has_error" to !result.error.isNullOrBlank()),
        )
        return true
    }

    fun operationResultFor(requestId: String): NativeMainOperationResult? =
        mutableOperationResult.value?.takeIf { it.requestId == requestId.trim() }

    fun settingsViewPaths(settingsPath: List<String>): List<List<String>> {
        val current = settingsPath.map { it.trim() }.filter { it.isNotEmpty() }
        return buildList {
            add(emptyList())
            for (index in 1..current.size) add(current.take(index))
        }
    }

    /** Native Compose shell descriptor replacing the Flet View/Control cache. */
    fun viewShell(
        route: String,
        viewRoute: String = route,
        key: String = route,
        settingsPath: List<String> = emptyList(),
    ): NativeMainViewShell {
        val normalizedRoute = routeForScreen(route).ifBlank { route.trim() }.ifBlank { ReiAnixRoutes.HOME }
        val normalizedViewRoute = routeForScreen(viewRoute).ifBlank { viewRoute.trim() }.ifBlank { normalizedRoute }
        return NativeMainViewShell(
            route = normalizedRoute,
            viewRoute = normalizedViewRoute,
            key = key.trim().ifBlank { normalizedViewRoute },
            generation = mutableSnapshot.value.generation,
            stateSignature = renderSignature(),
            settingsPath = settingsPath.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun buildScreenWithSettingsPath(
        route: String,
        force: Boolean = false,
        settingsPathOverride: List<String>? = null,
    ): Boolean {
        if (settingsPathOverride != null) {
            setSettingsUiPreference("settings_path", settingsPathOverride)
        }
        return renderCurrent(route, force, "build_screen")
    }

    /** Versioned durable-state contract translated from _navigation_state_payload. */
    fun loadNavigationState(): NativeMainUiPreferences = mutableUiPreferences.value

    fun navigationStatePayload(): org.json.JSONObject =
        preferencePayload(mutableUiPreferences.value)

    fun writeNavigationState(payload: org.json.JSONObject): Boolean {
        fun readMap(key: String): Map<String, Any?> {
            val objectValue = payload.optJSONObject(key) ?: return emptyMap()
            val target = linkedMapOf<String, Any?>()
            val iterator = objectValue.keys()
            while (iterator.hasNext()) {
                val name = iterator.next()
                val item = objectValue.opt(name)
                if (item != null && item !== org.json.JSONObject.NULL) target[name] = item
            }
            return target
        }
        val next = NativeMainUiPreferences(
            homeState = readMap("home_state"),
            organizeState = readMap("organize_state"),
            settingsState = readMap("settings_state"),
        )
        mutableUiPreferences.value = next
        return flushUiPreferences()
    }

    fun persistNavigationState(): Boolean = flushUiPreferences()

    fun flushNavigationState(): Boolean = flushUiPreferences()

    fun clearNavigationState() = clearPersistedNavigationState()

    fun handleFletViewPop(): Boolean = handleNativeBack()

    fun handlePageDisconnect() = onRuntimeDisconnect()
    fun handlePageConnect() = onRuntimeReconnect()
    fun handleAppLifecycleStateChange(eventOrState: Any?) = onLifecycleEvent(eventOrState)

    fun scanCoordinatorStateChanged(snapshots: Collection<ScanProgressSnapshot>) =
        updateScanUiState(snapshots)

    fun publishHomeRefreshState(): org.json.JSONObject {
        val state = HomeRefreshCoordinator.state.value
        return org.json.JSONObject()
            .put("active", state.active)
            .put("phase", state.phase.name)
            .put("requestId", state.requestId ?: org.json.JSONObject.NULL)
            .put("refreshId", state.refreshId ?: org.json.JSONObject.NULL)
            .put("source", state.source ?: org.json.JSONObject.NULL)
            .put("scanStarted", state.scanStarted)
            .put("scanTerminal", state.scanTerminal)
            .put("catalogUpdated", state.catalogUpdated)
            .put("terminalReason", state.terminalReason ?: org.json.JSONObject.NULL)
            .put("startedAtMillis", state.startedAtMillis ?: org.json.JSONObject.NULL)
    }

    fun homeRefreshUiUpdated() {
        publish(action = "home_refresh_ui_updated")
    }

    fun setHomeRefreshPhase(
        phase: String,
        requestId: String,
        reason: String? = null,
        refreshId: String = requestId,
        source: String = "native",
    ): Boolean {
        val normalized = phase.trim().uppercase(Locale.ROOT)
        val current = HomeRefreshCoordinator.state.value
        return when (normalized) {
            "IDLE" -> {
                if (current.active && current.requestId == requestId) {
                    HomeRefreshCoordinator.cancel(requestId, reason ?: "reset_to_idle")
                } else true
            }
            "REQUESTED" -> HomeRefreshCoordinator.begin(requestId, refreshId, source)
            "RUNNING", "SCANNING" -> {
                if (!current.active) HomeRefreshCoordinator.begin(requestId, refreshId, source)
                HomeRefreshCoordinator.markScanStarted(requestId)
            }
            "CATALOG_UPDATING", "SCAN_TERMINAL" ->
                HomeRefreshCoordinator.markScanTerminal(requestId)
            "UI_COMMIT", "CATALOG_UPDATED" ->
                HomeRefreshCoordinator.markCatalogUpdated(requestId)
            "SUCCESS", "COMPLETED" -> HomeRefreshCoordinator.complete(requestId)
            "ERROR", "FAILED" -> HomeRefreshCoordinator.fail(requestId, reason ?: "failed")
            "CANCELLED", "CANCELED" -> HomeRefreshCoordinator.cancel(requestId, reason ?: "cancelled")
            else -> false
        }
    }

    fun setHomeRefreshError(requestId: String, reason: String): Boolean =
        HomeRefreshCoordinator.fail(requestId, reason)

    fun setHomeRefreshCancelled(requestId: String, reason: String = "cancelled"): Boolean =
        HomeRefreshCoordinator.cancel(requestId, reason)

    fun dispatchArtworkEvent(eventName: String, payload: org.json.JSONObject): Boolean =
        dispatchNativeEvent("artwork_published", payload.putIfAbsentSafe("event", eventName))

    fun dispatchMetadataChange(eventName: String, payload: org.json.JSONObject): Boolean =
        dispatchNativeEvent(eventName, payload)


    fun completeNativeOperation(
        requestId: String,
        action: String,
        payload: org.json.JSONObject? = null,
        message: String? = null,
    ): Boolean = publishOperationResult(
        requestId = requestId,
        action = action,
        status = "COMPLETED",
        operationState = "COMPLETED",
        payload = payload,
        message = message,
    )

    fun failNativeOperation(requestId: String, action: String, error: String): Boolean =
        publishOperationResult(
            requestId = requestId,
            action = action,
            status = "ERROR",
            operationState = "ERROR",
            error = error,
        )

    fun queueNativeOperation(requestId: String, action: String, message: String? = null): Boolean =
        publishOperationResult(
            requestId = requestId,
            action = action,
            status = "ACK",
            operationState = "QUEUED",
            message = message ?: "Operacao nativa aceita para processamento.",
        )

    fun dispatchNativeCommand(
        requestId: String,
        action: String,
        payload: org.json.JSONObject = org.json.JSONObject(),
    ): Boolean {
        val request = requestId.trim()
        val normalizedAction = action.trim()
        if (request.isEmpty() || normalizedAction.isEmpty()) return false
        if (!claimRequestId(request, "native_command")) return false
        if (!queueNativeOperation(request, normalizedAction)) return false
        val accepted = dispatchMainAction(normalizedAction, payload, request)
        if (!accepted) {
            failNativeOperation(request, normalizedAction, "A operacao nativa nao foi aceita.")
            return false
        }
        recordDiagnostic(name = "NATIVE_OPERATION_ACCEPTED", requestId = request, result = "queued",
            context = mapOf("action" to normalizedAction))
        return true
    }

    /**
     * Re-scan only the tree whose canonical document identity was successfully re-keyed.
     * This mirrors the legacy follow-up request without changing source configuration or
     * rescanning after a rejected/ambiguous reconciliation.
     */
    private fun requestSafIdentityReconciliationRescan(
        treeUri: String,
        sourceRequestId: String? = null,
    ): Boolean {
        val normalizedTreeUri = treeUri.trim()
        if (normalizedTreeUri.isEmpty()) return false
        return launchTracked("saf_identity_followup_scan", lane = "scan") {
            val persistedReadUris = activity.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri.toString() }
            if (
                StorageAuthorization.safAccess(normalizedTreeUri, persistedReadUris) != SafAccessLevel.AVAILABLE ||
                authorizedScanTargets("saf", normalizedTreeUri).isEmpty()
            ) {
                recordDiagnostic(
                    name = "SAF_IDENTITY_FOLLOWUP_SCAN_SKIPPED",
                    severity = "WARN",
                    requestId = sourceRequestId,
                    result = "tree_not_authorized",
                    context = mapOf("tree_uri_present" to true),
                )
                return@launchTracked
            }
            val followupRequestId = UUID.randomUUID().toString()
            withContext(Dispatchers.Main) {
                activity.startActivity(
                    MainActivity.createNativeStorageActionIntent(
                        context = activity,
                        action = "scan_tree",
                        treeUri = normalizedTreeUri,
                        requestId = followupRequestId,
                        reason = "saf_document_identity_reconciled",
                    ),
                )
            }
            recordDiagnostic(
                name = "SAF_IDENTITY_FOLLOWUP_SCAN_REQUESTED",
                requestId = followupRequestId,
                result = "scan_tree",
                context = mapOf("source_request_id" to sourceRequestId),
            )
        }
    }

    fun dispatchNativeEvent(
        eventType: String,
        payload: org.json.JSONObject,
        eventRequestId: String? = null,
        eventId: String? = null,
    ): Boolean {
        val type = eventType.trim().lowercase(Locale.ROOT)
        if (type.isBlank()) return false
        val normalizedEventId = eventId?.trim().orEmpty()
        if (normalizedEventId.isNotEmpty()) {
            synchronized(lock) {
                if (!seenProjectionEventIds.add(normalizedEventId)) return true
                while (seenProjectionEventIds.size > MAX_SEEN_PROJECTION_EVENTS) {
                    val iterator = seenProjectionEventIds.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    } else break
                }
            }
        }
        fun longValue(vararg keys: String): Long? {
            for (key in keys) {
                val value = payload.optLong(key, 0L)
                if (value > 0L) return value
            }
            return null
        }
        fun stringValue(vararg keys: String): String? {
            for (key in keys) {
                val value = payload.optString(key, "").trim()
                if (value.isNotEmpty()) return value
            }
            return null
        }
        return when (type) {
            "anilist_metadata_committed" -> {
                val animeId = longValue("animeId", "anime_id") ?: return false
                refreshCurrentDetails(animeId)
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = "ANILIST_METADATA_PROJECTION_REFRESH",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = "canonical_write_already_committed",
                    context = mapOf("anime_id" to animeId),
                )
                true
            }
            "native_genres_committed" -> {
                val count = payload.optInt("changedCount", payload.optInt("changed_count", 0))
                if (count <= 0) return false
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = "NATIVE_GENRES_PROJECTION_REFRESH",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = "canonical_bulk_write_already_committed",
                    context = mapOf("changed_count" to count),
                )
                true
            }
            "artwork_published" -> {
                val event = stringValue("event")?.uppercase(Locale.ROOT) ?: return false
                val entity = stringValue("entity_type", "entityType")?.lowercase(Locale.ROOT) ?: return false
                val artworkType = stringValue("artwork_type", "artworkType") ?: return false
                val entityId = longValue("entity_id", "entityId") ?: return false
                val path = stringValue("local_path", "localPath") ?: return false
                val localFile = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
                if (event != "ARTWORK_PUBLISHED" ||
                    entity !in setOf("anime", "movie", "episode", "season", "special") ||
                    !localFile.isFile
                ) {
                    recordDiagnostic(
                        name = "ARTWORK_PUBLICATION_REJECTED",
                        severity = "WARN",
                        result = "invalid_local_publication",
                        context = mapOf("entity_type" to entity, "entity_id" to entityId, "artwork_type" to artworkType),
                    )
                    return false
                }
                if (entity in setOf("anime", "movie")) libraryViewModel.refreshDetails(entityId)
                libraryViewModel.queryNativeArtwork()
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = "ARTWORK_PROJECTION_REFRESH",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = "local_artwork_published",
                    context = mapOf("entity_type" to entity, "entity_id" to entityId, "artwork_type" to artworkType),
                )
                true
            }
            "compose_settings_set" -> {
                val key = stringValue("key") ?: return false
                val value = if (payload.has("value")) payload.opt("value")?.toString().orEmpty() else ""
                if (ReiAnixSettingsContract.definitions[key] == null) return false
                val requestId = eventRequestId ?: stringValue("requestId")
                if (requestId.isNullOrBlank() || !claimRequestId(requestId, "compose_settings")) {
                    recordDiagnostic(
                        name = "COMPOSE_SETTINGS_DUPLICATE_OR_INVALID_REQUEST",
                        severity = "WARN",
                        requestId = requestId,
                        result = "ignored",
                        context = mapOf("key" to key),
                    )
                    return false
                }
                if (payload.optBoolean("nativePersisted", payload.optBoolean("native_persisted", false))) {
                    settingsViewModel.refresh()
                    recordDiagnostic("SETTINGS_RUNTIME_APPLY", requestId = requestId,
                        result = "native_persisted_projection_refresh", context = mapOf("key" to key))
                    true
                } else {
                    val accepted = setSettingSerialized(key, value)
                    recordDiagnostic("SETTINGS_COMMAND_RECEIVED", requestId = requestId,
                        result = if (accepted) "queued" else "rejected", context = mapOf("key" to key))
                    accepted
                }
            }
            "compose_settings_action" -> {
                val action = stringValue("action")?.lowercase(Locale.ROOT) ?: return false
                val requestId = eventRequestId ?: stringValue("requestId")
                if (requestId.isNullOrBlank() || !claimRequestId(requestId, "compose_settings")) return false
                val accepted = dispatchSettingsAction(action, payload, requestId)
                recordDiagnostic("SETTINGS_ACTION_DISPATCH", requestId = eventRequestId ?: stringValue("requestId"),
                    result = if (accepted) "accepted" else "rejected", context = mapOf("action" to action))
                accepted
            }
            "compose_account_action" -> {
                val action = stringValue("action")?.lowercase(Locale.ROOT) ?: return false
                val requestId = eventRequestId ?: stringValue("requestId")
                if (action !in setOf("login", "logout", "switch", "switch_account") ||
                    requestId.isNullOrBlank() ||
                    !claimRequestId(requestId, "compose_account")
                ) return false
                requestAccountActionTracked(if (action == "switch") "switch_account" else action)
            }
            "compose_library_command" -> {
                val action = stringValue("action") ?: return false
                val requestId = eventRequestId ?: stringValue("requestId")
                if (requestId.isNullOrBlank() || !claimRequestId(requestId, "compose_library")) return false
                dispatchMainAction(action, payload, requestId)
            }
            "saf_permission", "saf_released", "saf_error", "saf_cancelled",
            "storage_status", "volume_changed",
            "mediastore_permission", "mediastore_error",
            "broad_storage_permission", "broad_storage_status", "broad_storage_error" -> {
                val correlatedRequestId = eventRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: stringValue("requestId")
                when (type) {
                    "saf_cancelled" -> finishPendingSafSelection(
                        eventRequestId = correlatedRequestId,
                        status = "CANCELLED",
                        message = stringValue("reason", "message") ?: "Seleção de pasta cancelada.",
                        payload = payload,
                    )
                    "saf_error" -> {
                        val message = stringValue("message", "reason", "status")
                            ?: "Não foi possível concluir a operação de armazenamento."
                        val error = stringValue("error", "reason", "status") ?: "saf_operation_failed"
                        finishPendingSafSelection(
                            eventRequestId = correlatedRequestId,
                            status = "ERROR",
                            message = message,
                            error = error,
                            payload = payload,
                        )
                        finishPendingSafRemoval(
                            eventRequestId = correlatedRequestId,
                            treeUri = stringValue("treeUri", "tree_uri"),
                            status = "ERROR",
                            message = message,
                            error = error,
                            payload = payload,
                        )
                    }
                    "saf_released" -> finishPendingSafRemoval(
                        eventRequestId = correlatedRequestId,
                        treeUri = stringValue("treeUri", "tree_uri"),
                        status = "COMPLETED",
                        message = "Pasta removida da biblioteca.",
                        payload = payload,
                    )
                    "mediastore_permission" -> {
                        val granted = payload.optBoolean("granted", false)
                        finishPendingStorageOperation(
                            eventRequestId = correlatedRequestId,
                            expectedNativeActions = setOf("request_media_access"),
                            status = if (granted) "COMPLETED" else "FAILED",
                            message = if (granted) "Permissão de acesso aos vídeos confirmada."
                                else "A permissão de acesso aos vídeos foi negada.",
                            error = if (granted) null else "media_access_denied",
                            payload = payload,
                        )
                    }
                    "mediastore_error" -> finishPendingStorageOperation(
                        eventRequestId = correlatedRequestId,
                        expectedNativeActions = setOf("request_media_access"),
                        status = "FAILED",
                        message = stringValue("message", "error") ?: "Não foi possível obter acesso aos vídeos.",
                        error = stringValue("error", "status") ?: "media_access_failed",
                        payload = payload,
                    )
                    "broad_storage_permission" -> {
                        val granted = payload.optBoolean("granted", false)
                        finishPendingStorageOperation(
                            eventRequestId = correlatedRequestId,
                            expectedNativeActions = setOf("open_broad_storage_settings"),
                            status = if (granted) "COMPLETED" else "BLOCKED",
                            message = if (granted) "Acesso amplo ao armazenamento confirmado."
                                else "O acesso amplo ao armazenamento não foi concedido.",
                            error = if (granted) null else "broad_storage_not_granted",
                            payload = payload,
                        )
                    }
                    "broad_storage_error" -> finishPendingStorageOperation(
                        eventRequestId = correlatedRequestId,
                        expectedNativeActions = setOf("open_broad_storage_settings"),
                        status = "FAILED",
                        message = stringValue("message", "error") ?: "Não foi possível abrir as configurações de armazenamento.",
                        error = stringValue("error", "status") ?: "broad_storage_action_failed",
                        payload = payload,
                    )
                    "saf_permission" -> if (payload.optBoolean("selected", false)) {
                        val treeUri = stringValue("treeUri", "tree_uri")
                        val persistedReadUris = activity.contentResolver.persistedUriPermissions
                            .filter { it.isReadPermission }
                            .map { it.uri.toString() }
                        val authorized = payload.optBoolean("granted", false) &&
                            !treeUri.isNullOrBlank() &&
                            StorageAuthorization.safAccess(treeUri, persistedReadUris) == SafAccessLevel.AVAILABLE &&
                            mergeAuthoritativeSafRoot(treeUri)
                        finishPendingSafSelection(
                            eventRequestId = correlatedRequestId,
                            status = if (authorized) "COMPLETED" else "ERROR",
                            message = if (authorized) "Pasta autorizada e configurada."
                                else "O Android não confirmou uma fonte SAF válida para a biblioteca.",
                            error = if (authorized) null else "saf_source_validation_failed",
                            payload = payload,
                        )
                    }
                }
                refreshAuthoritativeStorageSnapshot(payload, payload.optLong("createdAt", 0L))
                settingsViewModel.refresh()
                libraryViewModel.refreshNativeFolders()
                libraryViewModel.refresh()
                val reason = stringValue("reason", "state", "status", "message") ?: type
                recordDiagnostic(
                    name = "NATIVE_STORAGE_PROJECTION_REFRESH",
                    severity = if (type.endsWith("_error")) "WARN" else "INFO",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = reason,
                    context = mapOf(
                        "event_type" to type,
                        "broad_storage_is_library_source" to false,
                        "mediastore_is_library_source" to false,
                    ),
                )
                true
            }
            "native_error" -> {
                val correlatedRequestId = eventRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: stringValue("requestId")
                val nested = payload.optJSONObject("payload")
                fun errorField(vararg keys: String): String? {
                    stringValue(*keys)?.let { return it }
                    for (key in keys) {
                        val value = nested?.optString(key, "")?.trim().orEmpty()
                        if (value.isNotEmpty()) return value
                    }
                    return null
                }
                val failedAction = errorField("action")?.lowercase(Locale.ROOT) ?: return false
                val message = errorField("message", "detail")
                    ?: "A operação nativa foi rejeitada."
                val error = errorField("code", "error", "stage")
                    ?: "native_command_failed"
                when (failedAction) {
                    "select_tree" -> finishPendingSafSelection(
                        eventRequestId = correlatedRequestId,
                        status = "ERROR",
                        message = message,
                        error = error,
                        payload = payload,
                    )
                    "release_tree" -> finishPendingSafRemoval(
                        eventRequestId = correlatedRequestId,
                        treeUri = errorField("treeUri", "tree_uri"),
                        status = "ERROR",
                        message = message,
                        error = error,
                        payload = payload,
                    )
                    "request_media_access", "check_storage_access",
                    "open_broad_storage_settings", "scan_library" ->
                        finishPendingStorageOperation(
                            eventRequestId = correlatedRequestId,
                            expectedNativeActions = setOf(failedAction),
                            status = "FAILED",
                            message = message,
                            error = error,
                            payload = payload,
                        )
                    else -> {
                        recordDiagnostic(
                            name = "NATIVE_COMMAND_ERROR_OBSERVED",
                            severity = "WARN",
                            requestId = correlatedRequestId,
                            result = failedAction,
                            error = IllegalStateException(message),
                            context = mapOf("error_code" to error),
                        )
                        false
                    }
                }
            }
            "storage_capabilities" -> {
                val correlatedRequestId = eventRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: stringValue("requestId")
                finishPendingStorageOperation(
                    eventRequestId = correlatedRequestId,
                    expectedNativeActions = setOf("check_storage_access"),
                    status = "COMPLETED",
                    message = "Estado das permissões de armazenamento atualizado.",
                    payload = payload,
                )
                val capabilities = payload.optJSONObject("capabilities") ?: payload
                val publishedAt = payload.optLong(
                    "publishedAtMs",
                    payload.optLong("published_at_ms", payload.optLong("createdAt", 0L)),
                )
                refreshAuthoritativeStorageSnapshot(capabilities, publishedAt)
            }
            "saf_inventory", "saf_library_scan_complete" -> {
                if (type == "saf_inventory") recoverPendingSafSelectionFromInventory(payload)
                if (type == "saf_library_scan_complete") {
                    val status = stringValue("status")?.uppercase(Locale.ROOT) ?: "UNKNOWN"
                    if (status in setOf("COMPLETED", "PARTIAL")) {
                        scheduleCatalogMetadataHydration("native_scan_$status")
                        scheduleThumbnailReconciliation("native_scan_$status")
                    }
                    val requestId = eventRequestId?.trim()?.takeIf { it.isNotEmpty() } ?: stringValue("requestId")
                    val mappedStatus = when (status) {
                        "FAILED", "ERROR" -> "FAILED"
                        "CANCELLED" -> "CANCELLED"
                        "BLOCKED" -> "BLOCKED"
                        "COMPLETED", "PARTIAL" -> "COMPLETED"
                        else -> "FAILED"
                    }
                    finishPendingStorageOperation(
                        eventRequestId = requestId,
                        expectedNativeActions = setOf("scan_library"),
                        status = mappedStatus,
                        message = when (status) {
                            "COMPLETED" -> "Biblioteca atualizada."
                            "PARTIAL" -> "Atualização concluída parcialmente."
                            "CANCELLED" -> "Atualização da biblioteca cancelada."
                            "BLOCKED" -> "A atualização da biblioteca foi bloqueada."
                            else -> "Não foi possível concluir a atualização da biblioteca."
                        },
                        error = if (status in setOf("FAILED", "ERROR", "BLOCKED")) {
                            stringValue("error", "errorCode") ?: "library_scan_$status"
                        } else null,
                        payload = payload,
                    )
                }
                // Inventory is observational. It cannot create a source from an arbitrary
                // persisted grant or remove one when an inventory batch is incomplete.
                val treeUri = stringValue("treeUri", "tree_uri")
                if (!treeUri.isNullOrBlank()) mergeAuthoritativeSafRoot(treeUri)
                settingsViewModel.refresh()
                libraryViewModel.refreshNativeFolders()
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = "NATIVE_SAF_PROJECTION_REFRESH",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    scanId = stringValue("scanId", "scan_id"),
                    result = type,
                    context = mapOf("inventory_complete" to payload.optBoolean("complete", false)),
                )
                true
            }
            "saf_document_identity_change" -> {
                val oldUri = stringValue("oldUri", "old_uri") ?: return false
                val newUri = stringValue("newUri", "new_uri") ?: return false
                val name = stringValue("name", "displayName") ?: return false
                val treeUri = stringValue("treeUri", "tree_uri") ?: return false
                val nativeOwner = payload.optBoolean(
                    "nativeIdentityOwner",
                    payload.optBoolean("native_identity_owner", false),
                )
                if (nativeOwner) {
                    // A newer Kotlin scanner already committed the canonical re-key.
                    libraryViewModel.refreshNativeFolders()
                    libraryViewModel.refresh()
                    val reconciliationStatus = stringValue("status")?.lowercase(Locale.ROOT).orEmpty()
                    if (reconciliationStatus in setOf("reconciled", "already_reconciled")) {
                        requestSafIdentityReconciliationRescan(
                            treeUri = treeUri,
                            sourceRequestId = eventRequestId ?: stringValue("requestId"),
                        )
                    }
                    recordDiagnostic(
                        name = "SAF_IDENTITY_PROJECTION_REFRESH",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = reconciliationStatus.ifBlank { "native_commit_already_applied" },
                        context = mapOf("episode_id" to longValue("episodeId", "episode_id")),
                    )
                    true
                } else {
                    val persistedReadUris = activity.contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission }
                        .map { it.uri.toString() }
                    if (StorageAuthorization.safAccess(treeUri, persistedReadUris) != SafAccessLevel.AVAILABLE ||
                        authorizedScanTargets("saf", treeUri).isEmpty()
                    ) {
                        recordDiagnostic(
                            name = "SAF_IDENTITY_RECONCILIATION_REJECTED",
                            severity = "WARN",
                            requestId = eventRequestId ?: stringValue("requestId"),
                            result = "tree_not_authorized_library_source",
                        )
                        return false
                    }
                    launchTracked("saf_document_identity_change", lane = "library") {
                        val result = dependencies.sqliteLibraryRepository.reconcileSafDocumentIdentityChange(
                            oldUri = oldUri,
                            newUri = newUri,
                            name = name,
                            treeUri = treeUri,
                        )
                        val status = result.optString("status", "unknown")
                        publish(
                            action = "saf_document_identity_change",
                            error = if (status in setOf("rejected", "ambiguous", "not_found")) status else null,
                            transform = { it.copy(lastResultJson = result.toString()) },
                        )
                        if (status in setOf("reconciled", "already_reconciled")) {
                            libraryViewModel.refreshNativeFolders()
                            libraryViewModel.refresh()
                            libraryViewModel.queryNativeArtwork()
                            requestSafIdentityReconciliationRescan(
                                treeUri = treeUri,
                                sourceRequestId = eventRequestId ?: stringValue("requestId"),
                            )
                            recordDiagnostic(
                                name = "SAF_IDENTITY_RECONCILED",
                                requestId = eventRequestId ?: stringValue("requestId"),
                                result = status,
                                context = mapOf(
                                    "episode_id" to result.optLong("episode_id", 0L),
                                    "source" to "legacy_event_compatibility",
                                ),
                            )
                        } else {
                            recordDiagnostic(
                                name = "SAF_IDENTITY_RECONCILIATION_NOT_APPLIED",
                                severity = "WARN",
                                requestId = eventRequestId ?: stringValue("requestId"),
                                result = status,
                                context = mapOf("canonical_episode_row_preserved" to true),
                            )
                        }
                    }
                }
            }
            "saf_scan_progress", "mediastore_scan_progress", "broad_storage_scan_progress",
            "saf_scan", "mediastore_scan", "broad_storage_scan",
            "saf_scan_batch", "mediastore_scan_batch", "broad_storage_scan_batch",
            "scan_request" -> {
                val scanId = stringValue("scanId", "scan_id")
                val source = stringValue("source", "sourceKind", "source_kind")
                val operationState = stringValue("operationState", "status", "generationStatus")
                recordDiagnostic(
                    name = "NATIVE_SCAN_EVENT_OBSERVED",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    scanId = scanId,
                    severity = if (operationState.equals("FAILED", true) || operationState.equals("REVOKED", true)) "WARN" else "INFO",
                    result = operationState ?: type,
                    context = mapOf(
                        "event_type" to type,
                        "source" to source,
                        "canonical_writer" to "NativeScanController",
                        "payload_bytes" to payload.toString().length,
                    ),
                )
                // NativeScanController emits the authoritative StateFlow snapshots;
                // re-ingesting a reported batch here would create a second writer.
                true
            }
            "thumbnail_ready", "thumbnail_error" -> {
                val correlatedRequestId = eventRequestId ?: stringValue("requestId")
                if (type == "thumbnail_error") {
                    retryLegacyThumbnailError(payload, correlatedRequestId)
                } else {
                    val rawReference = stringValue("uri", "path")
                    if (!rawReference.isNullOrBlank()) {
                        val parsed = runCatching { Uri.parse(rawReference) }.getOrNull()
                        val uri = parsed?.let {
                            when (it.scheme?.lowercase(Locale.ROOT)) {
                                "content", "file" -> it
                                null -> if (rawReference.startsWith("/")) Uri.fromFile(File(rawReference)) else null
                                else -> null
                            }
                        }
                        if (uri != null) {
                            val size = payload.optLong("size", payload.optLong("file_size", 0L)).coerceAtLeast(0L)
                            val modifiedAt = payload.optLong("modifiedAt", payload.optLong("modified_at", 0L)).coerceAtLeast(0L)
                            val identity = stringValue("mediaIdentity", "media_identity") ?: uri.toString()
                            val key = thumbnailRequestKey(uri, size, modifiedAt, identity)
                            thumbnailRetryCounts.remove(key)
                            thumbnailRetryPendingKeys.remove(key)
                        }
                    }
                }
                libraryViewModel.queryNativeArtwork()
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = if (type == "thumbnail_ready") "NATIVE_THUMBNAIL_READY" else "NATIVE_THUMBNAIL_ERROR",
                    severity = if (type == "thumbnail_error") "WARN" else "INFO",
                    requestId = correlatedRequestId,
                    result = stringValue("path", "uri", "reason", "error") ?: type,
                    context = mapOf("media_identity" to stringValue("mediaIdentity", "media_identity")),
                )
                true
            }
            "google_account", "google_signed_out", "google_cancelled", "google_error", "google_sign_in_started" -> {
                val normalizedState = when (type) {
                    "google_account" -> "connected"
                    "google_signed_out" -> "disconnected"
                    "google_cancelled", "google_error", "google_sign_in_started" -> null
                    else -> null
                }
                if (normalizedState != null) setAccountState(normalizedState, "native_$type")
                settingsViewModel.refresh()
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = "NATIVE_ACCOUNT_EVENT",
                    severity = if (type == "google_error") "WARN" else "INFO",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = type,
                    error = stringValue("error", "message")?.let(::IllegalStateException),
                    context = mapOf("has_profile" to (payload.optJSONObject("profile") != null)),
                )
                true
            }
            "compose_settings_bulk_applied" -> {
                val requestId = eventRequestId ?: stringValue("requestId") ?: return false
                if (!claimRequestId(requestId, "compose_settings")) return false
                val values = payload.optJSONObject("settings") ?: return false
                val iterator = values.keys()
                val settings = linkedMapOf<String, String>()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    val raw = values.opt(key) ?: return false
                    settings[key] = raw.toString()
                }
                if (settings.isEmpty()) return false
                val nativePersisted = payload.optBoolean("nativePersisted", payload.optBoolean("native_persisted", true))
                val accepted = if (nativePersisted) {
                    settingsViewModel.refresh()
                    publishOperationResult(
                        requestId = requestId,
                        action = "settings_bulk_applied",
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        payload = org.json.JSONObject().put("refreshed", true).put("settingsCount", settings.size),
                    )
                    true
                } else {
                    applySettingsAtomically(settings)
                }
                recordDiagnostic(
                    name = "SETTINGS_RUNTIME_BULK_APPLY",
                    requestId = requestId,
                    result = if (accepted) "accepted" else "rejected",
                    context = mapOf("settings_count" to settings.size, "native_persisted" to nativePersisted),
                )
                accepted
            }
            "compose_settings_native_account_changed" -> {
                val requestId = eventRequestId ?: stringValue("requestId") ?: return false
                if (!claimRequestId(requestId, "compose_settings_account_changed")) return false
                settingsViewModel.refresh()
                libraryViewModel.refresh()
                setAccountState(
                    stringValue("accountState", "account_state", "state") ?: mutableSnapshot.value.accountState,
                    "native_account_changed",
                )
                completeNativeOperation(requestId, "account_changed")
            }
            "compose_settings_native_backup_restored" -> {
                val requestId = eventRequestId ?: stringValue("requestId") ?: return false
                if (!claimRequestId(requestId, "compose_settings_backup_restored")) return false
                settingsViewModel.refresh()
                val reconciled = reconcileRestoredFileAvailability()
                libraryViewModel.refresh()
                recordDiagnostic(
                    name = "NATIVE_BACKUP_RESTORE_PROJECTION_REFRESH",
                    requestId = requestId,
                    result = if (reconciled) "reconciliation_queued" else "catalog_refreshed",
                )
                completeNativeOperation(requestId, "backup_restored")
            }
            "performance_metrics", "performance_player" -> {
                recordDiagnostic(
                    name = "NATIVE_PERFORMANCE_EVENT",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                    result = type,
                    context = mapOf("payload" to payload.toString().take(2000)),
                )
                true
            }
            "player_opened" -> {
                val request = eventRequestId ?: stringValue("requestId") ?: return false
                val sessionId = stringValue("playerSessionId", "player_session_id", "sessionId")
                    ?: return false
                notePlayerSession(
                    requestId = request,
                    sessionId = sessionId,
                    transitionGeneration = payload.optLong("transitionGeneration", payload.optLong("generation", 0L)),
                )
                setActivePlayerMedia(
                    longValue("episodeId", "episode_id"),
                    longValue("animeId", "anime_id"),
                    stringValue("uri", "mediaUri", "media_uri"),
                )
                recordDiagnostic(
                    name = "PLAYER_OPENED_EVENT",
                    requestId = request,
                    sessionId = sessionId,
                    result = "session_active",
                )
                true
            }
            "player_paused", "player_completed" -> {
                val validation = validatePlayerCallback(
                    eventRequestId,
                    payload,
                    requireActive = true,
                    expectedEpisodeId = longValue("episodeId", "episode_id"),
                    expectedAnimeId = longValue("animeId", "anime_id"),
                    expectedMediaId = stringValue("mediaId", "media_id"),
                )
                if (!validation.isCurrent) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED",
                        severity = "WARN",
                        requestId = eventRequestId,
                        result = validation.reason,
                    )
                    return false
                }
                val episodeId = longValue("episodeId", "episode_id") ?: return false
                val position = payload.optLong("positionMs", payload.optLong("position_ms", -1L))
                val duration = payload.optLong("durationMs", payload.optLong("duration_ms", 0L))
                if (position < 0L) return false
                if (payload.optBoolean("nativeProgressPersisted", payload.optBoolean("native_progress_persisted", false))) {
                    // NativePlayerActivity already committed this checkpoint to canonical SQLite.
                    // Validate the callback and refresh Compose, but never issue a second write.
                    libraryViewModel.refresh()
                    recordDiagnostic(
                        name = "PLAYER_PROGRESS_NATIVE_COMMIT_REUSED",
                        requestId = eventRequestId,
                        sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                        result = type,
                        context = mapOf("episode_id" to episodeId, "position_ms" to position),
                    )
                    true
                } else {
                    savePlaybackProgress(
                        episodeId = episodeId,
                        positionMs = position,
                        durationMs = duration.takeIf { it > 0L },
                        completed = type == "player_completed" || payload.optBoolean("completed", false),
                    )
                }
            }
            "player_autoplay_changed" -> {
                // NativePlayerActivity persists player.autoplay_next before publishing this event.
                // Consume it as an invalidation signal, not as a second settings write.
                settingsViewModel.refresh()
                val enabled = payload.optBoolean("enabled", false)
                recordDiagnostic(
                    name = "PLAYER_AUTOPLAY_CHANGED",
                    requestId = eventRequestId,
                    result = if (enabled) "enabled" else "disabled",
                )
                true
            }
            "player_mark_watched", "player_mark_unwatched" -> {
                val validation = validatePlayerCallback(
                    eventRequestId,
                    payload,
                    requireActive = true,
                    expectedEpisodeId = longValue("episodeId", "episode_id"),
                    expectedAnimeId = longValue("animeId", "anime_id"),
                    expectedMediaId = stringValue("mediaId", "media_id"),
                )
                if (!validation.isCurrent) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED",
                        severity = "WARN",
                        requestId = eventRequestId,
                        result = validation.reason,
                    )
                    return false
                }
                val episodeId = longValue("episodeId", "episode_id") ?: return false
                markEpisodeWatchedTracked(episodeId, type == "player_mark_watched")
            }
            "player_error" -> {
                val validation = validatePlayerCallback(eventRequestId, payload, requireActive = true)
                if (!validation.isCurrent) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED",
                        severity = "WARN",
                        requestId = eventRequestId,
                        result = validation.reason,
                    )
                    return false
                }
                recordDiagnostic(
                    name = "PLAYER_RUNTIME_ERROR",
                    severity = "ERROR",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    sessionId = stringValue("playerSessionId", "sessionId"),
                    result = stringValue("errorCode", "code", "reason"),
                    error = IllegalStateException(stringValue("message", "error") ?: "Native player error"),
                    context = mapOf("episode_id" to longValue("episodeId", "episode_id")),
                )
                true
            }
            "player_next_request", "player_previous_request" -> {
                // In a native Media3 session the Activity owns adjacent-episode navigation;
                // do not dispatch a second launch from a mailbox projection callback.
                recordDiagnostic(
                    name = if (type == "player_next_request") "PLAYER_NEXT_ALREADY_NATIVE" else "PLAYER_PREVIOUS_ALREADY_NATIVE",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                    result = "delegated_to_native_player_activity",
                    context = mapOf("transition_direction" to if (type == "player_next_request") "NEXT" else "PREVIOUS"),
                )
                true
            }
            "player_progress", "playback_progress" -> {
                if (!validatePlayerCallback(eventRequestId, payload, requireActive = true).isCurrent) {
                    recordDiagnostic("PLAYER_CALLBACK_REJECTED", severity = "WARN",
                        requestId = eventRequestId, result = "stale_progress_callback")
                    return false
                }
                val episodeId = longValue("episodeId", "episode_id") ?: return false
                val positionMs = payload.optLong("positionMs", payload.optLong("position_ms", -1L))
                val durationMs = payload.optLong("durationMs", payload.optLong("duration_ms", 0L))
                if (positionMs < 0L) return false
                if (payload.optBoolean("nativeProgressPersisted", payload.optBoolean("native_progress_persisted", false))) {
                    // The player saved this exact episode checkpoint before publishing the mailbox event.
                    libraryViewModel.refresh()
                    recordDiagnostic(
                        name = "PLAYER_PROGRESS_NATIVE_COMMIT_REUSED",
                        requestId = eventRequestId,
                        sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                        result = type,
                        context = mapOf("episode_id" to episodeId, "position_ms" to positionMs),
                    )
                    true
                } else {
                    savePlaybackProgress(
                        episodeId = episodeId,
                        positionMs = positionMs,
                        durationMs = durationMs.takeIf { it > 0L },
                        completed = payload.optBoolean("completed", false),
                    )
                }
            }
            "player_exited", "player_exit" -> {
                val requestId = eventRequestId ?: stringValue("requestId") ?: return false
                notePlayerExit(
                    requestId = requestId,
                    atMs = payload.optLong("atMs", payload.optLong("at_ms", System.currentTimeMillis())),
                    sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                    activityInstanceId = stringValue("activityInstanceId", "activity_instance_id"),
                )
                true
            }
            "diagnostic" -> {
                val eventName = (stringValue("event", "name") ?: "NATIVE_MAILBOX_DIAGNOSTIC")
                    .trim()
                    .uppercase(Locale.ROOT)
                val requestId = eventRequestId ?: stringValue("requestId")
                val sessionId = stringValue("playerSessionId", "sessionId", "session_id")
                val activityInstanceId = stringValue("activityInstanceId", "activity_instance_id")
                when (eventName) {
                    "PLAYER_SESSION_CREATED" -> {
                        val createdSession = sessionId ?: stringValue("result") ?: return false
                        val current = mutableSnapshot.value
                        if (current.playerSessionActive &&
                            current.activePlayerSessionId != null &&
                            current.activePlayerSessionId != createdSession
                        ) {
                            recordDiagnostic(
                                name = "PLAYER_CALLBACK_STALE",
                                severity = "WARN",
                                requestId = requestId,
                                sessionId = createdSession,
                                result = "stale_session_created",
                                context = mapOf("current_session" to current.activePlayerSessionId),
                            )
                            return false
                        }
                        if (requestId.isNullOrBlank()) return false
                        notePlayerSession(
                            requestId,
                            createdSession,
                            payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                        )
                        if (!activityInstanceId.isNullOrBlank()) {
                            notePlayerActivityCreated(
                                activityInstanceId,
                                requestId,
                                createdSession,
                                payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                            )
                        }
                        true
                    }
                    "PLAYER_SESSION_INVALIDATED" -> {
                        val invalidSession = sessionId ?: return false
                        val current = mutableSnapshot.value
                        if (current.activePlayerSessionId != invalidSession ||
                            (!activityInstanceId.isNullOrBlank() &&
                                current.activePlayerActivityInstanceId != null &&
                                current.activePlayerActivityInstanceId != activityInstanceId)
                        ) {
                            recordDiagnostic(
                                name = "PLAYER_CALLBACK_STALE",
                                severity = "WARN",
                                requestId = requestId,
                                sessionId = invalidSession,
                                result = "stale_session_invalidated",
                            )
                            return false
                        }
                        invalidatePlayerSession(
                            reason = stringValue("reason") ?: "native_session_invalidated",
                            expectedSessionId = invalidSession,
                            expectedRequestId = requestId,
                        )
                    }
                    "PLAYER_HANDOFF_DISPATCHED" -> {
                        val active = mutableSnapshot.value
                        if (requestId.isNullOrBlank() ||
                            !active.playerSessionActive ||
                            active.activePlayerSessionId != sessionId
                        ) {
                            recordDiagnostic(
                                name = "PLAYER_CALLBACK_STALE",
                                severity = "WARN",
                                requestId = requestId,
                                sessionId = sessionId,
                                result = "stale_handoff_dispatch",
                            )
                            return false
                        }
                        publish(action = "player_handoff_dispatched", transform = {
                            it.copy(activePlayerRequestId = requestId)
                        })
                        true
                    }
                    "PLAYER_ACTIVITY_RESULT" -> {
                        val active = mutableSnapshot.value
                        val controlled = payload.optBoolean("controlled", false)
                        val instanceMatches = activityInstanceId.isNullOrBlank() ||
                            active.activePlayerActivityInstanceId == null ||
                            active.activePlayerActivityInstanceId == activityInstanceId
                        val requestMatches = active.activePlayerRequestId == null ||
                            active.activePlayerRequestId == requestId
                        if (controlled && instanceMatches && requestMatches) {
                            cancelPlayerTransition("player_activity_result")
                            invalidatePlayerSession("player_activity_result", sessionId, requestId)
                            true
                        } else false
                    }
                    "PLAYER_LIFECYCLE" -> {
                        val lifecycle = stringValue("lifecycle") ?: return false
                        val normalizedLifecycle = lifecycle.lowercase(Locale.ROOT)
                        val current = mutableSnapshot.value
                        if (!sessionId.isNullOrBlank() &&
                            !current.activePlayerSessionId.isNullOrBlank() &&
                            current.activePlayerSessionId != sessionId
                        ) return false
                        if (normalizedLifecycle in setOf("oncreate", "onnewintent", "onstart", "onresume")) {
                            if (requestId.isNullOrBlank() || sessionId.isNullOrBlank()) return false
                            notePlayerSession(
                                requestId,
                                sessionId,
                                payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                            )
                            if (!activityInstanceId.isNullOrBlank()) {
                                notePlayerActivityCreated(
                                    activityInstanceId,
                                    requestId,
                                    sessionId,
                                    payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                                )
                            }
                            val state = stringValue("sessionState")?.uppercase(Locale.ROOT)
                            if (state in setOf("EXITING", "DESTROYED")) {
                                invalidatePlayerSession("player_lifecycle_$lifecycle", sessionId, requestId)
                            }
                            true
                        } else if (normalizedLifecycle in setOf("ondestroy", "onstop") &&
                            stringValue("sessionState")?.uppercase(Locale.ROOT) in setOf("EXITING", "DESTROYED")
                        ) {
                            if (!sessionId.isNullOrBlank() && !requestId.isNullOrBlank()) {
                                invalidatePlayerSession("player_lifecycle_$lifecycle", sessionId, requestId)
                            } else false
                        } else {
                            NativeLifecycleCoordinator.onEvent(lifecycle)
                            true
                        }
                    }
                    "FIRST_FRAME_RENDERED" -> {
                        val validation = validatePlayerCallback(
                            requestId,
                            payload,
                            requireActive = true,
                        )
                        if (!validation.isCurrent) {
                            recordDiagnostic(
                                name = "PLAYER_CALLBACK_STALE",
                                severity = "WARN",
                                requestId = requestId,
                                sessionId = sessionId,
                                result = validation.reason,
                            )
                            false
                        } else {
                            recordDiagnostic(
                                name = "PLAYER_FIRST_FRAME_RENDERED",
                                requestId = requestId,
                                sessionId = sessionId,
                                result = "current_session",
                                context = mapOf(
                                    "episode_id" to payload.optLong("episodeId", 0L),
                                    "generation" to payload.optLong("generation", 0L),
                                    "transition_generation" to payload.optLong("transitionGeneration", 0L),
                                ),
                            )
                            true
                        }
                    }
                    else -> {
                        recordDiagnostic(
                            name = eventName,
                            severity = stringValue("severity") ?: "INFO",
                            result = stringValue("result"),
                            requestId = requestId,
                            sessionId = sessionId,
                            context = mapOf("event_type" to type),
                        )
                        true
                    }
                }
            }
            else -> false
        }
    }

    private fun launchNativeSettingsFileAction(
        action: String,
        requestId: String? = null,
    ): Boolean {
        val normalizedAction = action.trim().lowercase(Locale.ROOT)
        val allowedActions = setOf(
            "settings_export", "settings_import",
            "backup_create", "backup_restore",
            "backup_recovery_restore", "backup_recovery_snapshot",
            "backup_integrity", "backup_reconcile", "diagnostic_export",
        )
        if (normalizedAction !in allowedActions) return false
        val suppliedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedRequestId = suppliedRequestId ?: UUID.randomUUID().toString()
        val uuidRequestId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        if (!uuidRequestId.matches(resolvedRequestId)) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = normalizedAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "O identificador da operação de arquivo não é um UUID válido.",
                error = "invalid_request_id",
            )
            return false
        }
        if (!publishOperationResult(
                requestId = resolvedRequestId,
                action = normalizedAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Operação nativa de arquivo enfileirada.",
            )
        ) return false

        return try {
            activity.startActivity(
                android.content.Intent(activity, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(ReiAnixSettingsFileService.EXTRA_ACTION, normalizedAction)
                    putExtra(ReiAnixSettingsFileService.EXTRA_REQUEST_ID, resolvedRequestId)
                }
            )
            true
        } catch (failure: Exception) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = normalizedAction,
                status = "FAILED",
                operationState = "FAILED",
                message = failure.message ?: "Não foi possível iniciar a operação nativa de arquivo.",
                error = failure.javaClass.simpleName,
            )
            recordDiagnostic(
                name = "NATIVE_SETTINGS_FILE_ACTION_LAUNCH_FAILED",
                severity = "WARN",
                requestId = resolvedRequestId,
                result = normalizedAction,
                error = failure,
            )
            false
        }
    }

    fun dispatchSettingsAction(
        action: String,
        payload: org.json.JSONObject = org.json.JSONObject(),
        requestId: String? = null,
    ): Boolean {
        val normalized = action.trim().lowercase(Locale.ROOT)
        val resolvedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: stringValueFromPayload(payload, "requestId", "request_id")
        return when (normalized) {
            "reset_player" -> resetSettingsByPrefixes(setOf("player.", "gestures.", "audio."))
            "reset_all_settings" -> resetSettingsByPrefixes(emptySet())
            "clear_anilist_cache", "clear_artwork_cache" -> clearManagedArtworkCache()
            "settings_export" -> launchNativeSettingsFileAction(normalized, resolvedRequestId)
            "settings_import" -> {
                val reference = stringValueFromPayload(payload, "path", "filePath", "file_path")
                if (reference != null) {
                    importSettingsFromPrivateFile(File(reference))
                } else {
                    launchNativeSettingsFileAction(normalized, resolvedRequestId)
                }
            }
            "select_saf" -> requestSafFolderSelection(
                requestId = resolvedRequestId,
                bridge = "settings",
            )
            "request_media_access" -> requestMediaAccess(
                requestId = resolvedRequestId,
                resultAction = normalized,
            )
            "check_storage_access" -> checkStorageAccess(
                requestId = resolvedRequestId,
                resultAction = normalized,
            )
            "open_broad_storage_settings" -> openBroadStorageSettings(
                requestId = resolvedRequestId,
                resultAction = normalized,
            )
            "backup_create", "backup_integrity", "backup_reconcile", "diagnostic_export",
            "backup_recovery_restore", "backup_recovery_snapshot" ->
                launchNativeSettingsFileAction(normalized, resolvedRequestId)
            "backup_restore" -> {
                val reference = stringValueFromPayload(payload, "archivePath", "path", "filePath")
                if (reference != null) {
                    resolvePrivateBackupFile(reference)?.let(::restoreBackup) ?: false
                } else {
                    launchNativeSettingsFileAction(normalized, resolvedRequestId)
                }
            }
            "dismiss_storage_onboarding" -> {
                dismissNativeStorageOnboarding()
                if (!resolvedRequestId.isNullOrBlank()) {
                    publishOperationResult(
                        requestId = resolvedRequestId,
                        action = normalized,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Onboarding de armazenamento atualizado.",
                    )
                }
                true
            }
            else -> false
        }
    }

    private fun resetSettingsByPrefixes(prefixes: Set<String>): Boolean {
        val defaults = ReiAnixSettingsContract.definitions
            .filterKeys { key -> prefixes.isEmpty() || prefixes.any(key::startsWith) }
            .mapValues { (_, definition) -> definition.defaultValue }
        return applySettingsAtomically(defaults)
    }

    private fun org.json.JSONObject.putIfAbsentSafe(key: String, value: String): org.json.JSONObject =
        apply { if (!has(key)) put(key, value) }

    private fun stringValueFromPayload(payload: org.json.JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = payload.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return null
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
        homeRefreshJob?.cancel()
        homeRefreshJob = null
        thumbnailPumpJob?.cancel()
        thumbnailPumpJob = null
        synchronized(thumbnailQueueLock) {
            thumbnailQueue.clear()
            thumbnailRequests.values.forEach { it.completion.cancel() }
            thumbnailRequests.clear()
            thumbnailRetryCounts.clear()
            thumbnailRetryPendingKeys.clear()
            thumbnailLatestKeyByUri.clear()
            thumbnailLatestAtMillis.clear()
            thumbnailInFlight.clear()
            thumbnailPumpActive = false
        }
        lifecycleIsAlive = false
        publish(action = "cancel_background_tasks", transform = {
            it.copy(queuedThumbnailCount = 0, activeThumbnailCount = 0)
        })
    }

    fun resumeRuntimeTasks() {
        lifecycleIsAlive = true
        synchronized(thumbnailQueueLock) {
            if (thumbnailQueue.isNotEmpty()) startThumbnailPumpLocked()
        }
        publish(action = "resume_runtime_tasks")
    }
}
