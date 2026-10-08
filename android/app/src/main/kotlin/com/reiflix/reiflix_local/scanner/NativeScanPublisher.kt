package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.bridge.NativeMailbox
import com.reiflix.reiflix_local.storage.NativeIndex
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object NativeScanPublisher {
    private const val TAG = "[REIFLIX][ANDROID][SCAN]"
    fun publish(appContext: Context, eventType: String, source: String, scanId: String, requestId: String?, scopeKind: String, scopeRef: String, scopeKey: String, generation: Long, batchEvent: JSONObject) {
        try {
            val raw = batchEvent.optJSONArray("documents") ?: JSONArray()
            val batchId = batchEvent.optString("batchId").ifBlank { UUID.randomUUID().toString() }
            val batchNumber = batchEvent.optInt("batchNumber", 0)
            val reused = batchEvent.optBoolean("reused", false)
            val prepared = if (reused) null else NativeIndex.prepareBatch(appContext, source, scopeKey, raw, generation, batchId, batchNumber,
                JSONObject().put("scanId", scanId).put("requestId", requestId ?: "").put("source", source).put("scopeKind", scopeKind).put("scopeRef", scopeRef))
            val documents = prepared?.documents ?: raw
            val effectiveGeneration = prepared?.generation ?: generation
            val effectiveGenerationId = prepared?.generationId ?: NativeIndex.generationId(source, scopeKey, effectiveGeneration)
            val payload = JSONObject().put("scanId", if (scopeKind == "volume" && scopeRef.isNotBlank()) scanId + ":" + scopeRef else scanId)
                .put("scopeScanId", if (scopeKind == "volume" && scopeRef.isNotBlank()) scanId + ":" + scopeRef else scanId)
                .put("requestId", requestId ?: "").put("source", source).put("scope", scopeRef).put("scopeKind", scopeKind).put("scopeRef", scopeRef)
                .put("volumeId", batchEvent.optString("volumeId")).put("generationId", effectiveGenerationId).put("scanGeneration", effectiveGeneration)
                .put("batchId", batchId).put("batchNumber", batchNumber).put("batchSize", documents.length()).put("processed", documents.length())
                .put("discovered", raw.length()).put("duplicates", prepared?.duplicates ?: 0).put("reused", reused).put("documents", documents)
            NativeMailbox.writeOrThrow(appContext, JSONObject().put("type", eventType).put("requestId", requestId ?: "").put("payload", payload))
        } catch (exception: Exception) {
            Log.e(TAG, "Native batch publication failed", exception)
            NativeMailbox.write(appContext, JSONObject().put("type", eventType.replace("_batch", "_error")).put("requestId", requestId ?: "")
                .put("message", "Não foi possível preparar um lote da biblioteca.").put("payload", JSONObject().put("scanId", scanId).put("requestId", requestId ?: "")
                    .put("source", source).put("scopeKind", scopeKind).put("scopeRef", scopeRef).put("generationId", NativeIndex.generationId(source, scopeKey, generation))
                    .put("status", NativeIndex.STATUS_FAILED).put("batchId", batchEvent.optString("batchId")).put("batchNumber", batchEvent.optInt("batchNumber", 0))
                    .put("error", exception.message ?: "native_batch_failed")))
            throw exception
        }
    }
}
