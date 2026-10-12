package com.reiflix.reiflix_local.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.storage.NativeIndex
import com.reiflix.reiflix_local.data.sqlite.NativeDiscoveredMedia
import com.reiflix.reiflix_local.data.sqlite.NativeLibraryScanSnapshot
import com.reiflix.reiflix_local.data.sqlite.ReiAnixSqliteLibraryRepository
import org.json.JSONObject
import kotlinx.coroutines.CancellationException

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
                val partial = result.optBoolean("partial")
                val rawScanStatus = result.optString("status").uppercase()
                val scanStatus = rawScanStatus.takeIf {
                    it in setOf(
                        SafScanner.STATUS_REVOKED,
                        SafScanner.STATUS_UNAVAILABLE,
                        SafScanner.STATUS_CANCELLED,
                        SafScanner.STATUS_PARTIAL,
                        SafScanner.STATUS_EMPTY_COMPLETE,
                        SafScanner.STATUS_COMPLETED,
                        SafScanner.STATUS_FAILED,
                    )
                } ?: SafScanner.STATUS_FAILED
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
                        .put("stats", result.optJSONObject("stats") ?: JSONObject())
                        .put("status", status)
                        .put("scanId", scanId),
                )
                var nativeIndexingFailed = false
                var nativeIndexingStatus = "SKIPPED_INCOMPLETE"
                if (status == NativeIndex.STATUS_COMPLETED || status == NativeIndex.STATUS_EMPTY_COMPLETE) {
                    try {
                        val treeIdentity = SafScanner.treeIdentity(treeUri)
                        val committedDocuments = NativeIndex.committedDocuments(appContext, NativeIndex.SOURCE_SAF, scanKey)
                            ?: throw IllegalStateException("No verified committed snapshot is available for this scan.")
                        val errors = result.optJSONObject("stats")?.optJSONArray("errors")
                        val failedLocations = if (errors != null && errors.length() > 0) {
                            buildSet { for (i in 0 until errors.length()) add(errors.optString(i).ifBlank { "unknown-location" }) }
                        } else emptySet()
                        val discovered = buildList {
                            for (i in 0 until committedDocuments.length()) {
                                val document = committedDocuments.optJSONObject(i) ?: continue
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
                                        sourceFolder = treeIdentity.documentId,
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
                        val complete = status == NativeIndex.STATUS_COMPLETED || status == NativeIndex.STATUS_EMPTY_COMPLETE
                        val indexReport = ReiAnixSqliteLibraryRepository(appContext).indexDiscoveredMedia(
                            NativeLibraryScanSnapshot(
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
                            )
                        )
                        nativeIndexingStatus = if (indexReport.reconciliationAuthorized) "COMPLETED" else "PARTIAL"
                        result.put("nativeLibraryIndex", JSONObject()
                            .put("status", nativeIndexingStatus)
                            .put("inserted", indexReport.inserted)
                            .put("updated", indexReport.updated)
                            .put("unchanged", indexReport.unchanged)
                            .put("ambiguous", indexReport.ambiguous)
                            .put("rejectedOutOfScope", indexReport.rejectedOutOfScope)
                            .put("reconciledMissing", indexReport.reconciledMissing)
                            .put("reconciliationAuthorized", indexReport.reconciliationAuthorized))
                    } catch (indexError: Exception) {
                        nativeIndexingFailed = true
                        nativeIndexingStatus = "FAILED"
                        Log.e(TAG, "Native SQLite library indexing failed; discovery snapshot is preserved.", indexError)
                        result.put("nativeLibraryIndex", JSONObject()
                            .put("status", "FAILED")
                            .put("errorType", indexError::class.java.simpleName))
                        NativeMailbox.write(appContext, JSONObject()
                            .put("type", "native_library_index_error")
                            .put("requestId", requestId ?: "")
                            .put("payload", JSONObject().put("scanId", scanId)
                                .put("scope", runCatching { SafScanner.treeIdentity(treeUri).identity }.getOrDefault(""))
                                .put("status", "FAILED")
                                .put("errorType", indexError::class.java.simpleName)))
                    }
                } else {
                    result.put("nativeLibraryIndex", JSONObject().put("status", nativeIndexingStatus)
                        .put("reconciliationAuthorized", false))
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
                    .put("status", scanStatus)
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", result))
                NativeScanController.complete(scanId, if (nativeIndexingFailed) SafScanner.STATUS_PARTIAL else scanStatus.ifBlank { SafScanner.STATUS_FAILED }, result)
            } catch (exception: CancellationException) {
                NativeScanController.complete(scanId, "CANCELLED", JSONObject().put("cancelled", true))
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "SAF scan failed", exception)
                NativeScanController.complete(scanId, "FAILED", JSONObject().put("errorType", exception::class.java.simpleName))
                NativeIndex.failGeneration(appContext, NativeIndex.SOURCE_SAF, scanKey, generationId,
                    exception.message ?: "SAF scan failed",
                    JSONObject().put("treeUri", reference))
                NativeMailbox.write(appContext, JSONObject().put("type", "saf_error")
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível atualizar esta pasta autorizada.")
                    .put("payload", SafScanner.identityPayload(treeUri).put("scanId", scanId)
                        .put("generationId", "native:" + generationId).put("status", NativeIndex.STATUS_FAILED)
                        .put("source", "saf")))
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
