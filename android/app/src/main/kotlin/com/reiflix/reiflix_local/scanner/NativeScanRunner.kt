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
    fun recoverInterruptedSafScans(context: Context) {
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

            val outcome = item.optString("nativeCatalogWriteOutcome").ifBlank { "UNKNOWN" }
            val committed = outcome == "COMMITTED"
            val uncertain = outcome !in setOf("NOT_STARTED", "NOT_COMMITTED", "COMMITTED")
            val snapshotAvailable = item.optBoolean("snapshotAvailable") &&
                outcome in setOf("NOT_STARTED", "NOT_COMMITTED")
            val documents = if (snapshotAvailable) item.optJSONArray("documents") ?: JSONArray() else JSONArray()
            val report = item.optJSONObject("nativeCatalogWriteReport") ?: JSONObject()
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
                committed -> "NATIVE_COMMIT_CONFIRMED"
                uncertain -> "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED"
                snapshotAvailable -> "LEGACY_INGESTION_REQUIRED"
                else -> "DISCOVERY_SNAPSHOT_UNAVAILABLE"
            }
            val message = when (recovery) {
                "NATIVE_COMMIT_CONFIRMED" ->
                    "Recovered terminal SAF notification after confirmed native SQLite commit."
                "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED" ->
                    "Process ended during native SQLite transaction; commit outcome is unknown. Existing catalog preserved; controlled rescan required."
                "LEGACY_INGESTION_REQUIRED" ->
                    "Recovered interrupted SAF discovery snapshot after confirming no native catalog commit; ingesting positive results only."
                else ->
                    "SAF discovery was interrupted without a verifiable snapshot; existing catalog preserved; controlled rescan required."
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
                .put("documents", documents)
                .put("partial", !trustedNativeCompletion)
                .put("nativeLibraryIndexOwner", committed || uncertain)
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

            val written = runCatching {
                NativeMailbox.writeOrThrow(
                    appContext,
                    JSONObject().put("type", "saf_scan").put("requestId", metadata.optString("requestId")).put("payload", payload),
                )
            }.onFailure { Log.e(TAG, "Could not persist recovered SAF terminal event; receipt remains pending.", it) }
                .isSuccess
            if (!written) continue

            runCatching {
                NativeIndex.markRecoveryEventPublished(appContext, NativeIndex.SOURCE_SAF, scopeKey, generation)
            }.onFailure { Log.w(TAG, "Recovery event is durable, but its receipt marker could not be persisted.", it) }

            if (!committed && !uncertain &&
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

        fun canTransferToFallback(): Boolean {
            if (committedIndexReport.get() != null) return false
            if (!nativeIndexingCheckpointAttempted.get()) return true
            return indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.ROLLED_BACK &&
                rollbackReceiptPersisted.get()
        }

        fun recoveryDocuments(): JSONArray? {
            if (!canTransferToFallback()) return null
            val prior = terminalResult
            val priorRecovery = prior?.optJSONObject("nativeLibraryIndex")?.optString("recovery").orEmpty()
            if (prior != null && prior.has("nativeLibraryIndexOwner") &&
                !prior.optBoolean("nativeLibraryIndexOwner") &&
                priorRecovery == "LEGACY_INGESTION_REQUIRED"
            ) {
                prior.optJSONArray("documents")?.let { return it }
            }
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

        fun publishRecoveryTerminal(message: String, cancelled: Boolean): Boolean {
            val committedReport = committedIndexReport.get()
            val payload = terminalResult ?: SafScanner.identityPayload(treeUri)
            val fallbackOwnershipAllowed = committedReport == null && canTransferToFallback()
            val documents = if (fallbackOwnershipAllowed) recoveryDocuments() else null
            if (committedReport == null) {
                val stats = payload.optJSONObject("stats") ?: JSONObject()
                stats.put("status", SafScanner.STATUS_PARTIAL).put("partial", true).put("cancelled", cancelled)
                val errors = stats.optJSONArray("errors") ?: JSONArray().also { stats.put("errors", it) }
                if ((0 until errors.length()).none { errors.optString(it) == message }) errors.put(message)
                val outcome = indexTransactionOutcome.get()
                val recovery = when {
                    !fallbackOwnershipAllowed && outcome == ReiAnixSqliteTransactionOutcome.COMMITTED ->
                        "NATIVE_COMMIT_CONFIRMED"
                    !fallbackOwnershipAllowed && outcome == ReiAnixSqliteTransactionOutcome.ROLLED_BACK ->
                        "ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED"
                    !fallbackOwnershipAllowed -> "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED"
                    documents != null -> "LEGACY_INGESTION_REQUIRED"
                    else -> "DISCOVERY_SNAPSHOT_UNAVAILABLE"
                }
                payload.put("stats", stats)
                    .put("documents", documents ?: JSONArray())
                    .put("nativeLibraryIndexOwner", !fallbackOwnershipAllowed)
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
                // just because final event delivery failed after SQLite committed.
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
            val written = runCatching {
                NativeMailbox.writeOrThrow(
                    appContext,
                    JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", payload),
                )
            }.onFailure { error ->
                Log.e(TAG, "Could not publish terminal SAF recovery state; retaining staging data.", error)
            }.isSuccess
            if (written) {
                terminalResult = payload
                terminalEventPublished = true
                runCatching {
                    NativeIndex.markRecoveryEventPublished(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                }.onFailure { Log.w(TAG, "Recovery event was published but its receipt marker could not be persisted.", it) }
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
                // Retain this scan generation's verified discovery snapshot until the SQLite
                // transaction commits. If that transaction rolls back, the terminal event can
                // hand the same snapshot to the legacy ingestion path as a serialized fallback.
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
                        val discovered = buildList {
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
                                        sourceFolder = reference,
                                        relativePath = document.optString("relativePath").takeIf { it.isNotBlank() },
                                        volumeId = document.optString("volumeId").takeIf { it.isNotBlank() },
                                        volumeUuid = document.optString("volumeUuid").takeIf { it.isNotBlank() },
                                        mediaIdentity = document.optString("stableId").takeIf { it.isNotBlank() },
                                        ambiguous = ambiguities != null && ambiguities.length() > 0,
                                    )
                                )
                            }
                        }
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
                        val indexReport = ReiAnixAppDependencies.from(appContext).sqliteLibraryRepository.indexDiscoveredMedia(snapshot) { outcome, report ->
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
                            val ownershipTransferred = rollbackConfirmed && rollbackReceiptPersisted.get()
                            val committedWithoutReport = transactionOutcome == ReiAnixSqliteTransactionOutcome.COMMITTED
                            val fallbackAvailable = ownershipTransferred && fallbackDocuments != null
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
                                fallbackAvailable -> "LEGACY_INGESTION_REQUIRED"
                                ownershipTransferred -> "DISCOVERY_SNAPSHOT_UNAVAILABLE"
                                rollbackConfirmed -> "ROLLBACK_RECEIPT_UNAVAILABLE_RESCAN_REQUIRED"
                                committedWithoutReport -> "NATIVE_COMMIT_CONFIRMED"
                                else -> "COMMIT_OUTCOME_UNKNOWN_RESCAN_REQUIRED"
                            }
                            val recoveryMessage = when {
                                fallbackAvailable && nativeIndexingCancelled ->
                                    "native SQLite transaction rolled back after cancellation; serialized fallback ingestion required"
                                fallbackAvailable ->
                                    "native SQLite transaction rolled back; serialized fallback ingestion required"
                                ownershipTransferred ->
                                    "native SQLite transaction rolled back but verified discovery snapshot is unavailable; preserve catalog and rescan"
                                rollbackConfirmed ->
                                    "native SQLite transaction rolled back but the recovery receipt could not be persisted; fallback disabled and rescan required"
                                committedWithoutReport ->
                                    "native SQLite transaction committed but its report is unavailable; fallback disabled; controlled rescan required"
                                else ->
                                    "native SQLite transaction outcome unknown; fallback disabled to avoid a second writer; preserve catalog and rescan"
                            }
                            val fallbackStats = result.optJSONObject("stats") ?: JSONObject()
                            fallbackStats.put("status", SafScanner.STATUS_PARTIAL).put("partial", true)
                            val fallbackErrors = fallbackStats.optJSONArray("errors")
                                ?: org.json.JSONArray().also { fallbackStats.put("errors", it) }
                            fallbackErrors.put(recoveryMessage)
                            result.put("stats", fallbackStats)
                                .put("cancelled", nativeIndexingCancelled)
                            result.put("nativeLibraryIndex", JSONObject()
                                .put("status", nativeIndexingStatus)
                                .put("errorType", indexError::class.java.simpleName)
                                .put("commitOutcome", transactionOutcome?.name ?: "UNKNOWN")
                                .put("recovery", recovery))
                                .put("nativeLibraryIndexOwner", !ownershipTransferred)
                                .put("documents", if (fallbackAvailable) fallbackDocuments ?: JSONArray() else JSONArray())

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
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", result))
                terminalEventPublished = true
                runCatching {
                    NativeIndex.markRecoveryEventPublished(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId)
                }.onFailure { Log.w(TAG, "Terminal SAF event was published but its receipt marker could not be persisted.", it) }
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
                    val canTransfer = canTransferToFallback()
                    val snapshotAvailable = canTransfer && recoveryDocuments() != null
                    val message = when {
                        !canTransfer && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.COMMITTED ->
                            "SAF scan cancelled after confirmed SQLite commit; fallback disabled."
                        !canTransfer && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.ROLLED_BACK ->
                            "SAF scan cancelled after rollback, but the matching recovery receipt was not persisted; fallback disabled; rescan required."
                        !canTransfer ->
                            "SAF scan cancelled with an unconfirmed SQLite transaction outcome; catalog preserved; rescan required."
                        snapshotAvailable ->
                            "SAF scan cancelled; verified discoveries were handed to serialized partial ingestion."
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
                    val canTransfer = canTransferToFallback()
                    val snapshotAvailable = canTransfer && recoveryDocuments() != null
                    val message = when {
                        !canTransfer && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.COMMITTED ->
                            "SAF scan failed after confirmed SQLite commit; fallback disabled."
                        !canTransfer && indexTransactionOutcome.get() == ReiAnixSqliteTransactionOutcome.ROLLED_BACK ->
                            "SAF scan failed after rollback, but the matching recovery receipt was not persisted; fallback disabled; rescan required."
                        !canTransfer ->
                            "SAF scan failed with an unconfirmed SQLite transaction outcome; catalog preserved; rescan required."
                        snapshotAvailable ->
                            "SAF scan failed; verified discoveries were handed to serialized partial ingestion."
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

                // BroadStorage remains diagnostic-only, matching the existing library-source contract.
                // Its batch events are marked as native-owned so Python cannot ingest them into the catalog.
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

    suspend fun runMediaStoreScan(
        context: Context,
        scanId: String,
        requestId: String?,
    ) {
        val appContext = context.applicationContext

            try {
                NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_scan_progress")
                    .put("payload", JSONObject().put("source", MediaStoreScanner.SOURCE).put("scanId", scanId)
                        .put("requestId", requestId ?: "").put("phase", "started")))
                val result = MediaStoreScanner.scan(
                    appContext,
                    { progress ->
                        NativeScanController.updateProgress(scanId, progress)
                        NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_scan_progress")
                            .put("payload", progress.put("scanId", scanId).put("requestId", requestId ?: "").put("scopeKind", "global")))
                    },
                    { NativeScanController.isCancelled(scanId) },
                    scanId,
                    onBatch = { batch ->
                    val volume = batch.optString("volumeId")
                    NativeScanPublisher.publish(
                        appContext,
                        "mediastore_scan_batch",
                        "mediastore",
                        scanId,
                        requestId,
                        "volume",
                        volume,
                        "mediastore:" + volume,
                        batch.optLong("generation", NativeIndex.cachedGeneration(appContext, "mediastore:" + volume)),
                        batch,
                    )
                    }
                )
                result.put("requestId", requestId ?: "").put("scanId", scanId)
                    .put("scopeKind", "global").put("scopeRef", MediaStoreScanner.SOURCE)
                val finalStatus = result.optJSONObject("stats")?.optString("status").orEmpty().uppercase()
                if (finalStatus == NativeIndex.STATUS_WAITING_FOR_MEDIASTORE) {
                    NativeMailbox.write(appContext, JSONObject().put("type", "diagnostic")
                        .put("payload", JSONObject().put("event", "WAITING_FOR_MEDIASTORE").put("scanId", scanId).put("requestId", requestId ?: "")))
                    MediaStoreRetryScheduler.schedule(
                        appContext,
                        reason = "media_store_indexing_completed",
                        triggerRequestId = requestId,
                    )
                }

                // MediaStore remains diagnostic-only, matching the existing library-source contract.
                // Its batch events are marked as native-owned so Python cannot ingest them into the catalog.
                result.put("nativeLibraryIndexOwner", true)
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "mediastore_scan")
                    .put("requestId", requestId ?: "").put("payload", result))
                val terminalStatus = ScanResultClassifier.classifyMediaStore(
                    finalStatus,
                    cancelled = result.optBoolean("cancelled"),
                    partial = result.optBoolean("partial"),
                )
                NativeScanController.complete(scanId, terminalStatus, result)
            } catch (exception: CancellationException) {
                NativeScanController.complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "MediaStore scan failed", exception)
                NativeScanController.complete(scanId, "FAILED", JSONObject().put("errorType", exception::class.java.simpleName))
                NativeIndex.failActiveGenerations(appContext, NativeIndex.SOURCE_MEDIASTORE, exception.message ?: "MediaStore scan failed")
                NativeMailbox.write(appContext, JSONObject().put("type", "mediastore_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível atualizar os vídeos do dispositivo.")
                    .put("payload", JSONObject()
                        .put("source", MediaStoreScanner.SOURCE)
                        .put("scanId", scanId)
                        .put("status", NativeIndex.STATUS_FAILED)
                        .put("access", MediaStoreScanner.accessLevel(appContext))))
            } finally {
                NativeScanController.finish(scanId)
            }

    }
}
