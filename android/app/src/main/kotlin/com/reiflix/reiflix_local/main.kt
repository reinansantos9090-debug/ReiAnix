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
import kotlinx.coroutines.coroutineScope
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
import com.reiflix.reiflix_local.storage.NativeStorageOnboardingState
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
import java.security.MessageDigest

/**
 * Port of the persisted-progress validation performed by legacy main.py before
 * the native player handoff. Unknown duration keeps any finite non-negative
 * resume position; known duration rejects persisted progress beyond the media.
 */
internal data class NativePlaybackStartPosition(
    val positionSeconds: Double,
    val positionMsOverride: Long?,
    val progressInvalid: Boolean,
)

internal fun resolveNativePlaybackStartPosition(
    progressSeconds: Double?,
    durationSeconds: Double?,
    watched: Boolean?,
    resumeEnabled: Boolean,
): NativePlaybackStartPosition {
    val rawProgress = progressSeconds ?: 0.0
    // Python's max(0.0, duration) treats null/negative/NaN durations as unknown,
    // while positive infinity remains a positive duration just as in the legacy path.
    val knownDuration = durationSeconds?.takeIf { it > 0.0 } ?: 0.0
    val invalid = resumeEnabled && (
        !rawProgress.isFinite() ||
            rawProgress < 0.0 ||
            (knownDuration > 0.0 && rawProgress > knownDuration)
        )
    val forceStartAtZero = !resumeEnabled || invalid || watched == true
    val position = if (forceStartAtZero) 0.0 else rawProgress.coerceAtLeast(0.0)
    // Legacy start_native_player omitted position_ms for a valid zero-progress
    // episode, allowing the native player to consult its canonical progress row.
    // It explicitly sent zero only for disabled resume, invalid progress or watched episodes.
    val positionOverride = when {
        forceStartAtZero -> 0L
        position > 0.0 -> (position * 1_000.0).toLong().coerceAtLeast(0L)
        else -> null
    }
    return NativePlaybackStartPosition(
        positionSeconds = position,
        positionMsOverride = positionOverride,
        progressInvalid = invalid,
    )
}

/**
 * Validates the active player's URI against the canonical row before a Next/Previous
 * transition. This ports main.py's sqlite_current_row_mismatch guard: a valid episode
 * ID alone is insufficient if the media reference changed while the player was open.
 *
 * The lookup accepts the same absolute-path/file-URI spellings as progress callbacks,
 * and reads only the existing canonical SQLite catalogue.
 */
internal data class NativePlayerTransitionSourceValidation(
    val isCurrent: Boolean,
    val reason: String? = null,
)

internal suspend fun validateNativePlayerTransitionSource(
    dependencies: ReiAnixAppDependencies,
    sourceUri: String,
    expectedEpisodeId: Long,
    expectedAnimeId: Long,
): NativePlayerTransitionSourceValidation {
    val canonical = try {
        dependencies.sqliteLibraryRepository.findEpisodeByPathOrFileUri(sourceUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return NativePlayerTransitionSourceValidation(
        isCurrent = false,
        reason = "sqlite_current_row_mismatch",
    )
    return if (
        canonical.id == expectedEpisodeId &&
        canonical.animeId == expectedAnimeId &&
        canonical.path.isNotBlank()
    ) {
        NativePlayerTransitionSourceValidation(isCurrent = true)
    } else {
        NativePlayerTransitionSourceValidation(
            isCurrent = false,
            reason = "sqlite_current_row_mismatch",
        )
    }
}

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



    /**
     * Startup equivalent of SettingsStore._migrate_legacy_keys().
     * Copy recognized pre-schema-2 aliases only when the canonical key is absent;
     * invalid legacy values are ignored and the original keys are preserved.
     */
    private suspend fun migrateLegacySettingsKeys(dependencies: ReiAnixAppDependencies) {
        val stored = dependencies.sqliteSettingsRepository.allPreferences()
        val aliases = listOf(
            "resume_playback" to "player.resume",
            "autoplay_next" to "player.autoplay_next",
            "gesture_volume" to "gestures.volume",
            "gesture_brightness" to "gestures.brightness",
            "gesture_double_tap" to "gestures.double_tap",
            "gesture_long_press" to "gestures.long_press",
        )
        val updates = linkedMapOf<String, String>()
        aliases.forEach { (legacyKey, canonicalKey) ->
            val raw = stored[legacyKey] ?: return@forEach
            if (canonicalKey in stored) return@forEach
            val definition = ReiAnixSettingsContract.definitions[canonicalKey] ?: return@forEach
            val normalized = runCatching { definition.normalize(raw) }.getOrNull() ?: return@forEach
            updates[canonicalKey] = normalized
        }

        // Match SettingsStore.get(): legacy aspect values were normalized on first read.
        // Normalize them at native startup even when the stored settings schema is current.
        val legacyAspectRatio = stored["player.aspect_ratio"]
        if (legacyAspectRatio != null) {
            val compatibleAspectRatio = when (legacyAspectRatio.trim().lowercase(Locale.ROOT)) {
                "zoom" -> "fill"
                "auto", "original" -> "fit"
                else -> legacyAspectRatio
            }
            if (compatibleAspectRatio != legacyAspectRatio) {
                val definition = ReiAnixSettingsContract.definitions["player.aspect_ratio"]
                val normalized = definition?.let {
                    runCatching { it.normalize(compatibleAspectRatio) }.getOrNull()
                }
                if (normalized != null) updates["player.aspect_ratio"] = normalized
            }
        }

        val storedVersion = stored["settings.schema_version"]?.toIntOrNull() ?: 0
        if (storedVersion < ReiAnixSettingsFileService.SCHEMA_VERSION) {
            updates["settings.schema_version"] = ReiAnixSettingsFileService.SCHEMA_VERSION.toString()
        }
        if (updates.isEmpty()) return

        dependencies.sqliteSettingsRepository.setPreferencesAtomically(updates)
        Log.i(
            TAG,
            "LEGACY_SETTINGS_MIGRATION_COMPLETED migratedAliases=" +
                updates.keys.count { it != "settings.schema_version" } +
                " schemaVersionUpdated=" + (storedVersion < ReiAnixSettingsFileService.SCHEMA_VERSION),
        )
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

            if (!recoveryRequired) {
                migrateLegacySettingsKeys(dependencies)
                // The ViewModel may have read preferences while bootstrap was validating.
                // Refresh after the one-time alias migration and before exposing Settings.
                settingsViewModel.refresh()
            }

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
    val sequence: Long,
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

/**
 * Process-local deduplication for the startup warning translated from
 * main.py's recovered_scans notice. A notice is claimed only when at least one
 * interrupted scan exists, so transient query failures do not suppress retry.
 */
private object NativeMainStartupNoticeState {
    private var interruptedScanNoticeShown = false

    @Synchronized
    fun claimInterruptedScansNotice(count: Int): Boolean {
        if (count <= 0 || interruptedScanNoticeShown) return false
        interruptedScanNoticeShown = true
        return true
    }
}

internal class NativeMainRuntimePort(
    private val activity: MainActivity,
    private val libraryViewModel: ReiAnixLibraryViewModel,
    private val settingsViewModel: ReiAnixSettingsViewModel,
    private val composeHost: ReiAnixComposeLibraryHost,
) {
    private val lock = Any()
    private val nativeMailboxPollLock = Any()
    private val nativeMailboxEventLedgerLock = Any()
    private val nativeMailboxDirectory = File(File(activity.filesDir, "data"), "reiflix-native-events")
    private val nativeMailboxEventIds = LinkedHashSet<String>()
    @Volatile private var nativeMailboxLedgerLoaded = false
    @Volatile private var nativeMailboxPollJob: Job? = null
    private data class NativeMailboxQueueItem(
        val file: File,
        val envelope: org.json.JSONObject,
        val createdAtMillis: Long,
        val modifiedAtMillis: Long,
    )
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
    private val metadataMaintenanceMutex = Mutex()
    private val thumbnailMaintenanceMutex = Mutex()
    @Volatile private var lastStorageMutationAtMillis: Long = 0L
    @Volatile private var pendingSafSelectionStartedAtMillis: Long = 0L
    @Volatile private var pendingSafSelectionRequestId: String? = null
    @Volatile private var pendingSafSelectionBridge: String = "library"
    @Volatile private var pendingSafSelectionBaselineIdentities: Set<String> = emptySet()
    @Volatile private var pendingSafSelectionBaselineKnown: Boolean = false
    @Volatile private var pendingSafRemovalRequestId: String? = null
    @Volatile private var pendingSafRemovalReference: String? = null
    @Volatile private var pendingSafRemovalBridge: String = "library"
    @Volatile private var pendingSafRemovalResultAction: String = "remove_saf"
    @Volatile private var pendingFolderRemovalRequestId: String? = null
    @Volatile private var restoreMaintenanceInProgress: Boolean = false
    private var scanObserverJob: Job? = null
    private val preferenceLock = Any()
    private val preferenceFile = File(File(activity.filesDir, "data"), "navigation_state.json")
    private val preferenceWriteMutex = Mutex()
    private val thumbnailQueueLock = Any()
    private val thumbnailQueue = PriorityQueue<NativeThumbnailRequest>(
        compareByDescending<NativeThumbnailRequest> { it.priority }
            .thenBy { it.sequence },
    )
    // Same monotonically increasing insertion order as main.py's
    // thumbnail_queue_sequence; guarded by thumbnailQueueLock.
    private var thumbnailQueueSequence = 0L
    private val thumbnailRequests = ConcurrentHashMap<String, NativeThumbnailRequest>()
    // Bounded retry state mirrors the legacy thumbnail retry_after_error task.
    private val thumbnailRetryCounts = ConcurrentHashMap<String, Int>()
    private val thumbnailRetryPendingKeys = ConcurrentHashMap.newKeySet<String>()
    // Suppress repeated completion events for one request, matching main.py's
    // thumbnail_completed_request_by_key guard.
    private val thumbnailCompletedRequestByKey = java.util.LinkedHashMap<String, String>()
    // Older source-version requests for the same URI must not overtake the latest queued request.
    private val thumbnailLatestKeyByUri = ConcurrentHashMap<String, String>()
    private val thumbnailLatestAtMillis = ConcurrentHashMap<String, Long>()
    private val thumbnailInFlight = ConcurrentHashMap.newKeySet<String>()
    private val thumbnailSemaphore = Semaphore(MAX_CONCURRENT_THUMBNAIL_REQUESTS)
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
                    val changed = metadataMaintenanceMutex.withLock {
                        if (restoreMaintenanceInProgress) {
                            null
                        } else {
                            dependencies.aniListIntegrationService.reconcileUnmatchedLibraryMetadata()
                        }
                    }
                    if (changed == null) {
                        metadataHydrationRequests.trySend(reason)
                        delay(100)
                        continue
                    }
                    if (changed > 0) libraryViewModel.refreshCatalogProjection()
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
                    val reconciled = thumbnailMaintenanceMutex.withLock {
                        if (restoreMaintenanceInProgress) {
                            false
                        } else {
                            reconcileMissingLocalThumbnails(reason)
                            true
                        }
                    }
                    if (!reconciled) {
                        thumbnailReconciliationRequests.trySend(reason)
                        delay(100)
                        continue
                    }
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
     * Resolve exact artwork in priority order, skipping corrupt images without decoding
     * files under the database lock. This mirrors thumbnail_candidates(after_id, limit=128)
     * and also catches episodes with an existing thumbnail but missing duration.
     */
    private suspend fun loadThumbnailCandidateRows(
        afterId: Long,
        limit: Int = 128,
    ): List<NativeThumbnailCandidateRow> = withContext(Dispatchers.IO) {
        val pageLimit = limit.coerceIn(1, 128)
        val (episodes, artworkCandidates) = dependencies.sqliteDatabase.read { database ->
            val episodeRows = database.rawQuery(
                """
                SELECT
                    e.id,
                    e.path,
                    COALESCE(e.file_size, 0) AS file_size,
                    COALESCE(e.modified_at, 0) AS modified_at,
                    COALESCE(e.media_identity, '') AS media_identity,
                    COALESCE(e.duration, 0) AS duration
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
                                thumbnailPath = null,
                            ),
                        )
                    }
                }
            }
            if (episodeRows.isEmpty()) {
                return@read Pair(emptyList<NativeThumbnailCandidateRow>(), emptyList<Pair<Long, String>>())
            }

            val idPlaceholders = episodeRows.joinToString(",") { "?" }
            val episodeIds = episodeRows.map { it.id.toString() }
            val artworkRows = database.rawQuery(
                """
                SELECT entity_id, local_path
                FROM artwork
                WHERE entity_type = 'episode'
                  AND entity_id IN ($idPlaceholders)
                  AND artwork_type = 'episode_thumbnail'
                  AND status != 'failed'
                  AND local_path IS NOT NULL
                  AND trim(local_path) != ''
                ORDER BY entity_id, priority DESC, updated_at DESC, id DESC
                """.trimIndent(),
                episodeIds.toTypedArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val episodeId = cursor.getString(cursor.getColumnIndexOrThrow("entity_id"))
                            ?.toLongOrNull() ?: continue
                        val path = cursor.getString(cursor.getColumnIndexOrThrow("local_path"))
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() } ?: continue
                        add(episodeId to path)
                    }
                }
            }
            episodeRows to artworkRows
        }

        val validArtworkPathByEpisodeId = mutableMapOf<Long, String>()
        for ((episodeId, path) in artworkCandidates) {
            if (episodeId !in validArtworkPathByEpisodeId && hasValidLocalThumbnail(path)) {
                validArtworkPathByEpisodeId[episodeId] = path
            }
        }
        episodes.map { row -> row.copy(thumbnailPath = validArtworkPathByEpisodeId[row.id]) }
    }

    private fun hasValidLocalThumbnail(path: String?): Boolean {
        val normalized = path?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        val file = File(normalized)
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            // Generated frames are small, but manually supplied artwork may be huge.
            // Decode a sample so a valid header alone cannot hide a corrupt image body.
            val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
            val sampleSize = maxOf(1, largestDimension / 512)
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: return@runCatching false
            bitmap.recycle()
            true
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

                    // Keep the extractor's original fallback: a blank media identity
                    // is normalized to the URI, not an invented per-episode token.
                    val identity = row.mediaIdentity
                    while (lifecycleIsAlive && !activity.isFinishing && !activity.isDestroyed) {
                        if (
                            enqueueLocalThumbnail(
                                reference = row.reference,
                                size = row.size,
                                modifiedAt = row.modifiedAt,
                                mediaIdentity = identity,
                                // main.py's reconcile_missing_thumbnails() explicitly uses
                                // priority=100, independent of the general default heuristic.
                                priority = 100,
                            )
                        ) {
                            queued += 1
                            break
                        }
                        val queueHasRoom = synchronized(thumbnailQueueLock) {
                            thumbnailQueue.size < MAX_PENDING_THUMBNAIL_REQUESTS
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
                (restoreMaintenanceInProgress && normalizedLane in setOf("metadata", "thumbnail")) ||
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

    /**
     * Port of main.py refresh_library: an explicit refresh requests the native
     * scanner, not merely a view-model repaint. Capability discovery runs first
     * when the native storage snapshot is still unknown, and a pending SAF picker
     * blocks the refresh so it cannot race a user-authorized folder change.
     */
    fun refreshLibrary(requestId: String? = null): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (!pendingSafSelectionRequestId.isNullOrBlank()) {
            val message = "Conclua ou cancele a seleção da pasta antes de atualizar a biblioteca."
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = "refresh_library",
                    status = "BLOCKED",
                    operationState = "BLOCKED",
                    message = message,
                    error = "saf_selection_pending",
                )
            }
            recordDiagnostic(
                name = "LIBRARY_REFRESH_REJECTED",
                severity = "WARN",
                requestId = correlatedRequestId,
                result = "saf_selection_pending",
            )
            return false
        }

        val storage = storageSnapshot()
        if (storage.onboardingPhase.equals(
                NativeStorageOnboardingState.PHASE_CHECKING,
                ignoreCase = true,
            )
        ) {
            recordDiagnostic(
                name = "NATIVE_STORAGE_CAPABILITIES_CHECK_REQUESTED",
                requestId = correlatedRequestId,
                result = "refresh_library",
            )
            return checkStorageAccess(
                requestId = correlatedRequestId,
                resultAction = "refresh_library",
            )
        }

        return requestLibraryScan(
            requestId = correlatedRequestId,
            resultAction = "refresh_library",
        )
    }

    fun refreshCanonicalCatalog(
        requestId: String? = null,
        resultAction: String = "refresh_canonical_catalog",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Atualização do catálogo canônico enfileirada.",
            )
        }
        val accepted = launchTracked("refresh_canonical_catalog") {
            try {
                val refreshed = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
                if (!refreshed) {
                    throw IllegalStateException("O catálogo nativo ainda não pode ser atualizado antes da validação do banco.")
                }
                settingsViewModel.refresh()
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Catálogo canônico atualizado.",
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A atualização do catálogo foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível atualizar o catálogo canônico.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A atualização do catálogo não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
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

    private data class ParsedLegacyPlaybackTimestamp(
        val supplied: Boolean,
        val epochSeconds: Double?,
    )

    private fun pythonTruthyJsonValue(value: Any?): Boolean = when (value) {
        null, org.json.JSONObject.NULL -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        is org.json.JSONArray -> value.length() > 0
        is org.json.JSONObject -> value.length() > 0
        else -> true
    }

    private fun pythonJsonString(value: Any?): String = when (value) {
        null, org.json.JSONObject.NULL -> "None"
        is Boolean -> if (value) "True" else "False"
        else -> value.toString()
    }

    private fun firstTruthyJsonText(payload: org.json.JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = payload.opt(key)
            if (pythonTruthyJsonValue(value)) return pythonJsonString(value).trim()
        }
        return ""
    }

    private fun firstTruthyJsonLong(payload: org.json.JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = payload.opt(key)
            if (!pythonTruthyJsonValue(value)) continue
            return when (value) {
                is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
                    ?: throw IllegalArgumentException("Player callback generation must be finite.")
                is Boolean -> if (value) 1L else 0L
                is String -> value.trim().toLongOrNull()
                    ?: throw IllegalArgumentException("Player callback generation must be an integer.")
                else -> throw IllegalArgumentException("Player callback generation must be numeric.")
            }
        }
        return 0L
    }

    /**
     * Read the legacy mailbox envelope timestamp using Python's truthy
     * createdAt-then-timestamp fallback. Epoch-millisecond values are converted
     * to seconds to match episodes.last_played_at and the Kotlin stale-write guard.
     */
    private fun parseLegacyPlaybackTimestamp(payload: org.json.JSONObject): ParsedLegacyPlaybackTimestamp {
        val raw = if (payload.has("_legacySourceEventCreatedAt")) {
            payload.opt("_legacySourceEventCreatedAt")
        } else {
            val createdAt = payload.opt("createdAt")
            if (pythonTruthyJsonValue(createdAt)) createdAt else payload.opt("timestamp")
        }
        if (raw == null || raw === org.json.JSONObject.NULL) {
            return ParsedLegacyPlaybackTimestamp(supplied = false, epochSeconds = null)
        }
        val numeric = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            is Boolean -> if (raw) 1.0 else 0.0
            else -> null
        }
        if (numeric == null || !numeric.isFinite()) {
            return ParsedLegacyPlaybackTimestamp(supplied = true, epochSeconds = null)
        }
        val normalized = if (numeric > 10_000_000_000.0) numeric / 1_000.0 else numeric
        return ParsedLegacyPlaybackTimestamp(supplied = true, epochSeconds = normalized)
    }

    fun savePlaybackProgress(
        episodeId: Long,
        positionMs: Long,
        durationMs: Long? = null,
        completed: Boolean = false,
        playedAtEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
        callbackPayload: org.json.JSONObject? = null,
        callbackRequestId: String? = null,
    ): Boolean {
        if (
            episodeId <= 0L ||
            positionMs < 0L ||
            (durationMs != null && durationMs <= 0L) ||
            !playedAtEpochSeconds.isFinite() ||
            playedAtEpochSeconds < 0.0
        ) return false
        val callbackSnapshot = callbackPayload?.let { org.json.JSONObject(it.toString()) }
        return launchTracked("save_playback_progress", lane = "playback") {
            if (callbackSnapshot != null) {
                val validation = validatePlayerCallback(
                    eventRequestId = callbackRequestId,
                    payload = callbackSnapshot,
                    requireActive = true,
                    expectedEpisodeId = episodeId,
                    expectedAnimeId = callbackSnapshot.optLong("animeId", callbackSnapshot.optLong("anime_id", 0L))
                        .takeIf { it > 0L },
                    expectedMediaId = callbackSnapshot.optString(
                        "mediaId",
                        callbackSnapshot.optString("media_id", ""),
                    ).trim().takeIf { it.isNotEmpty() },
                )
                if (!validation.isCurrent) {
                    recordDiagnostic(
                        name = "PLAYER_PROGRESS_STALE",
                        severity = "WARN",
                        requestId = callbackRequestId,
                        result = "stale_before_persistence:" + validation.reason,
                        context = mapOf("episode_id" to episodeId),
                    )
                    return@launchTracked
                }
                val suppliedPath = callbackSnapshot.optString(
                    "uri",
                    callbackSnapshot.optString("path", ""),
                ).trim()
                if (suppliedPath.isNotEmpty()) {
                    val pathEpisode = dependencies.sqliteLibraryRepository.findEpisodeByPathOrFileUri(suppliedPath)
                    if (pathEpisode != null && pathEpisode.id != episodeId) {
                        recordDiagnostic(
                            name = "PLAYER_PROGRESS_IDENTITY_REJECTED",
                            severity = "WARN",
                            requestId = callbackRequestId,
                            result = "episode_path_mismatch",
                            context = mapOf(
                                "episode_id" to episodeId,
                                "path_episode_id" to pathEpisode.id,
                            ),
                        )
                        return@launchTracked
                    }
                }
            }
            val existingProgress = dependencies.consumptionService.getProgress(episodeId)
                ?: throw NoSuchElementException("Episode ID does not exist: $episodeId")
            if ((existingProgress.lastPlayedAtEpochSeconds ?: 0.0) >= playedAtEpochSeconds) {
                recordDiagnostic(
                    name = "PLAYER_PROGRESS_STALE",
                    result = "stale_event_timestamp",
                    context = mapOf(
                        "episode_id" to episodeId,
                        "event_time_seconds" to playedAtEpochSeconds,
                        "stored_last_played_at" to existingProgress.lastPlayedAtEpochSeconds,
                    ),
                )
                return@launchTracked
            }
            val progress = dependencies.consumptionService.saveProgress(
                episodeId = episodeId,
                positionMs = positionMs,
                durationMs = durationMs,
                playedAtEpochSeconds = playedAtEpochSeconds,
                completed = completed,
            )
            libraryViewModel.refreshCatalogProjection()
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

    fun markEpisodeWatchedTracked(
        episodeId: Long,
        watched: Boolean,
        path: String? = null,
    ): Boolean {
        if (episodeId <= 0L) return false
        return launchTracked("mark_episode_watched", lane = "playback") {
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
            libraryViewModel.refreshCatalogProjection()
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
            thumbnailLatestAtMillis[uriKey] = android.os.SystemClock.elapsedRealtime()
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
                    thumbnailQueueSequence += 1L
                    val promoted = existing.copy(
                        priority = priority,
                        enqueuedAtMillis = android.os.SystemClock.elapsedRealtime(),
                        sequence = thumbnailQueueSequence,
                    )
                    thumbnailRequests[key] = promoted
                    thumbnailQueue.add(promoted)
                }
                updateThumbnailQueueSnapshot()
                startThumbnailPumpLocked()
                return true
            }
            if (thumbnailQueue.size >= MAX_PENDING_THUMBNAIL_REQUESTS) {
                val lowest = thumbnailQueue.minWithOrNull(
                    compareBy<NativeThumbnailRequest> { it.priority }.thenBy { it.sequence },
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
                val evictedUriKey = lowest.uri.toString()
                if (thumbnailLatestKeyByUri.remove(evictedUriKey, lowest.key)) {
                    thumbnailLatestAtMillis.remove(evictedUriKey)
                }
                lowest.completion.complete(null)
            }
            thumbnailQueueSequence += 1L
            val request = NativeThumbnailRequest(
                key = key,
                uri = uri,
                size = boundedSize,
                modifiedAt = boundedModifiedAt,
                mediaIdentity = identity,
                priority = priority,
                enqueuedAtMillis = android.os.SystemClock.elapsedRealtime(),
                sequence = thumbnailQueueSequence,
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
    private fun pruneThumbnailQueueStateLocked(nowMillis: Long = android.os.SystemClock.elapsedRealtime()) {
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
        // Match the legacy queue's hard bound when many delayed retries are
        // cancelled by lifecycle or lane cancellation before their next attempt.
        if (thumbnailRetryCounts.size > 2_048) {
            thumbnailRetryCounts.keys.asSequence()
                .take(512)
                .forEach { key ->
                    thumbnailRetryCounts.remove(key)
                    thumbnailRetryPendingKeys.remove(key)
                }
        }
        if (thumbnailRetryPendingKeys.size > 2_048) {
            thumbnailRetryPendingKeys.asSequence()
                .take(512)
                .forEach { key -> thumbnailRetryPendingKeys.remove(key) }
        }
        if (thumbnailCompletedRequestByKey.size > 2_048) {
            thumbnailCompletedRequestByKey.keys.take(512)
                .forEach { key -> thumbnailCompletedRequestByKey.remove(key) }
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
                coroutineScope {
                    repeat(MAX_CONCURRENT_THUMBNAIL_REQUESTS) {
                        launch {
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
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val uri = when (parsed.scheme?.lowercase(Locale.ROOT)) {
            "content", "file" -> parsed
            null -> if (raw.startsWith("/")) Uri.fromFile(File(raw)) else return false
            else -> return false
        }
        val identity = mediaIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: uri.toString()
        val key = thumbnailRequestKey(uri, size.coerceAtLeast(0L), modifiedAt.coerceAtLeast(0L), identity)
        synchronized(thumbnailQueueLock) {
            val request = thumbnailRequests.remove(key)
            val hadRetry = thumbnailRetryCounts.remove(key) != null || thumbnailRetryPendingKeys.remove(key)
            if (request == null && !hadRetry) return false
            if (request != null) {
                thumbnailQueue.remove(request)
                request.completion.cancel()
            }
            // A failed request leaves the queue before its delayed retry is enqueued.
            // Cancellation must still win during that gap, even when no request object remains.
            thumbnailRetryCounts.remove(key)
            thumbnailRetryPendingKeys.remove(key)
            val uriKey = uri.toString()
            if (thumbnailLatestKeyByUri.remove(uriKey, key)) {
                thumbnailLatestAtMillis.remove(uriKey)
            }
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
        // MainActivity fences delayed native handoffs with its own revocation ledger.
        // Advance both guards so a late NEXT/PREVIOUS launch cannot outlive cancellation.
        val current = mutableSnapshot.value
        val originRequestId = current.activePlayerRequestId
        if (!originRequestId.isNullOrBlank()) {
            MainActivity.revokePlayerTransition(originRequestId, current.activePlayerSessionId)
        }
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
            current.activePlayerSessionId != expectedSessionId
        ) {
            recordDiagnostic(
                name = "PLAYER_CALLBACK_STALE",
                severity = "WARN",
                requestId = expectedRequestId ?: current.activePlayerRequestId,
                sessionId = expectedSessionId,
                result = "session_mismatch_on_invalidation",
                context = mapOf("current_player_session_id" to current.activePlayerSessionId),
            )
            return false
        }
        if (!expectedRequestId.isNullOrBlank() && current.activePlayerRequestId != null &&
            current.activePlayerRequestId != expectedRequestId
        ) {
            recordDiagnostic(
                name = "PLAYER_CALLBACK_STALE",
                severity = "WARN",
                requestId = expectedRequestId,
                sessionId = current.activePlayerSessionId,
                result = "request_mismatch_on_invalidation",
                context = mapOf("current_request_id" to current.activePlayerRequestId),
            )
            return false
        }
        // Match the former invalidate_player_session order: cancel the
        // transition and record its diagnostic before clearing the session.
        cancelPlayerTransition(reason)
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
                transitionGeneration = it.transitionGeneration,
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
        val session = firstTruthyJsonText(payload, "playerSessionId", "player_session_id")
        val episode = firstTruthyJsonText(payload, "episodeId")
        val media = firstTruthyJsonText(payload, "mediaId")
        val uri = firstTruthyJsonText(payload, "uri")
        return session.isNotEmpty() && episode.isNotEmpty() && media.isNotEmpty() && uri.isNotEmpty()
    }

    fun validatePlayerCallback(
        eventRequestId: String?,
        payload: org.json.JSONObject,
        requireActive: Boolean = true,
        expectedEpisodeId: Long? = null,
        expectedMediaId: String? = null,
        expectedAnimeId: Long? = null,
        allowMissingEpisodeId: Boolean = false,
        allowMissingAnimeId: Boolean = false,
    ): NativePlayerCallbackValidation {
        val current = mutableSnapshot.value
        fun reject(reason: String) = NativePlayerCallbackValidation(false, reason)
        val sessionId = firstTruthyJsonText(payload, "playerSessionId", "player_session_id")
        val activityId = firstTruthyJsonText(payload, "activityInstanceId", "activity_instance_id")
        val requestId = eventRequestId?.trim().orEmpty().ifBlank {
            firstTruthyJsonText(payload, "requestId")
        }
        fun numericIdentity(vararg keys: String): Long? = try {
            firstTruthyJsonLong(payload, *keys)
        } catch (_: IllegalArgumentException) {
            null
        }
        // Bad event fields are rejected here rather than escaping the dispatcher:
        // a thrown exception would make the durable mailbox requeue this poison event.
        val playerGeneration = numericIdentity("generation", "playerGeneration")
            ?: return reject("invalid_player_generation")
        val transition = numericIdentity("transitionGeneration")
            ?: return reject("invalid_transition_generation")
        val rawEpisodeIdentity = payload.opt("episodeId")
        val parsedEpisodeIdentity = numericIdentity("episodeId")
        val episodeId = expectedEpisodeId ?: parsedEpisodeIdentity?.takeIf { it > 0L }
        if (episodeId == null && !allowMissingEpisodeId) return reject("invalid_episode_id")
        if (
            allowMissingEpisodeId &&
            pythonTruthyJsonValue(rawEpisodeIdentity) &&
            (parsedEpisodeIdentity == null || parsedEpisodeIdentity <= 0L)
        ) return reject("invalid_episode_id")

        val rawAnimeIdentity = payload.opt("animeId")
        val parsedAnimeIdentity = numericIdentity("animeId")
        val animeId = expectedAnimeId ?: parsedAnimeIdentity?.takeIf { it > 0L }
        if (animeId == null && !allowMissingAnimeId) return reject("invalid_anime_id")
        if (
            allowMissingAnimeId &&
            pythonTruthyJsonValue(rawAnimeIdentity) &&
            (parsedAnimeIdentity == null || parsedAnimeIdentity <= 0L)
        ) return reject("invalid_anime_id")
        val mediaId = expectedMediaId?.trim().orEmpty().ifBlank {
            firstTruthyJsonText(payload, "mediaId")
        }

        // Preserve Python's raw JSON comparison for episodeId instead of accepting
        // a numeric-looking string such as "123" as the integer 123.
        val rawLegacyEpisodeId = payload.opt("episodeId")
        if (pythonTruthyJsonValue(rawLegacyEpisodeId) && current.activeEpisodeId != null) {
            val matchesLegacyEpisodeId = when (rawLegacyEpisodeId) {
                is Number -> rawLegacyEpisodeId.toDouble().isFinite() &&
                    rawLegacyEpisodeId.toDouble() == current.activeEpisodeId.toDouble()
                is Boolean -> (if (rawLegacyEpisodeId) 1L else 0L) == current.activeEpisodeId
                else -> false
            }
            if (!matchesLegacyEpisodeId) return reject("stale_episode")
        }
        // Python normalized anime IDs with str(value or "").strip(), so preserve
        // distinctions such as "0" and 123.0 rather than coercing both to Long.
        val rawLegacyAnimeId = firstTruthyJsonText(payload, "animeId")
        val currentAnimeId = current.activeAnimeId?.toString().orEmpty()
        if (
            rawLegacyAnimeId.isNotBlank() &&
            currentAnimeId.isNotBlank() &&
            rawLegacyAnimeId != currentAnimeId
        ) {
            return reject("stale_anime")
        }

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

    fun notePlayerSession(
        requestId: String,
        sessionId: String,
        transitionGeneration: Long = 0L,
        playerGeneration: Long? = null,
    ) {
        MainActivity.notePlayerSession(requestId, sessionId, transitionGeneration)
        publish(action = "player_session_active", transform = {
            it.copy(
                playerSessionActive = true,
                activePlayerRequestId = requestId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerSessionId = sessionId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerGeneration = playerGeneration?.coerceAtLeast(0L) ?: it.activePlayerGeneration,
                transitionGeneration = maxOf(it.transitionGeneration, transitionGeneration.coerceAtLeast(0L)),
            )
        })
    }

    fun notePlayerActivityCreated(
        instanceId: String,
        requestId: String,
        sessionId: String,
        transitionGeneration: Long = 0L,
        playerGeneration: Long? = null,
    ) {
        MainActivity.notePlayerActivityCreated(instanceId, requestId, sessionId, transitionGeneration)
        publish(action = "player_activity_created", transform = {
            it.copy(
                playerSessionActive = true,
                activePlayerActivityInstanceId = instanceId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerRequestId = requestId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerSessionId = sessionId.trim().takeIf { value -> value.isNotEmpty() },
                activePlayerGeneration = playerGeneration?.coerceAtLeast(0L) ?: it.activePlayerGeneration,
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
        if (restoreMaintenanceInProgress) {
            val message = "Aguarde a conclusão da restauração do backup antes de atualizar a biblioteca."
            recordDiagnostic(
                name = "LIBRARY_SCAN_REJECTED",
                severity = "WARN",
                requestId = requestId,
                result = "restore_in_progress",
            )
            requestId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "BLOCKED",
                    operationState = "BLOCKED",
                    message = message,
                    error = "restore_in_progress",
                )
            }
            return false
        }
        val storageMutationPending =
            !pendingSafSelectionRequestId.isNullOrBlank() ||
                !pendingSafRemovalRequestId.isNullOrBlank() ||
                !pendingFolderRemovalRequestId.isNullOrBlank()
        if (storageMutationPending) {
            val message = "Conclua a seleção ou remoção da pasta antes de atualizar a biblioteca."
            recordDiagnostic(
                name = "LIBRARY_SCAN_REJECTED",
                severity = "WARN",
                requestId = requestId,
                result = "storage_operation_in_progress",
            )
            requestId?.trim()?.takeIf { it.isNotEmpty() }?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "BLOCKED",
                    operationState = "BLOCKED",
                    message = message,
                    error = "storage_operation_in_progress",
                )
            }
            return false
        }
        val authorizedRoots = authorizedScanTargets("saf")
        if (authorizedRoots.isEmpty()) {
            val message = "Não há uma pasta SAF autorizada para atualizar a biblioteca."
            recordDiagnostic(
                name = "LIBRARY_SCAN_REJECTED",
                severity = "WARN",
                requestId = requestId,
                result = "no_authorized_library_saf_source",
                context = mapOf("authorized_saf_root_count" to authorizedRoots.size),
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
        if (hasAnyNativeScanInProgress()) {
            val activeScanIds = NativeScanController.progress.value.values
                .filter { !it.state.isTerminal }
                .map { it.operationId }
                .toSet()
            if (activeScanIds.isEmpty()) {
                // A scan completed between the initial check and snapshot read.
                return requestStorageAction("scan_library", requestId, resultAction)
            }
            val queuedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
                ?: UUID.randomUUID().toString()
            val message = "Atualização enfileirada; a varredura atual será concluída primeiro."
            publishOperationResult(
                requestId = queuedRequestId,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = message,
                payload = org.json.JSONObject()
                    .put("waitingForScanCount", activeScanIds.size)
                    .put("waitingForScanIds", org.json.JSONArray(activeScanIds.toList())),
            )
            recordDiagnostic(
                name = "LIBRARY_SCAN_QUEUED",
                requestId = queuedRequestId,
                result = "active_scan_in_progress",
                context = mapOf("active_scan_count" to activeScanIds.size),
            )
            val accepted = launchTracked("queued_library_scan", lane = "storage") {
                try {
                    NativeScanController.progress
                        .map { snapshots ->
                            activeScanIds.all { scanId -> snapshots[scanId]?.state?.isTerminal == true }
                        }
                        .first { allFinished -> allFinished }
                    delay(HOME_REFRESH_TERMINAL_SETTLE_MS)
                    // Re-enter through the same guards: a new scan, restore or
                    // storage mutation may have begun while this request waited.
                    requestLibraryScan(
                        requestId = queuedRequestId,
                        resultAction = resultAction,
                    )
                } catch (cancelled: CancellationException) {
                    publishOperationResult(
                        requestId = queuedRequestId,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A atualização enfileirada da biblioteca foi cancelada.",
                    )
                    throw cancelled
                }
            }
            if (!accepted) {
                publishOperationResult(
                    requestId = queuedRequestId,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = "Não foi possível enfileirar a atualização da biblioteca.",
                    error = "operation_rejected",
                )
                recordDiagnostic(
                    name = "LIBRARY_SCAN_QUEUE_REJECTED",
                    severity = "WARN",
                    requestId = queuedRequestId,
                    result = "operation_rejected",
                )
            }
            return accepted
        }
        return requestStorageAction("scan_library", requestId, resultAction)
    }
    fun selectSafFolder(): Boolean = requestStorageAction("select_tree")

    /** Return only the SAF roots already registered by the canonical native library repository. */
    fun configuredSafTreeUris(): List<Uri> =
        libraryViewModel.authorizedSafTreeUris().distinctBy { it.toString() }

    private fun recordStorageMutation(
        reason: String,
        treeUri: String? = null,
        eventTimestampMillis: Long = 0L,
    ) {
        val timestamp = eventTimestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        lastStorageMutationAtMillis = maxOf(lastStorageMutationAtMillis, timestamp)
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
     * Refresh the permission projection for capability-only events without
     * scheduling a full catalog refresh on every Activity lifecycle callback.
     * Android framework grants and explicitly configured SQLite roots remain authoritative.
     */
    private fun refreshStorageCapabilitiesProjection(
        payload: org.json.JSONObject? = null,
        eventCreatedAtMillis: Long = 0L,
    ): Boolean {
        val publishedAt = when {
            eventCreatedAtMillis > 0L -> eventCreatedAtMillis
            payload == null -> 0L
            else -> payload.optLong(
                "_legacySourceEventCreatedAt",
                payload.optLong(
                    "publishedAtMs",
                    payload.optLong("published_at_ms", payload.optLong("createdAt", payload.optLong("timestamp", 0L))),
                ),
            )
        }
        if (isStorageSnapshotStale(publishedAt)) {
            mutableStorageState.value = storageSnapshot()
            recordDiagnostic(
                name = "STORAGE_STALE_CAPABILITY_SNAPSHOT_IGNORED",
                severity = "INFO",
                result = "older_than_last_storage_mutation",
                context = mapOf(
                    "published_at_ms" to publishedAt,
                    "last_mutation_at_ms" to lastStorageMutationAtMillis,
                ),
            )
            settingsViewModel.refresh()
            return false
        }
        mutableStorageState.value = storageSnapshot()
        settingsViewModel.refresh()
        return true
    }

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
        libraryViewModel.refreshCatalogProjection()
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

    /**
     * Recalculate the aggregate episode availability from the latest observation
     * for each canonical source location after one source becomes unavailable.
     */
    private fun recomputeEpisodeAvailabilityForSource(
        database: android.database.sqlite.SQLiteDatabase,
        episodeId: Long,
    ) {
        val latestByLocation = linkedMapOf<String, String>()
        database.rawQuery(
            "SELECT source_kind,scope_kind,scope_ref,volume_id,state " +
                "FROM episode_observations WHERE episode_id=? ORDER BY last_checked_at DESC,id DESC",
            arrayOf(episodeId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val source = cursor.getString(0).orEmpty().trim()
                    .lowercase(Locale.ROOT).ifBlank { "unknown" }
                val scopeKind = cursor.getString(1).orEmpty().trim()
                    .lowercase(Locale.ROOT).ifBlank { "source" }
                val scopeRef = cursor.getString(2).orEmpty().trim()
                val volumeId = cursor.getString(3).orEmpty().trim()
                val key = if (volumeId.isNotEmpty()) {
                    "$source|volume|$volumeId"
                } else {
                    "$source|$scopeKind|$scopeRef"
                }
                if (!latestByLocation.containsKey(key)) {
                    latestByLocation[key] = cursor.getString(4).orEmpty()
                        .trim().lowercase(Locale.ROOT)
                }
            }
        }
        if (latestByLocation.isEmpty()) return
        val states = latestByLocation.values.toSet()
        val (missing, availability) = when {
            "available" in states -> 0 to "available"
            "volume_unavailable" in states -> 1 to "volume_unavailable"
            "scope_unavailable" in states -> 1 to "scope_unavailable"
            "unavailable" in states -> 1 to "unavailable"
            states.all { it == "scope_removed" } -> 1 to "scope_removed"
            else -> 1 to "missing"
        }
        database.update(
            "episodes",
            android.content.ContentValues().apply {
                put("missing", missing)
                put("availability_state", availability)
            },
            "id=?",
            arrayOf(episodeId.toString()),
        )
    }

    /**
     * Native equivalent of LibraryStore.mark_source_unavailable() for a SAF root.
     * A failed/revoked source must not leave episodes from that source playable just
     * because a failed scan correctly avoided destructive full-scan reconciliation.
     */
    private suspend fun markSafSourceUnavailable(
        treeUri: String,
        authorization: String,
        reason: String,
    ): Int {
        val reference = treeUri.trim()
        if (reference.isEmpty()) return 0
        val normalizedAuthorization = authorization.trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("revoked", "unavailable") } ?: "unavailable"
        val normalizedReason = reason.trim().takeIf { it.isNotEmpty() } ?: "saf_scan_error"
        return withContext(Dispatchers.IO) {
            dependencies.sqliteDatabase.transaction { database ->
                val now = System.currentTimeMillis() / 1000.0
                val folderValues = android.content.ContentValues().apply {
                    put("authorization", normalizedAuthorization)
                    put("last_error", normalizedReason)
                    put("last_scan_at", now)
                }
                database.update(
                    "folders",
                    folderValues,
                    "path=? AND kind='saf'",
                    arrayOf(reference),
                )

                val affectedEpisodes = linkedSetOf<Long>()
                database.rawQuery(
                    "SELECT DISTINCT episode_id FROM episode_observations WHERE source_kind='saf' AND scope_ref=?",
                    arrayOf(reference),
                ).use { cursor ->
                    while (cursor.moveToNext()) affectedEpisodes.add(cursor.getLong(0))
                }

                var changed = 0
                for (episodeId in affectedEpisodes) {
                    val observationValues = android.content.ContentValues().apply {
                        put("state", "scope_unavailable")
                        put("error", normalizedReason)
                        put("last_checked_at", now)
                    }
                    changed += database.update(
                        "episode_observations",
                        observationValues,
                        "episode_id=? AND source_kind='saf' AND scope_ref=?",
                        arrayOf(episodeId.toString(), reference),
                    )

                    // Recompute using the latest observation from each canonical source.
                    recomputeEpisodeAvailabilityForSource(database, episodeId)
                }

                // Legacy episodes without an observation row still need to reflect the
                // unavailable source, matching mark_source_unavailable() in LibraryStore.
                changed += database.update(
                    "episodes",
                    android.content.ContentValues().apply {
                        put("missing", 1)
                        put("availability_state", "scope_unavailable")
                    },
                    "source_folder=? AND availability_state != 'scope_removed' " +
                        "AND id NOT IN (SELECT episode_id FROM episode_observations)",
                    arrayOf(reference),
                )
                changed
            }
        }
    }

    /**
     * Mirror LibraryStore.mark_source_unavailable("broad-storage", reason) for
     * legacy catalog observations after the optional broad-storage grant disappears.
     * A current available observation from another source still keeps an episode playable.
     */
    private suspend fun markBroadStorageSourceUnavailable(
        authorization: String,
        reason: String,
    ): Int {
        val normalizedAuthorization = authorization.trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("revoked", "unavailable") } ?: "unavailable"
        val normalizedReason = reason.trim().takeIf { it.isNotEmpty() } ?: "broad_scan_failed"
        return withContext(Dispatchers.IO) {
            dependencies.sqliteDatabase.transaction { database ->
                val now = System.currentTimeMillis() / 1000.0
                database.update(
                    "folders",
                    android.content.ContentValues().apply {
                        put("authorization", normalizedAuthorization)
                        put("last_error", normalizedReason)
                        put("last_scan_at", now)
                    },
                    "path=?",
                    arrayOf("broad-storage"),
                )
                val affectedEpisodes = linkedSetOf<Long>()
                database.rawQuery(
                    "SELECT DISTINCT episode_id FROM episode_observations WHERE source_kind='broad_storage'",
                    null,
                ).use { cursor ->
                    while (cursor.moveToNext()) affectedEpisodes.add(cursor.getLong(0))
                }
                var changed = 0
                for (episodeId in affectedEpisodes) {
                    changed += database.update(
                        "episode_observations",
                        android.content.ContentValues().apply {
                            put("state", "scope_unavailable")
                            put("error", normalizedReason)
                            put("last_checked_at", now)
                        },
                        "episode_id=? AND source_kind='broad_storage'",
                        arrayOf(episodeId.toString()),
                    )
                    recomputeEpisodeAvailabilityForSource(database, episodeId)
                }
                changed += database.update(
                    "episodes",
                    android.content.ContentValues().apply {
                        put("missing", 1)
                        put("availability_state", "scope_unavailable")
                    },
                    "source_folder=? AND availability_state != 'scope_removed' " +
                        "AND id NOT IN (SELECT episode_id FROM episode_observations)",
                    arrayOf("broad-storage"),
                )
                changed
            }
        }
    }

    /**
     * Native equivalent of LibraryStore.mark_source_unavailable() for legacy
     * MediaStore observations. MediaStore is diagnostic-only for new imports, but
     * existing catalog rows can still point at this provider source.
     */
    private suspend fun markMediaStoreSourceUnavailable(
        sourceReference: String,
        authorization: String,
        reason: String,
    ): Int {
        val reference = sourceReference.trim().ifBlank { "mediastore:external:video" }
        val normalizedAuthorization = authorization.trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("revoked", "unavailable") } ?: "unavailable"
        val normalizedReason = reason.trim().takeIf { it.isNotEmpty() } ?: "mediastore_scan_failed"
        return withContext(Dispatchers.IO) {
            dependencies.sqliteDatabase.transaction { database ->
                val now = System.currentTimeMillis() / 1000.0
                database.update(
                    "folders",
                    android.content.ContentValues().apply {
                        put("authorization", normalizedAuthorization)
                        put("last_error", normalizedReason)
                        put("last_scan_at", now)
                    },
                    "path=?",
                    arrayOf(reference),
                )
                val affectedEpisodes = linkedSetOf<Long>()
                database.rawQuery(
                    "SELECT DISTINCT episode_id FROM episode_observations " +
                        "WHERE source_kind='mediastore' AND scope_ref=?",
                    arrayOf(reference),
                ).use { cursor ->
                    while (cursor.moveToNext()) affectedEpisodes.add(cursor.getLong(0))
                }
                var changed = 0
                for (episodeId in affectedEpisodes) {
                    changed += database.update(
                        "episode_observations",
                        android.content.ContentValues().apply {
                            put("state", "scope_unavailable")
                            put("error", normalizedReason)
                            put("last_checked_at", now)
                        },
                        "episode_id=? AND source_kind='mediastore' AND scope_ref=?",
                        arrayOf(episodeId.toString(), reference),
                    )
                    recomputeEpisodeAvailabilityForSource(database, episodeId)
                }
                changed += database.update(
                    "episodes",
                    android.content.ContentValues().apply {
                        put("missing", 1)
                        put("availability_state", "scope_unavailable")
                    },
                    "source_folder=? AND availability_state != 'scope_removed' " +
                        "AND id NOT IN (SELECT episode_id FROM episode_observations)",
                    arrayOf(reference),
                )
                changed
            }
        }
    }

    /**
     * A verify_tree result is a confirmation of an existing source, not permission to
     * create a new library root from an arbitrary persisted Android grant.
     */
    private suspend fun reconcileVerifiedSafRoot(
        treeUri: String,
        payload: org.json.JSONObject,
    ): Boolean {
        val reference = treeUri.trim()
        val uri = runCatching { Uri.parse(reference) }.getOrNull() ?: return false
        if (reference.isEmpty() || uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) return false
        val persistedReadUris = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
        if (StorageAuthorization.safAccess(reference, persistedReadUris) != SafAccessLevel.AVAILABLE) return false

        val suppliedIdentity = payload.optString("identity", payload.optString("safIdentity", "")).trim()
        val canonicalIdentity = StorageAuthorization.safIdentity(reference) ?: suppliedIdentity.takeIf { it.isNotEmpty() }
        val folders = withContext(Dispatchers.IO) { dependencies.sqliteLibraryRepository.listFolders() }
        val existing = folders.firstOrNull { folder ->
            folder.kind.equals("saf", ignoreCase = true) &&
                (folder.path == reference ||
                    (!suppliedIdentity.isNullOrBlank() && folder.safIdentity == suppliedIdentity) ||
                    (!canonicalIdentity.isNullOrBlank() && folder.safIdentity == canonicalIdentity))
        } ?: return false

        dependencies.sqliteLibraryRepository.upsertSafFolder(
            reference = reference,
            name = payload.optString("name", "").trim().ifBlank { existing.name.orEmpty() },
            authority = payload.optString("authority", "").trim().ifBlank { existing.safAuthority.orEmpty() },
            documentId = payload.optString("documentId", payload.optString("document_id", ""))
                .trim().ifBlank { existing.safDocumentId.orEmpty() },
            volumeId = payload.optString("volumeId", payload.optString("volume_id", ""))
                .trim().ifBlank { existing.safVolumeId.orEmpty() },
            identity = canonicalIdentity?.takeIf { it.isNotBlank() } ?: existing.safIdentity,
        )
        return true
    }

    private suspend fun updateSafFolderInventoryMetadata(
        reference: String,
        item: org.json.JSONObject,
        identity: String,
        authorization: String? = null,
        error: String? = null,
    ): Boolean {
        val path = reference.trim()
        if (path.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            dependencies.sqliteDatabase.transaction { database ->
                val values = android.content.ContentValues().apply {
                    put("kind", "saf")
                    put("saf_authority", item.optString("authority", ""))
                    put("saf_document_id", item.optString("documentId", item.optString("document_id", "")))
                    val volume = item.optString("volumeId", item.optString("volume_id", "")).trim()
                    if (volume.isEmpty()) putNull("saf_volume_id") else put("saf_volume_id", volume)
                    put("saf_identity", identity)
                    if (authorization != null) {
                        put("authorization", authorization)
                        if (error.isNullOrBlank()) putNull("last_error") else put("last_error", error)
                        put("last_scan_at", System.currentTimeMillis() / 1000.0)
                    }
                }
                database.update("folders", values, "path=? AND kind='saf'", arrayOf(path)) > 0
            }
        }
    }

    /** Mirror LibraryStore.restore_source() for a revalidated SAF root. */
    private suspend fun restoreSafSource(reference: String): Int {
        val path = reference.trim()
        if (path.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            dependencies.sqliteDatabase.transaction { database ->
                val now = System.currentTimeMillis() / 1000.0
                val affectedEpisodes = linkedSetOf<Long>()
                database.rawQuery(
                    "SELECT DISTINCT episode_id FROM episode_observations " +
                        "WHERE source_kind='saf' AND scope_ref=? AND state='scope_unavailable'",
                    arrayOf(path),
                ).use { cursor ->
                    while (cursor.moveToNext()) affectedEpisodes.add(cursor.getLong(0))
                }
                var changed = 0
                for (episodeId in affectedEpisodes) {
                    changed += database.update(
                        "episode_observations",
                        android.content.ContentValues().apply {
                            put("state", "available")
                            putNull("error")
                            put("last_checked_at", now)
                        },
                        "episode_id=? AND source_kind='saf' AND scope_ref=? AND state='scope_unavailable'",
                        arrayOf(episodeId.toString(), path),
                    )
                    recomputeEpisodeAvailabilityForSource(database, episodeId)
                }
                changed += database.update(
                    "episodes",
                    android.content.ContentValues().apply {
                        put("missing", 0)
                        put("availability_state", "available")
                    },
                    "source_folder=? AND availability_state='scope_unavailable' " +
                        "AND id NOT IN (SELECT episode_id FROM episode_observations)",
                    arrayOf(path),
                )
                changed
            }
        }
    }

    /**
     * Reconcile one full SAF inventory against explicitly configured folders.
     * Persisted Android grants alone are never promoted to library sources.
     */
    private suspend fun reconcileSafInventory(payload: org.json.JSONObject) {
        val inventoryStartedAt = payload.optLong(
            "inventoryStartedAtMs",
            payload.optLong("inventory_started_at_ms", 0L),
        )
        val inventoryComplete = payload.optBoolean(
            "inventoryComplete",
            payload.optBoolean("inventory_complete", payload.optBoolean("complete", false)),
        )
        if (inventoryStartedAt > 0L && lastStorageMutationAtMillis > inventoryStartedAt) {
            recordDiagnostic(
                name = "SAF_INVENTORY_STALE_IGNORED",
                severity = "INFO",
                result = "newer_storage_mutation",
                context = mapOf(
                    "inventory_started_at_ms" to inventoryStartedAt,
                    "last_mutation_at_ms" to lastStorageMutationAtMillis,
                    "inventory_complete" to inventoryComplete,
                ),
            )
            settingsViewModel.refresh()
            // main.py still re-evaluates the startup onboarding gate for a stale
            // inventory, but only from current canonical folders/grants—not from
            // the stale payload itself.
            reconcileStorageOnboardingProjection(
                reason = "storage_stale_inventory",
                requestId = payload.optString("requestId", "").takeIf { it.isNotBlank() },
            )
            return
        }

        val trees = payload.optJSONArray("trees") ?: org.json.JSONArray()
        val statusByIdentity = linkedMapOf<String, org.json.JSONObject>()
        fun statusPriority(item: org.json.JSONObject): Int = when (
            item.optString("status", "").trim().uppercase(Locale.ROOT)
        ) {
            "COMPLETED", "EMPTY_COMPLETE" -> 4
            "UNAVAILABLE", "FAILED", "PARTIAL" -> 3
            "REVOKED" -> 2
            else -> 1
        }
        for (index in 0 until trees.length()) {
            val item = trees.optJSONObject(index) ?: continue
            val treeUri = item.optString("treeUri", item.optString("tree_uri", "")).trim()
            if (treeUri.isEmpty()) continue
            val identity = StorageAuthorization.safIdentity(treeUri)
                ?: item.optString("identity", "").trim().takeIf { it.isNotEmpty() }
                ?: continue
            val previous = statusByIdentity[identity]
            if (previous == null || statusPriority(item) > statusPriority(previous)) {
                statusByIdentity[identity] = org.json.JSONObject(item.toString())
                    .put("treeUri", treeUri)
                    .put("identity", identity)
            }
        }

        // This recovery path is allowed only for a pending explicit picker request
        // and validates the new persisted grant before writing it to the catalog.
        recoverPendingSafSelectionFromInventory(payload)

        val folders = withContext(Dispatchers.IO) { dependencies.sqliteLibraryRepository.listFolders() }
        val configuredSafFolders = folders.filter { it.kind.equals("saf", ignoreCase = true) }
        val persistedReadUris = activity.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
        val availableConfiguredPaths = linkedSetOf<String>()
        var identityRowsUpdated = 0
        var sourcesRestored = 0
        var sourcesUnavailable = 0

        for ((identity, item) in statusByIdentity) {
            val matchingFolders = configuredSafFolders.filter { folder ->
                val folderIdentity = StorageAuthorization.safIdentity(folder.path)
                    ?: folder.safIdentity?.trim()?.takeIf { it.isNotEmpty() }
                folderIdentity == identity
            }
            // A persisted Android grant is a permission, not library configuration.
            if (matchingFolders.isEmpty()) continue

            val status = item.optString("status", "UNAVAILABLE").trim().uppercase(Locale.ROOT)
            for (folder in matchingFolders) {
                val reference = folder.path.trim()
                if (reference.isEmpty()) continue
                when (status) {
                    "COMPLETED", "EMPTY_COMPLETE" -> {
                        if (updateSafFolderInventoryMetadata(reference, item, identity, "granted", null)) {
                            identityRowsUpdated++
                        }
                        sourcesRestored += restoreSafSource(reference)
                        if (StorageAuthorization.safAccess(reference, persistedReadUris) == SafAccessLevel.AVAILABLE) {
                            availableConfiguredPaths.add(reference)
                        }
                    }
                    "REVOKED" -> {
                        if (updateSafFolderInventoryMetadata(
                                reference,
                                item,
                                identity,
                                "revoked",
                                "A autorização SAF desta pasta não está mais presente no Android.",
                            )
                        ) identityRowsUpdated++
                        sourcesUnavailable += markSafSourceUnavailable(
                            reference,
                            authorization = "revoked",
                            reason = "saf_permission_revoked",
                        )
                    }
                    "UNAVAILABLE", "FAILED", "PARTIAL" -> {
                        if (updateSafFolderInventoryMetadata(
                                reference,
                                item,
                                identity,
                                "unavailable",
                                "O provedor SAF desta pasta está indisponível no momento.",
                            )
                        ) identityRowsUpdated++
                        sourcesUnavailable += markSafSourceUnavailable(
                            reference,
                            authorization = "unavailable",
                            reason = "saf_provider_unavailable",
                        )
                    }
                    else -> {
                        if (updateSafFolderInventoryMetadata(reference, item, identity)) {
                            identityRowsUpdated++
                        }
                    }
                }
            }
        }

        if (inventoryComplete) {
            val knownIdentities = statusByIdentity.keys
            for (folder in configuredSafFolders) {
                val reference = folder.path.trim()
                val identity = StorageAuthorization.safIdentity(reference)
                    ?: folder.safIdentity?.trim()?.takeIf { it.isNotEmpty() }
                if (reference.isEmpty() || identity.isNullOrEmpty() || identity in knownIdentities) continue
                sourcesUnavailable += markSafSourceUnavailable(
                    reference,
                    authorization = "revoked",
                    reason = "saf_permission_revoked",
                )
            }
        }

        updateSafCapabilities(availableConfiguredPaths)
        settingsViewModel.refresh()
        libraryViewModel.refreshNativeFolders()
        libraryViewModel.refreshCatalogProjection()
        mutableStorageState.value = storageSnapshot()

        // Use the same onboarding transition path as permission/removal events.
        reconcileStorageOnboardingProjection(
            reason = "storage_ready_inventory",
            requestId = payload.optString("requestId", "").takeIf { it.isNotBlank() },
        )

        recordDiagnostic(
            name = "NATIVE_SAF_INVENTORY_RECONCILED",
            result = if (inventoryComplete) "complete" else "partial_inventory",
            context = mapOf(
                "inventory_started_at_ms" to inventoryStartedAt,
                "configured_root_count" to configuredSafFolders.size,
                "reported_identity_count" to statusByIdentity.size,
                "identity_rows_updated" to identityRowsUpdated,
                "sources_restored" to sourcesRestored,
                "sources_marked_unavailable" to sourcesUnavailable,
            ),
        )
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
        pendingSafSelectionBaselineKnown = false
        publish(action = "clear_pending_saf_selection")
    }

    private fun clearPendingSafRemovalContext() {
        pendingSafRemovalRequestId = null
        pendingSafRemovalReference = null
        pendingSafRemovalBridge = "library"
        pendingSafRemovalResultAction = "remove_saf"
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
            action = pendingSafRemovalResultAction,
            status = terminalStatus,
            operationState = terminalStatus,
            message = message,
            error = error,
            payload = resultPayload,
        )
        clearPendingSafRemovalContext()
        return published
    }

    /**
     * Kotlin port of main.py's _set_storage_onboarding_state. Lifecycle and
     * inventory snapshots cannot regress a validated library source from READY,
     * nor erase an actionable selection error before a user retry or valid grant.
     */
    private fun publishStorageOnboardingState(
        state: String,
        message: String? = null,
        error: String? = null,
        dismissed: Boolean? = null,
        diagnosticEvent: String? = null,
        result: String? = null,
        requestId: String? = null,
    ): String {
        var normalized = state.trim().uppercase(Locale.ROOT).ifBlank { "CHECKING" }
        val current = activity.nativeStorageOnboardingState.value
        val configuredRootAvailable = runCatching {
            storageSnapshot().configuredSafRoots.isNotEmpty()
        }.getOrDefault(false)
        var resolvedMessage = message
        var resolvedError = error

        if (
            current.phase.equals(NativeStorageOnboardingState.PHASE_READY, ignoreCase = true) &&
            normalized in setOf("CHECKING", "FOLDER_PICKER_OPEN", "NEEDS_FOLDER") &&
            configuredRootAvailable
        ) {
            normalized = "READY"
            resolvedMessage = null
            resolvedError = null
        }
        if (
            current.phase.equals(NativeStorageOnboardingState.PHASE_ERROR, ignoreCase = true) &&
            normalized in setOf("CHECKING", "NEEDS_FOLDER") &&
            !configuredRootAvailable &&
            pendingSafSelectionRequestId.isNullOrBlank()
        ) {
            normalized = "ERROR"
            resolvedMessage = current.message ?: resolvedMessage
            resolvedError = current.error ?: resolvedError
        }

        val phase = when (normalized) {
            "READY" -> NativeStorageOnboardingState.PHASE_READY
            "NEEDS_FOLDER" -> NativeStorageOnboardingState.PHASE_NEEDS_FOLDER
            "FOLDER_PICKER_OPEN" -> NativeStorageOnboardingState.PHASE_FOLDER_PICKER_OPEN
            "ERROR", "FAILED" -> NativeStorageOnboardingState.PHASE_ERROR
            else -> NativeStorageOnboardingState.PHASE_CHECKING
        }
        activity.reportNativeStorageOnboardingState(
            phase = phase,
            message = resolvedMessage,
            error = resolvedError,
            dismissed = dismissed,
        )
        diagnosticEvent?.takeIf { it.isNotBlank() }?.let { name ->
            recordDiagnostic(
                name = name,
                severity = if (phase == NativeStorageOnboardingState.PHASE_ERROR) "WARN" else "INFO",
                source = "saf",
                requestId = requestId,
                result = result ?: normalized.lowercase(Locale.ROOT),
                context = mapOf(
                    "phase" to phase,
                    "has_message" to !resolvedMessage.isNullOrBlank(),
                    "has_error" to !resolvedError.isNullOrBlank(),
                ),
            )
        }
        Log.i(
            "ReiAnixMainPort",
            "STORAGE_ONBOARDING_STATE phase=${phase} hasMessage=${!resolvedMessage.isNullOrBlank()} hasError=${!resolvedError.isNullOrBlank()}",
        )
        return normalized
    }

    private suspend fun reconcileStorageOnboardingProjection(
        reason: String,
        requestId: String? = null,
    ) {
        // Port main.py's startup onboarding order: repair stale SAF source identities
        // against Android's persisted read grants before deciding that a configured
        // library root is unavailable and prompting the user to select it again.
        val repairedSafIdentities = repairConfiguredSafSourceIdentities()
        if (repairedSafIdentities > 0) {
            recordDiagnostic(
                name = "SAF_SOURCE_IDENTITIES_REPAIRED",
                result = "startup_onboarding",
                requestId = requestId,
                context = mapOf("repaired_rows" to repairedSafIdentities, "reason" to reason),
            )
        }
        val validRoots = configuredValidLibrarySafRoots()
        val current = activity.nativeStorageOnboardingState.value
        if (validRoots.isNotEmpty()) {
            publishStorageOnboardingState(
                state = "READY",
                message = null,
                error = null,
                dismissed = current.dismissed,
                diagnosticEvent = if (
                    current.phase.equals(NativeStorageOnboardingState.PHASE_READY, ignoreCase = true)
                ) null else "STORAGE_READY",
                result = reason,
                requestId = requestId,
            )
            scheduleThumbnailReconciliation(reason)
            scheduleCatalogMetadataHydration(reason)
            return
        }

        if (!pendingSafSelectionRequestId.isNullOrBlank()) {
            publishStorageOnboardingState(
                state = "FOLDER_PICKER_OPEN",
                message = "Selecione a pasta onde estão armazenados seus animes.",
                error = null,
                dismissed = false,
                requestId = requestId,
            )
            return
        }

        val hasConfiguredSafRow = withContext(Dispatchers.IO) {
            dependencies.sqliteLibraryRepository.listFolders().any {
                it.kind.equals("saf", ignoreCase = true)
            }
        }
        val onboardingResult = if (hasConfiguredSafRow) "configured_root_invalid" else "library_root_missing"
        publishStorageOnboardingState(
            state = "NEEDS_FOLDER",
            message = if (hasConfiguredSafRow) {
                "O acesso à pasta da sua biblioteca foi perdido. Selecione novamente a pasta onde estão seus animes."
            } else {
                "Selecione a pasta onde estão armazenados seus animes.\n\nO ReiAnix usará essa pasta para encontrar e organizar seus vídeos."
            },
            error = null,
            dismissed = current.dismissed,
            diagnosticEvent = if (hasConfiguredSafRow) {
                "STORAGE_PERMISSION_REVOKED"
            } else {
                "STORAGE_PERMISSION_MISSING"
            },
            result = onboardingResult,
            requestId = requestId,
        )
        if (!current.dismissed) {
            recordDiagnostic(
                name = "STORAGE_ONBOARDING_SHOWN",
                source = "saf",
                result = onboardingResult,
                requestId = requestId,
            )
        }
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
    private suspend fun recoverPendingSafSelectionFromInventory(payload: org.json.JSONObject): Boolean {
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
            inventoryStarted <= 0L || !inventoryComplete || !pendingSafSelectionBaselineKnown
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

        if (pendingSafSelectionRequestId != request) return false
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
            // The legacy helper awaited catalog registration and validated that
            // the new grant became a usable source before publishing success.
            val recoveredRoot = withContext(Dispatchers.IO) {
                configuredValidLibrarySafRoots().firstOrNull { configured ->
                    configured == treeUri ||
                        StorageAuthorization.safIdentity(configured) == identity
                }
            }
            if (recoveredRoot == null) {
                val message =
                    "O Android autorizou a pasta, mas o ReiAnix não conseguiu confirmar a fonte da biblioteca. Toque em ESCOLHER PASTA para tentar novamente."
                activity.reportNativeStorageOnboardingState(
                    phase = NativeStorageOnboardingState.PHASE_ERROR,
                    message = message,
                    error = "selected_saf_source_validation_failed",
                    dismissed = false,
                )
                libraryViewModel.refreshNativeFolders()
                settingsViewModel.refresh()
                libraryViewModel.refreshCatalogProjection()
                mutableStorageState.value = storageSnapshot()
                finishPendingSafSelection(
                    eventRequestId = request,
                    status = "ERROR",
                    message = message,
                    error = "selected_saf_source_validation_failed",
                    payload = org.json.JSONObject()
                        .put("treeUri", treeUri)
                        .put("identity", identity)
                        .put("recoveredFromInventory", true),
                )
                recordDiagnostic(
                    name = "STORAGE_SOURCE_VALIDATION_FAILED",
                    severity = "WARN",
                    requestId = request,
                    result = "inventory_recovery_validation_failed",
                    context = mapOf("identity" to identity),
                )
                return true
            }

            recordStorageMutation("saf_selection_recovered_from_inventory", treeUri)
            libraryViewModel.refreshNativeFolders()
            settingsViewModel.refresh()
            libraryViewModel.refreshCatalogProjection()
            mutableStorageState.value = storageSnapshot()
            activity.reportNativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_READY,
                message = null,
                error = null,
                dismissed = false,
            )
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
            val message = failure.message ?: "Não foi possível recuperar a seleção de pasta."
            activity.reportNativeStorageOnboardingState(
                phase = NativeStorageOnboardingState.PHASE_ERROR,
                message = message,
                error = failure.javaClass.simpleName,
                dismissed = false,
            )
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
                message = message,
                error = failure.javaClass.simpleName,
                payload = org.json.JSONObject().put("treeUri", treeUri),
            )
        }
        return true
    }

    fun beginSafSelection(requestId: String? = null, bridge: String = "library"): Boolean {
        if (pendingSafSelectionRequestId?.isNotBlank() == true || !canMutateSafRoots()) return false
        pendingSafSelectionRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        pendingSafSelectionBridge = bridge.trim().lowercase(Locale.ROOT).takeIf {
            it == "library" || it == "settings"
        } ?: "library"
        val baseline = runCatching {
            activity.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .mapNotNull { StorageAuthorization.safIdentity(it.uri.toString()) }
                .toSet()
        }
        pendingSafSelectionBaselineKnown = baseline.isSuccess
        pendingSafSelectionBaselineIdentities = baseline.getOrDefault(emptySet())
        if (!pendingSafSelectionBaselineKnown) {
            recordDiagnostic(
                name = "SAF_SELECTION_BASELINE_UNAVAILABLE",
                severity = "WARN",
                requestId = pendingSafSelectionRequestId,
                result = "inventory_recovery_disabled",
            )
        }
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
            libraryViewModel.refreshCatalogProjection()
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
        !restoreMaintenanceInProgress &&
            pendingSafSelectionRequestId.isNullOrBlank() &&
            pendingSafRemovalRequestId.isNullOrBlank() &&
            pendingFolderRemovalRequestId.isNullOrBlank() &&
            !hasAnyNativeScanInProgress() &&
            !activity.nativeStorageOnboardingState.value.phase.equals("folder_picker_open", ignoreCase = true)

    private fun canContinueFolderRemoval(requestId: String): Boolean =
        !restoreMaintenanceInProgress &&
            pendingFolderRemovalRequestId == requestId &&
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



    /**
     * Drain the existing Android -> app event mailbox that main.py previously
     * polled. Files are claimed by atomic rename, acknowledged only after native
     * dispatch and the bounded event-ID ledger have both committed, and restored
     * to .json after a failed dispatch so a later poll can retry them.
     */
    private fun ensureNativeMailboxPoller() {
        synchronized(nativeMailboxPollLock) {
            if (nativeMailboxPollJob?.isActive == true) return
            nativeMailboxPollJob = activity.lifecycleScope.launch {
                pollNativeMailboxQueue()
            }
        }
    }

    private fun nativeMailboxDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun ensureNativeMailboxEventId(
        event: org.json.JSONObject,
        sourceName: String,
        index: Int,
        serializedSource: String,
    ): String {
        val supplied = event.optString("eventId", "").trim()
        if (supplied.isNotEmpty()) return supplied
        val digest = nativeMailboxDigest("$sourceName\u0000$index\u0000$serializedSource").take(24)
        val generated = "legacy:$sourceName:$index:$digest"
        event.put("eventId", generated)
        return generated
    }

    private suspend fun loadNativeMailboxEventLedger() {
        if (nativeMailboxLedgerLoaded) return
        val preferences = dependencies.sqliteSettingsRepository.allPreferences()
        val raw = preferences["native_event_ids"]
        val restored = LinkedHashSet<String>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val array = org.json.JSONArray(raw)
                for (index in 0 until array.length()) {
                    array.optString(index, "").trim().takeIf { it.isNotEmpty() }?.let(restored::add)
                }
            }.onFailure {
                Log.w("ReiAnixMainPort", "Native mailbox event ledger is malformed; rebuilding bounded ledger.", it)
            }
        }
        synchronized(nativeMailboxEventLedgerLock) {
            if (!nativeMailboxLedgerLoaded) {
                nativeMailboxEventIds.clear()
                restored.toList().takeLast(1_000).forEach(nativeMailboxEventIds::add)
                nativeMailboxLedgerLoaded = true
            }
        }
    }

    private fun hasClaimedNativeMailboxEvent(eventId: String): Boolean =
        synchronized(nativeMailboxEventLedgerLock) { eventId in nativeMailboxEventIds }

    private suspend fun persistNativeMailboxEventId(eventId: String) {
        val normalized = eventId.trim()
        if (normalized.isEmpty()) return
        val updated = synchronized(nativeMailboxEventLedgerLock) {
            LinkedHashSet(nativeMailboxEventIds).apply {
                add(normalized)
                while (size > 1_000) {
                    val iterator = iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    } else break
                }
            }.toList()
        }
        dependencies.sqliteSettingsRepository.setPreferencesAtomically(
            mapOf("native_event_ids" to org.json.JSONArray(updated).toString()),
        )
        synchronized(nativeMailboxEventLedgerLock) {
            nativeMailboxEventIds.clear()
            updated.forEach(nativeMailboxEventIds::add)
        }
    }

    /** Recover claims left by process death and migrate the older one-file mailbox format. */
    private fun recoverNativeMailboxQueueFiles() {
        if (!nativeMailboxDirectory.isDirectory && !nativeMailboxDirectory.mkdirs()) {
            throw IllegalStateException("Não foi possível abrir a fila de eventos nativos.")
        }
        val dataDirectory = nativeMailboxDirectory.parentFile
        if (dataDirectory != null) {
            val legacy = File(dataDirectory, "reiflix-native-events.json")
            val legacyClaimed = File(dataDirectory, "reiflix-native-events.consumed")
            if (legacyClaimed.exists() && !legacy.exists()) legacyClaimed.renameTo(legacy)
            if (legacy.exists() && legacy.renameTo(legacyClaimed)) {
                try {
                    val raw = legacyClaimed.readText(Charsets.UTF_8)
                    val parsed = runCatching { org.json.JSONArray(raw) }.getOrNull()
                    val legacyEvents = if (parsed != null) {
                        (0 until parsed.length()).mapNotNull { parsed.optJSONObject(it) }
                    } else {
                        listOf(org.json.JSONObject(raw))
                    }
                    legacyEvents.forEachIndexed { index, event ->
                        if (event.optString("type", "").isBlank()) return@forEachIndexed
                        val eventId = ensureNativeMailboxEventId(
                            event = event,
                            sourceName = legacy.name,
                            index = index,
                            serializedSource = event.toString(),
                        )
                        val nameDigest = nativeMailboxDigest(eventId).take(24)
                        val target = File(nativeMailboxDirectory, "event-legacy-$nameDigest.json")
                        if (target.exists()) return@forEachIndexed
                        val temporary = File(nativeMailboxDirectory, "${target.name}.tmp")
                        java.io.FileOutputStream(temporary).use { stream ->
                            stream.write(event.toString().toByteArray(Charsets.UTF_8))
                            stream.fd.sync()
                        }
                        if (!temporary.renameTo(target)) {
                            temporary.delete()
                            throw IllegalStateException("Não foi possível migrar um evento nativo legado.")
                        }
                    }
                    legacyClaimed.delete()
                    Log.i("ReiAnixMainPort", "LEGACY_NATIVE_MAILBOX_MIGRATED")
                } catch (failure: Throwable) {
                    if (!legacy.exists()) legacyClaimed.renameTo(legacy)
                    Log.w("ReiAnixMainPort", "Não foi possível migrar a fila de eventos legada.", failure)
                }
            }
        }

        nativeMailboxDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("event-") && it.name.endsWith(".consumed") }
            ?.sortedBy { it.name }
            ?.forEach { claimed ->
                val target = File(nativeMailboxDirectory, claimed.name.removeSuffix(".consumed") + ".json")
                if (target.exists()) {
                    claimed.delete()
                } else if (!claimed.renameTo(target)) {
                    Log.w("ReiAnixMainPort", "Não foi possível recuperar o evento reivindicado: ${claimed.name}")
                }
            }
    }

    /** Claim a bounded batch in createdAt order, using file publication time as a tie-breaker. */
    private fun claimNativeMailboxBatch(): List<NativeMailboxQueueItem> {
        if (!nativeMailboxDirectory.isDirectory && !nativeMailboxDirectory.mkdirs()) return emptyList()
        val candidates = mutableListOf<NativeMailboxQueueItem>()
        nativeMailboxDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("event-") && it.name.endsWith(".json") }
            ?.forEach { file ->
                try {
                    val raw = file.readText(Charsets.UTF_8)
                    val envelope = org.json.JSONObject(raw)
                    if (envelope.optString("type", "").isBlank()) {
                        Log.w("ReiAnixMainPort", "EVENT_REJECTED reason=missing_type file=${file.name}")
                        file.delete()
                        return@forEach
                    }
                    ensureNativeMailboxEventId(envelope, file.name, 0, raw)
                    val createdAt = envelope.optLong("createdAt", envelope.optLong("timestamp", 0L))
                        .coerceAtLeast(0L)
                    candidates += NativeMailboxQueueItem(
                        file = file,
                        envelope = envelope,
                        createdAtMillis = createdAt,
                        modifiedAtMillis = file.lastModified(),
                    )
                } catch (failure: Throwable) {
                    Log.w("ReiAnixMainPort", "EVENT_REJECTED reason=invalid_json file=${file.name}", failure)
                    file.delete()
                }
            }
        candidates.sortWith(
            compareBy<NativeMailboxQueueItem> { it.createdAtMillis }
                .thenBy { it.modifiedAtMillis }
                .thenBy { it.file.name },
        )

        val claimed = mutableListOf<NativeMailboxQueueItem>()
        candidates.take(64).forEach { candidate ->
            val target = File(
                nativeMailboxDirectory,
                candidate.file.name.removeSuffix(".json") + ".consumed",
            )
            if (candidate.file.renameTo(target)) {
                claimed += candidate.copy(file = target)
            }
        }
        return claimed
    }


    /** Match main.py's progress coalescing without merging different playback identities. */
    private fun coalesceNativeMailboxProgressEvents(
        events: List<NativeMailboxQueueItem>,
    ): List<NativeMailboxQueueItem> {
        val compacted = mutableListOf<NativeMailboxQueueItem>()
        fun pythonTruthy(value: Any?): Boolean = when (value) {
            null, org.json.JSONObject.NULL -> false
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty()
            is org.json.JSONArray -> value.length() > 0
            is org.json.JSONObject -> value.length() > 0
            else -> true
        }

        fun truthyText(source: org.json.JSONObject, vararg keys: String): String {
            var selected: Any? = null
            for (key in keys) {
                val candidate = source.opt(key)
                if (pythonTruthy(candidate)) {
                    selected = candidate
                    break
                }
            }
            return when (selected) {
                null, org.json.JSONObject.NULL -> ""
                is Boolean -> if (selected) "True" else "False"
                else -> selected.toString()
            }.trim()
        }

        fun eventType(item: NativeMailboxQueueItem): String =
            truthyText(item.envelope, "eventType", "type")

        fun identity(item: NativeMailboxQueueItem): List<String> {
            val envelope = item.envelope
            val payload = envelope.optJSONObject("payload") ?: org.json.JSONObject()
            return listOf(
                truthyText(payload, "playerSessionId"),
                truthyText(payload, "activityInstanceId"),
                truthyText(envelope, "requestId").ifEmpty {
                    // Python selects requestId before stripping whitespace. Keep
                    // fallback semantics exact, including a truthy whitespace-only ID.
                    val topLevel = envelope.opt("requestId")
                    if (pythonTruthy(topLevel)) truthyText(envelope, "requestId")
                    else truthyText(payload, "requestId")
                },
                truthyText(payload, "episodeId"),
                truthyText(payload, "uri"),
                truthyText(payload, "generation", "playerGeneration"),
                truthyText(payload, "transitionGeneration"),
            )
        }

        for (event in events) {
            val isProgress = eventType(event) == "player_progress"
            val previous = compacted.lastOrNull()
            if (
                isProgress &&
                previous != null &&
                eventType(previous) == "player_progress"
            ) {
                val currentIdentity = identity(event)
                if (currentIdentity.any { it.isNotEmpty() } && currentIdentity == identity(previous)) {
                    compacted[compacted.lastIndex] = event
                    continue
                }
            }
            compacted += event
        }
        return compacted
    }

    private suspend fun requeueNativeMailboxEvent(item: NativeMailboxQueueItem) {
        withContext(Dispatchers.IO) {
            val target = File(
                nativeMailboxDirectory,
                item.file.name.removeSuffix(".consumed") + ".json",
            )
            if (target.exists()) {
                Log.w("ReiAnixMainPort", "EVENT_REQUEUE_COLLISION file=${item.file.name}")
            } else if (!item.file.renameTo(target)) {
                Log.w("ReiAnixMainPort", "EVENT_REQUEUE_FAILED file=${item.file.name}")
            }
        }
    }

    private suspend fun acknowledgeNativeMailboxEvent(item: NativeMailboxQueueItem) {
        withContext(Dispatchers.IO) {
            if (!item.file.delete() && item.file.exists()) {
                Log.w("ReiAnixMainPort", "EVENT_ACK_FAILED file=${item.file.name}")
            }
        }
    }

    private suspend fun pollNativeMailboxQueue() {
        loadNativeMailboxEventLedger()
        withContext(Dispatchers.IO) { recoverNativeMailboxQueueFiles() }
        var pollDelayMillis = 200L
        while (!activity.isFinishing && !activity.isDestroyed) {
            try {
                val claimedEvents = withContext(Dispatchers.IO) { claimNativeMailboxBatch() }
                val events = coalesceNativeMailboxProgressEvents(claimedEvents)
                for (item in events) {
                    val eventId = item.envelope.optString("eventId", "").trim()
                    try {
                        if (eventId.isBlank() || !hasClaimedNativeMailboxEvent(eventId)) {
                            item.envelope.put("receivedAtMs", System.currentTimeMillis())
                            dispatchNativeMailboxEnvelope(item.envelope)
                            persistNativeMailboxEventId(eventId)
                        }
                        acknowledgeNativeMailboxEvent(item)
                    } catch (cancelled: CancellationException) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            requeueNativeMailboxEvent(item)
                        }
                        throw cancelled
                    } catch (failure: Throwable) {
                        synchronized(lock) { seenProjectionEventIds.remove(eventId) }
                        requeueNativeMailboxEvent(item)
                        Log.e(
                            "ReiAnixMainPort",
                            "EVENT_PROCESS_FAILED eventId=${eventId.ifBlank { "-" }} type=${item.envelope.optString("type")}",
                            failure,
                        )
                    }
                }
                val retainedEventFiles = events.map { it.file.absolutePath }.toSet()
                claimedEvents.filter { it.file.absolutePath !in retainedEventFiles }.forEach {
                    acknowledgeNativeMailboxEvent(it)
                }
                pollDelayMillis = when {
                    claimedEvents.isNotEmpty() -> 80L
                    mutableSnapshot.value.playerSessionActive -> (pollDelayMillis + 25L).coerceAtMost(250L)
                    else -> (pollDelayMillis * 3L / 2L).coerceIn(200L, 1_000L)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e("ReiAnixMainPort", "NATIVE_MAILBOX_LOOP_FAILED", failure)
                pollDelayMillis = (pollDelayMillis * 3L / 2L).coerceAtMost(1_000L)
            }
            delay(pollDelayMillis)
        }
    }

    fun onRuntimeDisconnect() {
        // Mirror main.py's cancellation of the pending player transition task.
        // A handoff that loses its runtime owner must not leave the ViewModel's
        // single-flight gate locked for subsequent play requests.
        cancelPlayerTransition("runtime_disconnect")
        libraryViewModel.cancelPendingNativePlayerHandoff("runtime_disconnect")
        cancelBackgroundTasks()
        // main.py clears the active home-refresh context when a Flet view
        // disconnects. Terminate the native coordinator as well so a reconnect
        // cannot inherit a permanently active refresh with a stale request ID.
        val activeRefresh = HomeRefreshCoordinator.state.value
        if (activeRefresh.active && !activeRefresh.requestId.isNullOrBlank()) {
            HomeRefreshCoordinator.cancel(
                activeRefresh.requestId,
                "runtime_disconnect",
            )
        }
        publish(action = "runtime_disconnect")
    }

    /**
     * Native equivalent of main.py's startup notice for scan_runs that the
     * existing SQLite startup migration marked as interrupted. This is a
     * read-only projection: it never starts a scan or edits catalog rows.
     */
    private fun showInterruptedScanNoticeIfNeeded() {
        launchTracked("startup_interrupted_scan_notice", lane = "startup") {
            val interruptedCount = withContext(Dispatchers.IO) {
                dependencies.sqliteDatabase.read { database ->
                    database.rawQuery(
                        "SELECT COUNT(*) FROM scan_runs WHERE status = ?",
                        arrayOf("interrupted"),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                }
            }
            if (!NativeMainStartupNoticeState.claimInterruptedScansNotice(interruptedCount)) {
                return@launchTracked
            }
            val message =
                "$interruptedCount varredura(s) anterior(es) foram interrompidas e poderão ser refeitas."
            android.widget.Toast.makeText(
                activity.applicationContext,
                message,
                android.widget.Toast.LENGTH_LONG,
            ).show()
            recordDiagnostic(
                name = "INTERRUPTED_SCAN_STARTUP_NOTICE",
                result = "shown",
                context = mapOf("interrupted_scan_count" to interruptedCount),
            )
        }
    }

    fun onRuntimeReconnect() {
        resumeRuntimeTasks()
        ensureNativeCommandDispatcher()
        settingsViewModel.refresh()
        libraryViewModel.refreshCatalogProjection()
        ensureNativeMailboxPoller()
        showInterruptedScanNoticeIfNeeded()
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
            else libraryViewModel.refreshCatalogProjection()
        }
        publish(action = "drop_screen_cache", transform = { it.copy(generation = it.generation + 1L) })
    }

    fun clearScreenCache() {
        libraryViewModel.refreshCatalogProjection()
        settingsViewModel.refresh()
        publish(action = "clear_screen_cache", transform = { it.copy(generation = it.generation + 1L) })
    }

    fun invalidateCatalogViews(animeId: Long? = null) {
        if (animeId != null && animeId > 0L) libraryViewModel.refreshDetails(animeId)
        libraryViewModel.refreshCatalogProjection()
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
    suspend fun exportSettingsJson(): String = withContext(Dispatchers.IO) { settingsFileService.exportJson() }

    suspend fun importSettingsJson(raw: String): ReiAnixSettingsFileService.ImportResult {
        require(raw.length <= 1_048_576) { "O arquivo de configurações excede o limite suportado." }
        val result = withContext(Dispatchers.IO) { settingsFileService.importJson(raw) }
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
            val artifactPath = withContext(Dispatchers.IO) {
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
                target.absolutePath
            }
            publish(action = "export_settings", transform = { it.copy(lastArtifactPath = artifactPath) })
        }

    fun importSettingsFromPrivateFile(
        file: File,
        requestId: String? = null,
        resultAction: String = "settings_import",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        val sourcePath = runCatching { file.canonicalFile.toPath() }.getOrNull()
        val allowedRoots = listOf(activity.filesDir, activity.cacheDir).mapNotNull {
            runCatching { it.canonicalFile.toPath() }.getOrNull()
        }
        if (
            sourcePath == null || allowedRoots.none { sourcePath.startsWith(it) } ||
            !file.isFile || file.length() !in 1..1_048_576L
        ) {
            val message = "O arquivo de configurações não está em uma área local permitida."
            publish(action = "import_settings", error = message)
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = message,
                    error = "settings_file_not_allowed",
                )
            }
            return false
        }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Importação das configurações enfileirada.",
            )
        }
        val accepted = launchTracked("import_settings", lane = "settings") {
            try {
                val raw = withContext(Dispatchers.IO) { file.readText(Charsets.UTF_8) }
                val imported = importSettingsJson(raw)
                publish(action = "import_settings", transform = {
                    it.copy(lastArtifactPath = file.absolutePath)
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Configurações importadas.",
                        payload = org.json.JSONObject()
                            .put("imported", imported.imported)
                            .put("unknownKeys", org.json.JSONArray(imported.unknownKeys)),
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A importação das configurações foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível importar as configurações.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A importação das configurações não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    /** Build the existing canonical backup format; no alternate catalogue or database is created. */
    fun createBackup(
        requestId: String? = null,
        resultAction: String = "create_backup",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Criação do backup enfileirada.",
            )
        }
        val accepted = launchTracked("create_backup", lane = "backup") {
            val backupStartedAtMillis = android.os.SystemClock.elapsedRealtime()
            try {
                val archive = withContext(Dispatchers.IO) { backupService.createBackupFile() }
                require(archive.isFile && archive.length() > 0L) {
                    "O serviço de backup não produziu um arquivo válido."
                }
                recordDiagnostic(
                    name = "BACKUP_CREATED",
                    result = "success",
                    context = mapOf(
                        "backup_duration_ms" to (android.os.SystemClock.elapsedRealtime() - backupStartedAtMillis),
                        "size_bytes" to archive.length(),
                    ),
                )
                publish(action = "create_backup", transform = {
                    it.copy(lastArtifactPath = archive.absolutePath, lastResultJson = null)
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Backup criado.",
                        payload = org.json.JSONObject()
                            .put("fileName", archive.name)
                            .put("sizeBytes", archive.length()),
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A criação do backup foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível criar o backup.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A criação do backup não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    /** Inspect archive structure and integrity before an explicit restore request. */
    fun inspectBackup(
        archive: File,
        requestId: String? = null,
        resultAction: String = "inspect_backup",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (!archive.isFile || archive.length() <= 0L) {
            val message = "O arquivo de backup está ausente ou vazio."
            publish(action = "inspect_backup", error = message)
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = message,
                    error = "backup_missing_or_empty",
                )
            }
            return false
        }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Inspeção do backup enfileirada.",
            )
        }
        val accepted = launchTracked("inspect_backup", lane = "backup") {
            try {
                val inspection = withContext(Dispatchers.IO) { backupService.inspectBackup(archive) }
                val summary = org.json.JSONObject()
                    .put("manifest", inspection.manifest)
                    .put("counts", inspection.counts)
                    .put("invalidOptionalEntries", org.json.JSONArray(inspection.invalidOptionalEntries.toList()))
                    .put("warnings", org.json.JSONArray(inspection.warnings))
                publish(action = "inspect_backup", transform = {
                    it.copy(lastArtifactPath = archive.absolutePath, lastResultJson = summary.toString())
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Inspeção do backup concluída.",
                        payload = summary,
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A inspeção do backup foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível inspecionar o backup.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A inspeção do backup não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    /** Restore through the canonical Kotlin service, including its pre-restore safety backup. */
    fun restoreBackup(
        archive: File,
        requestId: String? = null,
        resultAction: String = "restore_backup",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (!archive.isFile || archive.length() <= 0L) {
            val message = "O arquivo de backup está ausente ou vazio."
            publish(action = "restore_backup", error = message)
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = message,
                    error = "backup_missing_or_empty",
                )
            }
            return false
        }
        val reserved = synchronized(lock) {
            val storageMutationPending =
                !pendingSafSelectionRequestId.isNullOrBlank() ||
                    !pendingSafRemovalRequestId.isNullOrBlank() ||
                    !pendingFolderRemovalRequestId.isNullOrBlank()
            if (restoreMaintenanceInProgress || hasAnyNativeScanInProgress() || storageMutationPending) {
                false
            } else {
                restoreMaintenanceInProgress = true
                true
            }
        }
        if (!reserved) {
            val message = "Finalize a varredura ou operação de armazenamento atual antes de restaurar um backup."
            publish(action = "restore_backup", error = message)
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "BLOCKED",
                    operationState = "BLOCKED",
                    message = message,
                    error = "scan_in_progress_or_restore_active",
                )
            }
            recordDiagnostic(
                name = "BACKUP_RESTORE_BLOCKED",
                severity = "WARN",
                requestId = correlatedRequestId,
                result = "scan_in_progress_or_restore_active",
            )
            return false
        }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Restauração do backup enfileirada.",
            )
        }
        val accepted = launchTracked("restore_backup", lane = "backup") {
            val restoreStartedAtMillis = android.os.SystemClock.elapsedRealtime()
            try {
                // Match the old library metadata lock and artwork-generation invalidation:
                // stop tracked metadata/thumbnail work, wait for in-flight reconciliations
                // to finish, then restore the canonical snapshot without stale work writing
                // into the restored catalogue.
                cancelBackgroundTasksForLane("metadata")
                cancelBackgroundTasksForLane("thumbnail")
                val result = metadataMaintenanceMutex.withLock {
                    thumbnailMaintenanceMutex.withLock {
                        withContext(Dispatchers.IO) {
                            // MainActivity starts SAF/broad scans through NativeScanController
                            // directly, outside this runtime adapter. Serialize this final
                            // preflight and restore against the controller's synchronized
                            // begin/launch methods so a new scan cannot start during restore.
                            synchronized(NativeScanController) {
                                if (NativeScanController.progress.value.values.any { !it.state.isTerminal }) {
                                    throw IllegalStateException(
                                        "Uma varredura foi iniciada antes da reserva exclusiva do restore.",
                                    )
                                }
                                backupService.restoreBackup(archive)
                            }
                        }
                    }
                }
                recordDiagnostic(
                    name = "RESTORE_COMPLETED",
                    result = "success",
                    context = mapOf(
                        "restore_duration_ms" to (android.os.SystemClock.elapsedRealtime() - restoreStartedAtMillis),
                        "imported_rows" to result.importedRows,
                        "missing_files" to result.missingFiles,
                        "manual_artwork_reconnected" to result.manualArtworkReconnected,
                        "warnings_count" to result.warnings.size,
                    ),
                )
                libraryViewModel.refreshCatalogProjection()
                settingsViewModel.refresh()
                scheduleCatalogMetadataHydration("backup_restored")
                scheduleThumbnailReconciliation("backup_restored")
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
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Backup restaurado.",
                        payload = summary,
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A restauração do backup foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível restaurar o backup.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            } finally {
                synchronized(lock) { restoreMaintenanceInProgress = false }
            }
        }
        if (!accepted) {
            synchronized(lock) { restoreMaintenanceInProgress = false }
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = "A restauração do backup não foi aceita.",
                    error = "operation_rejected",
                )
            }
        }
        return accepted
    }

    fun createRecoverySafetySnapshot(
        requestId: String? = null,
        resultAction: String = "create_recovery_safety_snapshot",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Snapshot de segurança enfileirado.",
            )
        }
        val accepted = launchTracked("create_recovery_safety_snapshot", lane = "backup") {
            try {
                val file = withContext(Dispatchers.IO) { backupService.createRecoverySafetySnapshotForUser() }
                publish(action = "create_recovery_safety_snapshot", transform = {
                    it.copy(lastArtifactPath = file.absolutePath)
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Snapshot de segurança criado.",
                        payload = org.json.JSONObject()
                            .put("fileName", file.name)
                            .put("sizeBytes", file.length()),
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A criação do snapshot foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível criar o snapshot de segurança.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A criação do snapshot não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    fun restoreRecoveryBackup(
        archive: File,
        requestId: String? = null,
        resultAction: String = "restore_recovery_backup",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (!archive.isFile || archive.length() <= 0L) {
            val message = "O arquivo de recuperação está ausente ou vazio."
            publish(action = "restore_recovery_backup", error = message)
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = message,
                    error = "recovery_archive_missing_or_empty",
                )
            }
            return false
        }
        val reserved = synchronized(lock) {
            val storageMutationPending =
                !pendingSafSelectionRequestId.isNullOrBlank() ||
                    !pendingSafRemovalRequestId.isNullOrBlank() ||
                    !pendingFolderRemovalRequestId.isNullOrBlank()
            if (restoreMaintenanceInProgress || hasAnyNativeScanInProgress() || storageMutationPending) {
                false
            } else {
                restoreMaintenanceInProgress = true
                true
            }
        }
        if (!reserved) {
            val message = "Finalize a varredura ou operação de armazenamento atual antes de restaurar a recuperação."
            publish(action = "restore_recovery_backup", error = message)
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "BLOCKED",
                    operationState = "BLOCKED",
                    message = message,
                    error = "scan_in_progress_or_restore_active",
                )
            }
            recordDiagnostic(
                name = "RECOVERY_RESTORE_BLOCKED",
                severity = "WARN",
                requestId = correlatedRequestId,
                result = "scan_in_progress_or_restore_active",
            )
            return false
        }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Restauração de recuperação enfileirada.",
            )
        }
        val accepted = launchTracked("restore_recovery_backup", lane = "backup") {
            try {
                cancelBackgroundTasksForLane("metadata")
                cancelBackgroundTasksForLane("thumbnail")
                // Match normal backup restore semantics: cancel queued work, wait for
                // any in-flight metadata/artwork maintenance to leave its critical section,
                // and then serialize the restore against scanner startup. Recovery mode
                // must not let an old reconciliation write into the recovered catalogue.
                val result = metadataMaintenanceMutex.withLock {
                    thumbnailMaintenanceMutex.withLock {
                        withContext(Dispatchers.IO) {
                            synchronized(NativeScanController) {
                                if (NativeScanController.progress.value.values.any { !it.state.isTerminal }) {
                                    throw IllegalStateException(
                                        "Uma varredura foi iniciada antes da reserva exclusiva da recuperação.",
                                    )
                                }
                                backupService.restoreRecoveryBackup(archive)
                            }
                        }
                    }
                }
                libraryViewModel.refreshCatalogProjection()
                settingsViewModel.refresh()
                scheduleCatalogMetadataHydration("recovery_backup_restored")
                scheduleThumbnailReconciliation("recovery_backup_restored")
                val summary = org.json.JSONObject()
                    .put("importedRows", result.importedRows)
                    .put("missingFiles", result.missingFiles)
                    .put("manualArtworkReconnected", result.manualArtworkReconnected)
                    .put("safetyBackup", result.safetyBackup.absolutePath)
                    .put("warnings", org.json.JSONArray(result.warnings))
                    .put("accountStatePreserved", result.accountStatePreserved)
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
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Recuperação restaurada.",
                        payload = summary,
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A restauração de recuperação foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível restaurar a recuperação.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            } finally {
                synchronized(lock) { restoreMaintenanceInProgress = false }
            }
        }
        if (!accepted) {
            synchronized(lock) { restoreMaintenanceInProgress = false }
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = "A restauração de recuperação não foi aceita.",
                    error = "operation_rejected",
                )
            }
        }
        return accepted
    }

    fun checkDatabaseIntegrity(
        requestId: String? = null,
        resultAction: String = "integrity_check",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Verificação de integridade enfileirada.",
            )
        }
        val accepted = launchTracked("database_integrity", lane = "backup") {
            try {
                // main.py's integrity_check returned the complete privacy-filtered
                // diagnostic report (database, library, files, artwork, AniList,
                // playback, performance, storage and scan state), not only the
                // backup subsystem's SQLite integrity fragment.
                val reportBytes = withContext(Dispatchers.IO) {
                    val raw = ReiAnixDiagnosticsService(activity.applicationContext).exportJson()
                    if (mutableSnapshot.value.recoveryRequired) enrichRecoveryDiagnostic(raw) else raw
                }
                val report = org.json.JSONObject(String(reportBytes, Charsets.UTF_8))
                publish(action = "database_integrity", transform = {
                    it.copy(lastResultJson = report.toString())
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Verificação de integridade concluída.",
                        payload = report,
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A verificação de integridade foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível verificar a integridade do banco.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A verificação de integridade não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    fun reconcileRestoredFileAvailability(): Boolean =
        launchTracked("reconcile_restored_file_availability", lane = "backup") {
            val count = withContext(Dispatchers.IO) { backupService.reconcileRestoredFileAvailability() }
            publish(action = "reconcile_restored_file_availability", transform = {
                it.copy(lastResultJson = org.json.JSONObject().put("updatedRows", count).toString())
            })
            libraryViewModel.refreshCatalogProjection()
        }

    /**
     * Explicit post-restore reconciliation mirrors ScanCoordinator.request_restore_reconciliation:
     * repair local availability first, then ask the native scanner to revisit every currently
     * authorized source. It is never triggered implicitly by process/activity resume.
     */
    private fun requestRestoreReconciliation(requestId: String?): Boolean {
        val resolvedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        if (restoreMaintenanceInProgress) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "backup_reconcile",
                status = "BLOCKED",
                operationState = "BLOCKED",
                message = "Aguarde a restauração do backup terminar antes de reconciliar a biblioteca.",
                error = "restore_in_progress",
            )
            recordDiagnostic(
                name = "RESTORE_RECONCILIATION_BLOCKED",
                severity = "WARN",
                requestId = resolvedRequestId,
                result = "restore_in_progress",
            )
            return false
        }
        if (mutableSnapshot.value.recoveryRequired) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "backup_reconcile",
                status = "FAILED",
                operationState = "FAILED",
                message = "A reconciliação normal está indisponível durante o Recovery Mode.",
                error = "recovery_required",
            )
            return false
        }
        val accepted = launchTracked("backup_reconcile", lane = "backup") {
            try {
                val scanState = mutableScanUiState.value.state.uppercase(Locale.ROOT)
                if (scanState in setOf("RUNNING", "QUEUED", "CANCELLING")) {
                    recordDiagnostic(
                        name = "RESTORE_RECONCILIATION_SCAN_ALREADY_RUNNING",
                        severity = "WARN",
                        requestId = resolvedRequestId,
                        result = scanState,
                        context = mapOf("reconciliation" to "blocked_before_mutation"),
                    )
                    publishOperationResult(
                        requestId = resolvedRequestId,
                        action = "backup_reconcile",
                        status = "BLOCKED",
                        operationState = "BLOCKED",
                        message = "Aguarde a varredura atual terminar antes de reconciliar novamente.",
                        error = "scan_already_running",
                    )
                    return@launchTracked
                }
                val updated = withContext(Dispatchers.IO) {
                    backupService.reconcileRestoredFileAvailability()
                }
                libraryViewModel.refreshCatalogProjection()
                scheduleCatalogMetadataHydration("post_restore_reconciliation")
                scheduleThumbnailReconciliation("post_restore_reconciliation")

                val scanAccepted = requestLibraryScan(
                    requestId = resolvedRequestId,
                    resultAction = "backup_reconcile",
                )
                if (!scanAccepted) {
                    // requestLibraryScan already publishes the specific BLOCKED or
                    // FAILED terminal result. Do not overwrite that reason with a
                    // generic failure from this caller.
                    recordDiagnostic(
                        name = "RESTORE_RECONCILIATION_SCAN_NOT_QUEUED",
                        severity = "WARN",
                        requestId = resolvedRequestId,
                        result = operationResultFor(resolvedRequestId)?.status ?: "rejected",
                        context = mapOf("local_references_updated" to updated),
                    )
                    return@launchTracked
                }
                recordDiagnostic(
                    name = "RESTORE_RECONCILIATION_QUEUED",
                    requestId = resolvedRequestId,
                    result = "scan_library",
                    context = mapOf("local_references_updated" to updated),
                )
            } catch (cancelled: CancellationException) {
                publishOperationResult(
                    requestId = resolvedRequestId,
                    action = "backup_reconcile",
                    status = "CANCELLED",
                    operationState = "CANCELLED",
                    message = "A reconciliação pós-restauração foi cancelada.",
                )
                throw cancelled
            } catch (failure: Throwable) {
                publishOperationResult(
                    requestId = resolvedRequestId,
                    action = "backup_reconcile",
                    status = "FAILED",
                    operationState = "FAILED",
                    message = failure.message ?: "Não foi possível reconciliar a biblioteca restaurada.",
                    error = failure.javaClass.simpleName,
                )
                throw failure
            }
        }
        if (!accepted) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = "backup_reconcile",
                status = "FAILED",
                operationState = "FAILED",
                message = "A operação de reconciliação não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    /** Native privacy-filtered report mirrors the old diagnostic export action. */
    fun exportDiagnosticsJson(
        requestId: String? = null,
        resultAction: String = "export_diagnostics",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Exportação de diagnósticos enfileirada.",
            )
        }
        val accepted = launchTracked("export_diagnostics", lane = "diagnostics") {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val raw = diagnosticsService.exportJson()
                    if (mutableSnapshot.value.recoveryRequired) {
                        enrichRecoveryDiagnostic(raw)
                    } else {
                        raw
                    }
                }
                val json = bytes.toString(Charsets.UTF_8)
                publish(action = "export_diagnostics", transform = {
                    it.copy(lastResultJson = json)
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Diagnósticos exportados.",
                        payload = org.json.JSONObject().put("jsonBytes", bytes.size),
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A exportação de diagnósticos foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível exportar os diagnósticos.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A exportação de diagnósticos não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    /**
     * Port RecoveryService.diagnostic_bytes without exposing the local database path.
     * The ordinary privacy-filtered report remains unchanged outside Recovery Mode.
     */
    private fun enrichRecoveryDiagnostic(raw: ByteArray): ByteArray {
        val report = org.json.JSONObject(String(raw, Charsets.UTF_8))
        val database = report.optJSONObject("database") ?: org.json.JSONObject()
        val databaseFile = File(File(activity.filesDir, "data"), "library.sqlite3")
        val quickCheck = database.optJSONArray("quick_check") ?: org.json.JSONArray()
        val foreignKeyViolations = database.optInt("foreign_key_violations", -1)
        report.put(
            "recovery",
            org.json.JSONObject()
                .put("required", true)
                .put("database_exists", databaseFile.isFile)
                .put("size_bytes", if (databaseFile.isFile) databaseFile.length() else 0L)
                .put("quick_check", quickCheck)
                // Keep the FK result independent from the overall SQLite/schema
                // health flag, matching the legacy RecoveryService diagnostic contract.
                .put("foreign_key_ok", foreignKeyViolations == 0)
                .put("error", database.opt("error") ?: org.json.JSONObject.NULL),
        )
        return (report.toString(2) + "\n").toByteArray(Charsets.UTF_8)
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
    fun setSettingSerialized(
        key: String,
        value: String,
        requestId: String? = null,
    ): Boolean {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return false
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        val keyLock = settingLocks.getOrPut(normalizedKey) { Mutex() }
        return launchTracked("set_setting", lane = "settings") {
            keyLock.withLock {
                settingsViewModel.setSetting(normalizedKey, value, correlatedRequestId)
            }
        }
    }

    /**
     * Legacy settings commands already contain an action-specific payload and request ID.
     * Dispatch those commands through the same native action port instead of handing them
     * to the Compose button adapter, which would mint an unrelated operation ID.
     */
    fun requestSettingsActionTracked(
        action: String,
        payload: org.json.JSONObject = org.json.JSONObject(),
        requestId: String? = null,
    ): Boolean {
        val normalized = action.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        return dispatchSettingsAction(normalized, payload, requestId)
    }
    /** Validate the entire update before committing any settings row. */
    fun applySettingsAtomically(
        values: Map<String, String>,
        requestId: String? = null,
        resultAction: String = "settings_bulk_applied",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        if (values.isEmpty() || values.size > ReiAnixSettingsContract.definitions.size) {
            correlatedRequestId?.let {
                publishOperationResult(
                    requestId = it,
                    action = resultAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = "A lista de configurações está vazia ou excede o limite permitido.",
                    error = "invalid_settings_count",
                )
            }
            return false
        }
        val normalized = linkedMapOf<String, String>()
        for ((rawKey, rawValue) in values) {
            val key = rawKey.trim()
            val definition = ReiAnixSettingsContract.definitions[key]
            if (definition == null) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = "A configuração solicitada não é reconhecida.",
                        error = "unknown_setting_key",
                        payload = org.json.JSONObject().put("key", key),
                    )
                }
                return false
            }
            val value = runCatching { definition.normalize(rawValue) }.getOrNull()
            if (value == null) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = "O valor de uma configuração não é válido.",
                        error = "invalid_setting_value",
                        payload = org.json.JSONObject().put("key", key),
                    )
                }
                return false
            }
            normalized[key] = value
        }
        if (normalized.isEmpty()) return false
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "As alterações de configuração foram enfileiradas.",
            )
        }
        val accepted = launchTracked("settings_bulk_applied", lane = "settings") {
            try {
                dependencies.sqliteSettingsRepository.setPreferencesAtomically(normalized)
                settingsViewModel.refresh()
                val resultPayload = org.json.JSONObject()
                    .put("applied", normalized.size)
                    .put("keys", org.json.JSONArray(normalized.keys.toList()))
                publish(
                    action = "settings_bulk_applied",
                    transform = { it.copy(lastResultJson = resultPayload.toString()) },
                )
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "As configurações foram aplicadas.",
                        payload = resultPayload,
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A atualização das configurações foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível aplicar as configurações.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A operação de configurações não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
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
            libraryViewModel.refreshCatalogProjection()
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


    fun requestAccountActionTracked(action: String, requestId: String? = null): Boolean {
        val rawAction = action.trim().lowercase(Locale.ROOT)
        val normalized = when (rawAction) {
            "switch_account", "switchaccount" -> "switch"
            else -> rawAction
        }
        if (normalized.isEmpty()) return false
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        return launchTracked("account_action", lane = "settings") {
            settingsViewModel.requestAccountAction(normalized, correlatedRequestId)
        }
    }

    /** Native scanner snapshots replace the old Python ScanCoordinator projection. */
    fun currentScanUiState(): NativeMainScanUiState = mutableScanUiState.value

    fun canStartLibraryScan(source: String? = null): Boolean {
        if (restoreMaintenanceInProgress) return false
        val normalized = source?.trim()?.lowercase(Locale.ROOT)
        if (normalized != null && normalized !in setOf("saf", "all", "library")) return false
        // Storage selection/removal and scanning are mutually exclusive. A queued
        // folder deletion must not race a newly requested scan before its path has
        // been classified as SAF or non-SAF by the canonical folder repository.
        if (
            !pendingSafSelectionRequestId.isNullOrBlank() ||
            !pendingSafRemovalRequestId.isNullOrBlank() ||
            !pendingFolderRemovalRequestId.isNullOrBlank()
        ) return false
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
        synchronized(lock) {
            !restoreMaintenanceInProgress && NativeScanController.begin(scanId, sourceKey)
        }

    fun launchNativeScan(scanId: String, block: suspend CoroutineScope.() -> Unit): Boolean =
        synchronized(lock) {
            !restoreMaintenanceInProgress && NativeScanController.launch(scanId, block)
        }

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

    /**
     * Legacy main.py distinguishes SAF roots (release the Android URI grant, then
     * remove the canonical row) from ordinary folder records (remove the SQLite row).
     * Keep that distinction at this boundary instead of treating every folder as SAF.
     */
    private fun requestFolderRemovalFromCanonicalCatalog(
        reference: String,
        requestId: String?,
        bridge: String,
        resultAction: String,
    ): Boolean {
        val normalizedReference = reference.trim()
        val normalizedAction = resultAction.trim().lowercase(Locale.ROOT)
            .takeIf { it in setOf("remove_folder", "remove_saf", "remove_saf_tree", "release_tree") }
            ?: "remove_folder"
        val resolvedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()

        if (normalizedReference.isEmpty()) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = normalizedAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A referência da pasta não foi informada.",
                error = "folder_reference_missing",
            )
            return false
        }
        val reserved = synchronized(lock) {
            if (!canMutateSafRoots()) {
                false
            } else {
                pendingFolderRemovalRequestId = resolvedRequestId
                true
            }
        }
        if (!reserved) {
            publishOperationResult(
                requestId = resolvedRequestId,
                action = normalizedAction,
                status = "BLOCKED",
                operationState = "BLOCKED",
                message = "Aguarde a varredura ou a seleção de pasta atual terminar.",
                error = "storage_operation_in_progress",
            )
            return false
        }

        publishOperationResult(
            requestId = resolvedRequestId,
            action = normalizedAction,
            status = "QUEUED",
            operationState = "QUEUED",
            message = "Remoção da pasta enfileirada.",
        )
        val accepted = launchTracked("remove_folder", lane = "storage") {
            try {
                if (!canContinueFolderRemoval(resolvedRequestId)) {
                    throw IllegalStateException("A operação de armazenamento não pode continuar neste momento.")
                }
                val folder = withContext(Dispatchers.IO) {
                    dependencies.sqliteLibraryRepository.listFolders()
                        .firstOrNull { it.path == normalizedReference }
                } ?: throw IllegalArgumentException("A pasta não está configurada na biblioteca.")

                val isSaf = folder.kind.equals("saf", ignoreCase = true)
                if (!isSaf && normalizedAction != "remove_folder") {
                    throw IllegalArgumentException("A referência não corresponde a uma pasta SAF configurada.")
                }

                if (isSaf) {
                    if (!canContinueFolderRemoval(resolvedRequestId)) {
                        throw IllegalStateException("Outra operação de armazenamento está em andamento.")
                    }
                    val parsedTree = runCatching { Uri.parse(folder.path) }.getOrNull()
                    require(
                        parsedTree != null &&
                            parsedTree.scheme.equals("content", ignoreCase = true) &&
                            DocumentsContract.isTreeUri(parsedTree),
                    ) { "A pasta SAF configurada contém uma referência inválida." }

                    if (!pendingSafRemovalRequestId.isNullOrBlank()) {
                        throw IllegalStateException("Já existe uma remoção SAF em andamento.")
                    }
                    pendingSafRemovalRequestId = resolvedRequestId
                    pendingSafRemovalReference = folder.path
                    pendingSafRemovalBridge = bridge.trim().lowercase(Locale.ROOT).takeIf {
                        it == "library" || it == "settings"
                    } ?: "library"
                    pendingSafRemovalResultAction = normalizedAction
                    pendingFolderRemovalRequestId = null
                    try {
                        withContext(Dispatchers.Main) {
                            activity.startActivity(
                                MainActivity.createNativeStorageActionIntent(
                                    context = activity,
                                    action = "release_tree",
                                    treeUri = folder.path,
                                    requestId = resolvedRequestId,
                                ),
                            )
                        }
                    } catch (failure: Exception) {
                        clearPendingSafRemovalContext()
                        throw failure
                    }
                    recordDiagnostic(
                        name = "NATIVE_FOLDER_RELEASE_REQUESTED",
                        requestId = resolvedRequestId,
                        result = normalizedAction,
                        context = mapOf("folder_kind" to "saf", "tree_uri_present" to true),
                    )
                    return@launchTracked
                }

                if (!canContinueFolderRemoval(resolvedRequestId)) {
                    throw IllegalStateException("Outra operação de armazenamento está em andamento.")
                }
                val removed = withContext(Dispatchers.IO) {
                    dependencies.sqliteLibraryRepository.removeFolder(normalizedReference)
                }
                if (!removed) throw IllegalStateException("A pasta não pôde ser removida do catálogo local.")
                libraryViewModel.refreshNativeFolders()
                libraryViewModel.refreshCatalogProjection()
                if (pendingFolderRemovalRequestId == resolvedRequestId) {
                    pendingFolderRemovalRequestId = null
                }
                publishOperationResult(
                    requestId = resolvedRequestId,
                    action = normalizedAction,
                    status = "COMPLETED",
                    operationState = "COMPLETED",
                    message = "Pasta removida da biblioteca.",
                    payload = org.json.JSONObject()
                        .put("reference", normalizedReference)
                        .put("folderKind", folder.kind ?: ""),
                )
                recordDiagnostic(
                    name = "NATIVE_FOLDER_REMOVED",
                    requestId = resolvedRequestId,
                    result = normalizedAction,
                    context = mapOf("folder_kind" to (folder.kind ?: "unknown")),
                )
                publish(action = "remove_folder")
            } catch (cancelled: CancellationException) {
                if (pendingSafRemovalRequestId == resolvedRequestId) clearPendingSafRemovalContext()
                if (pendingFolderRemovalRequestId == resolvedRequestId) {
                    pendingFolderRemovalRequestId = null
                }
                publishOperationResult(
                    requestId = resolvedRequestId,
                    action = normalizedAction,
                    status = "CANCELLED",
                    operationState = "CANCELLED",
                    message = "Remoção da pasta cancelada.",
                )
                throw cancelled
            } catch (failure: Throwable) {
                if (pendingSafRemovalRequestId == resolvedRequestId) clearPendingSafRemovalContext()
                if (pendingFolderRemovalRequestId == resolvedRequestId) {
                    pendingFolderRemovalRequestId = null
                }
                publishOperationResult(
                    requestId = resolvedRequestId,
                    action = normalizedAction,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = failure.message ?: "Não foi possível remover a pasta.",
                    error = failure.javaClass.simpleName,
                )
                recordDiagnostic(
                    name = "NATIVE_FOLDER_REMOVAL_FAILED",
                    severity = "WARN",
                    requestId = resolvedRequestId,
                    result = normalizedAction,
                    error = failure,
                )
            }
        }
        if (!accepted) {
            if (pendingFolderRemovalRequestId == resolvedRequestId) {
                pendingFolderRemovalRequestId = null
            }
            publishOperationResult(
                requestId = resolvedRequestId,
                action = normalizedAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A operação de remoção não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
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

    /**
     * Port of main.py refresh_current_metadata: refresh the existing local
     * association, or perform the legacy forced title-match flow when no
     * valid AniList ID has been assigned. SQLite remains the only catalog.
     */
    fun refreshAniListMetadata(
        animeId: Long,
        requestId: String? = null,
        resultAction: String = "refresh_anilist_metadata",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        val operation = resultAction.trim().ifEmpty { "refresh_anilist_metadata" }
        if (animeId <= 0L) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = operation,
                status = "FAILED",
                operationState = "FAILED",
                message = "O identificador local do anime é inválido.",
                error = "invalid_anime_id",
            )
            return false
        }
        publishOperationResult(
            requestId = correlatedRequestId,
            action = operation,
            status = "QUEUED",
            operationState = "QUEUED",
            message = "Atualização dos metadados AniList enfileirada.",
        )
        val accepted = launchTracked("refresh_anilist_metadata", lane = "metadata") {
            try {
                val integration = dependencies.aniListIntegrationService
                var result = integration.refreshMetadata(animeId)
                var completionMessage = "Metadados AniList atualizados."
                var completionPayload = org.json.JSONObject().put("animeId", animeId)

                if (result is com.reiflix.reiflix_local.data.anilist.AniListResult.Failure) {
                    val error = result.error
                    val invalidRequest = error as? com.reiflix.reiflix_local.data.anilist.AniListError.InvalidRequest
                    if (invalidRequest?.message?.contains("disabled in settings", ignoreCase = true) == true) {
                        val message = "O AniList está desativado nas configurações."
                        completionPayload.put("result", "disabled")
                        publish(action = "refresh_anilist_metadata", transform = {
                            it.copy(lastResultJson = completionPayload.toString(), selectedAnimeId = animeId)
                        })
                        publishOperationResult(
                            requestId = correlatedRequestId,
                            action = operation,
                            status = "BLOCKED",
                            operationState = "BLOCKED",
                            message = message,
                            error = "anilist_disabled",
                            payload = completionPayload,
                        )
                        return@launchTracked
                    }
                    val missingAssociation = invalidRequest
                        ?.message
                        ?.contains("no valid AniList association", ignoreCase = true) == true
                    if (missingAssociation) {
                        when (val matchResult = integration.autoMatch(animeId, force = true)) {
                            is com.reiflix.reiflix_local.data.anilist.AniListResult.Failure -> {
                                throw IllegalStateException(matchResult.error.toString())
                            }
                            is com.reiflix.reiflix_local.data.anilist.AniListResult.Success -> {
                                when (val match = matchResult.value) {
                                    is com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.Matched -> {
                                        result = com.reiflix.reiflix_local.data.anilist.AniListResult.Success(match.anime)
                                        completionMessage = "Metadados AniList encontrados e atualizados."
                                        completionPayload
                                            .put("match", "matched")
                                            .put("anilistId", match.remote.id)
                                    }
                                    is com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.AlreadyMatched,
                                    is com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.AlreadyManuallyMatched -> {
                                        result = integration.refreshMetadata(animeId)
                                        completionMessage = "Associação AniList existente verificada."
                                        completionPayload.put("match", "already_matched")
                                    }
                                    is com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.Ambiguous -> {
                                        val candidates = org.json.JSONArray().apply {
                                            match.candidates.forEach { candidate ->
                                                put(
                                                    org.json.JSONObject()
                                                        .put("id", candidate.id)
                                                        .put("titleRomaji", candidate.titleRomaji ?: org.json.JSONObject.NULL)
                                                        .put("titleEnglish", candidate.titleEnglish ?: org.json.JSONObject.NULL)
                                                        .put("titleNative", candidate.titleNative ?: org.json.JSONObject.NULL)
                                                        .put("year", candidate.seasonYear ?: org.json.JSONObject.NULL)
                                                        .put("format", candidate.format ?: org.json.JSONObject.NULL),
                                                )
                                            }
                                        }
                                        completionPayload
                                            .put("match", "ambiguous")
                                            .put("candidates", candidates)
                                        completionMessage = "Foram encontradas várias correspondências possíveis. Revise as opções AniList antes de associar."
                                        publishOperationResult(
                                            requestId = correlatedRequestId,
                                            action = operation,
                                            status = "COMPLETED",
                                            operationState = "COMPLETED",
                                            message = completionMessage,
                                            payload = completionPayload,
                                        )
                                        publish(action = "refresh_anilist_metadata", transform = {
                                            it.copy(lastResultJson = completionPayload.toString(), selectedAnimeId = animeId)
                                        })
                                        return@launchTracked
                                    }
                                    com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.NoMatch -> {
                                        completionPayload.put("match", "no_match")
                                        completionMessage = "Nenhuma correspondência AniList confiável foi encontrada."
                                        publishOperationResult(
                                            requestId = correlatedRequestId,
                                            action = operation,
                                            status = "COMPLETED",
                                            operationState = "COMPLETED",
                                            message = completionMessage,
                                            payload = completionPayload,
                                        )
                                        publish(action = "refresh_anilist_metadata", transform = {
                                            it.copy(lastResultJson = completionPayload.toString(), selectedAnimeId = animeId)
                                        })
                                        return@launchTracked
                                    }
                                    com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.RecentlyAttempted -> {
                                        completionPayload.put("match", "recently_attempted")
                                        completionMessage = "A correspondência AniList foi tentada recentemente. Tente novamente mais tarde."
                                        publishOperationResult(
                                            requestId = correlatedRequestId,
                                            action = operation,
                                            status = "COMPLETED",
                                            operationState = "COMPLETED",
                                            message = completionMessage,
                                            payload = completionPayload,
                                        )
                                        publish(action = "refresh_anilist_metadata", transform = {
                                            it.copy(lastResultJson = completionPayload.toString(), selectedAnimeId = animeId)
                                        })
                                        return@launchTracked
                                    }
                                    com.reiflix.reiflix_local.data.anilist.AniListAutoMatchResult.Disabled -> {
                                        publishOperationResult(
                                            requestId = correlatedRequestId,
                                            action = operation,
                                            status = "BLOCKED",
                                            operationState = "BLOCKED",
                                            message = "O auto-match AniList está desativado nas configurações.",
                                            error = "auto_match_disabled",
                                        )
                                        return@launchTracked
                                    }
                                }
                            }
                        }
                    } else {
                        throw IllegalStateException(error.toString())
                    }
                }

                when (val finalResult = result) {
                    is com.reiflix.reiflix_local.data.anilist.AniListResult.Success -> {
                        val refreshedRecord = finalResult.value
                        val stale = refreshedRecord?.metadataStatus.equals("stale", ignoreCase = true)
                        if (stale) {
                            completionMessage = "Não foi possível atualizar os metadados AniList agora; os dados locais foram mantidos e marcados como desatualizados."
                            completionPayload.put("metadataStatus", "stale")
                        }
                        libraryViewModel.refreshDetails(animeId)
                        libraryViewModel.refreshCatalogProjection()
                        settingsViewModel.refresh()
                        completionPayload.put("result", "success")
                        publish(
                            action = "refresh_anilist_metadata",
                            transform = {
                                it.copy(lastResultJson = completionPayload.toString(), selectedAnimeId = animeId)
                            },
                        )
                        publishOperationResult(
                            requestId = correlatedRequestId,
                            action = operation,
                            status = "COMPLETED",
                            operationState = "COMPLETED",
                            message = completionMessage,
                            payload = completionPayload,
                        )
                        recordDiagnostic(
                            name = "ANILIST_METADATA_REFRESH_COMPLETED",
                            requestId = correlatedRequestId,
                            result = if (stale) "stale_metadata_retained" else "updated",
                            context = mapOf("anime_id" to animeId),
                        )
                    }
                    is com.reiflix.reiflix_local.data.anilist.AniListResult.Failure -> {
                        throw IllegalStateException(finalResult.error.toString())
                    }
                }
            } catch (cancelled: CancellationException) {
                publishOperationResult(
                    requestId = correlatedRequestId,
                    action = operation,
                    status = "CANCELLED",
                    operationState = "CANCELLED",
                    message = "A atualização dos metadados AniList foi cancelada.",
                )
                throw cancelled
            } catch (failure: Throwable) {
                publishOperationResult(
                    requestId = correlatedRequestId,
                    action = operation,
                    status = "FAILED",
                    operationState = "FAILED",
                    message = failure.message ?: "Não foi possível atualizar os metadados AniList.",
                    error = failure.javaClass.simpleName,
                )
                recordDiagnostic(
                    name = "ANILIST_METADATA_REFRESH_FAILED",
                    severity = "WARN",
                    requestId = correlatedRequestId,
                    result = failure.javaClass.simpleName,
                    error = failure,
                    context = mapOf("anime_id" to animeId),
                )
                throw failure
            }
        }
        if (!accepted) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = operation,
                status = "FAILED",
                operationState = "FAILED",
                message = "A atualização dos metadados AniList não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
    }

    fun reconcileUnmatchedAniListMetadata(): Boolean =
        launchTracked("reconcile_unmatched_metadata", lane = "metadata") {
            val updated = dependencies.aniListIntegrationService.reconcileUnmatchedLibraryMetadata()
            publish(action = "reconcile_unmatched_metadata", transform = {
                it.copy(lastResultJson = org.json.JSONObject().put("updated", updated).toString())
            })
        }

    fun requestAnimeArtwork(animeId: Long, force: Boolean = false): Boolean {
        if (animeId <= 0L || restoreMaintenanceInProgress) {
            if (restoreMaintenanceInProgress) {
                recordDiagnostic(
                    name = "ARTWORK_REQUEST_BLOCKED",
                    severity = "WARN",
                    result = "restore_in_progress",
                    context = mapOf("anime_id" to animeId),
                )
            }
            return false
        }
        val accepted = dependencies.artworkService.requestAnimeArtwork(animeId, force)
        publish(action = "request_anime_artwork", error = if (accepted) null else "Solicitação de artwork não aceita.")
        return accepted
    }

    fun requestPendingArtwork(): Boolean {
        if (restoreMaintenanceInProgress) {
            recordDiagnostic(
                name = "ARTWORK_RECONCILIATION_BLOCKED",
                severity = "WARN",
                result = "restore_in_progress",
            )
            return false
        }
        val accepted = dependencies.artworkService.requestPendingArtwork()
        publish(action = "request_pending_artwork", error = if (accepted) null else "Não há solicitação pendente aceita.")
        return accepted
    }

    fun clearManagedArtworkCache(
        requestId: String? = null,
        resultAction: String = "clear_anilist_cache",
    ): Boolean {
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = resultAction,
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Limpeza do cache de arte enfileirada.",
            )
        }
        val accepted = launchTracked("clear_managed_artwork_cache", lane = "metadata") {
            try {
                // Keep parity with LibraryService.clear_anilist_cache(): the modern artwork
                // engine clears its own managed cache and expires metadata in canonical SQLite,
                // while older AniList covers may still sit directly under data/covers.
                val removed = dependencies.artworkService.clearManagedCache()
                val legacyCoverRoot = File(activity.applicationContext.filesDir, "data/covers")
                var legacyRemoved = 0
                if (legacyCoverRoot.exists()) {
                    val entries = legacyCoverRoot.listFiles()
                        ?: throw java.io.IOException("Não foi possível listar o cache legado de capas.")
                    entries.forEach { entry ->
                        // The Python compatibility path removes files in this directory only;
                        // nested directories are intentionally preserved.
                        if (entry.isFile) {
                            if (!entry.delete()) {
                                throw java.io.IOException("Não foi possível remover uma capa legada: ${entry.name}")
                            }
                            legacyRemoved++
                        }
                    }
                }
                libraryViewModel.refreshCatalogProjection()
                val resultPayload = org.json.JSONObject()
                    .put("removed", removed + legacyRemoved)
                    .put("managedRemoved", removed)
                    .put("legacyRemoved", legacyRemoved)
                publish(action = "clear_managed_artwork_cache", transform = {
                    it.copy(lastResultJson = resultPayload.toString())
                })
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Cache de arte limpo.",
                        payload = resultPayload,
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A limpeza do cache de arte foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = resultAction,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível limpar o cache de arte.",
                        error = failure.javaClass.simpleName,
                    )
                }
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = resultAction,
                status = "FAILED",
                operationState = "FAILED",
                message = "A limpeza do cache de arte não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
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
        val refreshSource = source.trim().lowercase(Locale.ROOT)
            .takeIf { it == "button" || it == "pull" } ?: "button"
        if (restoreMaintenanceInProgress) {
            recordDiagnostic(
                name = "HOME_REFRESH_BLOCKED",
                severity = "WARN",
                result = "restore_in_progress",
                context = mapOf("source" to refreshSource),
            )
            return false
        }
        if (HomeRefreshCoordinator.state.value.active) {
            recordDiagnostic(
                name = "HOME_REFRESH_REJECTED",
                severity = "INFO",
                result = "already_refreshing",
                context = mapOf("source" to refreshSource),
            )
            // The legacy request_home_refresh reports an active refresh as an
            // accepted wait, not a failed tap; leave the existing coordinator
            // untouched and let its current terminal transition own the UI state.
            return true
        }
        val requestId = UUID.randomUUID().toString()
        val refreshId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val knownOperations = NativeScanController.progress.value.keys.toSet()
        recordDiagnostic(
            name = "HOME_REFRESH_REQUESTED",
            requestId = requestId,
            refreshId = refreshId,
            result = refreshSource,
            context = mapOf("source" to refreshSource),
        )
        if (!HomeRefreshCoordinator.begin(requestId, refreshId, refreshSource)) {
            recordDiagnostic(
                name = "HOME_REFRESH_REJECTED",
                severity = "WARN",
                requestId = requestId,
                refreshId = refreshId,
                result = "coordinator_rejected",
            )
            return false
        }
        if (!pendingSafSelectionRequestId.isNullOrBlank()) {
            HomeRefreshCoordinator.fail(requestId, "saf_selection_pending")
            recordDiagnostic(
                name = "HOME_REFRESH_REJECTED",
                severity = "WARN",
                requestId = requestId,
                refreshId = refreshId,
                result = "saf_selection_pending",
            )
            return false
        }
        val capabilitiesCheckPending = storageSnapshot().onboardingPhase.equals(
            NativeStorageOnboardingState.PHASE_CHECKING,
            ignoreCase = true,
        )
        if (capabilitiesCheckPending) {
            // Port main.py's refresh_library preflight: query Android storage
            // capabilities first, then request the catalogue scan once the result
            // has updated the canonical onboarding projection.
            recordDiagnostic(
                name = "NATIVE_STORAGE_CAPABILITIES_CHECK_REQUESTED",
                requestId = requestId,
                refreshId = refreshId,
                result = "home_refresh",
            )
            if (!checkStorageAccess()) {
                HomeRefreshCoordinator.fail(requestId, "storage_capabilities_check_rejected")
                recordDiagnostic(
                    name = "HOME_REFRESH_FAILED",
                    severity = "WARN",
                    requestId = requestId,
                    refreshId = refreshId,
                    result = "storage_capabilities_check_rejected",
                )
                return false
            }
        } else {
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
            if (!requestLibraryScan(requestId = requestId, resultAction = "scan_library")) {
                HomeRefreshCoordinator.fail(requestId, "native_scan_request_rejected")
                recordDiagnostic(
                    name = "HOME_REFRESH_FAILED",
                    severity = "WARN",
                    requestId = requestId,
                    refreshId = refreshId,
                    result = "native_scan_request_rejected",
                )
                return false
            }
        }
        recordDiagnostic(
            name = "HOME_REFRESH_ACCEPTED",
            requestId = requestId,
            refreshId = refreshId,
            result = if (capabilitiesCheckPending) "storage_capability_check_accepted" else "native_scan_request_accepted",
            context = mapOf("source" to refreshSource, "capabilities_check_pending" to capabilitiesCheckPending),
        )
        synchronized(lock) {
            homeRefreshJob?.cancel()
            homeRefreshJob = activity.lifecycleScope.launch {
                try {
                    if (capabilitiesCheckPending) {
                        val checkedStorage = withTimeoutOrNull(HOME_REFRESH_SCAN_START_TIMEOUT_MS) {
                            activity.nativeStorageOnboardingState.first { state ->
                                !state.phase.equals(
                                    NativeStorageOnboardingState.PHASE_CHECKING,
                                    ignoreCase = true,
                                )
                            }
                        }
                        if (checkedStorage == null) {
                            HomeRefreshCoordinator.fail(requestId, "storage_capabilities_check_timeout")
                            recordDiagnostic(
                                name = "HOME_REFRESH_FAILED",
                                severity = "WARN",
                                requestId = requestId,
                                refreshId = refreshId,
                                result = "storage_capabilities_check_timeout",
                            )
                            return@launch
                        }
                        if (!canStartLibraryScan("saf")) {
                            HomeRefreshCoordinator.fail(requestId, "no_authorized_library_saf_source")
                            recordDiagnostic(
                                name = "HOME_REFRESH_FAILED",
                                severity = "WARN",
                                requestId = requestId,
                                refreshId = refreshId,
                                result = "no_authorized_library_saf_source",
                            )
                            return@launch
                        }
                        if (!requestLibraryScan(requestId = requestId, resultAction = "scan_library")) {
                            HomeRefreshCoordinator.fail(requestId, "native_scan_request_rejected_after_capability_check")
                            recordDiagnostic(
                                name = "HOME_REFRESH_FAILED",
                                severity = "WARN",
                                requestId = requestId,
                                refreshId = refreshId,
                                result = "native_scan_request_rejected_after_capability_check",
                            )
                            return@launch
                        }
                    }
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
                            requestId = requestId, context = mapOf("source" to refreshSource))
                        return@launch
                    }
                    HomeRefreshCoordinator.markScanStarted(requestId)
                    recordDiagnostic(
                        name = "HOME_REFRESH_STARTED",
                        requestId = requestId,
                        refreshId = refreshId,
                        result = "scan_started",
                        context = mapOf("source" to refreshSource, "operation_count" to firstBatch.size),
                    )
                    recordDiagnostic(
                        name = "HOME_REFRESH_SCAN_STARTED",
                        requestId = requestId,
                        refreshId = refreshId,
                        result = "scan_started",
                        context = mapOf("source" to refreshSource, "operation_count" to firstBatch.size),
                    )
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
                    // refreshCanonicalCatalogAfterBootstrapValidation() already reloads
                    // the canonical SQLite projection. Calling ViewModel.refresh() here
                    // would enqueue a second filesystem scan instead of completing this
                    // refresh, unlike main.py's on_catalog_changed/UI-commit path.
                    if (!HomeRefreshCoordinator.markCatalogUpdated(requestId)) {
                        throw IllegalStateException("Home refresh refused catalog update before scan completion.")
                    }
                    // The Home StateFlow projection has been committed. Do not force
                    // navigation if the user left during the scan; Compose observes the
                    // updated StateFlow when the relevant screen is shown.
                    if (!HomeRefreshCoordinator.complete(requestId)) {
                        throw IllegalStateException("Home refresh refused completion before UI commit.")
                    }
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    recordDiagnostic(
                        "HOME_REFRESH_UI_UPDATED",
                        requestId = requestId,
                        refreshId = refreshId,
                        context = mapOf("elapsed_ms" to elapsedMs, "rebuild" to false, "db_updated" to true),
                    )
                    recordDiagnostic(
                        "HOME_REFRESH_COMPLETED",
                        requestId = requestId,
                        refreshId = refreshId,
                        context = mapOf(
                            "elapsed_ms" to elapsedMs,
                            "partial_count" to stableTerminalBatch.count { it.state == ScanLifecycleState.PARTIAL },
                        ),
                    )
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
        const val MAX_PENDING_THUMBNAIL_REQUESTS = 128
        const val MAX_CONCURRENT_THUMBNAIL_REQUESTS = 4
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
    /**
     * Port of main.py on_catalog_changed for Compose-primary Android. The legacy
     * path treats this as projection invalidation after a canonical SQLite write,
     * not as a request to rerun bootstrap catalog validation.
     */
    fun onCatalogChanged(refreshDetailsForAnimeId: Long? = null): Boolean =
        launchTracked("catalog_changed", lane = "library") {
            libraryViewModel.refreshCatalogProjection()
            settingsViewModel.refresh()
            if (refreshDetailsForAnimeId != null && refreshDetailsForAnimeId > 0L) {
                libraryViewModel.refreshDetails(refreshDetailsForAnimeId)
            }
            scheduleThumbnailReconciliation("catalog_changed")
            scheduleCatalogMetadataHydration("catalog_changed")
            recordDiagnostic(
                name = "UI_REFRESHED",
                result = "catalog_changed",
                context = mapOf("route" to mutableSnapshot.value.route),
            )
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

    /**
     * Compatibility port of main.py's _set_episode_identification_from_details /
     * LibraryStore.set_episode_identification(path, ...). Resolve the supplied
     * media reference to the canonical persisted episode ID before mutating it;
     * never identify by a display filename or create a second catalogue.
     */
    private fun setEpisodeIdentificationFromLegacyPayload(
        payload: org.json.JSONObject,
        requestId: String?,
    ): Boolean {
        val reference = firstTruthyJsonText(payload, "path", "uri", "filePath", "file_path")
        if (reference.isBlank()) return false

        fun numericValue(key: String): Double? {
            if (!payload.has(key)) return null
            val raw = payload.opt(key)
            if (raw == null || raw === org.json.JSONObject.NULL) return null
            val parsed = when (raw) {
                is Number -> raw.toDouble()
                is Boolean -> if (raw) 1.0 else 0.0
                is String -> raw.trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException(
                        if (key == "season") "Temporada inválida." else "Número do episódio inválido.",
                    )
                else -> throw IllegalArgumentException(
                    if (key == "season") "Temporada inválida." else "Número do episódio inválido.",
                )
            }
            if (!parsed.isFinite()) {
                throw IllegalArgumentException(
                    if (key == "season") "Temporada inválida." else "Número do episódio inválido.",
                )
            }
            return parsed
        }

        val normalizedSeason = (numericValue("season") ?: 0.0).let { value ->
            require(value >= 0.0 && value % 1.0 == 0.0 && value <= Int.MAX_VALUE.toDouble()) {
                "Temporada inválida."
            }
            value.toInt()
        }
        val normalizedNumber = numericValue("number")?.also { value ->
            require(value >= 0.0) { "Número do episódio inválido." }
        }
        val kind = firstTruthyJsonText(payload, "episodeType", "episode_type")
            .ifBlank { "regular" }
            .lowercase(Locale.ROOT)
        require(kind in setOf("regular", "special", "ova", "oad", "ona", "extra", "movie", "unknown")) {
            "Tipo de episódio inválido."
        }
        val rawTitle = payload.opt("title")
        val title = if (rawTitle == null || rawTitle === org.json.JSONObject.NULL) {
            null
        } else {
            pythonJsonString(rawTitle).trim().takeIf { it.isNotEmpty() }
        }
        val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }

        correlatedRequestId?.let {
            publishOperationResult(
                requestId = it,
                action = "set_episode_identification",
                status = "QUEUED",
                operationState = "QUEUED",
                message = "Identificação manual do episódio enfileirada.",
            )
        }
        val accepted = launchTracked("manual_episode_identification", lane = "library") {
            try {
                val episode = dependencies.sqliteLibraryRepository.findEpisodeByPathOrFileUri(reference)
                    ?: throw IllegalArgumentException("Arquivo local não encontrado.")
                libraryViewModel.setEpisodeManualIdentificationNative(
                    episodeId = episode.id,
                    season = normalizedSeason,
                    number = normalizedNumber,
                    episodeType = kind,
                    title = title,
                )
                invalidateCatalogViews()
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = "set_episode_identification",
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Identificação manual do episódio salva.",
                        payload = org.json.JSONObject()
                            .put("episodeId", episode.id)
                            .put("season", normalizedSeason)
                            .put("number", normalizedNumber ?: org.json.JSONObject.NULL)
                            .put("episodeType", kind)
                            .put("title", title ?: org.json.JSONObject.NULL),
                    )
                }
            } catch (cancelled: CancellationException) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = "set_episode_identification",
                        status = "CANCELLED",
                        operationState = "CANCELLED",
                        message = "A identificação manual do episódio foi cancelada.",
                    )
                }
                throw cancelled
            } catch (failure: Throwable) {
                correlatedRequestId?.let {
                    publishOperationResult(
                        requestId = it,
                        action = "set_episode_identification",
                        status = "FAILED",
                        operationState = "FAILED",
                        message = failure.message ?: "Não foi possível salvar a identificação do episódio.",
                        error = failure.javaClass.simpleName,
                    )
                }
                recordDiagnostic(
                    name = "MANUAL_EPISODE_IDENTIFICATION_FAILED",
                    severity = "WARN",
                    requestId = correlatedRequestId,
                    result = failure.javaClass.simpleName,
                    error = failure,
                )
                throw failure
            }
        }
        if (!accepted && correlatedRequestId != null) {
            publishOperationResult(
                requestId = correlatedRequestId,
                action = "set_episode_identification",
                status = "FAILED",
                operationState = "FAILED",
                message = "A identificação manual do episódio não foi aceita.",
                error = "operation_rejected",
            )
        }
        return accepted
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
        if (episode.optBoolean("missing", false)) return false

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
                libraryViewModel.refreshCatalogProjection()
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
            "refresh_library" -> refreshLibrary(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
            )
            "refresh_catalog", "refresh_canonical_catalog" -> refreshCanonicalCatalog(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
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

            "login" -> requestAccountActionTracked(
                "login",
                requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
            )
            "logout" -> requestAccountActionTracked(
                "logout",
                requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
            )
            "switch", "switch_account", "switchaccount" -> requestAccountActionTracked(
                "switch",
                requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
            )
            "account_state_changed" -> {
                setAccountState(payload.optString("state", "disconnected"), "native_event")
                true
            }

            "dismiss_storage_onboarding" -> {
                dismissNativeStorageOnboarding()
                val commandRequestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id")
                if (!commandRequestId.isNullOrBlank()) {
                    publishOperationResult(
                        requestId = commandRequestId,
                        action = normalized,
                        status = "COMPLETED",
                        operationState = "COMPLETED",
                        message = "Onboarding de armazenamento atualizado.",
                    )
                }
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
                source = nonBlank("source") ?: "button",
            )
            "remove_folder", "remove_saf_tree", "remove_saf", "release_tree" ->
                (nonBlank("treeUri") ?: nonBlank("tree_uri") ?: nonBlank("reference") ?: nonBlank("source"))
                    ?.let { reference ->
                        requestFolderRemovalFromCanonicalCatalog(
                            reference = reference,
                            requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                            bridge = nonBlank("bridge") ?: "library",
                            resultAction = normalized,
                        )
                    } ?: false

            "set_setting", "compose_settings_set" -> {
                val key = nonBlank("key") ?: nonBlank("setting_key") ?: return false
                val value = if (payload.has("value")) payload.opt("value")?.toString().orEmpty()
                    else payload.optString("setting_value", "")
                setSettingSerialized(
                    key,
                    value,
                    requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                )
            }
            "settings_action", "compose_settings_action" ->
                (nonBlank("action") ?: nonBlank("setting_action"))?.let { settingsAction ->
                    requestSettingsActionTracked(
                        action = settingsAction,
                        payload = payload,
                        requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    )
                } ?: false
            "account_action", "compose_account_action", "compose_settings_account_action" ->
                (nonBlank("accountAction") ?: nonBlank("account_action"))?.let { accountAction ->
                    requestAccountActionTracked(
                        accountAction,
                        requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    )
                } ?: false
            "set_theme" -> setSettingSerialized(
                "appearance.theme",
                payload.optString("theme", "dark"),
                requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
            )

            "open_episode", "open_media", "play_episode" -> {
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
            "set_episode_identification" -> try {
                setEpisodeIdentificationFromLegacyPayload(
                    payload = payload,
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                )
            } catch (failure: IllegalArgumentException) {
                recordDiagnostic(
                    name = "MANUAL_EPISODE_IDENTIFICATION_REJECTED",
                    severity = "WARN",
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    result = failure.message ?: "invalid_episode_identification",
                )
                false
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
            "refresh_details" -> {
                val animeId = positiveLong("animeId") ?: positiveLong("anime_id")
                    ?: mutableSnapshot.value.selectedAnimeId
                animeId?.takeIf { it > 0L }?.let(::refreshCurrentDetails) ?: false
            }
            "refresh_anilist_metadata", "refresh_current_metadata", "refresh_metadata" -> {
                // Legacy refresh_current_metadata used the active Details item, so
                // accept an explicit local ID first and otherwise use that selection.
                val animeId = positiveLong("animeId") ?: positiveLong("anime_id")
                    ?: mutableSnapshot.value.selectedAnimeId
                if (animeId == null || animeId <= 0L) {
                    val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: nonBlank("requestId")
                        ?: nonBlank("request_id")
                        ?: UUID.randomUUID().toString()
                    publishOperationResult(
                        requestId = correlatedRequestId,
                        action = normalized,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = "Abra um anime ou informe o identificador local antes de atualizar os metadados.",
                        error = "missing_anime_id",
                    )
                    false
                } else {
                    refreshAniListMetadata(
                        animeId = animeId,
                        requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                        resultAction = normalized,
                    )
                }
            }
            "resolve_match", "associate_anilist" -> {
                val correlatedRequestId = requestId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: nonBlank("requestId")
                    ?: nonBlank("request_id")
                    ?: UUID.randomUUID().toString()
                // Keep request correlation even when a malformed payload omits the remote ID.
                val remoteIdLong = positiveLong("anilistId") ?: positiveLong("anilist_id") ?: 0L
                val animeId = positiveLong("animeId") ?: positiveLong("anime_id")
                val lookupTitle = nonBlank("lookupTitle") ?: nonBlank("lookup_title")
                val remoteId = remoteIdLong.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
                if (remoteId == null) {
                    val message = "Informe um ID AniList positivo dentro do intervalo aceito."
                    publishOperationResult(
                        requestId = correlatedRequestId,
                        action = normalized,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = message,
                        error = "invalid_anilist_id",
                    )
                    recordDiagnostic(
                        name = "ANILIST_MANUAL_ASSOCIATION_REJECTED",
                        severity = "WARN",
                        requestId = correlatedRequestId,
                        result = "invalid_anilist_id",
                        context = mapOf("anilist_id" to remoteIdLong),
                    )
                    false
                } else if (animeId == null && lookupTitle.isNullOrBlank()) {
                    val message = "Informe o anime local que deve ser associado."
                    publishOperationResult(
                        requestId = correlatedRequestId,
                        action = normalized,
                        status = "FAILED",
                        operationState = "FAILED",
                        message = message,
                        error = "missing_local_anime_identity",
                    )
                    false
                } else {
                    publishOperationResult(
                        requestId = correlatedRequestId,
                        action = normalized,
                        status = "QUEUED",
                        operationState = "QUEUED",
                        message = "Associação AniList enfileirada.",
                    )
                    val accepted = launchTracked("associate_anilist", lane = "metadata") {
                        try {
                            val resolvedAnimeId = animeId
                                ?: dependencies.sqliteLibraryRepository.getAnimeByLookupTitle(lookupTitle!!)
                                    ?.id
                                ?: throw IllegalArgumentException("A chave local do anime não existe no catálogo SQLite.")
                            val result = dependencies.aniListIntegrationService.associateManually(
                                resolvedAnimeId,
                                remoteId,
                            )
                            if (result is com.reiflix.reiflix_local.data.anilist.AniListResult.Failure) {
                                val associationError = result.error
                                val integrationDisabled =
                                    associationError is com.reiflix.reiflix_local.data.anilist.AniListError.InvalidRequest &&
                                        associationError.message.contains("disabled in settings", ignoreCase = true)
                                if (integrationDisabled) {
                                    publishOperationResult(
                                        requestId = correlatedRequestId,
                                        action = normalized,
                                        status = "BLOCKED",
                                        operationState = "BLOCKED",
                                        message = "O AniList está desativado nas configurações.",
                                        error = "anilist_disabled",
                                    )
                                    recordDiagnostic(
                                        name = "ANILIST_MANUAL_ASSOCIATION_BLOCKED",
                                        severity = "WARN",
                                        requestId = correlatedRequestId,
                                        result = "anilist_disabled",
                                        context = mapOf("anime_id" to resolvedAnimeId),
                                    )
                                    return@launchTracked
                                }
                                throw IllegalStateException(associationError.toString())
                            }
                            libraryViewModel.refreshDetails(resolvedAnimeId)
                            libraryViewModel.refreshCatalogProjection()
                            settingsViewModel.refresh()
                            publish(
                                action = normalized,
                                transform = { it.copy(lastResultJson = result.toString(), selectedAnimeId = resolvedAnimeId) },
                            )
                            publishOperationResult(
                                requestId = correlatedRequestId,
                                action = normalized,
                                status = "COMPLETED",
                                operationState = "COMPLETED",
                                message = "Associação AniList salva e catálogo local preservado.",
                                payload = org.json.JSONObject()
                                    .put("animeId", resolvedAnimeId)
                                    .put("anilistId", remoteId),
                            )
                            recordDiagnostic(
                                name = "ANILIST_MANUAL_ASSOCIATION_COMPLETED",
                                requestId = correlatedRequestId,
                                result = "associated",
                                context = mapOf("anime_id" to resolvedAnimeId, "anilist_id" to remoteId),
                            )
                        } catch (cancelled: CancellationException) {
                            publishOperationResult(
                                requestId = correlatedRequestId,
                                action = normalized,
                                status = "CANCELLED",
                                operationState = "CANCELLED",
                                message = "A associação AniList foi cancelada.",
                            )
                            throw cancelled
                        } catch (failure: Throwable) {
                            publishOperationResult(
                                requestId = correlatedRequestId,
                                action = normalized,
                                status = "FAILED",
                                operationState = "FAILED",
                                message = failure.message ?: "Não foi possível salvar a associação AniList.",
                                error = failure.javaClass.simpleName,
                            )
                            recordDiagnostic(
                                name = "ANILIST_MANUAL_ASSOCIATION_FAILED",
                                severity = "WARN",
                                requestId = correlatedRequestId,
                                result = failure.javaClass.simpleName,
                                error = failure,
                            )
                            throw failure
                        }
                    }
                    if (!accepted) {
                        publishOperationResult(
                            requestId = correlatedRequestId,
                            action = normalized,
                            status = "FAILED",
                            operationState = "FAILED",
                            message = "A associação AniList não foi aceita.",
                            error = "operation_rejected",
                        )
                    }
                    accepted
                }
            }
            "reconcile_unmatched_metadata" -> reconcileUnmatchedAniListMetadata()
            "request_anime_artwork" -> positiveLong("animeId")?.let {
                requestAnimeArtwork(it, payload.optBoolean("force", false))
            } ?: false
            "request_pending_artwork" -> requestPendingArtwork()
            "clear_artwork_cache" -> clearManagedArtworkCache(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
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
            "request_restore_reconciliation", "reconcile_after_restore" ->
                requestRestoreReconciliation(requestId ?: nonBlank("requestId") ?: nonBlank("request_id"))

            "create_backup" -> createBackup(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
            "inspect_backup" -> {
                val archive = nonBlank("archivePath")?.let(::resolvePrivateBackupFile) ?: return false
                inspectBackup(
                    archive = archive,
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    resultAction = normalized,
                )
            }
            "restore_backup" -> {
                val archive = nonBlank("archivePath")?.let(::resolvePrivateBackupFile) ?: return false
                restoreBackup(
                    archive = archive,
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    resultAction = normalized,
                )
            }
            "create_recovery_safety_snapshot" -> createRecoverySafetySnapshot(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
            "restore_recovery_backup" -> {
                val archive = nonBlank("archivePath")?.let(::resolvePrivateBackupFile) ?: return false
                restoreRecoveryBackup(
                    archive = archive,
                    requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                    resultAction = normalized,
                )
            }
            "integrity_check" -> checkDatabaseIntegrity(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )
            "export_diagnostics" -> exportDiagnosticsJson(
                requestId = requestId ?: nonBlank("requestId") ?: nonBlank("request_id"),
                resultAction = normalized,
            )

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

    /**
     * Adapter for the complete NativeMailbox envelope formerly unpacked by
     * main.py's poll_native_bridge loop. Preserve top-level fields such as
     * Google error code/message while preferring values already nested in payload.
     * The mailbox owner remains responsible for acknowledging an event after this
     * dispatcher returns.
     */
    fun dispatchNativeMailboxEnvelope(event: org.json.JSONObject): Boolean {
        val eventType = event.optString("type", "").trim()
        if (eventType.isEmpty()) return false
        val payload = event.optJSONObject("payload")
            ?.let { org.json.JSONObject(it.toString()) }
            ?: org.json.JSONObject()
        val sourceCreatedAt = event.opt("createdAt")
        val sourceEventTimestamp = if (pythonTruthyJsonValue(sourceCreatedAt)) {
            sourceCreatedAt
        } else {
            event.opt("timestamp")
        }
        val keys = event.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "type" || key == "payload" || payload.has(key)) continue
            val value = event.opt(key) ?: continue
            payload.put(key, value)
        }
        val requestId = sequenceOf(
            event.optString("requestId", ""),
            event.optString("request_id", ""),
            payload.optString("requestId", ""),
            payload.optString("request_id", ""),
        ).firstOrNull { it.isNotBlank() }
        val eventId = sequenceOf(
            event.optString("eventId", ""),
            event.optString("event_id", ""),
            event.optString("id", ""),
            payload.optString("eventId", ""),
            payload.optString("event_id", ""),
            payload.optString("id", ""),
        ).firstOrNull { it.isNotBlank() }
        payload.put(
            "_legacySourceEventCreatedAt",
            sourceEventTimestamp ?: org.json.JSONObject.NULL,
        )
        return dispatchNativeEvent(eventType, payload, requestId, eventId)
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
            "permission_requested" -> {
                recordDiagnostic(
                    name = "STORAGE_PERMISSION_REQUESTED",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = stringValue("source", "permission", "reason") ?: "native_permission_request",
                )
                true
            }
            "permission_cancelled" -> {
                val correlatedRequestId = eventRequestId ?: stringValue("requestId")
                finishPendingSafSelection(
                    eventRequestId = correlatedRequestId,
                    status = "CANCELLED",
                    message = stringValue("reason", "message") ?: "Seleção de pasta cancelada.",
                    payload = payload,
                )
                refreshAuthoritativeStorageSnapshot(payload, payload.optLong("createdAt", 0L))
                settingsViewModel.refresh()
                libraryViewModel.refreshNativeFolders()
                libraryViewModel.refreshCatalogProjection()
                recordDiagnostic(
                    name = "STORAGE_PERMISSION_CANCELLED",
                    requestId = correlatedRequestId,
                    result = stringValue("reason") ?: "user_cancelled",
                )
                true
            }
            "translation_succeeded", "translation_cache_hit", "translation_failed" -> {
                val animeId = longValue("animeId", "anime_id")
                if (type != "translation_failed" && (animeId == null || animeId <= 0L)) {
                    recordDiagnostic(
                        name = "METADATA_TRANSLATION_EVENT_REJECTED",
                        severity = "WARN",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = "missing_anime_id",
                        context = mapOf("event_type" to type),
                    )
                    return false
                }
                if (type in setOf("translation_succeeded", "translation_cache_hit")) {
                    if (animeId != null) libraryViewModel.refreshDetails(animeId)
                }
                // The Python metadata listener republishes the catalog for all
                // translation outcomes; only successful/cache-hit events hydrate
                // Details, while failures still refresh the observable status.
                libraryViewModel.refreshCatalogProjection()
                recordDiagnostic(
                    name = "METADATA_TRANSLATION_PROJECTION_REFRESH",
                    severity = if (type == "translation_failed") "WARN" else "INFO",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = type,
                    context = mapOf(
                        "anime_id" to animeId,
                        "lookup_title" to stringValue("lookupTitle", "lookup_title"),
                    ),
                )
                true
            }
            "anilist_metadata_committed" -> {
                val animeId = longValue("animeId", "anime_id") ?: return false
                refreshCurrentDetails(animeId)
                libraryViewModel.refreshCatalogProjection()
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
                libraryViewModel.refreshCatalogProjection()
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
                val entity = stringValue("entity_type", "entityType")?.lowercase(Locale.ROOT).orEmpty()
                val artworkType = stringValue("artwork_type", "artworkType").orEmpty()
                val entityId = longValue("entity_id", "entityId") ?: 0L
                val localPath = stringValue("local_path", "localPath").orEmpty()
                val publicationEvents = setOf(
                    "ARTWORK_DOWNLOAD_SUCCEEDED",
                    "ARTWORK_PUBLISHED",
                    "ARTWORK_CACHE_HIT",
                )
                val stateOnlyEvents = setOf(
                    "ARTWORK_CACHE_INVALID",
                    "ARTWORK_DOWNLOAD_FAILED",
                    "ARTWORK_RETRY",
                )
                if (event in publicationEvents) {
                    val localFile = localPath.takeIf { it.isNotBlank() }
                        ?.let { runCatching { File(it).canonicalFile }.getOrNull() }
                    if (
                        entity !in setOf("anime", "movie", "episode", "season", "special") ||
                        entityId <= 0L ||
                        artworkType.isBlank() ||
                        localFile?.isFile != true
                    ) {
                        recordDiagnostic(
                            name = "ARTWORK_PUBLICATION_REJECTED",
                            severity = "WARN",
                            requestId = eventRequestId ?: stringValue("requestId"),
                            result = "invalid_local_publication",
                            context = mapOf(
                                "event" to event,
                                "entity_type" to entity,
                                "entity_id" to entityId,
                                "artwork_type" to artworkType,
                                "path_present" to localPath.isNotBlank(),
                            ),
                        )
                        return false
                    }
                    if (entity in setOf("anime", "movie")) libraryViewModel.refreshDetails(entityId)
                    libraryViewModel.queryNativeArtwork()
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "ARTWORK_PROJECTION_REFRESH",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = event.lowercase(Locale.ROOT),
                        context = mapOf(
                            "entity_type" to entity,
                            "entity_id" to entityId,
                            "artwork_type" to artworkType,
                        ),
                    )
                    return true
                }
                if (event in stateOnlyEvents) {
                    // Cache invalidation, retry and failure events have no successful
                    // local path to validate. Refresh the native artwork projection so
                    // the UI can observe the persisted status instead of dropping them.
                    if (entityId > 0L && entity in setOf("anime", "movie")) {
                        libraryViewModel.refreshDetails(entityId)
                    }
                    libraryViewModel.queryNativeArtwork()
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "ARTWORK_STATE_PROJECTION_REFRESH",
                        severity = if (event == "ARTWORK_DOWNLOAD_FAILED") "WARN" else "INFO",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = event.lowercase(Locale.ROOT),
                        context = mapOf(
                            "entity_type" to entity,
                            "entity_id" to entityId,
                            "artwork_type" to artworkType,
                        ),
                    )
                    return true
                }
                recordDiagnostic(
                    name = "ARTWORK_EVENT_IGNORED",
                    severity = "WARN",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    result = event,
                    context = mapOf("unsupported_event" to true),
                )
                false
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
                    val accepted = setSettingSerialized(key, value, requestId)
                    recordDiagnostic("SETTINGS_COMMAND_RECEIVED", requestId = requestId,
                        result = if (accepted) "queued" else "rejected", context = mapOf("key" to key))
                    accepted
                }
            }
            "compose_settings_action" -> {
                val action = stringValue("action")?.lowercase(Locale.ROOT) ?: return false
                val requestId = eventRequestId ?: stringValue("requestId")
                if (requestId.isNullOrBlank() || !claimRequestId(requestId, "compose_settings")) return false
                if (payload.optBoolean("nativeHandled", payload.optBoolean("native_handled", false))) {
                    // Native settings repositories publish a compatibility event after
                    // committing actions such as artwork-cache clearing. Consume it as
                    // a projection/acknowledgement only; do not execute the action twice.
                    // Refresh the visible library state because the database change was
                    // already committed by the native settings repository.
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "SETTINGS_ACTION_ALREADY_HANDLED_NATIVELY",
                        requestId = requestId,
                        result = action,
                    )
                    return true
                }
                val accepted = dispatchSettingsAction(action, payload, requestId)
                recordDiagnostic("SETTINGS_ACTION_DISPATCH", requestId = requestId,
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
                requestAccountActionTracked(
                    if (action == "switch") "switch" else action,
                    requestId,
                )
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
                if (type == "volume_changed") {
                    val nativeStorageOwner = payload.optBoolean("nativeStorageOwner", false)
                    if (nativeStorageOwner && payload.optBoolean("nativeVolumeStateIgnored", false)) {
                        recordDiagnostic(
                            name = "NATIVE_VOLUME_CHANGE_IGNORED",
                            requestId = correlatedRequestId,
                            result = "stale_native_volume_snapshot",
                            context = mapOf("event_timestamp" to payload.optLong("eventTimestamp", payload.optLong("timestamp", 0L))),
                        )
                        return true
                    }

                    val volumeEvent = org.json.JSONObject(payload.toString())
                    if (!volumeEvent.has("eventTimestamp")) {
                        volumeEvent.put(
                            "eventTimestamp",
                            volumeEvent.optLong("timestamp", System.currentTimeMillis()),
                        )
                    }
                    val nativeScanDispatchOwner = payload.optBoolean("nativeScanDispatchOwner", false)
                    return launchTracked("volume_changed", lane = "storage") {
                        val volumeResult = if (nativeStorageOwner) {
                            org.json.JSONObject().put("ignored", false)
                        } else {
                            dependencies.libraryService.recordNativeVolumeChange(volumeEvent)
                        }
                        if (volumeResult.optBoolean("ignored", false)) {
                            recordDiagnostic(
                                name = "NATIVE_VOLUME_CHANGE_IGNORED",
                                requestId = correlatedRequestId,
                                result = "stale_volume_event",
                                context = mapOf("event_timestamp" to volumeEvent.optLong("eventTimestamp", 0L)),
                            )
                            return@launchTracked
                        }

                        val catalogRefreshed = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
                        if (!catalogRefreshed) {
                            throw IllegalStateException(
                                "A disponibilidade de armazenamento mudou, mas o catálogo canônico não pôde ser atualizado.",
                            )
                        }
                        libraryViewModel.refreshNativeFolders()
                        libraryViewModel.refreshCatalogProjection()
                        settingsViewModel.refresh()
                        scheduleCatalogMetadataHydration("volume_changed")
                        scheduleThumbnailReconciliation("volume_changed")

                        fun volumeBecameAvailable(): Boolean {
                            val added = volumeEvent.optJSONArray("added")
                            if (added != null) {
                                for (index in 0 until added.length()) {
                                    if (added.optJSONObject(index)?.optBoolean("available", false) == true) {
                                        return true
                                    }
                                }
                            }
                            val changed = volumeEvent.optJSONArray("changedVolumes")
                            if (changed != null) {
                                for (index in 0 until changed.length()) {
                                    val change = changed.optJSONObject(index) ?: continue
                                    val after = change.optJSONObject("after") ?: continue
                                    val before = change.optJSONObject("before")
                                    if (after.optBoolean("available", false) &&
                                        before?.optBoolean("available", false) != true
                                    ) return true
                                }
                            }
                            return false
                        }

                        val volumeReturned = volumeBecameAvailable()
                        if (volumeReturned && nativeScanDispatchOwner) {
                            recordDiagnostic(
                                name = "NATIVE_VOLUME_SCAN_DISPATCH",
                                requestId = correlatedRequestId,
                                result = "native_owner",
                            )
                        } else if (volumeReturned) {
                            val scanRequestId = UUID.randomUUID().toString()
                            val scanUri = Uri.parse("reiflix://native").buildUpon()
                                .appendQueryParameter("action", "scan_all_storage")
                                .appendQueryParameter("request_id", scanRequestId)
                                .appendQueryParameter("protocol_version", "2")
                                .appendQueryParameter("created_at", System.currentTimeMillis().toString())
                                .build()
                            activity.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    scanUri,
                                    activity,
                                    MainActivity::class.java,
                                ).addFlags(
                                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                                ),
                            )
                            recordDiagnostic(
                                name = "NATIVE_VOLUME_SCAN_DISPATCH",
                                requestId = scanRequestId,
                                result = "volume_returned",
                                context = mapOf("source_request_id" to correlatedRequestId),
                            )
                        }
                        recordDiagnostic(
                            name = "NATIVE_VOLUME_CHANGE_APPLIED",
                            requestId = correlatedRequestId,
                            result = if (nativeStorageOwner) "native_persisted" else "compatibility_persisted",
                            context = mapOf(
                                "volume_returned" to volumeReturned,
                                "native_scan_dispatch_owner" to nativeScanDispatchOwner,
                                "event_timestamp" to volumeEvent.optLong("eventTimestamp", 0L),
                            ),
                        )
                    }
                }
                when (type) {
                    "saf_cancelled" -> {
                        val cancelMessage = stringValue("reason", "message") ?: "Seleção de pasta cancelada."
                        finishPendingSafSelection(
                            eventRequestId = correlatedRequestId,
                            status = "CANCELLED",
                            message = cancelMessage,
                            payload = payload,
                        )
                        if (storageSnapshot().configuredSafRoots.isEmpty()) {
                            publishStorageOnboardingState(
                                state = "NEEDS_FOLDER",
                                message = "O ReiAnix precisa de acesso à pasta onde seus animes estão armazenados.",
                                error = null,
                                dismissed = true,
                                diagnosticEvent = "STORAGE_PICKER_CANCELLED",
                                result = "picker_cancelled",
                                requestId = correlatedRequestId,
                            )
                        }
                        true
                    }
                    "saf_error" -> {
                        val message = stringValue("message", "reason", "status")
                            ?: "Não foi possível concluir a operação de armazenamento."
                        val error = stringValue("error", "reason", "status") ?: "saf_operation_failed"
                        val treeUri = stringValue("treeUri", "tree_uri")
                        val failedPendingSelection = !pendingSafSelectionRequestId.isNullOrBlank() &&
                            pendingSafSelectionRequestId == correlatedRequestId
                        val scanStatus = stringValue("status")?.uppercase(Locale.ROOT).orEmpty()
                        val isPendingRemoval = !pendingSafRemovalRequestId.isNullOrBlank() &&
                            (
                                pendingSafRemovalRequestId == correlatedRequestId ||
                                    (!treeUri.isNullOrBlank() && pendingSafRemovalReference == treeUri)
                            )
                        if (!treeUri.isNullOrBlank() && !isPendingRemoval) {
                            val authorization = if (scanStatus == "REVOKED") "revoked" else "unavailable"
                            val unavailableReason = when (scanStatus) {
                                "REVOKED" -> "saf_permission_revoked"
                                "UNAVAILABLE", "FAILED", "PARTIAL" -> "saf_provider_unavailable"
                                else -> "saf_scan_error"
                            }
                            launchTracked("saf_source_unavailable", lane = "storage") {
                                val changed = markSafSourceUnavailable(treeUri, authorization, unavailableReason)
                                libraryViewModel.refreshNativeFolders()
                                libraryViewModel.refreshCatalogProjection()
                                recordDiagnostic(
                                    name = "SAF_SOURCE_MARKED_UNAVAILABLE",
                                    severity = "WARN",
                                    requestId = correlatedRequestId,
                                    result = unavailableReason,
                                    context = mapOf("updated_rows" to changed, "status" to scanStatus),
                                )
                            }
                        }
                        finishPendingSafSelection(
                            eventRequestId = correlatedRequestId,
                            status = "ERROR",
                            message = message,
                            error = error,
                            payload = payload,
                        )
                        if (failedPendingSelection) {
                            publishStorageOnboardingState(
                                state = "ERROR",
                                message = message,
                                error = error,
                                dismissed = false,
                                diagnosticEvent = "STORAGE_SELECTION_FAILED",
                                result = error,
                                requestId = correlatedRequestId,
                            )
                        }
                        finishPendingSafRemoval(
                            eventRequestId = correlatedRequestId,
                            treeUri = treeUri,
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
                        refreshStorageCapabilitiesProjection(payload)
                        val sourceReference = stringValue("source") ?: "mediastore:external:video"
                        if (!granted) {
                            launchTracked("mediastore_permission_revoked", lane = "storage") {
                                val changed = markMediaStoreSourceUnavailable(
                                    sourceReference = sourceReference,
                                    authorization = "revoked",
                                    reason = "media_permission_revoked",
                                )
                                libraryViewModel.refreshNativeFolders()
                                libraryViewModel.refreshCatalogProjection()
                                recordDiagnostic(
                                    name = "MEDIASTORE_SOURCE_MARKED_UNAVAILABLE",
                                    severity = "WARN",
                                    requestId = correlatedRequestId,
                                    result = "media_permission_revoked",
                                    context = mapOf("updated_rows" to changed),
                                )
                            }
                        }
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
                    "mediastore_error" -> {
                        val sourceReference = stringValue("source") ?: "mediastore:external:video"
                        val status = stringValue("status")?.uppercase(Locale.ROOT).orEmpty()
                        val revoked = status in setOf("REVOKED", "DENIED")
                        launchTracked("mediastore_error_state", lane = "storage") {
                            val changed = markMediaStoreSourceUnavailable(
                                sourceReference = sourceReference,
                                authorization = if (revoked) "revoked" else "unavailable",
                                reason = if (revoked) "media_permission_revoked" else "mediastore_scan_failed",
                            )
                            libraryViewModel.refreshNativeFolders()
                            libraryViewModel.refreshCatalogProjection()
                            recordDiagnostic(
                                name = "MEDIASTORE_SOURCE_MARKED_UNAVAILABLE",
                                severity = "WARN",
                                requestId = correlatedRequestId,
                                result = if (revoked) "media_permission_revoked" else "mediastore_scan_failed",
                                context = mapOf("updated_rows" to changed, "status" to status),
                            )
                        }
                        finishPendingStorageOperation(
                            eventRequestId = correlatedRequestId,
                            expectedNativeActions = setOf("request_media_access"),
                            status = "FAILED",
                            message = stringValue("message", "error") ?: "Não foi possível obter acesso aos vídeos.",
                            error = stringValue("error", "status") ?: "media_access_failed",
                            payload = payload,
                        )
                    }
                    "broad_storage_status" -> {
                        refreshStorageCapabilitiesProjection(payload)
                        if (!payload.optBoolean("hasAccess", false)) {
                            launchTracked("broad_storage_marked_unavailable", lane = "storage") {
                                val changed = markBroadStorageSourceUnavailable(
                                    authorization = "revoked",
                                    reason = "broad_access_revoked",
                                )
                                libraryViewModel.refreshNativeFolders()
                                libraryViewModel.refreshCatalogProjection()
                                recordDiagnostic(
                                    name = "BROAD_STORAGE_SOURCE_MARKED_UNAVAILABLE",
                                    severity = "WARN",
                                    result = "broad_access_revoked",
                                    context = mapOf("updated_rows" to changed),
                                )
                            }
                        }
                    }
                    "broad_storage_permission" -> {
                        val granted = payload.optBoolean("granted", false)
                        refreshStorageCapabilitiesProjection(payload)
                        if (!granted) {
                            launchTracked("broad_storage_permission_denied", lane = "storage") {
                                val changed = markBroadStorageSourceUnavailable(
                                    authorization = "revoked",
                                    reason = "broad_access_denied",
                                )
                                libraryViewModel.refreshNativeFolders()
                                libraryViewModel.refreshCatalogProjection()
                                recordDiagnostic(
                                    name = "BROAD_STORAGE_SOURCE_MARKED_UNAVAILABLE",
                                    severity = "WARN",
                                    requestId = correlatedRequestId,
                                    result = "broad_access_denied",
                                    context = mapOf("updated_rows" to changed),
                                )
                            }
                        }
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
                    "broad_storage_error" -> {
                        val status = stringValue("status")?.uppercase(Locale.ROOT).orEmpty()
                        val revoked = status in setOf("REVOKED", "DENIED")
                        launchTracked("broad_storage_error_state", lane = "storage") {
                            val changed = markBroadStorageSourceUnavailable(
                                authorization = if (revoked) "revoked" else "unavailable",
                                reason = if (revoked) "broad_access_revoked" else "broad_scan_failed",
                            )
                            libraryViewModel.refreshNativeFolders()
                            libraryViewModel.refreshCatalogProjection()
                            recordDiagnostic(
                                name = "BROAD_STORAGE_SOURCE_MARKED_UNAVAILABLE",
                                severity = "WARN",
                                requestId = correlatedRequestId,
                                result = if (revoked) "broad_access_revoked" else "broad_scan_failed",
                                context = mapOf("updated_rows" to changed, "status" to status),
                            )
                        }
                        finishPendingStorageOperation(
                            eventRequestId = correlatedRequestId,
                            expectedNativeActions = setOf("open_broad_storage_settings"),
                            status = "FAILED",
                            message = stringValue("message", "error") ?: "Não foi possível abrir as configurações de armazenamento.",
                            error = stringValue("error", "status") ?: "broad_storage_action_failed",
                            payload = payload,
                        )
                    }
                    "saf_permission" -> {
                        val treeUri = stringValue("treeUri", "tree_uri")
                        val granted = payload.optBoolean("granted", false)
                        val selected = payload.optBoolean("selected", false)
                        // Permission callback arrival is a storage mutation even when it
                        // only revalidates an existing source. Fence older SAF inventories
                        // so they cannot undo this grant/revocation decision.
                        recordStorageMutation(
                            reason = "saf_permission_event",
                            treeUri = treeUri,
                            eventTimestampMillis = payload.optLong(
                                "_legacySourceEventCreatedAt",
                                payload.optLong("timestamp", 0L),
                            ),
                        )
                        val nativeFolderConfigOwner = payload.optBoolean(
                            "nativeFolderConfigOwner",
                            payload.optBoolean("native_folder_config_owner", false),
                        )
                        if (!granted && !treeUri.isNullOrBlank()) {
                            launchTracked("saf_permission_revoked", lane = "storage") {
                                val changed = markSafSourceUnavailable(
                                    treeUri,
                                    authorization = "revoked",
                                    reason = "saf_permission_revoked",
                                )
                                libraryViewModel.refreshNativeFolders()
                                libraryViewModel.refreshCatalogProjection()
                                recordDiagnostic(
                                    name = "SAF_SOURCE_MARKED_UNAVAILABLE",
                                    severity = "WARN",
                                    requestId = correlatedRequestId,
                                    result = "saf_permission_revoked",
                                    context = mapOf("updated_rows" to changed),
                                )
                            }
                        } else if (!selected && granted && !nativeFolderConfigOwner && !treeUri.isNullOrBlank()) {
                            launchTracked("saf_permission_revalidated", lane = "storage") {
                                val updated = reconcileVerifiedSafRoot(treeUri, payload)
                                if (updated) {
                                    libraryViewModel.refreshNativeFolders()
                                    libraryViewModel.refreshCatalogProjection()
                                }
                                recordDiagnostic(
                                    name = "SAF_PERMISSION_REVALIDATED",
                                    severity = if (updated) "INFO" else "WARN",
                                    requestId = correlatedRequestId,
                                    result = if (updated) "granted" else "existing_source_not_reconciled",
                                    context = mapOf("tree_uri_present" to true),
                                )
                            }
                        }
                        if (selected) {
                            val persistedReadUris = activity.contentResolver.persistedUriPermissions
                                .filter { it.isReadPermission }
                                .map { it.uri.toString() }
                            val authorized = granted &&
                                !treeUri.isNullOrBlank() &&
                                StorageAuthorization.safAccess(treeUri, persistedReadUris) == SafAccessLevel.AVAILABLE &&
                                mergeAuthoritativeSafRoot(treeUri)
                            val selectionMessage = when {
                                authorized -> "Pasta autorizada e configurada."
                                treeUri.isBlank() -> "O Android retornou a seleção sem informar a pasta. Toque em ESCOLHER PASTA para tentar novamente."
                                !granted -> "O Android não confirmou o acesso à pasta selecionada. Toque em ESCOLHER PASTA para tentar novamente."
                                else -> "O Android autorizou a pasta, mas o ReiAnix não conseguiu confirmar essa fonte da biblioteca. Toque em ESCOLHER PASTA para tentar novamente."
                            }
                            val selectionError = when {
                                authorized -> null
                                treeUri.isBlank() -> "selected_saf_uri_missing"
                                !granted -> "selected_saf_grant_missing"
                                else -> "selected_saf_source_validation_failed"
                            }
                            finishPendingSafSelection(
                                eventRequestId = correlatedRequestId,
                                status = if (authorized) "COMPLETED" else "ERROR",
                                message = selectionMessage,
                                error = selectionError,
                                payload = payload,
                            )
                            publishStorageOnboardingState(
                                state = if (authorized) "READY" else "ERROR",
                                message = if (authorized) null else selectionMessage,
                                error = selectionError,
                                dismissed = false,
                                diagnosticEvent = when {
                                    authorized -> "STORAGE_READY"
                                    !granted || treeUri.isBlank() -> "STORAGE_PERMISSION_MISSING"
                                    else -> "STORAGE_SOURCE_VALIDATION_FAILED"
                                },
                                result = when {
                                    authorized -> "library_root_valid"
                                    treeUri.isBlank() -> "selected_tree_uri_missing"
                                    !granted -> "selected_tree_not_granted"
                                    else -> "configured_source_missing"
                                },
                                requestId = correlatedRequestId,
                            )
                        }
                    }
                }
                refreshAuthoritativeStorageSnapshot(payload, payload.optLong("createdAt", 0L))
                settingsViewModel.refresh()
                libraryViewModel.refreshNativeFolders()
                libraryViewModel.refreshCatalogProjection()
                val reason = stringValue("reason", "state", "status", "message") ?: type
                if (type in setOf("saf_permission", "saf_released", "saf_error", "saf_cancelled")) {
                    launchTracked("storage_onboarding_projection", lane = "storage") {
                        reconcileStorageOnboardingProjection(
                            reason = "storage_event_$type",
                            requestId = eventRequestId ?: stringValue("requestId"),
                        )
                    }
                }
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
                    "select_tree" -> {
                        finishPendingSafSelection(
                            eventRequestId = correlatedRequestId,
                            status = "ERROR",
                            message = message,
                            error = error,
                            payload = payload,
                        )
                        publishStorageOnboardingState(
                            state = "ERROR",
                            message = message,
                            error = error,
                            dismissed = false,
                            diagnosticEvent = "STORAGE_SELECTION_FAILED",
                            result = error,
                            requestId = correlatedRequestId,
                        )
                    }
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
                    "_legacySourceEventCreatedAt",
                    payload.optLong(
                        "publishedAtMs",
                        payload.optLong("published_at_ms", payload.optLong("createdAt", payload.optLong("timestamp", 0L))),
                    ),
                )
                refreshAuthoritativeStorageSnapshot(capabilities, publishedAt)
            }
            "saf_inventory" -> launchTracked("reconcile_saf_inventory", lane = "storage") {
                reconcileSafInventory(payload)
            }
            "saf_library_scan_complete" -> {
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
                val treeUri = stringValue("treeUri", "tree_uri")
                if (!treeUri.isNullOrBlank()) mergeAuthoritativeSafRoot(treeUri)
                settingsViewModel.refresh()
                libraryViewModel.refreshNativeFolders()
                libraryViewModel.refreshCatalogProjection()
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
                    libraryViewModel.refreshCatalogProjection()
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
                            libraryViewModel.refreshCatalogProjection()
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
                            val eventDisposition = synchronized(thumbnailQueueLock) {
                                pruneThumbnailQueueStateLocked()
                                val completedRequestId = thumbnailCompletedRequestByKey[key]
                                val latestKey = thumbnailLatestKeyByUri[uri.toString()]
                                when {
                                    !correlatedRequestId.isNullOrBlank() &&
                                        completedRequestId == correlatedRequestId &&
                                        key !in thumbnailRequests -> "duplicate"
                                    latestKey != null && latestKey != key -> "stale"
                                    else -> {
                                        thumbnailRetryCounts.remove(key)
                                        thumbnailRetryPendingKeys.remove(key)
                                        if (!correlatedRequestId.isNullOrBlank()) {
                                            thumbnailCompletedRequestByKey[key] = correlatedRequestId
                                            if (thumbnailCompletedRequestByKey.size > 2_048) {
                                                thumbnailCompletedRequestByKey.keys.take(512)
                                                    .forEach { thumbnailCompletedRequestByKey.remove(it) }
                                            }
                                        }
                                        "accepted"
                                    }
                                }
                            }
                            when (eventDisposition) {
                                "duplicate" -> {
                                    recordDiagnostic(
                                        name = "THUMBNAIL_DUPLICATE",
                                        requestId = correlatedRequestId,
                                        result = "IGNORED",
                                    )
                                    return true
                                }
                                "stale" -> {
                                    recordDiagnostic(
                                        name = "THUMBNAIL_STALE",
                                        requestId = correlatedRequestId,
                                        result = "IGNORED",
                                        context = mapOf("uri_present" to true),
                                    )
                                    return true
                                }
                            }
                        }
                    }
                }
                libraryViewModel.queryNativeArtwork()
                libraryViewModel.refreshCatalogProjection()
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
                val correlatedRequestId = eventRequestId ?: stringValue("requestId")
                if (type == "google_account" || type == "google_signed_out") {
                    launchTracked("native_account_event", lane = "settings") {
                        // The mailbox event is a notification, not the source of truth.
                        // Re-read the canonical account row so a delayed sign-out event
                        // cannot overwrite a newer successful login in the UI projection.
                        val profile = dependencies.sqliteAccountRepository.loadProfile()
                        val connected = profile != null &&
                            profile.id.isNotBlank() &&
                            profile.email.contains('@')
                        setAccountState(
                            if (connected) "connected" else "disconnected",
                            "native_$type",
                        )
                        settingsViewModel.refresh()
                        libraryViewModel.refreshCatalogProjection()
                        recordDiagnostic(
                            name = "NATIVE_ACCOUNT_EVENT",
                            requestId = correlatedRequestId,
                            result = type,
                            context = mapOf(
                                "profile_present" to (profile != null),
                                "connected" to connected,
                            ),
                        )
                    }
                } else {
                    val accountErrorCode = stringValue("code", "errorCode", "error_code")
                        ?.lowercase(Locale.ROOT)
                    val accountMessage = when (accountErrorCode) {
                        "no_credential" ->
                            "Nenhuma conta/credencial Google disponível. Verifique se uma conta Google está configurada no dispositivo."
                        "unsupported" ->
                            "Este dispositivo não oferece suporte ao Gerenciador de Credenciais usado pelo ReiAnix."
                        "provider_configuration" ->
                            "O provedor Google do Gerenciador de Credenciais não está configurado corretamente."
                        "invalid_credential" ->
                            "O Google retornou uma credencial que não pôde ser validada com segurança."
                        "configuration_required" ->
                            "O login Google precisa de um Web Client ID válido neste APK."
                        else -> stringValue("message", "error")
                            ?: "O login Google não pôde ser concluído."
                    }
                    when (type) {
                        "google_sign_in_started" ->
                            setAccountState("awaiting_google", "google_sign_in_started")
                        "google_cancelled" ->
                            setAccountState("disconnected", "google_sign_in_cancelled")
                        "google_error" ->
                            setAccountState(
                                if (accountErrorCode == "configuration_required") "configuration_required" else "error",
                                "google_error",
                            )
                    }
                    settingsViewModel.refresh()
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "NATIVE_ACCOUNT_EVENT",
                        severity = if (type == "google_error") "WARN" else "INFO",
                        requestId = correlatedRequestId,
                        result = type,
                        error = if (type == "google_error") IllegalStateException(accountMessage) else
                            stringValue("error", "message")?.let(::IllegalStateException),
                        context = mapOf(
                            "has_profile" to (payload.optJSONObject("profile") != null),
                            "error_code" to accountErrorCode,
                            "account_state" to when (type) {
                                "google_sign_in_started" -> "awaiting_google"
                                "google_cancelled" -> "disconnected"
                                "google_error" -> if (accountErrorCode == "configuration_required") "configuration_required" else "error"
                                else -> null
                            },
                        ),
                    )
                    if (type == "google_error") {
                        publish(action = "google_error", error = accountMessage)
                    }
                    true
                }
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
                    applySettingsAtomically(
                        values = settings,
                        requestId = requestId,
                        resultAction = "compose_settings_bulk_applied",
                    )
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
                val accepted = launchTracked("compose_settings_native_account_changed", lane = "settings") {
                    // The event is only a notification that Kotlin has committed the
                    // account row. Re-read SQLite instead of trusting stale event state.
                    val profile = dependencies.sqliteAccountRepository.loadProfile()
                    val connected = profile != null &&
                        profile.id.isNotBlank() &&
                        profile.email.contains('@')
                    setAccountState(
                        if (connected) "connected" else "disconnected",
                        "native_account_changed",
                    )
                    settingsViewModel.refresh()
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "NATIVE_COMPOSE_ACCOUNT_PROJECTION_REFRESHED",
                        requestId = requestId,
                        result = "SUCCESS",
                        context = mapOf(
                            "connected" to connected,
                            "profile_present" to (profile != null),
                            "account_write_repeated" to false,
                        ),
                    )
                    completeNativeOperation(requestId, "account_changed")
                }
                if (!accepted) {
                    publishOperationResult(
                        requestId = requestId,
                        action = "account_changed",
                        status = "FAILED",
                        operationState = "FAILED",
                        message = "Não foi possível atualizar o estado da conta.",
                        error = "operation_rejected",
                    )
                }
                accepted
            }
            "compose_settings_native_backup_restored" -> {
                val requestId = eventRequestId ?: stringValue("requestId") ?: return false
                if (!claimRequestId(requestId, "compose_settings_backup_restored")) return false
                settingsViewModel.refresh()
                val reconciled = reconcileRestoredFileAvailability()
                libraryViewModel.refreshCatalogProjection()
                scheduleCatalogMetadataHydration("backup_restored")
                scheduleThumbnailReconciliation("backup_restored")
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
                val current = mutableSnapshot.value
                // The legacy handler accepted player_opened without requestId;
                // preserve the current request correlation when possible instead
                // of dropping a valid session event.
                val request = eventRequestId ?: stringValue("requestId") ?: current.activePlayerRequestId.orEmpty()
                val sessionId = stringValue("playerSessionId", "player_session_id", "sessionId")
                    ?: return false
                val originRequestId = stringValue("originRequestId", "origin_request_id").orEmpty()
                if (originRequestId.isNotBlank()) {
                    // Port the legacy pending-transition target check. A successor may
                    // reuse the same Activity/session, so session equality alone is not
                    // enough: the request, source session and transition generation must
                    // still describe the intended Next/Previous handoff.
                    val originSessionId =
                        stringValue("originPlayerSessionId", "origin_player_session_id").orEmpty()
                    val originGeneration = payload.optLong(
                        "originTransitionGeneration",
                        payload.optLong("origin_transition_generation", 0L),
                    )
                    val openedGeneration = payload.optLong("transitionGeneration", 0L)
                    val direction = stringValue("transitionDirection", "transition_direction").orEmpty()
                    val revoked = originSessionId.isNotBlank() &&
                        MainActivity.isPlayerTransitionRevoked(originRequestId, originSessionId)
                    val transitionMismatch = when {
                        request.isBlank() -> "missing_target_request_id"
                        request == originRequestId -> "target_request_matches_origin"
                        originSessionId.isBlank() || originSessionId != sessionId ->
                            "transition_session_mismatch"
                        originGeneration <= 0L || openedGeneration != originGeneration ->
                            "transition_generation_mismatch"
                        revoked -> "transition_revoked"
                        else -> null
                    }
                    if (transitionMismatch != null) {
                        recordDiagnostic(
                            name = "PLAYER_CALLBACK_STALE",
                            severity = "WARN",
                            requestId = request,
                            sessionId = sessionId,
                            result = transitionMismatch,
                            context = mapOf(
                                "origin_request_id" to originRequestId,
                                "origin_session_matches" to (originSessionId == sessionId),
                                "origin_generation" to originGeneration,
                                "opened_generation" to openedGeneration,
                                "direction" to direction,
                            ),
                        )
                        recordDiagnostic(
                            name = "PLAYER_OPENED_IGNORED",
                            requestId = request,
                            sessionId = sessionId,
                            result = transitionMismatch,
                            context = mapOf("origin_request_id" to originRequestId),
                        )
                        return false
                    }
                }
                if (!current.activePlayerSessionId.isNullOrBlank() &&
                    current.activePlayerSessionId != sessionId
                ) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_STALE",
                        severity = "WARN",
                        requestId = request,
                        sessionId = sessionId,
                        result = "player_opened_session_mismatch",
                        context = mapOf("current_session" to current.activePlayerSessionId),
                    )
                    recordDiagnostic(
                        name = "PLAYER_OPENED_IGNORED",
                        requestId = request,
                        sessionId = sessionId,
                        result = "stale_player_session",
                    )
                    return false
                }
                notePlayerSession(
                    requestId = request,
                    sessionId = sessionId,
                    transitionGeneration = payload.optLong("transitionGeneration", 0L),
                    playerGeneration = payload.optLong("generation", payload.optLong("playerGeneration", 0L)),
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
                if (!playerCallbackIdentityIsComplete(payload)) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED_INCOMPLETE_IDENTITY",
                        severity = "WARN",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = type,
                        context = mapOf(
                            "has_session_id" to stringValue("playerSessionId", "player_session_id").isNullOrBlank().not(),
                            "has_episode_id" to (longValue("episodeId", "episode_id") != null),
                            "has_media_id" to stringValue("mediaId", "media_id").isNullOrBlank().not(),
                            "has_uri" to stringValue("uri").isNullOrBlank().not(),
                        ),
                    )
                    return false
                }
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
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "PLAYER_PROGRESS_NATIVE_COMMIT_REUSED",
                        requestId = eventRequestId,
                        sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                        result = type,
                        context = mapOf("episode_id" to episodeId, "position_ms" to position),
                    )
                    true
                } else {
                    val eventTime = parseLegacyPlaybackTimestamp(payload)
                    if (eventTime.supplied && eventTime.epochSeconds == null) {
                        recordDiagnostic(
                            name = "PLAYER_PROGRESS_TIMESTAMP_REJECTED",
                            severity = "WARN",
                            requestId = eventRequestId,
                            result = type,
                        )
                        true
                    } else {
                        savePlaybackProgress(
                            episodeId = episodeId,
                            positionMs = position,
                            durationMs = duration.takeIf { it > 0L },
                            completed = type == "player_completed" || payload.optBoolean("completed", false),
                            playedAtEpochSeconds = eventTime.epochSeconds ?: System.currentTimeMillis() / 1000.0,
                            callbackPayload = payload,
                            callbackRequestId = eventRequestId,
                        )
                    }
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
                markEpisodeWatchedTracked(
                    episodeId = episodeId,
                    watched = type == "player_mark_watched",
                    path = stringValue("uri", "path"),
                )
            }
            "player_error" -> {
                val validation = validatePlayerCallback(
                    eventRequestId = eventRequestId,
                    payload = payload,
                    requireActive = true,
                    allowMissingEpisodeId = true,
                    allowMissingAnimeId = true,
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
                val playerErrorMessage = stringValue("message", "error")
                    ?: "Não foi possível reproduzir este arquivo localmente."
                // Match the legacy main.py error path: a failure for the current
                // launch invalidates an in-flight NEXT/PREVIOUS transition.
                val playerErrorRequestId = eventRequestId ?: stringValue("requestId")
                if (
                    !playerErrorRequestId.isNullOrBlank() &&
                    mutableSnapshot.value.activePlayerRequestId == playerErrorRequestId
                ) {
                    cancelPlayerTransition("player_error")
                }
                recordDiagnostic(
                    name = "PLAYER_RUNTIME_ERROR",
                    severity = "ERROR",
                    requestId = eventRequestId ?: stringValue("requestId"),
                    sessionId = stringValue("playerSessionId", "sessionId"),
                    result = stringValue("errorCode", "code", "reason"),
                    error = IllegalStateException(playerErrorMessage),
                    context = mapOf("episode_id" to longValue("episodeId", "episode_id")),
                )
                // Preserve the old runtime's user-visible error state alongside the diagnostic.
                publish(action = "player_error", error = playerErrorMessage)
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
                if (!playerCallbackIdentityIsComplete(payload)) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED_INCOMPLETE_IDENTITY",
                        severity = "WARN",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = type,
                        context = mapOf(
                            "has_session_id" to stringValue("playerSessionId", "player_session_id").isNullOrBlank().not(),
                            "has_episode_id" to (longValue("episodeId", "episode_id") != null),
                            "has_media_id" to stringValue("mediaId", "media_id").isNullOrBlank().not(),
                            "has_uri" to stringValue("uri").isNullOrBlank().not(),
                        ),
                    )
                    return false
                }
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
                    libraryViewModel.refreshCatalogProjection()
                    recordDiagnostic(
                        name = "PLAYER_PROGRESS_NATIVE_COMMIT_REUSED",
                        requestId = eventRequestId,
                        sessionId = stringValue("playerSessionId", "sessionId", "session_id"),
                        result = type,
                        context = mapOf("episode_id" to episodeId, "position_ms" to positionMs),
                    )
                    true
                } else {
                    val eventTime = parseLegacyPlaybackTimestamp(payload)
                    if (eventTime.supplied && eventTime.epochSeconds == null) {
                        recordDiagnostic(
                            name = "PLAYER_PROGRESS_TIMESTAMP_REJECTED",
                            severity = "WARN",
                            requestId = eventRequestId,
                            result = type,
                        )
                        true
                    } else {
                        savePlaybackProgress(
                            episodeId = episodeId,
                            positionMs = positionMs,
                            durationMs = durationMs.takeIf { it > 0L },
                            completed = payload.optBoolean("completed", false),
                            playedAtEpochSeconds = eventTime.epochSeconds ?: System.currentTimeMillis() / 1000.0,
                            callbackPayload = payload,
                            callbackRequestId = eventRequestId,
                        )
                    }
                }
            }
            "player_exited", "player_exit" -> {
                if (!playerCallbackIdentityIsComplete(payload)) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED_INCOMPLETE_IDENTITY",
                        severity = "WARN",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = type,
                        context = mapOf(
                            "has_session_id" to stringValue("playerSessionId", "player_session_id").isNullOrBlank().not(),
                            "has_episode_id" to (longValue("episodeId", "episode_id") != null),
                            "has_media_id" to stringValue("mediaId", "media_id").isNullOrBlank().not(),
                            "has_uri" to stringValue("uri").isNullOrBlank().not(),
                        ),
                    )
                    return false
                }
                val validation = validatePlayerCallback(
                    eventRequestId = eventRequestId,
                    payload = payload,
                    requireActive = mutableSnapshot.value.playerSessionActive,
                    expectedEpisodeId = longValue("episodeId", "episode_id"),
                    expectedAnimeId = longValue("animeId", "anime_id"),
                    expectedMediaId = stringValue("mediaId", "media_id"),
                )
                if (!validation.isCurrent) {
                    recordDiagnostic(
                        name = "PLAYER_CALLBACK_REJECTED",
                        severity = "WARN",
                        requestId = eventRequestId ?: stringValue("requestId"),
                        result = validation.reason,
                    )
                    return false
                }
                val exitRequestId = eventRequestId ?: stringValue("requestId")
                val exitSessionId = stringValue("playerSessionId", "sessionId", "session_id")
                val exitActivityInstanceId = stringValue("activityInstanceId", "activity_instance_id")
                val exitEpisodeId = longValue("episodeId", "episode_id") ?: return false
                val exitUri = stringValue("uri", "path").orEmpty()
                val exitPositionMs = payload.optLong("positionMs", payload.optLong("position_ms", -1L))
                val exitDurationMs = payload.optLong("durationMs", payload.optLong("duration_ms", 0L))
                val nativeProgressPersisted = payload.optBoolean(
                    "nativeProgressPersisted",
                    payload.optBoolean("native_progress_persisted", false),
                )
                // A valid exit may omit requestId; session/activity identity and the
                // active snapshot still fence stale exits. The legacy path invalidated
                // the playback session instead of merely clearing the exit projection.
                MainActivity.notePlayerExit(
                    requestId = exitRequestId.orEmpty(),
                    atMs = payload.optLong("atMs", payload.optLong("at_ms", System.currentTimeMillis())),
                    sessionId = exitSessionId,
                    activityInstanceId = exitActivityInstanceId,
                )
                invalidatePlayerSession(
                    reason = "player_exited",
                    expectedSessionId = exitSessionId,
                    expectedRequestId = exitRequestId,
                )
                // Keep the final progress write and catalog projection ordered like the
                // legacy awaited save -> on_catalog_changed sequence. savePlaybackProgress()
                // queues a separate job, so calling it here could race the catalog refresh.
                return launchTracked("player_exit_finalize", lane = "playback") {
                    var exitProgressResult = "NOT_UPDATED"
                    if (exitUri.isNotBlank() && exitPositionMs >= 0L) {
                        if (nativeProgressPersisted) {
                            // NativePlayerActivity already committed this final checkpoint.
                            // The awaited catalog projection below observes it without a second write.
                            exitProgressResult = "NATIVE_COMMIT_REUSED"
                        } else {
                            val eventTime = parseLegacyPlaybackTimestamp(payload)
                            when {
                                eventTime.supplied && eventTime.epochSeconds == null -> {
                                    recordDiagnostic(
                                        name = "PLAYER_PROGRESS_TIMESTAMP_REJECTED",
                                        severity = "WARN",
                                        requestId = exitRequestId,
                                        sessionId = exitSessionId,
                                        result = "player_exited",
                                    )
                                    exitProgressResult = "INVALID_TIMESTAMP"
                                }
                                else -> {
                                    val timestampSeconds =
                                        eventTime.epochSeconds ?: System.currentTimeMillis() / 1000.0
                                    val existingProgress = dependencies.consumptionService.getProgress(exitEpisodeId)
                                        ?: throw NoSuchElementException("Episode ID does not exist: $exitEpisodeId")
                                    val pathEpisode = if (exitUri.isNotBlank()) {
                                        dependencies.sqliteLibraryRepository.findEpisodeByPathOrFileUri(exitUri)
                                    } else null
                                    if (pathEpisode != null && pathEpisode.id != exitEpisodeId) {
                                        exitProgressResult = "IDENTITY_MISMATCH"
                                        recordDiagnostic(
                                            name = "PLAYER_PROGRESS_IDENTITY_REJECTED",
                                            severity = "WARN",
                                            requestId = exitRequestId,
                                            sessionId = exitSessionId,
                                            result = "player_exit_episode_path_mismatch",
                                            context = mapOf(
                                                "episode_id" to exitEpisodeId,
                                                "path_episode_id" to pathEpisode.id,
                                            ),
                                        )
                                    } else if ((existingProgress.lastPlayedAtEpochSeconds ?: 0.0) >= timestampSeconds) {
                                        exitProgressResult = "STALE"
                                        recordDiagnostic(
                                            name = "PLAYER_PROGRESS_STALE",
                                            result = "player_exit_stale_timestamp",
                                            requestId = exitRequestId,
                                            sessionId = exitSessionId,
                                            context = mapOf(
                                                "episode_id" to exitEpisodeId,
                                                "event_time_seconds" to timestampSeconds,
                                                "stored_last_played_at" to existingProgress.lastPlayedAtEpochSeconds,
                                            ),
                                        )
                                    } else {
                                        dependencies.consumptionService.saveProgress(
                                            episodeId = exitEpisodeId,
                                            positionMs = exitPositionMs,
                                            durationMs = exitDurationMs.takeIf { it > 0L },
                                            playedAtEpochSeconds = timestampSeconds,
                                            completed = payload.optBoolean("completed", false),
                                        )
                                        exitProgressResult = "SAVED"
                                    }
                                }
                            }
                        }
                    }
                    val catalogAccepted = libraryViewModel.refreshCanonicalCatalogAfterBootstrapValidation()
                    if (!catalogAccepted) {
                        throw IllegalStateException("Não foi possível atualizar o catálogo após sair do player.")
                    }
                    scheduleCatalogMetadataHydration("player_exited")
                    scheduleThumbnailReconciliation("player_exited")
                    recordDiagnostic(
                        name = "PLAYER_EXITED",
                        requestId = exitRequestId,
                        sessionId = exitSessionId,
                        result = (stringValue("reason") ?: "exit") +
                            ":progress_" + exitProgressResult.lowercase(Locale.ROOT),
                        context = mapOf(
                            "episode_id" to exitEpisodeId,
                            "media_identity" to stringValue("mediaId", "media_id"),
                            "position_ms" to exitPositionMs.takeIf { it >= 0L },
                            "duration_ms" to exitDurationMs,
                        ),
                    )
                }
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
                        // Legacy callbacks may omit requestId after a native
                        // activity has already registered its active launch request.
                        // Retain that correlation rather than dropping the valid session.
                        val correlatedRequestId = requestId?.takeIf { it.isNotBlank() }
                            ?: current.activePlayerRequestId.orEmpty()
                        // The legacy diagnostic handler accepted a valid new session
                        // even if its envelope had no requestId. Retain the current
                        // correlation when available, but do not discard session state
                        // solely because the request correlation is absent.
                        notePlayerSession(
                            correlatedRequestId,
                            createdSession,
                            payload.optLong("transitionGeneration", 0L),
                            payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                        )
                        if (!activityInstanceId.isNullOrBlank()) {
                            notePlayerActivityCreated(
                                activityInstanceId,
                                correlatedRequestId,
                                createdSession,
                                payload.optLong("transitionGeneration", 0L),
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
                            publish(action = "player_activity_result", transform = {
                                it.copy(
                                    playerSessionActive = false,
                                    activePlayerRequestId = null,
                                    activePlayerActivityInstanceId = null,
                                )
                            })
                            true
                        } else false
                    }
                    "PLAYER_LIFECYCLE" -> {
                        val lifecycle = stringValue("lifecycle") ?: return false
                        val normalizedLifecycle = lifecycle.lowercase(Locale.ROOT)
                        val current = mutableSnapshot.value
                        if (
                            !sessionId.isNullOrBlank() &&
                            !current.activePlayerActivityInstanceId.isNullOrBlank() &&
                            !activityInstanceId.isNullOrBlank() &&
                            current.activePlayerActivityInstanceId != activityInstanceId
                        ) {
                            recordDiagnostic(
                                name = "PLAYER_CALLBACK_STALE",
                                severity = "WARN",
                                requestId = requestId,
                                sessionId = sessionId,
                                result = "stale_activity_instance_lifecycle",
                                context = mapOf(
                                    "activity_instance_id" to activityInstanceId,
                                    "current_activity_instance_id" to current.activePlayerActivityInstanceId,
                                    "lifecycle" to lifecycle,
                                ),
                            )
                            return false
                        }
                        if (!sessionId.isNullOrBlank() &&
                            !current.activePlayerSessionId.isNullOrBlank() &&
                            current.activePlayerSessionId != sessionId
                        ) return false
                        if (normalizedLifecycle in setOf("oncreate", "onnewintent", "onstart", "onresume")) {
                            if (sessionId.isNullOrBlank()) return false
                            // PLAYER_LIFECYCLE did not require requestId in the legacy
                            // handler; an omitted ID keeps the active request correlation.
                            val correlatedRequestId = requestId?.takeIf { it.isNotBlank() }
                                ?: current.activePlayerRequestId
                            notePlayerSession(
                                correlatedRequestId.orEmpty(),
                                sessionId,
                                payload.optLong("transitionGeneration", 0L),
                                payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                            )
                            if (!activityInstanceId.isNullOrBlank()) {
                                notePlayerActivityCreated(
                                    activityInstanceId,
                                    correlatedRequestId.orEmpty(),
                                    sessionId,
                                    payload.optLong("transitionGeneration", 0L),
                                    payload.optLong("playerGeneration", payload.optLong("generation", 0L)),
                                )
                            }
                            val state = stringValue("sessionState")?.uppercase(Locale.ROOT)
                            if (state in setOf("EXITING", "DESTROYED")) {
                                invalidatePlayerSession("player_lifecycle_$lifecycle", sessionId, requestId)
                            }
                            true
                        } else if (
                            !sessionId.isNullOrBlank() &&
                            normalizedLifecycle in setOf("onpause", "onstop", "ondestroy") &&
                            (
                                stringValue("sessionState")?.uppercase(Locale.ROOT) in setOf("EXITING", "DESTROYED") ||
                                    normalizedLifecycle == "ondestroy"
                            )
                        ) {
                            // Preserve the legacy lifecycle rule: an exiting session
                            // is invalidated on pause/stop, and any current session is
                            // invalidated on destroy even if requestId was omitted.
                            invalidatePlayerSession(
                                "player_lifecycle_$lifecycle",
                                sessionId,
                                requestId,
                            )
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
            "reset_player" -> resetSettingsByPrefixes(
                setOf("player."),
                resolvedRequestId,
                "reset_player",
            )
            "reset_all_settings" -> resetSettingsByPrefixes(
                emptySet(),
                resolvedRequestId,
                "reset_all_settings",
            )
            "clear_anilist_cache", "clear_artwork_cache" -> clearManagedArtworkCache(
                requestId = resolvedRequestId,
                resultAction = normalized,
            )
            "settings_export" -> launchNativeSettingsFileAction(normalized, resolvedRequestId)
            "settings_import" -> {
                val reference = stringValueFromPayload(payload, "path", "filePath", "file_path")
                if (reference != null) {
                    importSettingsFromPrivateFile(
                        file = File(reference),
                        requestId = resolvedRequestId,
                        resultAction = normalized,
                    )
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
            "backup_reconcile" -> requestRestoreReconciliation(resolvedRequestId)
            "backup_create", "backup_integrity", "diagnostic_export",
            "backup_recovery_restore", "backup_recovery_snapshot" ->
                launchNativeSettingsFileAction(normalized, resolvedRequestId)
            "backup_restore" -> {
                val reference = stringValueFromPayload(payload, "archivePath", "path", "filePath")
                if (reference != null) {
                    val archive = resolvePrivateBackupFile(reference) ?: return false
                    restoreBackup(
                        archive = archive,
                        requestId = resolvedRequestId,
                        resultAction = normalized,
                    )
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

    private fun resetSettingsByPrefixes(
        prefixes: Set<String>,
        requestId: String? = null,
        resultAction: String = "settings_bulk_applied",
    ): Boolean {
        val defaults = ReiAnixSettingsContract.definitions
            .filterKeys { key -> prefixes.isEmpty() || prefixes.any(key::startsWith) }
            .mapValues { (_, definition) -> definition.defaultValue }
        return applySettingsAtomically(defaults, requestId, resultAction)
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
        synchronized(nativeMailboxPollLock) {
            nativeMailboxPollJob?.cancel()
            nativeMailboxPollJob = null
        }
        thumbnailPumpJob?.cancel()
        thumbnailPumpJob = null
        lifecycleIsAlive = false
        synchronized(thumbnailQueueLock) {
            // main.py's page-disconnect hook cancels the dispatcher but keeps
            // queued thumbnail requests so a reconnect can resume pending work.
            // Preserve those queue entries and their completion objects; only
            // discard retry/freshness state that no queued request can use.
            val queuedKeys = thumbnailQueue.map { it.key }.toSet()
            val inFlightKeys = thumbnailInFlight.toSet()
            thumbnailRequests.keys.toList().forEach { key ->
                if (key !in queuedKeys && key !in inFlightKeys) {
                    thumbnailRequests.remove(key)?.completion?.cancel()
                }
            }
            thumbnailRetryCounts.keys.toList().forEach { key ->
                if (key !in queuedKeys && key !in inFlightKeys) thumbnailRetryCounts.remove(key)
            }
            thumbnailRetryPendingKeys.toList().forEach { key ->
                if (key !in queuedKeys && key !in inFlightKeys) thumbnailRetryPendingKeys.remove(key)
            }
            val queuedUris = thumbnailQueue.map { it.uri.toString() }.toSet()
            thumbnailLatestKeyByUri.entries.toList().forEach { entry ->
                if (entry.value !in queuedKeys) {
                    thumbnailLatestKeyByUri.remove(entry.key, entry.value)
                }
            }
            thumbnailLatestAtMillis.keys.toList().forEach { uri ->
                if (uri !in queuedUris) thumbnailLatestAtMillis.remove(uri)
            }
        }
        publish(action = "cancel_background_tasks", transform = {
            it.copy(
                queuedThumbnailCount = synchronized(thumbnailQueueLock) { thumbnailQueue.size },
                activeThumbnailCount = thumbnailInFlight.size,
            )
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
