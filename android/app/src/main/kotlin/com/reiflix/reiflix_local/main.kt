package com.reiflix.reiflix_local

import androidx.activity.ComponentActivity
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeLibraryHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
