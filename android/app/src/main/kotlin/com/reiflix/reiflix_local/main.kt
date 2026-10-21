package com.reiflix.reiflix_local

import androidx.activity.ComponentActivity
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.backup.ReiAnixDiagnosticsService
import java.io.File
import com.reiflix.reiflix_local.viewmodel.ReiAnixSettingsViewModel
import com.reiflix.reiflix_local.viewmodel.ReiAnixLibraryViewModel
import com.reiflix.reiflix_local.ui.host.ReiAnixComposeLibraryHost
import com.reiflix.reiflix_local.ui.navigation.ReiAnixRoutes
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.scanner.NativeScanController
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

    fun onLifecycleEvent(eventOrState: Any?) {
        NativeLifecycleCoordinator.onEvent(eventOrState)
    }

    fun cancelBackgroundTasks() {
        val copy = synchronized(lock) { jobs.toList() }
        copy.forEach { it.cancel() }
        publish(action = "cancel_background_tasks")
    }
}
