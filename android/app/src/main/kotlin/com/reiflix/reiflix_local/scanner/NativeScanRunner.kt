package com.reiflix.reiflix_local.scanner

import android.content.Context
import android.net.Uri
import android.util.Log
import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.storage.NativeIndex
import org.json.JSONObject

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
                val scanStatus = result.optString("status").uppercase()
                val status = when (scanStatus) {
                    SafScanner.STATUS_REVOKED, SafScanner.STATUS_UNAVAILABLE -> NativeIndex.STATUS_UNAVAILABLE
                    SafScanner.STATUS_CANCELLED -> NativeIndex.STATUS_CANCELLED
                    SafScanner.STATUS_PARTIAL -> NativeIndex.STATUS_PARTIAL
                    SafScanner.STATUS_EMPTY_COMPLETE -> NativeIndex.STATUS_EMPTY_COMPLETE
                    else -> NativeIndex.STATUS_COMPLETED
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
                    .put("status", scanStatus.ifBlank { SafScanner.STATUS_COMPLETED })
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "saf_scan").put("requestId", requestId ?: "").put("payload", result))
            } catch (exception: Exception) {
                Log.e(TAG, "SAF scan failed", exception)
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
                val partial = result.optBoolean("partial")
                result.put("requestId", requestId ?: "")
                    .put("scanId", scanId).put("scopeKind", "global").put("scopeRef", "broad-storage")
                    .put("generationId", "native-scoped")
                    .put("generationStatus", if (result.optBoolean("cancelled")) NativeIndex.STATUS_CANCELLED else if (partial) NativeIndex.STATUS_PARTIAL else NativeIndex.STATUS_COMPLETED)
                NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", "broad_storage_scan")
                    .put("requestId", requestId ?: "").put("payload", result))
            } catch (exception: Exception) {
                Log.e(TAG, "Broad storage scan failed", exception)
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
            } catch (exception: Exception) {
                Log.e(TAG, "MediaStore scan failed", exception)
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
