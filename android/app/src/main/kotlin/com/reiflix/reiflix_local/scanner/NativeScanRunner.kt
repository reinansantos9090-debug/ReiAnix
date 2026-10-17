package com.reiflix.reiflix_local.scanner

import android.content.Context
import com.reiflix.reiflix_local.ReiAnixAppDependencies
import android.net.Uri
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.data.sqlite.NativeDiscoveredMedia
import com.reiflix.reiflix_local.data.sqlite.NativeLibraryScanSnapshot
import com.reiflix.reiflix_local.data.sqlite.NativeLibraryIndexReport
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteTransactionOutcome
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-owned execution boundary for native storage scans.
 *
 * MainActivity remains the Android lifecycle/permission entry point, while the
 * actual scanner I/O, reconciliation publication, and cancellation handling
 * live here. The caller supplies the scan token already registered with
 * NativeScanController so Activity recreation cannot create a duplicate run.
 */
object NativeScanRunner {
    private const val TAG = "[REIANIX][NATIVE_SCAN]"

    /**
     * Replays recovery receipts only for generations started by the durable recovery protocol.
     * An unconfirmed native transaction is never handed to Python; it is recorded as partial
     * and requires a controlled native rescan.
     */
    suspend fun recoverInterruptedSafScans(context: Context) {
        val appContext = context.applicationContext
        val pending = runCatching { NativeIndex.pendingSafRecovery(appContext) }
            .onFailure { Log.e(TAG, "Could not inspect persisted SAF recovery state.", it) }
            .getOrNull() ?: return

        for (index in 0 until pending.length()) {
            val item = pending.optJSONObject(index) ?: continue
            val scanId = item.optString("scanId").trim()
            val scopeKey = item.optString("scopeKey").trim()
            val generation = item.optLong("generation", 0L)
            val treeUri = item.optString("treeUri").trim()
            if (scanId.isBlank() || scopeKey.isBlank() || generation <= 0L || treeUri.isBlank()) {
                Log.w(TAG, "Skipping SAF recovery receipt with incomplete identity; preserving persisted generation.")
                continue
            }
            val active = NativeScanController.snapshot(scanId)
            if (active != null && !active.state.isTerminal) continue

            var outcome = item.optString("nativeCatalogWriteOutcome").ifBlank { "UNKNOWN" }
            val snapshotAvailable = item.optBoolean("snapshotAvailable") &&
                outcome in setOf("NOT_STARTED", "NOT_COMMITTED")
            val documents = if (snapshotAvailable) item.optJSONArray("documents") ?: JSONArray() else JSONArray()
            var report = item.optJSONObject("nativeCatalogWriteReport")?.let { JSONObject(it.toString()) }
                ?: JSONObject()

            // Recovery snapshots are positive evidence only. Re-index them in Kotlin with an
            // intentionally partial snapshot so a process interruption can never authorize
            // missing-file reconciliation. Unknown write outcomes never reach this path.
            var preserveRecoveryReceipt = false
            if (snapshotAvailable) {
                val recoveryOutcome = AtomicReference<ReiAnixSqliteTransactionOutcome?>(null)
                val rollbackReceiptPersisted = AtomicBoolean(false)
                val recoveryReport = AtomicReference<NativeLibraryIndexReport?>(null)
                var recoveryCheckpointAttempted = false
                var indexedReport: NativeLibraryIndexReport? = null
                try {
                    val recoveredTreeUri = Uri.parse(treeUri)
                    val treeIdentity = SafScanner.treeIdentity(recoveredTreeUri)
                    check(treeIdentity.documentId.isNotBlank()) {
                        "Recovered SAF snapshot has no validated scope identity."
                    }
                    val authorization = runCatching {
                        SafScanner.inspectTree(appContext, recoveredTreeUri, requirePersisted = true)
                    }.getOrElse { error ->
                        Log.w(TAG, "Recovered SAF snapshot is retained as positive evidence after authorization inspection failed.", error)
                        JSONObject().put("status", SafScanner.STATUS_UNAVAILABLE)
                    }
                    val scopeRef = item.optString("scopeRef").trim().ifBlank { treeUri }
                    val recoveredSnapshot = NativeLibraryScanSnapshot(
                        operationId = scanId,
                        source = NativeIndex.SOURCE_SAF,
                        scopeRoot = treeIdentity.documentId,
                        expectedLocations = setOf(treeIdentity.documentId),
                        processedLocations = emptySet(),
                        failedLocations = emptySet(),
                        unprocessedLocations = setOf(treeIdentity.documentId),
                        status = SafScanner.STATUS_PARTIAL,
                        complete = false,
                        cancelled = item.optString("status").equals(NativeIndex.STATUS_CANCELLED, ignoreCase = true),
                        authorizationValid = authorization.optString("status") in setOf(
                            SafScanner.STATUS_COMPLETED, SafScanner.STATUS_EMPTY_COMPLETE,
                        ),
                        items = discoveredMediaFromSnapshot(documents, scopeRef),
                        scopeReference = scopeRef,
                        scanGeneration = generation,
                    )
                    check(NativeIndex.markNativeCatalogIndexing(
                        appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                    )) { "Could not persist recovery indexing receipt for the verified SAF snapshot." }
                    recoveryCheckpointAttempted = true

                    indexedReport = ReiAnixAppDependencies.from(appContext).libraryService
                        .indexDiscoveredMedia(recoveredSnapshot) { transactionOutcome, transactionReport ->
                            recoveryOutcome.set(transactionOutcome)
                            when (transactionOutcome) {
                                ReiAnixSqliteTransactionOutcome.COMMITTED -> {
                                    if (transactionReport != null) {
                                        recoveryReport.set(transactionReport)
                                        if (!NativeIndex.markNativeCatalogCommitted(
                                                appContext, NativeIndex.SOURCE_SAF, scopeKey, generation, transactionReport,
                                            )
                                        ) {
                                            Log.e(TAG, "Recovery SQLite commit succeeded but its durable commit receipt could not be recorded.")
                                        }
                                    } else {
                                        NativeIndex.markNativeCatalogCommittedWithoutReport(
                                            appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                                            "Recovery SQLite commit was confirmed but its report was unavailable.",
                                        )
                                    }
                                }
                                ReiAnixSqliteTransactionOutcome.ROLLED_BACK -> {
                                    rollbackReceiptPersisted.set(
                                        NativeIndex.markNativeCatalogNotCommitted(
                                            appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                                            "Recovery SQLite transaction rolled back before a commit was confirmed.",
                                        ),
                                    )
                                    if (!rollbackReceiptPersisted.get()) {
                                        Log.w(TAG, "Could not persist recovery rollback receipt; no data will be sent to a legacy writer.")
                                    }
                                }
                                ReiAnixSqliteTransactionOutcome.UNKNOWN -> {
                                    NativeIndex.markNativeCatalogOutcomeUnknown(
                                        appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                                        "Recovery SQLite transaction finalization did not provide a definitive outcome.",
                                    )
                                }
                            }
                        }

                    when (recoveryOutcome.get()) {
                        ReiAnixSqliteTransactionOutcome.COMMITTED -> {
                            val committedReport = recoveryReport.get() ?: indexedReport
                            outcome = "COMMITTED"
                            if (committedReport != null) {
                                report = JSONObject()
                                    .put("status", if (committedReport.reconciliationAuthorized) "COMPLETED" else "PARTIAL")
                                    .put("operationId", committedReport.operationId)
                                    .put("inserted", committedReport.inserted)
                                    .put("updated", committedReport.updated)
                                    .put("unchanged", committedReport.unchanged)
                                    .put("ambiguous", committedReport.ambiguous)
                                    .put("rejectedOutOfScope", committedReport.rejectedOutOfScope)
                                    .put("reconciledMissing", committedReport.reconciledMissing)
                                    .put("reconciliationAuthorized", committedReport.reconciliationAuthorized)
                            } else {
                                report = JSONObject().put("status", "COMMITTED_REPORT_UNAVAILABLE")
                            }
                        }
                        ReiAnixSqliteTransactionOutcome.ROLLED_BACK -> {
                            outcome = "NOT_COMMITTED"
                            preserveRecoveryReceipt = rollbackReceiptPersisted.get() && documents.length() > 0
                            report = JSONObject().put(
                                "status",
                                if (preserveRecoveryReceipt) "NATIVE_POSITIVE_RECOVERY_PENDING" else "ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED",
                            )
                        }
                        ReiAnixSqliteTransactionOutcome.UNKNOWN -> {
                            outcome = "UNKNOWN"
                            NativeIndex.markNativeCatalogOutcomeUnknown(
                                appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                                "Recovery SQLite transaction outcome is unknown; controlled rescan required.",
                            )
                            report = JSONObject().put("status", "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED")
                        }
                        null -> {
                            // If the receipt had already been moved to IN_PROGRESS, absence of an
                            // outcome callback cannot safely be interpreted as a rollback.
                            if (recoveryCheckpointAttempted) {
                                outcome = "UNKNOWN"
                                NativeIndex.markNativeCatalogOutcomeUnknown(
                                    appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                                    "Recovery did not report a definitive SQLite transaction outcome.",
                                )
                                report = JSONObject().put("status", "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED")
                            } else {
                                outcome = "NOT_COMMITTED"
                                preserveRecoveryReceipt = true
                                report = JSONObject().put("status", "NATIVE_POSITIVE_RECOVERY_PENDING")
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not recover interrupted SAF discoveries in Kotlin; preserving the receipt without transferring writes to Python.", exception)
                    when (recoveryOutcome.get()) {
                        ReiAnixSqliteTransactionOutcome.COMMITTED -> {
                            outcome = "COMMITTED"
                            report = recoveryReport.get()?.let { committedReport ->
                                JSONObject()
                                    .put("status", "COMMITTED_REPORT_UNAVAILABLE")
                                    .put("operationId", committedReport.operationId)
                                    .put("inserted", committedReport.inserted)
                                    .put("updated", committedReport.updated)
                                    .put("unchanged", committedReport.unchanged)
                                    .put("ambiguous", committedReport.ambiguous)
                                    .put("rejectedOutOfScope", committedReport.rejectedOutOfScope)
                                    .put("reconciledMissing", committedReport.reconciledMissing)
                                    .put("reconciliationAuthorized", committedReport.reconciliationAuthorized)
                            } ?: JSONObject().put("status", "COMMITTED_REPORT_UNAVAILABLE")
                        }
                        ReiAnixSqliteTransactionOutcome.ROLLED_BACK -> {
                            outcome = "NOT_COMMITTED"
                            preserveRecoveryReceipt = rollbackReceiptPersisted.get() && documents.length() > 0
                            report = JSONObject().put(
                                "status",
                                if (preserveRecoveryReceipt) "NATIVE_POSITIVE_RECOVERY_PENDING" else "ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED",
                            )
                        }
                        else -> {
                            outcome = "UNKNOWN"
                            if (recoveryCheckpointAttempted) {
                                NativeIndex.markNativeCatalogOutcomeUnknown(
                                    appContext, NativeIndex.SOURCE_SAF, scopeKey, generation,
                                    "Recovery failed without a confirmed SQLite transaction outcome.",
                                )
                                report = JSONObject().put("status", "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED")
                            } else {
                                // No transaction was started. Keep the original NOT_STARTED /
                                // NOT_COMMITTED receipt eligible for a later native retry.
                                preserveRecoveryReceipt = true
                                outcome = item.optString("nativeCatalogWriteOutcome")
                                    .ifBlank { "NOT_COMMITTED" }
                                report = JSONObject().put("status", "NATIVE_POSITIVE_RECOVERY_PENDING")
                            }
                        }
                    }
                }
            }

            val committed = outcome == "COMMITTED"
            val uncertain = outcome !in setOf("NOT_STARTED", "NOT_COMMITTED", "COMMITTED")
            val sourceStatus = item.optString("status")
            val trustedNativeCompletion = committed &&
                item.optBoolean("completeSnapshotPublished") &&
                report.optBoolean("reconciliationAuthorized") &&
                sourceStatus in setOf(NativeIndex.STATUS_COMPLETED, NativeIndex.STATUS_EMPTY_COMPLETE)
            val terminalStatus = when {
                trustedNativeCompletion && sourceStatus == NativeIndex.STATUS_EMPTY_COMPLETE -> SafScanner.STATUS_EMPTY_COMPLETE
                trustedNativeCompletion -> SafScanner.STATUS_COMPLETED
                else -> SafScanner.STATUS_PARTIAL
            }
            val recovery = when {
                committed && report.optBoolean("reconciliationAuthorized") &&
                    item.optBoolean("completeSnapshotPublished") &&
                    sourceStatus in setOf(NativeIndex.STATUS_COMPLETED, NativeIndex.STATUS_EMPTY_COMPLETE) ->
                    "NATIVE_COMMIT_CONFIRMED"
                committed -> "NATIVE_PARTIAL_COMMIT_CONFIRMED"
                uncertain -> "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED"
                preserveRecoveryReceipt -> "NATIVE_POSITIVE_RECOVERY_PENDING"
                else -> "DISCOVERY_SNAPSHOT_UNAVAILABLE_RESCAN_REQUIRED"
            }
            val message = when (recovery) {
                "NATIVE_COMMIT_CONFIRMED" ->
                    "Recovered terminal SAF notification after confirmed native SQLite commit."
                "NATIVE_PARTIAL_COMMIT_CONFIRMED" ->
                    "Recovered terminal SAF notification after confirmed positive-only native indexing; previous rows were preserved."
                "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED" ->
                    "Process ended during native SQLite transaction; commit outcome is unknown. Existing catalog preserved; controlled rescan required."
                "NATIVE_POSITIVE_RECOVERY_PENDING" ->
                    "Verified positive discoveries could not be committed during this recovery attempt; the receipt remains available for a later native retry."
                else ->
                    "SAF discovery was interrupted without a safely recoverable snapshot; existing catalog preserved; controlled rescan required."
            }
            val metadata = item.optJSONObject("metadata") ?: JSONObject()
            val stats = metadata.optJSONObject("stats")?.let { JSONObject(it.toString()) } ?: JSONObject()
            if (!trustedNativeCompletion) {
                stats.put("status", SafScanner.STATUS_PARTIAL).put("partial", true)
                val errors = stats.optJSONArray("errors") ?: JSONArray().also { stats.put("errors", it) }
                if ((0 until errors.length()).none { errors.optString(it) == message }) errors.put(message)
            }

            val payload = runCatching { SafScanner.identityPayload(Uri.parse(treeUri)) }
                .getOrElse { JSONObject().put("treeUri", treeUri) }
                .put("scanId", scanId)
                .put("requestId", metadata.optString("requestId"))
                .put("scanGeneration", generation)
                .put("generationId", item.optString("generationId").ifBlank {
                    NativeIndex.generationId(NativeIndex.SOURCE_SAF, scopeKey, generation)
                })
                .put("scopeKind", "root")
                .put("scopeRef", metadata.optString("scopeRef").ifBlank { treeUri })
                .put("source", "saf")
                .put("status", terminalStatus)
                .put("stats", stats)
                // The receipt may contain verified discoveries for a future Kotlin retry,
                // but scan documents never cross to Python as a catalog-mutation fallback.
                .put("documents", JSONArray())
                .put("partial", !trustedNativeCompletion)
                .put("nativeLibraryIndexOwner", true)
                .put("nativeLibraryIndex", JSONObject()
                    .put("status", if (committed) report.optString("status", "COMMITTED") else recovery)
                    .put("commitOutcome", outcome)
                    .put("recovery", recovery)
                    .put("snapshotAvailable", snapshotAvailable)
                    .put("terminalError", item.optString("nativeCatalogWriteError"))
                    .put("inserted", report.optInt("inserted", 0))
                    .put("updated", report.optInt("updated", 0))
                    .put("unchanged", report.optInt("unchanged", 0))
                    .put("ambiguous", report.optInt("ambiguous", 0))
                    .put("rejectedOutOfScope", report.optInt("rejectedOutOfScope", 0))
                    .put("reconciledMissing", report.optInt("reconciledMissing", 0))
                    .put("reconciliationAuthorized", report.optBoolean("reconciliationAuthorized")))

            val nativeScanRunOwner = if (payload.optBoolean("nativeLibraryIndexOwner")) {
                try {
                    ReiAnixAppDependencies.from(appContext).libraryService.finishNativeScanRun(
                        scanId = scanId,
                        requestId = metadata.optString("requestId").takeIf { it.isNotBlank() },
                        sourceKind = NativeIndex.SOURCE_SAF,
                        scopeKind = "root",
                        scopeRef = payload.optString("scopeRef").ifBlank { treeUri },
                        nativeGeneration = generation,
                        generationId = payload.optString("generationId"),
                        status = terminalStatus,
                        stats = stats,
                        indexReport = payload.optJSONObject("nativeLibraryIndex"),
                        startedAtMillis = null,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    Log.e(TAG, "Could not finalize recovered SAF scan in Kotlin; retaining compatibility fallback.", exception)
                    false
                }
            } else {
                false
            }
            payload.put("nativeScanRunOwner", nativeScanRunOwner)

            val written = runCatching {
                NativeMailbox.writeOrThrow(
                    appContext,
                    JSONObject().put("type", "saf_scan").put("requestId", metadata.optString("requestId")).put("payload", payload),
                )
            }.onFailure { Log.e(TAG, "Could not persist recovered SAF terminal event; receipt remains pending.", it) }
                .isSuccess
            if (!written) continue

            if (!preserveRecoveryReceipt) {
                runCatching {
                    NativeIndex.markRecoveryEventPublished(appContext, NativeIndex.SOURCE_SAF, scopeKey, generation)
                }.onFailure { Log.w(TAG, "Recovery event is durable, but its receipt marker could not be persisted.", it) }
            }

            if (
                item.optString("status") in setOf(NativeIndex.STATUS_STARTED, NativeIndex.STATUS_RUNNING)
            ) {
                runCatching {
                    NativeIndex.failGeneration(
                        appContext,
                        NativeIndex.SOURCE_SAF,
                        scopeKey,
                        generation,
                        "Interrupted SAF generation recovered through a durable partial terminal event.",
                        JSONObject().put("treeUri", treeUri).put("scanId", scanId),
                    )
                }.onFailure { Log.w(TAG, "Could not finalize recovered SAF generation metadata.", it) }
            }
        }
    }

    suspend fun runSafScan(
        context: Context,
        treeUri: Uri,
        reference: String,
        scanId: String,
        requestId: String?,
        scanKey: String,
        generationId: Long,
    ) {
        val appContext = context.applicationContext
        // Once discovery generation publication succeeds, later mailbox failures must not
        // discard its committed snapshot; a controlled rescan can recover UI/catalog state.
        var scanGenerationFinished = false
        var publishedCompleteGeneration = false
        var terminalEventPublished = false
        var terminalResult: JSONObject? = null
        // Set inside the repository immediately after endTransaction() returns. This
        // distinguishes a rollback from cancellation of withContext result delivery.
        val committedIndexReport = AtomicReference<NativeLibraryIndexReport?>(null)
        val indexTransactionOutcome = AtomicReference<ReiAnixSqliteTransactionOutcome?>(null)
        val rollbackReceiptPersisted = AtomicBoolean(false)
        val nativeIndexingCheckpointAttempted = AtomicBoolean(false)

        fun canRecoverInKotlin(): Boolean {
            if (committedIndexReport.get() != null) return false
            if (!nativeIndexingCheckpointAttempted.get()) return true
            return indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.ROLLED_BACK &&
                rollbackReceiptPersisted.get()
        }

        suspend fun persistNativeScanRun(payload: JSONObject): Boolean {
            // Native Kotlin owns scan bookkeeping when catalog indexing is owned here; a verified rollback schedules a later native retry.
            // When Kotlin owns catalog persistence, it also owns the normal scan-run row.
            if (!payload.optBoolean("nativeLibraryIndexOwner")) return false
            return try {
                val stats = payload.optJSONObject("stats") ?: JSONObject()
                val indexReport = payload.optJSONObject("nativeLibraryIndex")
                val startedAt = NativeScanController.snapshot(scanId)?.startedAtMillis
                ReiAnixAppDependencies.from(appContext).libraryService.finishNativeScanRun(
                    scanId = scanId,
                    requestId = requestId,
                    sourceKind = NativeIndex.SOURCE_SAF,
                    scopeKind = payload.optString("scopeKind").ifBlank { "root" },
                    scopeRef = payload.optString("scopeRef").ifBlank { reference },
                    nativeGeneration = payload.optLong("scanGeneration", generationId),
                    generationId = payload.optString("generationId").ifBlank {
                        NativeIndex.generationId(NativeIndex.SOURCE_SAF, scanKey, generationId)
                    },
                    status = payload.optString("status").ifBlank { SafScanner.STATUS_PARTIAL },
                    stats = stats,
                    indexReport = indexReport,
                    startedAtMillis = startedAt,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.e(TAG, "Native scan history finalization failed; catalog ownership remains Kotlin and recovery stays receipt-gated.", exception)
                false
            }
        }

        fun recoveryDocuments(): JSONArray? {
            if (!canRecoverInKotlin()) return null
            if (committedIndexReport.get() != null) return null
            return runCatching {
                NativeIndex.recoveryDocuments(
                    appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                ) ?: if (scanGenerationFinished && publishedCompleteGeneration) {
                    NativeIndex.committedDocuments(appContext, NativeIndex.SOURCE_SAF, scanKey)
                } else {
                    NativeIndex.stagedDocuments(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not read a verified SAF discovery snapshot for recovery.", error)
            }.getOrNull()
        }

        suspend fun publishRecoveryTerminal(message: String, cancelled: Boolean): Boolean {
            val committedReport = committedIndexReport.get()
            val payload = terminalResult ?: SafScanner.identityPayload(treeUri)
            var recoverablePositiveSnapshot = false
            var preserveRecoveryReceipt = false

            if (committedReport == null) {
                val outcomeBeforeRecovery = indexTransactionOutcome.get()
                val canReadPositiveSnapshot = canRecoverInKotlin() && recoveryDocuments() != null
                if (!nativeIndexingCheckpointAttempted.get() && canReadPositiveSnapshot) {
                    // No catalog transaction was started. Persist this fact before publishing
                    // terminal state so pendingSafRecovery can safely retry positive discoveries
                    // through Kotlin after the process/activity is restarted.
                    rollbackReceiptPersisted.set(
                        runCatching {
                            NativeIndex.markNativeCatalogNotCommitted(
                                appContext,
                                NativeIndex.SOURCE_SAF,
                                scanKey,
                                generationId,
                                "No canonical SQLite transaction was started; verified positive discoveries remain recoverable in Kotlin.",
                            )
                        }.onFailure {
                            Log.w(TAG, "Could not persist the pre-transaction recovery receipt; rescan remains required.", it)
                        }.getOrDefault(false),
                    )
                }
                recoverablePositiveSnapshot = canReadPositiveSnapshot &&
                    ((outcomeBeforeRecovery == ReiAnixSqliteTransactionOutcome.ROLLED_BACK &&
                        rollbackReceiptPersisted.get() && recoveryDocuments()?.length()?.let { it > 0 } == true) ||
                        (!nativeIndexingCheckpointAttempted.get() && rollbackReceiptPersisted.get() && recoveryDocuments()?.length()?.let { it > 0 } == true))
                preserveRecoveryReceipt = recoverablePositiveSnapshot

                val stats = payload.optJSONObject("stats") ?: JSONObject()
                stats.put("status", SafScanner.STATUS_PARTIAL)
                    .put("partial", true)
                    .put("cancelled", cancelled)
                val errors = stats.optJSONArray("errors") ?: JSONArray().also { stats.put("errors", it) }
                if ((0 until errors.length()).none { errors.optString(it) == message }) errors.put(message)

                val outcome = indexTransactionOutcome.get()
                val recovery = when {
                    outcome == ReiAnixSqliteTransactionOutcome.COMMITTED ->
                        "NATIVE_COMMIT_CONFIRMED_REPORT_UNAVAILABLE"
                    recoverablePositiveSnapshot ->
                        "NATIVE_POSITIVE_RECOVERY_PENDING"
                    outcome == ReiAnixSqliteTransactionOutcome.ROLLED_BACK ->
                        "ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED"
                    outcome == null && !nativeIndexingCheckpointAttempted.get() ->
                        "NATIVE_DISCOVERY_SNAPSHOT_UNAVAILABLE_RESCAN_REQUIRED"
                    else -> "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED"
                }

                payload.put("stats", stats)
                    // Discovery documents never cross to Python as a catalog-write fallback.
                    .put("documents", JSONArray())
                    .put("nativeLibraryIndexOwner", true)
                    .put("status", SafScanner.STATUS_PARTIAL)
                    .put("partial", true)
                    .put("cancelled", cancelled)
                val indexState = payload.optJSONObject("nativeLibraryIndex") ?: JSONObject()
                indexState.put("recovery", recovery)
                    .put("commitOutcome", outcome?.name ?: if (nativeIndexingCheckpointAttempted.get()) "UNKNOWN" else "NOT_STARTED")
                    .put("terminalError", message)
                payload.put("nativeLibraryIndex", indexState)
            } else {
                // A confirmed native commit remains authoritative; never fall back to Python
                // just because terminal event delivery failed after SQLite committed.
                val indexState = payload.optJSONObject("nativeLibraryIndex") ?: JSONObject()
                indexState.put("commitOutcome", "COMMITTED")
                    .put("terminalDeliveryError", message)
                payload.put("nativeLibraryIndex", indexState)
                    .put("nativeLibraryIndexOwner", true)
            }
            payload.put("requestId", requestId ?: "")
                .put("scanId", scanId)
                .put("scanGeneration", generationId)
                .put("generationId", NativeIndex.generationId(NativeIndex.SOURCE_SAF, scanKey, generationId))
                .put("scopeKind", "root")
                .put("scopeRef", reference)
                .put("source", "saf")
                .put("scope", runCatching { SafScanner.treeIdentity(treeUri).identity }.getOrDefault(""))
                .put("nativeScanRunOwner", persistNativeScanRun(payload))
            val written = runCatching {
                NativeMailbox.writeOrThrow(
                    appContext,
                    JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", payload),
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not publish terminal SAF recovery state; retaining the native receipt.", error)
            }.isSuccess
            if (written) {
                terminalResult = payload
                terminalEventPublished = true
                if (!preserveRecoveryReceipt) {
                    runCatching {
                        NativeIndex.markRecoveryEventPublished(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                    }.onFailure { Log.w(TAG, "Recovery event was published but its receipt marker could not be persisted.", it) }
                }
            }
            return written
        }

            try {
                NativeIndex.markGenerationRunning(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                NativeMailbox.write(appContext, JSONObject().put("type", "saf_scan_progress")
                    .put("requestId", requestId ?: "")
                    .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId).put("phase", "started")
                        .put("source", "saf").put("generationId", NativeIndex.generationId(NativeIndex.SOURCE_SAF, scanKey, generationId))))
                val onScanProgress: (JSONObject) -> Unit = { progress ->
                    NativeScanController.updateProgress(scanId, progress)
                    NativeMailbox.write(
                        appContext,
                        JSONObject()
                            .put("type", "saf_scan_progress")
                            .put(
                                "payload",
                                progress
                                    .put("treeUri", reference)
                                    .put("scanId", scanId)
                                    .put("requestId", requestId ?: "")
                                    .put("phase", "scanning")
                            )
                    )
                }
                val shouldCancelScan: () -> Boolean = { NativeScanController.isCancelled(scanId) }
                val result = SafScanner.scan(
                    appContext,
                    treeUri,
                    onScanProgress,
                    shouldCancelScan,
                    scanId,
                ) { batch ->
                    NativeScanPublisher.publish(
                        appContext,
                        "saf_scan_batch",
                        "saf",
                        scanId,
                        requestId,
                        "root",
                        reference,
                        scanKey,
                        generationId,
                        batch,
                    )
                }
                terminalResult = result
                val partial = result.optBoolean("partial")
                val rawScanStatus = result.optString("status").uppercase()
                val scanStatus = when {
                    rawScanStatus in setOf(
                        SafScanner.STATUS_REVOKED,
                        SafScanner.STATUS_UNAVAILABLE,
                        SafScanner.STATUS_CANCELLED,
                        SafScanner.STATUS_FAILED,
                    ) -> rawScanStatus
                    partial || rawScanStatus == SafScanner.STATUS_PARTIAL -> SafScanner.STATUS_PARTIAL
                    rawScanStatus in setOf(
                        SafScanner.STATUS_EMPTY_COMPLETE,
                        SafScanner.STATUS_COMPLETED,
                    ) -> rawScanStatus
                    else -> SafScanner.STATUS_FAILED
                }
                val status = when (scanStatus) {
                    SafScanner.STATUS_REVOKED, SafScanner.STATUS_UNAVAILABLE -> NativeIndex.STATUS_UNAVAILABLE
                    SafScanner.STATUS_CANCELLED -> NativeIndex.STATUS_CANCELLED
                    SafScanner.STATUS_PARTIAL -> NativeIndex.STATUS_PARTIAL
                    SafScanner.STATUS_EMPTY_COMPLETE -> NativeIndex.STATUS_EMPTY_COMPLETE
                    SafScanner.STATUS_COMPLETED -> NativeIndex.STATUS_COMPLETED
                    else -> NativeIndex.STATUS_FAILED
                }
                val finished = NativeIndex.finishGeneration(
                    appContext,
                    NativeIndex.SOURCE_SAF,
                    scanKey,
                    generationId,
                    status,
                    JSONObject()
                        .put("treeUri", reference)
                        .put("scopeRef", reference)
                        .put("requestId", requestId ?: "")
                        .put("stats", result.optJSONObject("stats") ?: JSONObject())
                        .put("status", status)
                        .put("scanId", scanId),
                )
                scanGenerationFinished = true
                publishedCompleteGeneration = status == NativeIndex.STATUS_COMPLETED || status == NativeIndex.STATUS_EMPTY_COMPLETE
                var nativeIndexingFailed = false
                var nativeIndexingCancelled = false
                var cancellationAfterCommittedIndex = false
                var nativeIndexingStatus = "SKIPPED_INCOMPLETE"
                // Retain this generation's verified positive snapshot for native recovery only.
                // It is never transferred to Python as a catalog mutation fallback.
                var fallbackDocuments: org.json.JSONArray? = null
                try {
                        val treeIdentity = SafScanner.treeIdentity(treeUri)
                        val complete = status == NativeIndex.STATUS_COMPLETED || status == NativeIndex.STATUS_EMPTY_COMPLETE
                        val scanDocuments = if (complete) {
                            NativeIndex.committedDocuments(appContext, NativeIndex.SOURCE_SAF, scanKey)
                        } else {
                            NativeIndex.stagedDocuments(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                        } ?: throw IllegalStateException("No verified scan snapshot is available for indexing.")
                        fallbackDocuments = scanDocuments
                        val errors = result.optJSONObject("stats")?.optJSONArray("errors")
                        val failedLocations = if (errors != null && errors.length() > 0) {
                            buildSet { for (i in 0 until errors.length()) add(errors.optString(i).ifBlank { "unknown-location" }) }
                        } else emptySet()
                        val discovered = discoveredMediaFromSnapshot(scanDocuments, reference)
                        val authorization = SafScanner.inspectTree(appContext, treeUri, requirePersisted = true)
                        val authorizationValid = authorization.optString("status") in setOf(
                            SafScanner.STATUS_COMPLETED, SafScanner.STATUS_EMPTY_COMPLETE,
                        )
                        val snapshot = NativeLibraryScanSnapshot(
                                operationId = scanId,
                                source = NativeIndex.SOURCE_SAF,
                                scopeRoot = treeIdentity.documentId,
                                expectedLocations = setOf(treeIdentity.documentId),
                                processedLocations = if (complete) setOf(treeIdentity.documentId) else emptySet(),
                                failedLocations = failedLocations,
                                unprocessedLocations = if (complete) emptySet() else setOf(treeIdentity.documentId),
                                status = status,
                                complete = complete,
                                cancelled = scanStatus == SafScanner.STATUS_CANCELLED,
                                authorizationValid = authorizationValid,
                                items = discovered,
                                scopeReference = reference,
                                scanGeneration = generationId,
                            )
                        nativeIndexingCheckpointAttempted.set(true)
                        check(
                            NativeIndex.markNativeCatalogIndexing(
                                appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                            )
                        ) { "Could not persist the native catalog transaction start receipt." }
                        val indexReport = ReiAnixAppDependencies.from(appContext).libraryService.indexDiscoveredMedia(snapshot) { outcome, report ->
                            indexTransactionOutcome.set(outcome)
                            when (outcome) {
                                ReiAnixSqliteTransactionOutcome.COMMITTED -> {
                                    if (report != null) {
                                        committedIndexReport.set(report)
                                        if (!NativeIndex.markNativeCatalogCommitted(
                                                appContext, NativeIndex.SOURCE_SAF, scanKey, generationId, report,
                                            )
                                        ) {
                                            Log.e(TAG, "Native SQLite commit succeeded but its durable commit receipt could not be recorded.")
                                        }
                                    } else {
                                        Log.e(TAG, "SQLite reported COMMITTED without a report; fallback remains disabled.")
                                        NativeIndex.markNativeCatalogCommittedWithoutReport(
                                            appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                            "SQLite commit was confirmed but its index report was unavailable.",
                                        )
                                    }
                                }
                                ReiAnixSqliteTransactionOutcome.ROLLED_BACK -> {
                                    val persisted = runCatching {
                                        NativeIndex.markNativeCatalogNotCommitted(
                                            appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                            "SQLite transaction rolled back before a commit was confirmed.",
                                        )
                                    }.onFailure { Log.w(TAG, "Could not persist the confirmed rollback receipt.", it) }
                                        .getOrDefault(false)
                                    rollbackReceiptPersisted.set(persisted)
                                    if (!persisted) {
                                        Log.w(TAG, "The confirmed rollback receipt does not match the active generation; fallback ownership stays disabled.")
                                    }
                                }
                                ReiAnixSqliteTransactionOutcome.UNKNOWN -> {
                                    runCatching {
                                        NativeIndex.markNativeCatalogOutcomeUnknown(
                                            appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                            "SQLite transaction finalization did not provide a definitive outcome.",
                                        )
                                    }.onFailure { Log.w(TAG, "Could not persist the unknown transaction outcome.", it) }
                                }
                            }
                        }
                        nativeIndexingStatus = if (indexReport.reconciliationAuthorized) "COMPLETED" else "PARTIAL"
                        if (complete && !indexReport.reconciliationAuthorized) {
                            nativeIndexingFailed = true
                            val partialStats = result.optJSONObject("stats") ?: JSONObject()
                            partialStats.put("status", SafScanner.STATUS_PARTIAL).put("partial", true)
                            val partialErrors = partialStats.optJSONArray("errors")
                                ?: JSONArray().also { partialStats.put("errors", it) }
                            val reconciliationMessage =
                                "native SQLite preserved positive discoveries but did not authorize full reconciliation; previous rows were preserved"
                            if ((0 until partialErrors.length()).none { partialErrors.optString(it) == reconciliationMessage }) {
                                partialErrors.put(reconciliationMessage)
                            }
                            result.put("stats", partialStats)
                        }
                        result.put("nativeLibraryIndex", JSONObject()
                            .put("status", nativeIndexingStatus)
                            .put("inserted", indexReport.inserted)
                            .put("updated", indexReport.updated)
                            .put("unchanged", indexReport.unchanged)
                            .put("ambiguous", indexReport.ambiguous)
                            .put("rejectedOutOfScope", indexReport.rejectedOutOfScope)
                            .put("reconciledMissing", indexReport.reconciledMissing)
                            .put("reconciliationAuthorized", indexReport.reconciliationAuthorized))
                        // SQLite indexing above is the persistence owner for this scan,
                        // even when conservative policy blocks missing-state reconciliation.
                        // The host may still refresh its read-only UI projection, but must not
                        // invoke LibraryStore's ingestion/finalization writer for this run.
                        result.put("nativeLibraryIndexOwner", true)
                    } catch (indexError: Exception) {
                        val committedReport = committedIndexReport.get()
                        if (committedReport != null) {
                            // database.transaction returned after endTransaction(), so canonical
                            // SQLite is already committed. A cancelled withContext result must not
                            // transfer ownership to Python and create a second writer.
                            nativeIndexingCancelled = indexError is CancellationException
                            cancellationAfterCommittedIndex = nativeIndexingCancelled
                            nativeIndexingFailed = publishedCompleteGeneration && !committedReport.reconciliationAuthorized
                            nativeIndexingStatus = if (committedReport.reconciliationAuthorized) "COMPLETED" else "PARTIAL"
                            Log.w(TAG, "Native SQLite commit was confirmed although its result was not delivered to the caller.", indexError)
                            result.put("nativeLibraryIndex", JSONObject()
                                .put("status", nativeIndexingStatus)
                                .put("inserted", committedReport.inserted)
                                .put("updated", committedReport.updated)
                                .put("unchanged", committedReport.unchanged)
                                .put("ambiguous", committedReport.ambiguous)
                                .put("rejectedOutOfScope", committedReport.rejectedOutOfScope)
                                .put("reconciledMissing", committedReport.reconciledMissing)
                                .put("reconciliationAuthorized", committedReport.reconciliationAuthorized)
                                .put("commitOutcome", "COMMITTED")
                                .put("resultDeliveryCancelled", nativeIndexingCancelled))
                                .put("nativeLibraryIndexOwner", true)
                        } else {
                            nativeIndexingCancelled = indexError is CancellationException
                            nativeIndexingFailed = true
                            Log.e(TAG, "Native SQLite library indexing did not return a confirmed commit report.", indexError)
                            val transactionOutcome = indexTransactionOutcome.get()
                            val rollbackConfirmed = transactionOutcome == ReiAnixSqliteTransactionOutcome.ROLLED_BACK
                            val committedWithoutReport = transactionOutcome == ReiAnixSqliteTransactionOutcome.COMMITTED
                            var recoverablePositiveSnapshot = rollbackConfirmed &&
                                rollbackReceiptPersisted.get() && (fallbackDocuments?.length() ?: 0) > 0

                            if (!nativeIndexingCheckpointAttempted.get() && fallbackDocuments != null) {
                                // Nothing entered the canonical SQLite transaction, so the verified
                                // snapshot remains safe to retry as positive-only native indexing.
                                rollbackReceiptPersisted.set(
                                    runCatching {
                                        NativeIndex.markNativeCatalogNotCommitted(
                                            appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                            "Canonical SQLite indexing did not start; preserve verified discoveries for native recovery.",
                                        )
                                    }.onFailure { Log.w(TAG, "Could not persist the pre-transaction native recovery receipt.", it) }
                                        .getOrDefault(false),
                                )
                                recoverablePositiveSnapshot = rollbackReceiptPersisted.get() && (fallbackDocuments?.length() ?: 0) > 0
                            }

                            nativeIndexingStatus = when {
                                committedWithoutReport -> "COMMITTED_REPORT_UNAVAILABLE"
                                rollbackConfirmed && nativeIndexingCancelled -> "CANCELLED_AFTER_ROLLBACK"
                                rollbackConfirmed -> "FAILED_ROLLED_BACK"
                                else -> "OUTCOME_UNKNOWN"
                            }

                            if (!rollbackConfirmed && !committedWithoutReport) {
                                runCatching {
                                    NativeIndex.markNativeCatalogOutcomeUnknown(
                                        appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                        indexError.message ?: "SQLite transaction outcome could not be confirmed.",
                                    )
                                }.onFailure { Log.w(TAG, "Could not persist the conservative transaction outcome.", it) }
                            }

                            val recovery = when {
                                recoverablePositiveSnapshot -> "NATIVE_POSITIVE_RECOVERY_PENDING"
                                rollbackConfirmed -> "ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED"
                                committedWithoutReport -> "NATIVE_COMMIT_CONFIRMED_REPORT_UNAVAILABLE"
                                else -> "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED"
                            }
                            val recoveryMessage = when {
                                recoverablePositiveSnapshot && nativeIndexingCancelled ->
                                    "Native SQLite rolled back after cancellation; verified discoveries were retained for a later native positive-only recovery."
                                recoverablePositiveSnapshot ->
                                    "Native SQLite rolled back; verified discoveries were retained for a later native positive-only recovery."
                                rollbackConfirmed ->
                                    "Native SQLite rolled back but the matching recovery receipt or verified snapshot is unavailable; controlled rescan required."
                                committedWithoutReport ->
                                    "Native SQLite committed but its report is unavailable; catalog preserved and controlled rescan required."
                                else ->
                                    "Native SQLite transaction outcome is unknown; no data was transferred to Python and the catalog was preserved."
                            }
                            val failureStats = result.optJSONObject("stats") ?: JSONObject()
                            failureStats.put("status", SafScanner.STATUS_PARTIAL).put("partial", true)
                            val failureErrors = failureStats.optJSONArray("errors")
                                ?: JSONArray().also { failureStats.put("errors", it) }
                            failureErrors.put(recoveryMessage)
                            result.put("stats", failureStats)
                                .put("cancelled", nativeIndexingCancelled)
                            result.put("nativeLibraryIndex", JSONObject()
                                .put("status", nativeIndexingStatus)
                                .put("errorType", indexError::class.java.simpleName)
                                .put("commitOutcome", transactionOutcome?.name ?: "UNKNOWN")
                                .put("recovery", recovery))
                                .put("nativeLibraryIndexOwner", true)
                                .put("documents", JSONArray())

                            // The diagnostic is secondary; the terminal event remains the recovery
                            // channel for either a confirmed rollback or an uncertain transaction.
                            runCatching {
                                NativeMailbox.write(appContext, JSONObject()
                                    .put("type", "native_library_index_error")
                                    .put("requestId", requestId ?: "")
                                    .put("payload", JSONObject().put("scanId", scanId)
                                        .put("scope", runCatching { SafScanner.treeIdentity(treeUri).identity }.getOrDefault(""))
                                        .put("status", nativeIndexingStatus)
                                        .put("commitOutcome", transactionOutcome?.name ?: "UNKNOWN")
                                        .put("errorType", indexError::class.java.simpleName)))
                            }.onFailure { mailboxError ->
                                Log.w(TAG, "Could not publish native index diagnostic; terminal SAF event retains recovery state.", mailboxError)
                            }
                        }
                    }
                result.put("scanGeneration", generationId)
                    .put("generationId", NativeIndex.generationId(NativeIndex.SOURCE_SAF, scanKey, generationId))
                    .put("generationStatus", status)
                    .put("batchCount", finished.optInt("batchCount", 0))
                    .put("processed", finished.optInt("processed", 0))
                    .put("nativeDuplicates", finished.optInt("duplicates", 0))
                    .put("requestId", requestId ?: "")
                    .put("scanId", scanId)
                    .put("scopeKind", "root")
                    .put("scopeRef", reference)
                    .put("source", "saf")
                    .put("scope", SafScanner.treeIdentity(treeUri).identity)
                    .put("volumeId", result.optString("volumeId"))
                    .put("status", if (nativeIndexingFailed && scanStatus in setOf(SafScanner.STATUS_COMPLETED, SafScanner.STATUS_EMPTY_COMPLETE)) SafScanner.STATUS_PARTIAL else scanStatus)
                result.put("nativeScanRunOwner", persistNativeScanRun(result))
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", result))
                terminalEventPublished = true
                val nativePositiveRecoveryPending =
                    result.optJSONObject("nativeLibraryIndex")?.optString("recovery") == "NATIVE_POSITIVE_RECOVERY_PENDING"
                if (!nativePositiveRecoveryPending) {
                    runCatching {
                        NativeIndex.markRecoveryEventPublished(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                    }.onFailure { Log.w(TAG, "Terminal SAF event was published but its receipt marker could not be persisted.", it) }
                }
                NativeScanController.complete(
                    scanId,
                    when {
                        nativeIndexingCancelled && !cancellationAfterCommittedIndex -> SafScanner.STATUS_CANCELLED
                        nativeIndexingFailed && scanStatus in setOf(SafScanner.STATUS_COMPLETED, SafScanner.STATUS_EMPTY_COMPLETE) -> SafScanner.STATUS_PARTIAL
                        else -> scanStatus.ifBlank { SafScanner.STATUS_FAILED }
                    },
                    result,
                )
                if (nativeIndexingCancelled) {
                    throw CancellationException("SAF native indexing cancellation was handled according to the confirmed transaction outcome.")
                }
            } catch (exception: CancellationException) {
                if (!terminalEventPublished) {
                    val canRecover = canRecoverInKotlin()
                    val snapshotAvailable = canRecover && recoveryDocuments()?.length()?.let { it > 0 } == true
                    val message = when {
                        !canRecover && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.COMMITTED ->
                            "SAF scan cancelled after confirmed SQLite commit; fallback disabled."
                        !canRecover && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.ROLLED_BACK ->
                            "SAF scan cancelled after rollback, but the matching recovery receipt was not persisted; fallback disabled; rescan required."
                        !canRecover ->
                            "SAF scan cancelled with an unconfirmed SQLite transaction outcome; catalog preserved; rescan required."
                        snapshotAvailable ->
                            "SAF scan cancelled; verified discoveries were retained for native positive-only recovery."
                        else ->
                            "SAF scan cancelled before a verified discovery snapshot was available; catalog preserved; rescan required."
                    }
                    val published = publishRecoveryTerminal(message, cancelled = true)
                    if (published && committedIndexReport.get() == null && !scanGenerationFinished) {
                        runCatching {
                            NativeIndex.failGeneration(
                                appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                "SAF scan cancelled after publishing a non-destructive recovery receipt.",
                                JSONObject().put("treeUri", reference),
                            )
                        }.onFailure { Log.w(TAG, "Could not finalize cancelled SAF generation metadata.", it) }
                    }
                    if (committedIndexReport.get() == null) {
                        NativeScanController.complete(
                            scanId,
                            "CANCELLED",
                            terminalResult ?: JSONObject().put("cancelled", true),
                        )
                    }
                }
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "SAF scan failed", exception)
                if (!terminalEventPublished) {
                    val canRecover = canRecoverInKotlin()
                    val snapshotAvailable = canRecover && recoveryDocuments()?.length()?.let { it > 0 } == true
                    val message = when {
                        !canRecover && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.COMMITTED ->
                            "SAF scan failed after confirmed SQLite commit; fallback disabled."
                        !canRecover && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.ROLLED_BACK ->
                            "SAF scan failed after rollback, but the matching recovery receipt was not persisted; fallback disabled; rescan required."
                        !canRecover ->
                            "SAF scan failed with an unconfirmed SQLite transaction outcome; catalog preserved; rescan required."
                        snapshotAvailable ->
                            "SAF scan failed; verified discoveries were retained for native positive-only recovery."
                        else ->
                            "SAF scan failed before a verified discovery snapshot was available; catalog preserved; rescan required."
                    }
                    val published = publishRecoveryTerminal(message, cancelled = false)
                    if (published && committedIndexReport.get() == null && !scanGenerationFinished) {
                        runCatching {
                            NativeIndex.failGeneration(
                                appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                                exception.message ?: "SAF scan failed after publishing a non-destructive recovery receipt.",
                                JSONObject().put("treeUri", reference),
                            )
                        }.onFailure { Log.w(TAG, "Could not finalize failed SAF generation metadata.", it) }
                    }
                    NativeScanController.complete(
                        scanId,
                        when {
                            committedIndexReport.get() != null -> terminalResult?.optString("status").orEmpty().ifBlank { SafScanner.STATUS_COMPLETED }
                            published -> SafScanner.STATUS_PARTIAL
                            else -> SafScanner.STATUS_FAILED
                        },
                        terminalResult ?: JSONObject().put("errorType", exception::class.java.simpleName),
                    )
                }
            } finally {
                NativeScanController.finish(scanId)
            }

    }

    suspend fun runBroadScan(
        context: Context,
        scanId: String,
        requestId: String?,
    ) {
        val appContext = context.applicationContext

            try {
                val result = BroadStorageScanner.scan(
                    appContext,
                    { progress ->
                        NativeScanController.updateProgress(scanId, progress)
                        NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_scan_progress")
                            .put("payload", progress.put("scanId", scanId).put("requestId", requestId ?: "").put("scopeKind", "global")))
                    },
                    { NativeScanController.isCancelled(scanId) },
                    scanId,
                    onBatch = { batch ->
                    val volume = batch.optString("volumeId")
                    NativeScanPublisher.publish(
                        appContext,
                        "broad_storage_scan_batch",
                        "broad_storage",
                        scanId,
                        requestId,
                        "volume",
                        volume,
                        "broad-storage:" + volume,
                        batch.optLong("generation", 0L),
                        batch,
                    )
                    }
                )
                val terminalStatus = ScanResultClassifier.classifyBroadStorage(
                    result.optJSONObject("stats")?.optString("status"),
                    cancelled = result.optBoolean("cancelled"),
                    partial = result.optBoolean("partial"),
                )
                val generationStatus = when (terminalStatus) {
                    "CANCELLED" -> NativeIndex.STATUS_CANCELLED
                    "PARTIAL" -> NativeIndex.STATUS_PARTIAL
                    "EMPTY_COMPLETE" -> NativeIndex.STATUS_EMPTY_COMPLETE
                    "COMPLETED" -> NativeIndex.STATUS_COMPLETED
                    else -> NativeIndex.STATUS_FAILED
                }
                result.put("requestId", requestId ?: "")
                    .put("scanId", scanId).put("scopeKind", "global").put("scopeRef", "broad-storage")
                    .put("generationId", "native-scoped")
                    .put("generationStatus", generationStatus)
                // BroadStorage is diagnostic-only under the existing library-source contract.
                result.put("nativeLibraryIndexOwner", true)
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "broad_storage_scan")
                    .put("requestId", requestId ?: "").put("payload", result))
                NativeScanController.complete(scanId, terminalStatus, result)
            } catch (exception: CancellationException) {
                NativeScanController.complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Broad storage scan failed", exception)
                NativeScanController.complete(scanId, "FAILED", JSONObject().put("errorType", exception::class.java.simpleName))
                NativeIndex.failActiveGenerations(
                    appContext,
                    NativeIndex.SOURCE_BROAD,
                    exception.message ?: "Broad storage scan failed",
                )
                NativeMailbox.write(appContext, JSONObject().put("type", "broad_storage_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível concluir a varredura do armazenamento local.")
                    .put("payload", JSONObject()
                        .put("source", BroadStorageScanner.SOURCE)
                        .put("scanId", scanId)
                        .put("status", NativeIndex.STATUS_FAILED)
                        .put("generationStatus", NativeIndex.STATUS_FAILED)
                        .put("partial", true)
                        .put("errorType", exception::class.java.simpleName)
                        .put("error", exception.message ?: "Broad storage scan failed")))
            } finally {
                NativeScanController.finish(scanId)
            }

    }


    /**
     * MediaStore can enter the catalog only inside a currently persisted, readable SAF
     * library root. This keeps Android's broader media permission from silently expanding
     * the user's library to unrelated folders and gives SAF/MediaStore a shared path identity.
     */
    private suspend fun configuredMediaStoreScopes(context: Context): List<MediaStoreScanner.LibraryScope> {
        val dependencies = ReiAnixAppDependencies.from(context)
        val folders = dependencies.sqliteLibraryRepository.listFolders()
        return folders.mapNotNull { folder ->
            val reference = folder.path.trim()
            if (reference.isBlank() || !folder.kind.equals("saf", ignoreCase = true) ||
                folder.authorization.equals("revoked", ignoreCase = true) ||
                folder.authorization.equals("denied", ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            try {
                val treeUri = Uri.parse(reference)
                val identity = SafScanner.treeIdentity(treeUri)
                // Only ExternalStorageProvider document IDs map safely to MediaStore's
                // volume-relative RELATIVE_PATH. Other providers continue through SAF.
                if (identity.authority != "com.android.externalstorage.documents") {
                    Log.i(TAG, "MediaStore catalog scope skipped for non-filesystem SAF provider authority=" + identity.authority)
                    return@mapNotNull null
                }
                val authorization = SafScanner.inspectTree(context, treeUri, requirePersisted = true)
                if (authorization.optString("status") != SafScanner.STATUS_COMPLETED) {
                    Log.w(TAG, "MediaStore catalog scope skipped because the persisted SAF root is not readable: " + reference)
                    return@mapNotNull null
                }
                MediaStoreScanner.LibraryScope(
                    authority = identity.authority,
                    volumeId = identity.volumeId,
                    treeDocumentId = identity.documentId,
                    treeUri = reference,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not validate a persisted SAF root for MediaStore indexing.", error)
                null
            }
        }.distinctBy { listOf(it.authority, it.volumeId, it.treeDocumentId).joinToString("|") }
    }

    /**
     * Stages MediaStore discoveries natively, indexes each volume snapshot in the same
     * canonical SQLite repository as SAF, and publishes a terminal UI state only after
     * the database transaction outcome is known. Partial scopes may add positive items but
     * can never reconcile missing rows.
     */
    suspend fun runMediaStoreScan(
        context: Context,
        scanId: String,
        requestId: String?,
    ) {
        val appContext = context.applicationContext
        val dependencies = ReiAnixAppDependencies.from(appContext)
        try {
            val libraryScopes = configuredMediaStoreScopes(appContext)
            NativeMailbox.write(
                appContext,
                JSONObject().put("type", "mediastore_scan_progress")
                    .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)
                        .put("scanId", scanId).put("requestId", requestId ?: "")
                        .put("phase", "started").put("configuredLibraryScopes", libraryScopes.size)),
            )
            val result = MediaStoreScanner.scan(
                appContext,
                { progress ->
                    NativeScanController.updateProgress(scanId, progress)
                    NativeMailbox.write(
                        appContext,
                        JSONObject().put("type", "mediastore_scan_progress")
                            .put("payload", progress.put("scanId", scanId)
                                .put("requestId", requestId ?: "").put("scopeKind", "volume")),
                    )
                },
                { NativeScanController.isCancelled(scanId) },
                scanId,
                onBatch = { batch ->
                    val volume = batch.optString("volumeId")
                    NativeScanPublisher.publish(
                        appContext,
                        "mediastore_scan_batch",
                        NativeIndex.SOURCE_MEDIASTORE,
                        scanId,
                        requestId,
                        "volume",
                        volume,
                        "mediastore:" + volume,
                        batch.optLong("generation", NativeIndex.cachedGeneration(appContext, "mediastore:" + volume)),
                        batch,
                    )
                },
                libraryScopes = libraryScopes,
            )
            result.put("requestId", requestId ?: "")
                .put("scanId", scanId)
                .put("scopeKind", "volume")
                .put("scopeRef", MediaStoreScanner.SOURCE)

            val stats = result.optJSONObject("stats") ?: JSONObject()
            val rejection = stats.optString("rejection").trim()
            if (rejection == "NO_CONFIGURED_LIBRARY_SOURCE") {
                stats.optJSONArray("errors")?.put(
                    "Selecione uma pasta da biblioteca e mantenha sua autorização SAF válida para indexar os vídeos dessa pasta pelo MediaStore.",
                )
                result.put("message", "Nenhuma pasta SAF autorizada está configurada para delimitar a indexação MediaStore.")
            } else if (rejection == "SAFE_LIBRARY_SCOPE_REQUIRES_API_29") {
                stats.optJSONArray("errors")?.put(
                    "Neste Android, a indexação MediaStore não consegue provar a pertença à pasta autorizada. A descoberta SAF continua disponível.",
                )
                result.put("message", "A indexação MediaStore delimitada por pasta requer Android 10 (API 29) ou posterior.")
            }

            val indexSummary = indexMediaStoreSnapshots(
                appContext = appContext,
                scanId = scanId,
                result = result,
                libraryScopes = libraryScopes,
            )
            result.put("nativeCatalogIndex", indexSummary)
                .put("nativeLibraryIndexOwner", true)

            val discoveryStatus = stats.optString("status").trim().uppercase()
            val cancelled = result.optBoolean("cancelled") ||
                discoveryStatus == NativeIndex.STATUS_CANCELLED
            val hasDiscoveryBlock = rejection.isNotBlank()
            val indexFailures = indexSummary.optInt("failedVolumes", 0)
            val receiptWarnings = indexSummary.optInt("receiptWarnings", 0)
            val indexedVolumes = indexSummary.optInt("committedVolumes", 0) +
                indexSummary.optInt("reusedVolumes", 0)
            val classifiedDiscoveryStatus = ScanResultClassifier.classifyMediaStore(
                stats.optString("status"),
                cancelled = cancelled,
                partial = result.optBoolean("partial"),
            )
            val terminalStatus = when {
                cancelled -> "CANCELLED"
                hasDiscoveryBlock -> "FAILED"
                indexFailures > 0 || receiptWarnings > 0 ->
                    if (indexedVolumes > 0) "PARTIAL" else "FAILED"
                else -> classifiedDiscoveryStatus
            }
            if (indexFailures > 0 || receiptWarnings > 0 || terminalStatus == "PARTIAL") {
                result.put("partial", true)
            }
            result.put("status", terminalStatus)
                .put("nativeCatalogIndexingStatus", when {
                    indexFailures > 0 -> "PARTIAL"
                    receiptWarnings > 0 -> "COMMIT_CONFIRMED_RECEIPT_WARNING"
                    indexedVolumes > 0 -> "COMMITTED_OR_UNCHANGED"
                    hasDiscoveryBlock -> "BLOCKED"
                    else -> "NO_CATALOG_CHANGES"
                })

            val indexReport = JSONObject(indexSummary.toString())
            val persistedStats = JSONObject(stats.toString())
                .put("nativeCatalogIndex", indexReport)
            try {
                dependencies.libraryService.finishNativeScanRun(
                    scanId = scanId,
                    requestId = requestId,
                    sourceKind = NativeIndex.SOURCE_MEDIASTORE,
                    scopeKind = "volume",
                    scopeRef = MediaStoreScanner.SOURCE,
                    nativeGeneration = null,
                    generationId = null,
                    status = terminalStatus,
                    stats = persistedStats,
                    indexReport = indexReport,
                    startedAtMillis = NativeScanController.snapshot(scanId)?.startedAtMillis,
                )
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            } catch (historyError: Exception) {
                // Scan-history failure must not undo or misrepresent a confirmed catalog commit.
                Log.e(TAG, "MediaStore catalog transaction completed but scan history could not be finalized.", historyError)
                result.put("scanHistoryWarning", "A biblioteca foi processada, mas o histórico da varredura não pôde ser atualizado.")
            }

            if (stats.optString("status").equals(NativeIndex.STATUS_WAITING_FOR_MEDIASTORE, ignoreCase = true)) {
                NativeMailbox.write(
                    appContext,
                    JSONObject().put("type", "diagnostic")
                        .put("payload", JSONObject().put("event", "WAITING_FOR_MEDIASTORE")
                            .put("scanId", scanId).put("requestId", requestId ?: "")),
                )
                MediaStoreRetryScheduler.schedule(
                    appContext,
                    reason = "media_store_indexing_completed",
                    triggerRequestId = requestId,
                )
            }

            // Mailbox consumers are compatibility/diagnostic subscribers. SQLite and the
            // NativeScanController outcome have already been finalized at this point.
            NativeMailbox.write(
                appContext,
                JSONObject().put("type", "mediastore_scan")
                    .put("requestId", requestId ?: "").put("payload", result),
            )
            NativeScanController.complete(scanId, terminalStatus, result)
        } catch (exception: CancellationException) {
            runCatching {
                dependencies.libraryService.finishNativeScanRun(
                    scanId = scanId,
                    requestId = requestId,
                    sourceKind = NativeIndex.SOURCE_MEDIASTORE,
                    scopeKind = "volume",
                    scopeRef = MediaStoreScanner.SOURCE,
                    nativeGeneration = null,
                    generationId = null,
                    status = "CANCELLED",
                    stats = JSONObject().put("cancelled", true),
                    indexReport = null,
                    startedAtMillis = NativeScanController.snapshot(scanId)?.startedAtMillis,
                )
            }.onFailure { Log.w(TAG, "Could not finalize cancelled MediaStore scan history.", it) }
            NativeScanController.complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "MediaStore scan or catalog indexing failed", exception)
            NativeIndex.failActiveGenerations(
                appContext,
                NativeIndex.SOURCE_MEDIASTORE,
                exception.message ?: "MediaStore scan failed",
            )
            runCatching {
                dependencies.libraryService.finishNativeScanRun(
                    scanId = scanId,
                    requestId = requestId,
                    sourceKind = NativeIndex.SOURCE_MEDIASTORE,
                    scopeKind = "volume",
                    scopeRef = MediaStoreScanner.SOURCE,
                    nativeGeneration = null,
                    generationId = null,
                    status = "FAILED",
                    stats = JSONObject().put("errorType", exception::class.java.simpleName),
                    indexReport = null,
                    startedAtMillis = NativeScanController.snapshot(scanId)?.startedAtMillis,
                )
            }.onFailure { Log.w(TAG, "Could not finalize failed MediaStore scan history.", it) }
            NativeScanController.complete(scanId, "FAILED", JSONObject().put("errorType", exception::class.java.simpleName))
            NativeMailbox.write(
                appContext,
                JSONObject().put("type", "mediastore_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível atualizar os vídeos da biblioteca.")
                    .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE)
                        .put("scanId", scanId).put("status", NativeIndex.STATUS_FAILED)
                        .put("access", MediaStoreScanner.accessLevel(appContext))),
            )
        } finally {
            NativeScanController.finish(scanId)
        }
    }

    private suspend fun indexMediaStoreSnapshots(
        appContext: Context,
        scanId: String,
        result: JSONObject,
        libraryScopes: List<MediaStoreScanner.LibraryScope>,
    ): JSONObject {
        val summary = JSONObject()
            .put("source", NativeIndex.SOURCE_MEDIASTORE)
            .put("attemptedVolumes", 0)
            .put("committedVolumes", 0)
            .put("reusedVolumes", 0)
            .put("failedVolumes", 0)
            .put("receiptWarnings", 0)
            .put("inserted", 0)
            .put("updated", 0)
            .put("unchanged", 0)
            .put("ambiguous", 0)
            .put("rejectedOutOfScope", 0)
            .put("reconciledMissing", 0)
            .put("volumes", JSONArray())
            .put("errors", JSONArray())
        val allVolumes = result.optJSONArray("volumeScopes") ?: JSONArray()
        val stats = result.optJSONObject("stats") ?: JSONObject()
        val canReadMediaStore = stats.optBoolean("canScan", false)
        val dependencies = ReiAnixAppDependencies.from(appContext)

        for (index in 0 until allVolumes.length()) {
            val volume = allVolumes.optJSONObject(index) ?: continue
            val volumeName = volume.optString("volumeId").trim()
            val scopeKey = "mediastore:" + volumeName
            val generation = volume.optLong("scanGeneration", 0L)
            val volumeResult = JSONObject().put("volumeId", volumeName)
                .put("scopeKey", scopeKey).put("generation", generation)
                .put("complete", volume.optBoolean("complete"))
                .put("reused", volume.optBoolean("reused"))
            summary.put("attemptedVolumes", summary.optInt("attemptedVolumes") + 1)

            if (volumeName.isBlank() || generation <= 0L) {
                summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                summary.optJSONArray("errors")?.put("MediaStore retornou uma geração inválida para o volume " + volumeName + ".")
                volumeResult.put("status", "INVALID_GENERATION")
                summary.optJSONArray("volumes")?.put(volumeResult)
                continue
            }
            if (volume.optBoolean("reused")) {
                // canReuseMediaStoreVolume now requires a matching scope signature and a
                // confirmed COMMITTED receipt for this exact generation.
                summary.put("reusedVolumes", summary.optInt("reusedVolumes") + 1)
                volumeResult.put("status", "UNCHANGED_CACHED").put("catalogWriteOutcome", "COMMITTED")
                summary.optJSONArray("volumes")?.put(volumeResult)
                continue
            }
            if (!canReadMediaStore || libraryScopes.isEmpty()) {
                summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                summary.optJSONArray("errors")?.put("A permissão MediaStore ou a pasta SAF não estava disponível ao persistir o volume " + volumeName + ".")
                volumeResult.put("status", "ACCESS_NOT_VALID")
                summary.optJSONArray("volumes")?.put(volumeResult)
                continue
            }

            val complete = volume.optBoolean("complete")
            val documents = if (complete) {
                NativeIndex.committedDocuments(appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey)
            } else {
                NativeIndex.stagedDocuments(appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation)
            }
            if (documents == null) {
                summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                summary.optJSONArray("errors")?.put("O snapshot MediaStore do volume " + volumeName + " não pôde ser validado para indexação.")
                volumeResult.put("status", "SNAPSHOT_UNAVAILABLE")
                summary.optJSONArray("volumes")?.put(volumeResult)
                continue
            }

            val status = volume.optString("status").trim().uppercase()
            val cancelled = result.optBoolean("cancelled") || status == NativeIndex.STATUS_CANCELLED
            val scopeErrors = volume.optJSONArray("errors")
            val failedLocations = if (scopeErrors != null && scopeErrors.length() > 0) {
                buildSet {
                    for (errorIndex in 0 until scopeErrors.length()) {
                        add(scopeErrors.optString(errorIndex).ifBlank { "media_store_query_error" })
                    }
                }
            } else emptySet()
            val snapshot = NativeLibraryScanSnapshot(
                operationId = scanId + ":" + volumeName,
                source = NativeIndex.SOURCE_MEDIASTORE,
                scopeRoot = volumeName,
                expectedLocations = setOf(volumeName),
                processedLocations = if (complete) setOf(volumeName) else emptySet(),
                failedLocations = failedLocations,
                unprocessedLocations = if (complete) emptySet() else setOf(volumeName),
                status = status,
                complete = complete,
                cancelled = cancelled,
                authorizationValid = canReadMediaStore && libraryScopes.isNotEmpty(),
                items = discoveredMediaFromSnapshot(documents, scopeKey),
                scopeReference = scopeKey,
                scanGeneration = generation,
            )
            if (!NativeIndex.markNativeCatalogIndexing(appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation)) {
                summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                summary.optJSONArray("errors")?.put("O recibo de início da escrita SQLite não pôde ser registrado para " + volumeName + ".")
                volumeResult.put("status", "INDEX_RECEIPT_REJECTED")
                summary.optJSONArray("volumes")?.put(volumeResult)
                continue
            }

            val outcome = AtomicReference<ReiAnixSqliteTransactionOutcome?>(null)
            val committedReport = AtomicReference<NativeLibraryIndexReport?>(null)
            val commitReceiptPersisted = AtomicBoolean(true)
            try {
                val report = dependencies.libraryService.indexDiscoveredMedia(snapshot) { transactionOutcome, transactionReport ->
                    outcome.set(transactionOutcome)
                    if (transactionReport != null) committedReport.set(transactionReport)
                    when (transactionOutcome) {
                        ReiAnixSqliteTransactionOutcome.COMMITTED -> {
                            val persisted = if (transactionReport != null) {
                                NativeIndex.markNativeCatalogCommitted(
                                    appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation, transactionReport,
                                )
                            } else {
                                NativeIndex.markNativeCatalogCommittedWithoutReport(
                                    appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation,
                                    "MediaStore SQLite commit was confirmed but its report was unavailable.",
                                )
                            }
                            if (!persisted) commitReceiptPersisted.set(false)
                        }
                        ReiAnixSqliteTransactionOutcome.ROLLED_BACK -> {
                            val persisted = NativeIndex.markNativeCatalogNotCommitted(
                                appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation,
                                "MediaStore SQLite transaction rolled back before a commit was confirmed.",
                            )
                            if (!persisted) commitReceiptPersisted.set(false)
                        }
                        ReiAnixSqliteTransactionOutcome.UNKNOWN -> {
                            val persisted = NativeIndex.markNativeCatalogOutcomeUnknown(
                                appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation,
                                "MediaStore SQLite transaction finalization did not provide a definitive outcome.",
                            )
                            if (!persisted) commitReceiptPersisted.set(false)
                        }
                        null -> commitReceiptPersisted.set(false)
                    }
                }
                if (outcome.get() != ReiAnixSqliteTransactionOutcome.COMMITTED) {
                    summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                    summary.optJSONArray("errors")?.put(
                        "A escrita SQLite do volume " + volumeName + " não teve resultado COMMITTED confirmado.",
                    )
                    volumeResult.put("status", "TRANSACTION_NOT_COMMITTED")
                } else {
                    val actualReport = committedReport.get() ?: report
                    summary.put("committedVolumes", summary.optInt("committedVolumes") + 1)
                    summary.put("inserted", summary.optInt("inserted") + actualReport.inserted)
                    summary.put("updated", summary.optInt("updated") + actualReport.updated)
                    summary.put("unchanged", summary.optInt("unchanged") + actualReport.unchanged)
                    summary.put("ambiguous", summary.optInt("ambiguous") + actualReport.ambiguous)
                    summary.put("rejectedOutOfScope", summary.optInt("rejectedOutOfScope") + actualReport.rejectedOutOfScope)
                    summary.put("reconciledMissing", summary.optInt("reconciledMissing") + actualReport.reconciledMissing)
                    volumeResult.put("status", "COMMITTED")
                        .put("catalogWriteOutcome", "COMMITTED")
                        .put("indexReport", JSONObject()
                            .put("inserted", actualReport.inserted)
                            .put("updated", actualReport.updated)
                            .put("unchanged", actualReport.unchanged)
                            .put("ambiguous", actualReport.ambiguous)
                            .put("rejectedOutOfScope", actualReport.rejectedOutOfScope)
                            .put("reconciledMissing", actualReport.reconciledMissing)
                            .put("reconciliationAuthorized", actualReport.reconciliationAuthorized))
                    if (!commitReceiptPersisted.get()) {
                        summary.put("receiptWarnings", summary.optInt("receiptWarnings") + 1)
                        summary.optJSONArray("errors")?.put("O commit SQLite foi confirmado, mas o recibo de catálogo não pôde ser persistido para " + volumeName + ".")
                    }
                }
            } catch (cancelledException: CancellationException) {
                throw cancelledException
            } catch (error: Exception) {
                val transactionOutcome = outcome.get()
                when (transactionOutcome) {
                    ReiAnixSqliteTransactionOutcome.COMMITTED -> {
                        // Do not retry or transfer ownership after a confirmed commit.
                        summary.put("committedVolumes", summary.optInt("committedVolumes") + 1)
                        summary.put("receiptWarnings", summary.optInt("receiptWarnings") + 1)
                        summary.optJSONArray("errors")?.put("A transação do volume " + volumeName + " foi confirmada, mas o relatório retornou com erro.")
                        volumeResult.put("status", "COMMITTED_REPORT_UNAVAILABLE")
                    }
                    ReiAnixSqliteTransactionOutcome.ROLLED_BACK -> {
                        if (!NativeIndex.markNativeCatalogNotCommitted(
                                appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation,
                                "MediaStore SQLite transaction rolled back: " + error::class.java.simpleName,
                            )
                        ) {
                            summary.put("receiptWarnings", summary.optInt("receiptWarnings") + 1)
                        }
                        summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                        summary.optJSONArray("errors")?.put("A transação SQLite do volume " + volumeName + " foi revertida.")
                        volumeResult.put("status", "ROLLED_BACK")
                    }
                    else -> {
                        if (!NativeIndex.markNativeCatalogOutcomeUnknown(
                                appContext, NativeIndex.SOURCE_MEDIASTORE, scopeKey, generation,
                                "MediaStore SQLite transaction result is unknown: " + error::class.java.simpleName,
                            )
                        ) {
                            summary.put("receiptWarnings", summary.optInt("receiptWarnings") + 1)
                        }
                        summary.put("failedVolumes", summary.optInt("failedVolumes") + 1)
                        summary.optJSONArray("errors")?.put("O resultado da transação SQLite do volume " + volumeName + " é desconhecido; não será transferido para um escritor legado.")
                        volumeResult.put("status", "TRANSACTION_UNKNOWN")
                    }
                }
                Log.e(TAG, "MediaStore catalog indexing failed for volume " + volumeName, error)
            }
            summary.optJSONArray("volumes")?.put(volumeResult)
        }
        return summary
    }

}

private fun discoveredMediaFromSnapshot(
    scanDocuments: JSONArray,
    sourceFolder: String,
): List<NativeDiscoveredMedia> = buildList {
                    for (i in 0 until scanDocuments.length()) {
                        val document = scanDocuments.optJSONObject(i) ?: continue
                        val parsed = document.optJSONObject("parsedMedia") ?: JSONObject()
                        val documentId = document.optString("documentId").takeIf { it.isNotBlank() }
                        val uri = document.optString("uri").takeIf { it.isNotBlank() } ?: continue
                        val name = document.optString("name").takeIf { it.isNotBlank() } ?: continue
                        val season = if (parsed.has("season") && !parsed.isNull("season")) parsed.optInt("season") else null
                        val episodeNumber = if (parsed.has("episodeNumber") && !parsed.isNull("episodeNumber")) parsed.optDouble("episodeNumber") else null
                        val absoluteNumber = if (parsed.has("absoluteNumber") && !parsed.isNull("absoluteNumber")) parsed.optDouble("absoluteNumber") else null
                        val size = if (document.has("size") && !document.isNull("size")) document.optLong("size").takeIf { it >= 0L } else null
                        val modifiedMillis = if (document.has("modifiedAt") && !document.isNull("modifiedAt")) document.optLong("modifiedAt").takeIf { it >= 0L } else null
                        val ambiguities = parsed.optJSONArray("ambiguities")
                        add(
                            NativeDiscoveredMedia(
                                location = uri,
                                fileName = name,
                                scopeLocation = documentId,
                                scopeVerified = document.optBoolean("scopeVerified", false),
                                animeTitle = parsed.optString("animeTitle").takeIf { it.isNotBlank() },
                                season = season,
                                episodeNumber = episodeNumber,
                                absoluteNumber = absoluteNumber,
                                episodeType = parsed.optString("kind").takeIf { it.isNotBlank() },
                                episodeTitle = parsed.optString("displayTitle").takeIf { it.isNotBlank() },
                                identificationSource = parsed.optString("identificationSource").takeIf { it.isNotBlank() },
                                identificationConfidence = parsed.optString("confidence").takeIf { it.isNotBlank() },
                                mimeType = document.optString("mimeType").takeIf { it.isNotBlank() && it != "null" },
                                sizeBytes = size,
                                modifiedAtEpochSeconds = modifiedMillis?.div(1000.0),
                                // Persist the same canonical source reference used by LibraryStore;
                                // the SAF document ID remains separate scan-scope evidence above.
                                sourceFolder = sourceFolder,
                                relativePath = document.optString("relativePath").takeIf { it.isNotBlank() },
                                volumeId = document.optString("volumeId").takeIf { it.isNotBlank() },
                                volumeUuid = document.optString("volumeUuid").takeIf { it.isNotBlank() },
                                mediaIdentity = document.optString("stableId").takeIf { it.isNotBlank() },
                                ambiguous = ambiguities != null && ambiguities.length() > 0,
                            )
                        )
                    }
                }
