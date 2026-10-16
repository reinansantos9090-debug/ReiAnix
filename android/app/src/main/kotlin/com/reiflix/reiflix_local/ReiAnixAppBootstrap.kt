package com.reiflix.reiflix_local

import android.content.Context
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.scanner.NativeScanRunner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReiAnixBootstrapPhase {
    NOT_STARTED,
    STARTING,
    READY,
    DEGRADED,
}

enum class ReiAnixBootstrapStage {
    NOT_STARTED,
    RECOVERING_INTERRUPTED_SCANS,
    STARTING_NATIVE_COMMAND_DISPATCHER,
    NATIVE_SERVICES_READY,
    VALIDATING_CANONICAL_DATABASE,
    READY,
    RECOVERY_REQUIRED,
}

enum class ReiAnixBootstrapPersistenceStatus {
    NOT_CHECKED,
    NOT_CREATED,
    HEALTHY,
    RECOVERY_REQUIRED,
}

data class ReiAnixBootstrapState(
    val phase: ReiAnixBootstrapPhase = ReiAnixBootstrapPhase.NOT_STARTED,
    val stage: ReiAnixBootstrapStage = ReiAnixBootstrapStage.NOT_STARTED,
    val persistenceStatus: ReiAnixBootstrapPersistenceStatus =
        ReiAnixBootstrapPersistenceStatus.NOT_CHECKED,
    val recoveryRequired: Boolean = false,
    val completedStages: Set<ReiAnixBootstrapStage> = emptySet(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val startedAtMillis: Long? = null,
    val updatedAtMillis: Long = 0L,
)

/**
 * Process-owned startup coordinator.
 *
 * It starts only native process services whose ordering matters: interrupted SAF
 * scan recovery is attempted before the file-observer command dispatcher starts
 * consuming queued work. Database health is validated on demand by the activity
 * recovery decision; an absent database is a fresh install unless durable
 * recovery evidence exists.
 *
 * This class does not start a scanner, issue remote AniList requests, warm artwork,
 * create a player session, or start a second Python/Flet runtime.
 */
class ReiAnixAppBootstrap internal constructor(
    context: Context,
    private val dependencies: ReiAnixAppDependencies,
) {
    private val appContext = context.applicationContext
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val startupLock = Any()
    private val databaseValidationMutex = Mutex()
    private val mutableState = MutableStateFlow(ReiAnixBootstrapState())

    val state: StateFlow<ReiAnixBootstrapState> = mutableState.asStateFlow()

    @Volatile
    private var startupJob: Job? = null

    /**
     * Starts process-owned work at most once, even when Application and Activity
     * both request startup. No file traversal or service initialization blocks the
     * Android main thread.
     */
    fun start() {
        synchronized(startupLock) {
            if (startupJob != null) return
            updateState {
                it.copy(
                    phase = ReiAnixBootstrapPhase.STARTING,
                    stage = ReiAnixBootstrapStage.RECOVERING_INTERRUPTED_SCANS,
                    startedAtMillis = System.currentTimeMillis(),
                    error = null,
                )
            }
            startupJob = processScope.launch { initializeProcessServices() }
        }
    }

    /** Waits on the job's completion contract instead of polling or sleeping. */
    suspend fun awaitStarted() {
        start()
        val job = synchronized(startupLock) { startupJob }
        job?.join()
    }

    /**
     * Preserves the existing non-destructive recovery policy and centralizes its
     * result in bootstrap state. A mutex prevents simultaneous Activity instances
     * from opening the canonical database for competing health checks.
     */
    suspend fun validateCanonicalDatabase(): Boolean {
        awaitStarted()
        return databaseValidationMutex.withLock {
            updateState {
                it.copy(
                    phase = ReiAnixBootstrapPhase.STARTING,
                    stage = ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE,
                )
            }
            recordDiagnostic(
                name = "APP_BOOTSTRAP_STAGE_STARTED",
                result = "VALIDATING_CANONICAL_DATABASE",
                stage = ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE,
            )

            val check = try {
                withContext(Dispatchers.IO) { inspectCanonicalDatabase() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Canonical SQLite validation failed; recovery will be offered.", error)
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = "A base canônica SQLite requer validação ou recuperação.",
                )
            }

            updateState { current ->
                val nextWarnings = check.warning?.let { warning ->
                    if (warning in current.warnings) current.warnings
                    else current.warnings + warning
                } ?: current.warnings
                val finalStage = if (check.recoveryRequired) {
                    ReiAnixBootstrapStage.RECOVERY_REQUIRED
                } else {
                    ReiAnixBootstrapStage.READY
                }
                current.copy(
                    phase = if (check.recoveryRequired || nextWarnings.isNotEmpty()) {
                        ReiAnixBootstrapPhase.DEGRADED
                    } else {
                        ReiAnixBootstrapPhase.READY
                    },
                    stage = finalStage,
                    persistenceStatus = check.status,
                    recoveryRequired = check.recoveryRequired,
                    completedStages = if (check.recoveryRequired) {
                        current.completedStages
                    } else {
                        current.completedStages + ReiAnixBootstrapStage.READY
                    },
                    warnings = nextWarnings,
                    error = check.warning ?: current.error,
                )
            }
            recordDiagnostic(
                name = if (check.recoveryRequired) {
                    "APP_BOOTSTRAP_RECOVERY_REQUIRED"
                } else {
                    "APP_BOOTSTRAP_DATABASE_VALIDATED"
                },
                result = check.status.name,
                stage = state.value.stage,
            )
            check.recoveryRequired
        }
    }

    private suspend fun initializeProcessServices() {
        // Timeline storage is observational work too; keep it off Application.onCreate's main thread.
        recordDiagnostic(
            name = "APP_BOOTSTRAP_STARTED",
            result = ReiAnixBootstrapPhase.STARTING.name,
            stage = ReiAnixBootstrapStage.RECOVERING_INTERRUPTED_SCANS,
        )
        updateState {
            it.copy(
                phase = ReiAnixBootstrapPhase.STARTING,
                stage = ReiAnixBootstrapStage.RECOVERING_INTERRUPTED_SCANS,
            )
        }
        try {
            NativeScanRunner.recoverInterruptedSafScans(appContext)
            completeStage(ReiAnixBootstrapStage.RECOVERING_INTERRUPTED_SCANS)
            recordDiagnostic(
                name = "APP_BOOTSTRAP_STAGE_COMPLETED",
                result = "COMPLETED",
                stage = ReiAnixBootstrapStage.RECOVERING_INTERRUPTED_SCANS,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordWarning(
                "A recuperação de varreduras SAF interrompidas não pôde ser concluída.",
                error,
            )
            recordDiagnostic(
                name = "APP_BOOTSTRAP_STAGE_FAILED",
                result = "RECOVERY_DEFERRED",
                stage = ReiAnixBootstrapStage.RECOVERING_INTERRUPTED_SCANS,
            )
        }

        updateState {
            it.copy(
                phase = ReiAnixBootstrapPhase.STARTING,
                stage = ReiAnixBootstrapStage.STARTING_NATIVE_COMMAND_DISPATCHER,
            )
        }
        try {
            NativeCommandDispatcher.start(appContext)
            completeStage(ReiAnixBootstrapStage.STARTING_NATIVE_COMMAND_DISPATCHER)
            recordDiagnostic(
                name = "APP_BOOTSTRAP_STAGE_COMPLETED",
                result = "COMPLETED",
                stage = ReiAnixBootstrapStage.STARTING_NATIVE_COMMAND_DISPATCHER,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordWarning(
                "O canal interno de comandos nativos não pôde ser iniciado.",
                error,
            )
            recordDiagnostic(
                name = "APP_BOOTSTRAP_STAGE_FAILED",
                result = "COMMAND_CHANNEL_UNAVAILABLE",
                stage = ReiAnixBootstrapStage.STARTING_NATIVE_COMMAND_DISPATCHER,
            )
        }

        updateState { current ->
            val nextPhase = if (current.warnings.isEmpty()) {
                ReiAnixBootstrapPhase.READY
            } else {
                ReiAnixBootstrapPhase.DEGRADED
            }
            current.copy(
                phase = nextPhase,
                stage = ReiAnixBootstrapStage.NATIVE_SERVICES_READY,
                completedStages = current.completedStages +
                    ReiAnixBootstrapStage.NATIVE_SERVICES_READY,
                error = current.error ?: current.warnings.firstOrNull(),
            )
        }
        recordDiagnostic(
            name = if (state.value.phase == ReiAnixBootstrapPhase.READY) {
                "APP_BOOTSTRAP_NATIVE_SERVICES_READY"
            } else {
                "APP_BOOTSTRAP_DEGRADED"
            },
            result = state.value.phase.name,
            stage = ReiAnixBootstrapStage.NATIVE_SERVICES_READY,
        )
        Log.i(
            TAG,
            "NATIVE_BOOTSTRAP_FINISHED phase=${state.value.phase} " +
                "warnings=${state.value.warnings.size}",
        )
    }

    private fun inspectCanonicalDatabase(): CanonicalDatabaseCheck {
        val dataDirectory = File(appContext.filesDir, "data")
        val canonicalFile = File(dataDirectory, ReiAnixBackupService.DATABASE_NAME)
        if (!canonicalFile.exists()) {
            val interruptedPromotion = dataDirectory.listFiles()
                ?.any { file ->
                    file.isFile && (
                        file.name.startsWith(".library.sqlite3.previous-") ||
                            file.name.startsWith(".library.sqlite3.candidate-")
                        )
                } == true
            val priorRecoverySnapshots = File(dataDirectory, "backups")
                .listFiles()
                ?.any { file ->
                    file.isDirectory && file.name.startsWith("recovery-snapshot-")
                } == true
            return if (interruptedPromotion || priorRecoverySnapshots) {
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = "Há evidências de uma recuperação SQLite interrompida.",
                )
            } else {
                // LibraryStore in the remaining hybrid runtime initializes a fresh install.
                // Absence without durable recovery evidence is not data corruption.
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
                    recoveryRequired = false,
                )
            }
        }

        if (!canonicalFile.isFile || canonicalFile.length() <= 0L) {
            return CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                recoveryRequired = true,
                warning = "O arquivo da base SQLite existe, mas não pode ser validado.",
            )
        }

        val schemaCompatible = dependencies.sqliteDatabase.inspectSchema().supportsCurrentContract
        val integrityHealthy = ReiAnixBackupService(appContext).integrityReport().healthy
        return if (schemaCompatible && integrityHealthy) {
            CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.HEALTHY,
                recoveryRequired = false,
            )
        } else {
            CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                recoveryRequired = true,
                warning = "A base canônica SQLite requer validação ou recuperação.",
            )
        }
    }

    private fun completeStage(stage: ReiAnixBootstrapStage) {
        updateState { current ->
            current.copy(completedStages = current.completedStages + stage)
        }
    }

    private fun recordWarning(message: String, error: Throwable) {
        Log.e(TAG, message, error)
        updateState { current ->
            current.copy(
                warnings = if (message in current.warnings) current.warnings
                else current.warnings + message,
                error = current.error ?: message,
            )
        }
    }

    private fun updateState(
        transform: (ReiAnixBootstrapState) -> ReiAnixBootstrapState,
    ) {
        synchronized(stateLock) {
            mutableState.value = transform(mutableState.value).copy(
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }

    private fun recordDiagnostic(
        name: String,
        result: String,
        stage: ReiAnixBootstrapStage,
    ) {
        // Diagnostic persistence can touch disk. Enqueue it on the process IO scope
        // so Application/Activity lifecycle callbacks never perform it synchronously.
        processScope.launch {
            runCatching {
                ReiAnixDiagnosticTimeline.record(
                    name = name,
                    source = "ReiAnixAppBootstrap",
                    result = result,
                    context = mapOf(
                        "stage" to stage.name,
                        "phase" to state.value.phase.name,
                        "persistence" to state.value.persistenceStatus.name,
                    ),
                )
            }.onFailure { error ->
                // Diagnostics are observational; they must not gate startup.
                Log.w(TAG, "Could not record bootstrap diagnostic event=$name.", error)
            }
        }
    }

    private data class CanonicalDatabaseCheck(
        val status: ReiAnixBootstrapPersistenceStatus,
        val recoveryRequired: Boolean,
        val warning: String? = null,
    )

    private companion object {
        const val TAG = "ReiAnixAppBootstrap"
    }
}
