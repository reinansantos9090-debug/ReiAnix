package com.reiflix.reiflix_local

import android.content.Context
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.scanner.NativeScanRunner
import com.reiflix.reiflix_local.storage.NativeIndex
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
    private val nativeServicesMutex = Mutex()
    @Volatile
    private var nativeServicesStarted = false
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
                    stage = ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE,
                    startedAtMillis = System.currentTimeMillis(),
                    error = null,
                )
            }
            startupJob = processScope.launch { initializeProcessServices() }
        }
    }

    /** Waits for the one process-owned startup decision without polling or sleeping. */
    suspend fun awaitStarted() {
        start()
        val job = synchronized(startupLock) { startupJob }
        job?.join()
    }

    /**
     * Revalidates the canonical database using the same startup contract. A successful
     * revalidation resumes the existing service graph exactly once; failure keeps the
     * dispatcher gated and publishes Recovery Mode to all process-owned observers.
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
                result = ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE.name,
                stage = ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE,
            )

            val check = try {
                withContext(Dispatchers.IO) { inspectCanonicalDatabase() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Canonical SQLite validation failed; normal services remain gated. errorType=" + error::class.java.simpleName)
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = DATABASE_RECOVERY_WARNING,
                )
            }
            publishDatabaseCheck(check)
            if (check.recoveryRequired) {
                recordDiagnostic(
                    name = "APP_BOOTSTRAP_RECOVERY_REQUIRED",
                    result = check.status.name,
                    stage = ReiAnixBootstrapStage.RECOVERY_REQUIRED,
                )
            } else {
                ensureNativeServicesStarted()
                recordDiagnostic(
                    name = if (state.value.phase == ReiAnixBootstrapPhase.READY) {
                        "APP_BOOTSTRAP_DATABASE_VALIDATED"
                    } else {
                        "APP_BOOTSTRAP_DEGRADED"
                    },
                    result = state.value.persistenceStatus.name,
                    stage = state.value.stage,
                )
            }
            state.value.recoveryRequired
        }
    }

    /**
     * The first database decision happens before SAF receipt recovery or command
     * dispatch. A corrupt/mid-promotion database leaves receipts intact and exposes
     * Recovery Mode without allowing queued catalog mutations to run.
     */
    private suspend fun initializeProcessServices() {
        recordDiagnostic(
            name = "APP_BOOTSTRAP_STARTED",
            result = ReiAnixBootstrapPhase.STARTING.name,
            stage = ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE,
        )
        val check = try {
            withContext(Dispatchers.IO) { inspectCanonicalDatabase() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Initial canonical SQLite validation failed; normal services remain gated. errorType=" + error::class.java.simpleName)
            CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                recoveryRequired = true,
                warning = DATABASE_RECOVERY_WARNING,
            )
        }
        publishDatabaseCheck(check)
        if (check.recoveryRequired) {
            recordDiagnostic(
                name = "APP_BOOTSTRAP_RECOVERY_REQUIRED",
                result = check.status.name,
                stage = ReiAnixBootstrapStage.RECOVERY_REQUIRED,
            )
            Log.w(TAG, "Normal native services were not started because canonical SQLite requires recovery.")
            return
        }
        ensureNativeServicesStarted()
    }

    private fun publishDatabaseCheck(check: CanonicalDatabaseCheck) {
        updateState { current ->
            val retainedWarnings = current.warnings.filterNot { it.isCanonicalDatabaseWarning() }
            val nextWarnings = if (check.recoveryRequired && !check.warning.isNullOrBlank()) {
                (retainedWarnings + check.warning).distinct()
            } else {
                retainedWarnings
            }
            val servicesReady = nativeServicesStarted
            current.copy(
                phase = when {
                    check.recoveryRequired -> ReiAnixBootstrapPhase.DEGRADED
                    servicesReady && nextWarnings.isEmpty() -> ReiAnixBootstrapPhase.READY
                    servicesReady -> ReiAnixBootstrapPhase.DEGRADED
                    else -> ReiAnixBootstrapPhase.STARTING
                },
                stage = when {
                    check.recoveryRequired -> ReiAnixBootstrapStage.RECOVERY_REQUIRED
                    servicesReady -> ReiAnixBootstrapStage.READY
                    else -> ReiAnixBootstrapStage.VALIDATING_CANONICAL_DATABASE
                },
                persistenceStatus = check.status,
                recoveryRequired = check.recoveryRequired,
                completedStages = when {
                    check.recoveryRequired -> current.completedStages - ReiAnixBootstrapStage.READY
                    servicesReady -> current.completedStages + ReiAnixBootstrapStage.READY
                    else -> current.completedStages - ReiAnixBootstrapStage.READY
                },
                warnings = nextWarnings,
                error = if (check.recoveryRequired) {
                    check.warning ?: DATABASE_RECOVERY_WARNING
                } else {
                    nextWarnings.firstOrNull()
                },
            )
        }
    }

    private fun String.isCanonicalDatabaseWarning(): Boolean =
        contains("base canônica SQLite", ignoreCase = true) ||
            contains("arquivo da base SQLite", ignoreCase = true) ||
            contains("recuperação SQLite interrompida", ignoreCase = true) ||
            contains("promoção SQLite interrompida", ignoreCase = true) ||
            contains("recibos SAF interrompidos", ignoreCase = true)

    /**
     * May be reached on first healthy startup or after Recovery Mode succeeds. The
     * mutex makes Application/Activity calls idempotent and keeps the single graph's
     * receipt recovery ahead of the existing dispatcher.
     */
    private suspend fun ensureNativeServicesStarted() {
        nativeServicesMutex.withLock {
            if (nativeServicesStarted) {
                updateState { current ->
                    if (current.recoveryRequired) current else current.copy(
                        phase = if (current.warnings.isEmpty()) {
                            ReiAnixBootstrapPhase.READY
                        } else {
                            ReiAnixBootstrapPhase.DEGRADED
                        },
                        stage = ReiAnixBootstrapStage.READY,
                        completedStages = current.completedStages +
                            ReiAnixBootstrapStage.NATIVE_SERVICES_READY +
                            ReiAnixBootstrapStage.READY,
                    )
                }
                return@withLock
            }

            val validated = state.value
            if (
                validated.recoveryRequired ||
                validated.persistenceStatus !in setOf(
                    ReiAnixBootstrapPersistenceStatus.HEALTHY,
                    ReiAnixBootstrapPersistenceStatus.NOT_CREATED,
                )
            ) return@withLock

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
                    "A recuperação dos recibos de varreduras SAF foi adiada; os recibos foram preservados.",
                    error,
                )
                // A recovery pass can discover that a previously healthy database is no
                // longer safe to mutate. Revalidate before allowing queued commands through.
                val afterFailure = try {
                    withContext(Dispatchers.IO) { inspectCanonicalDatabase() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (validationError: Exception) {
                    Log.e(TAG, "SQLite revalidation after SAF receipt recovery failed. errorType=" + validationError::class.java.simpleName)
                    CanonicalDatabaseCheck(
                        status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                        recoveryRequired = true,
                        warning = DATABASE_RECOVERY_WARNING,
                    )
                }
                if (afterFailure.recoveryRequired) {
                    publishDatabaseCheck(afterFailure)
                    recordDiagnostic(
                        name = "APP_BOOTSTRAP_RECOVERY_REQUIRED",
                        result = afterFailure.status.name,
                        stage = ReiAnixBootstrapStage.RECOVERY_REQUIRED,
                    )
                    return@withLock
                }
                publishDatabaseCheck(afterFailure)
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

            nativeServicesStarted = true
            updateState { current ->
                if (current.recoveryRequired) {
                    current
                } else {
                    current.copy(
                        phase = if (current.warnings.isEmpty()) {
                            ReiAnixBootstrapPhase.READY
                        } else {
                            ReiAnixBootstrapPhase.DEGRADED
                        },
                        stage = ReiAnixBootstrapStage.READY,
                        completedStages = current.completedStages +
                            ReiAnixBootstrapStage.NATIVE_SERVICES_READY +
                            ReiAnixBootstrapStage.READY,
                        error = current.warnings.firstOrNull(),
                    )
                }
            }
            recordDiagnostic(
                name = if (state.value.phase == ReiAnixBootstrapPhase.READY) {
                    "APP_BOOTSTRAP_NATIVE_SERVICES_READY"
                } else {
                    "APP_BOOTSTRAP_DEGRADED"
                },
                result = state.value.phase.name,
                stage = ReiAnixBootstrapStage.READY,
            )
            Log.i(
                TAG,
                "NATIVE_BOOTSTRAP_FINISHED phase=${state.value.phase} " +
                    "persistence=${state.value.persistenceStatus} " +
                    "warnings=${state.value.warnings.size}",
            )
        }
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
            val pendingSafRecovery = try {
                NativeIndex.pendingSafRecovery(appContext).length() > 0
            } catch (error: Exception) {
                Log.e(TAG, "Could not inspect durable SAF recovery receipts; treating the state as recovery evidence. errorType=" + error::class.java.simpleName)
                true
            }
            return if (interruptedPromotion || priorRecoverySnapshots || pendingSafRecovery) {
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = when {
                        pendingSafRecovery -> SAF_RECEIPT_RECOVERY_WARNING
                        interruptedPromotion -> PROMOTION_RECOVERY_WARNING
                        else -> INTERRUPTED_RECOVERY_WARNING
                    },
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
                warning = DATABASE_RECOVERY_WARNING,
            )
        }
    }

    private fun completeStage(stage: ReiAnixBootstrapStage) {
        updateState { current ->
            current.copy(completedStages = current.completedStages + stage)
        }
    }

    private fun recordWarning(message: String, error: Throwable) {
        Log.e(TAG, message + " errorType=" + error::class.java.simpleName)
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
        const val DATABASE_RECOVERY_WARNING = "A base canônica SQLite requer validação ou recuperação."
        const val PROMOTION_RECOVERY_WARNING = "Há evidências de uma promoção SQLite interrompida."
        const val INTERRUPTED_RECOVERY_WARNING = "Há evidências de uma recuperação SQLite interrompida."
        const val SAF_RECEIPT_RECOVERY_WARNING = "Há recibos SAF interrompidos sem base canônica; a recuperação é necessária."
    }
}
