package com.reiflix.reiflix_local

import android.content.Context
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeCommandDispatcher
import com.reiflix.reiflix_local.data.backup.ReiAnixBackupService
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteDatabase
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
 * It validates the canonical database before attempting interrupted SAF receipt
 * recovery or starting the file-observer command dispatcher. A damaged database or
 * durable evidence of an interrupted promotion keeps normal work gated until Recovery
 * Mode succeeds; an absent database without such evidence remains a fresh install.
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
                Log.e(TAG, "Canonical SQLite validation failed; normal services remain gated.", error)
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = databaseFailureWarning(error),
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
            Log.e(TAG, "Initial canonical SQLite validation failed; normal services remain gated.", error)
            CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                recoveryRequired = true,
                warning = databaseFailureWarning(error),
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

    private fun databaseFailureWarning(error: Throwable): String {
        val category = databaseFailureCategory(error)
        val detail = error.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(640)
            ?.takeIf { it.isNotBlank() }
        return if (detail == null) "[$category] $DATABASE_RECOVERY_WARNING"
        else "[$category] Falha na validação do banco SQLite: $detail"
    }

    private fun databaseFailureCategory(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        val message = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        val types = chain.map { it.javaClass.simpleName.lowercase() }
        return when {
            "missing existing core tables" in message ||
                "missing required tables" in message ||
                "canonical schema migration did not produce" in message ->
                "SCHEMA_CORE_TABLES_OR_CONTRACT_MISSING"
            "user_version is" in message -> "SCHEMA_USER_VERSION_MISMATCH"
            "database schema migration version" in message ||
                "schema migration failed" in message ->
                "SCHEMA_MIGRATION_FAILED"
            "database disk image is malformed" in message ||
                "not a database" in message ||
                types.any { it.contains("corrupt") } ->
                "SQLITE_CORRUPT_OR_UNRECOGNIZED"
            chain.any { it is SecurityException } -> "FILE_ACCESS_DENIED"
            "database is locked" in message || "database is busy" in message ||
                types.any { it.contains("busy") || it.contains("locked") } ->
                "SQLITE_BUSY_OR_LOCKED"
            types.any { it.contains("cantopendatabase") || it.contains("filenotfound") ||
                it.contains("diskio") || it == "ioexception" } ->
                "SQLITE_OPEN_OR_IO_FAILURE"
            "empty or is not a regular file" in message -> "SQLITE_FILE_EMPTY_OR_NOT_REGULAR"
            else -> "SQLITE_VALIDATION_FAILED"
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
        val canonicalFile = ReiAnixSqliteDatabase.canonicalFile(appContext)
        val dataDirectory = canonicalFile.parentFile ?: File(appContext.filesDir, "data")
        Log.i(
            TAG,
            "APP_BOOTSTRAP_SQLITE_INSPECT file=data/${ReiAnixSqliteDatabase.DATABASE_FILE_NAME} " +
                "exists=${canonicalFile.exists()} regularFile=${canonicalFile.isFile} " +
                "bytes=${canonicalFile.takeIf { it.isFile }?.length() ?: 0L} schemaContract=v29",
        )
        if (!canonicalFile.exists()) {
            val interruptedPromotion = dataDirectory.listFiles()
                ?.any { file ->
                    file.isFile && (
                        file.name.startsWith(".${ReiAnixSqliteDatabase.DATABASE_FILE_NAME}.previous-") ||
                            file.name.startsWith(".${ReiAnixSqliteDatabase.DATABASE_FILE_NAME}.candidate-")
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
            if (interruptedPromotion || priorRecoverySnapshots || pendingSafRecovery) {
                val reason = when {
                    pendingSafRecovery -> "SAF_RECEIPTS_PENDING"
                    interruptedPromotion -> "SQLITE_PROMOTION_INTERRUPTED"
                    else -> "SQLITE_RECOVERY_SNAPSHOT_PRESENT"
                }
                Log.w(
                    TAG,
                    "APP_BOOTSTRAP_SQLITE_DECISION file=data/${ReiAnixSqliteDatabase.DATABASE_FILE_NAME} " +
                        "exists=false recoveryRequired=true reason=$reason",
                )
                return CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = when {
                        pendingSafRecovery -> "[$reason] $SAF_RECEIPT_RECOVERY_WARNING"
                        interruptedPromotion -> "[$reason] $PROMOTION_RECOVERY_WARNING"
                        else -> "[$reason] $INTERRUPTED_RECOVERY_WARNING"
                    },
                )
            }

            Log.i(
                TAG,
                "APP_BOOTSTRAP_SQLITE_DECISION file=data/${ReiAnixSqliteDatabase.DATABASE_FILE_NAME} " +
                    "exists=false recoveryRequired=false reason=FRESH_DATABASE",
            )
            // No durable recovery evidence exists: initialize the one canonical file
            // natively. The schema manager is additive and never replaces existing data.
            val migrated = dependencies.sqliteDatabase.initializeOrMigrate(allowCreate = true)
            val schemaSnapshot = dependencies.sqliteDatabase.inspectSchema()
            val integrityHealthy = ReiAnixBackupService(appContext).integrityReport().healthy
            logCanonicalSqliteValidation(
                migrationSnapshot = migrated,
                schemaSnapshot = schemaSnapshot,
                integrityHealthy = integrityHealthy,
            )
            return if (migrated.supportsCompatibleRuntimeContract &&
                schemaSnapshot.supportsCompatibleRuntimeContract && integrityHealthy
            ) {
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.HEALTHY,
                    recoveryRequired = false,
                )
            } else {
                CanonicalDatabaseCheck(
                    status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                    recoveryRequired = true,
                    warning = "[FRESH_DATABASE_VALIDATION_FAILED] " + DATABASE_RECOVERY_WARNING,
                )
            }
        }

        if (!canonicalFile.isFile || canonicalFile.length() <= 0L) {
            Log.e(
                TAG,
                "APP_BOOTSTRAP_SQLITE_DECISION file=data/${ReiAnixSqliteDatabase.DATABASE_FILE_NAME} " +
                    "exists=true regularFile=${canonicalFile.isFile} bytes=${canonicalFile.length()} " +
                    "recoveryRequired=true reason=SQLITE_FILE_EMPTY_OR_NOT_REGULAR",
            )
            return CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                recoveryRequired = true,
                warning = "[SQLITE_FILE_EMPTY_OR_NOT_REGULAR] O arquivo da base SQLite existe, mas não pode ser validado.",
            )
        }

        val migrationSnapshot = dependencies.sqliteDatabase.initializeOrMigrate(allowCreate = false)
        val schemaSnapshot = dependencies.sqliteDatabase.inspectSchema()
        val integrity = ReiAnixBackupService(appContext).integrityReport()
        logCanonicalSqliteValidation(
            migrationSnapshot = migrationSnapshot,
            schemaSnapshot = schemaSnapshot,
            integrityHealthy = integrity.healthy,
        )
        return if (migrationSnapshot.supportsCompatibleRuntimeContract &&
            schemaSnapshot.supportsCompatibleRuntimeContract && integrity.healthy
        ) {
            CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.HEALTHY,
                recoveryRequired = false,
            )
        } else {
            val details = buildList {
                if (!migrationSnapshot.supportsCompatibleRuntimeContract) addAll(migrationSnapshot.incompatibilities)
                if (!schemaSnapshot.supportsCompatibleRuntimeContract) addAll(schemaSnapshot.incompatibilities)
                if (!integrity.healthy) add(integrity.error ?: "SQLite integrity validation failed.")
            }.distinct().joinToString("; ").take(640)
            val reason = databaseFailureCategory(IllegalStateException(details.ifBlank { DATABASE_RECOVERY_WARNING }))
            Log.w(
                TAG,
                "APP_BOOTSTRAP_SQLITE_DECISION file=data/${ReiAnixSqliteDatabase.DATABASE_FILE_NAME} " +
                    "exists=true recoveryRequired=true reason=$reason",
            )
            CanonicalDatabaseCheck(
                status = ReiAnixBootstrapPersistenceStatus.RECOVERY_REQUIRED,
                recoveryRequired = true,
                warning = "[$reason] " + details.ifBlank { DATABASE_RECOVERY_WARNING },
            )
        }
    }

    private fun logCanonicalSqliteValidation(
        migrationSnapshot: com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSchemaSnapshot,
        schemaSnapshot: com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteSchemaSnapshot,
        integrityHealthy: Boolean,
    ) {
        Log.i(
            TAG,
            "APP_BOOTSTRAP_SQLITE_VALIDATION file=data/${ReiAnixSqliteDatabase.DATABASE_FILE_NAME} " +
                "migrationCompatible=${migrationSnapshot.supportsCompatibleRuntimeContract} " +
                "runtimeCompatible=${schemaSnapshot.supportsCompatibleRuntimeContract} " +
                "schemaVersion=${schemaSnapshot.schemaVersion ?: -1} " +
                "sqliteUserVersion=${schemaSnapshot.sqliteUserVersion} tableCount=${schemaSnapshot.tables.size} " +
                "missingTables=${schemaSnapshot.missingRequiredTables.sorted().joinToString(",").ifBlank { "none" }} " +
                "integrityHealthy=$integrityHealthy",
        )
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
