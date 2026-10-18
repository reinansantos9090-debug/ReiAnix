package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.data.diagnostics.ReiAnixDiagnosticTimeline
import com.reiflix.reiflix_local.storage.NativeIndex
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Native scan-batch staging boundary.
 *
 * The current scanner/indexer and canonical catalog writes are owned by Kotlin.
 * Batch documents stay in NativeIndex's durable staging area; they are not copied
 * into NativeMailbox for Python to discard as duplicate observations. The mailbox
 * remains only for lightweight failure compatibility while other legacy consumers
 * are being migrated.
 */
object NativeScanPublisher {
    private const val TAG = "[REIANIX][ANDROID][SCAN]"

    fun publish(
        appContext: Context,
        eventType: String,
        source: String,
        scanId: String,
        requestId: String?,
        scopeKind: String,
        scopeRef: String,
        scopeKey: String,
        generation: Long,
        batchEvent: JSONObject,
    ) {
        try {
            val raw = batchEvent.optJSONArray("documents") ?: JSONArray()
            val batchId = batchEvent.optString("batchId").ifBlank { UUID.randomUUID().toString() }
            val batchNumber = batchEvent.optInt("batchNumber", 0)
            val reused = batchEvent.optBoolean("reused", false)
            val prepared = if (reused && source != NativeIndex.SOURCE_SAF) {
                null
            } else {
                NativeIndex.prepareBatch(
                    appContext,
                    source,
                    scopeKey,
                    raw,
                    generation,
                    batchId,
                    batchNumber,
                    JSONObject()
                        .put("scanId", scanId)
                        .put("requestId", requestId ?: "")
                        .put("source", source)
                        .put("scopeKind", scopeKind)
                        .put("scopeRef", scopeRef),
                )
            }

            val effectiveGeneration = prepared?.generation ?: generation
            val effectiveGenerationId = prepared?.generationId
                ?: NativeIndex.generationId(source, scopeKey, effectiveGeneration)
            val stagedCount = prepared?.documents?.length() ?: raw.length()
            val outcome = if (reused && source != NativeIndex.SOURCE_SAF) "REUSED" else "STAGED"

            // NativeIndex already owns the durable batch contents. Only bounded
            // metadata enters the in-process timeline; no document array is copied
            // through the Python polling queue.
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_SCAN_BATCH_STAGED",
                source = source,
                result = outcome,
                requestId = requestId,
                scanId = scanId,
                context = mapOf(
                    "event_type" to eventType,
                    "scope_kind" to scopeKind,
                    "batch_id" to batchId,
                    "batch_number" to batchNumber,
                    "generation" to effectiveGeneration,
                    "generation_id" to effectiveGenerationId,
                    "document_count" to stagedCount,
                    "duplicates" to (prepared?.duplicates ?: 0),
                ),
            )
            Log.d(
                TAG,
                "Batch staged natively eventType=$eventType scanId=$scanId " +
                    "batchId=$batchId generation=$effectiveGeneration documents=$stagedCount outcome=$outcome",
            )
        } catch (exception: Exception) {
            if (exception is kotlinx.coroutines.CancellationException) throw exception
            ReiAnixDiagnosticTimeline.record(
                name = "NATIVE_SCAN_BATCH_PUBLICATION_FAILED",
                severity = "ERROR",
                source = "NativeScanPublisher",
                result = "FAILED",
                requestId = requestId,
                scanId = scanId,
                error = exception,
                context = mapOf(
                    "scan_source" to source,
                    "scope_kind" to scopeKind,
                    "generation" to generation,
                    "batch_number" to batchEvent.optInt("batchNumber", 0),
                ),
            )
            Log.e(TAG, "Native batch staging failed", exception)

            // Error-only compatibility event; successful document batches are
            // never sent to the embedded Python poller.
            NativeMailbox.write(
                appContext,
                JSONObject()
                    .put("type", eventType.replace("_batch", "_error"))
                    .put("requestId", requestId ?: "")
                    .put("message", "Não foi possível preparar um lote da biblioteca.")
                    .put(
                        "payload",
                        JSONObject()
                            .put("scanId", scanId)
                            .put("requestId", requestId ?: "")
                            .put("source", source)
                            .put("scopeKind", scopeKind)
                            .put("scopeRef", scopeRef)
                            .put("generationId", NativeIndex.generationId(source, scopeKey, generation))
                            .put("status", NativeIndex.STATUS_FAILED)
                            .put("batchId", batchEvent.optString("batchId"))
                            .put("batchNumber", batchEvent.optInt("batchNumber", 0))
                            .put("error", exception.message ?: "native_batch_failed"),
                    ),
            )
            throw exception
        }
    }
}
