package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.bridge.NativeMailbox
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object MediaStoreRetryScheduler {
    private const val TAG = "[REIFLIX][ANDROID]"
    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    fun schedule(appContext: Context, reason: String, triggerRequestId: String? = null) {
        if (!scheduled.compareAndSet(false, true)) { Log.i(TAG, "MEDIASTORE_RETRY_DEDUPED reason=" + reason); return }
        handler.postDelayed({
            scheduled.set(false)
            if (!MediaStoreScanner.hasReadPermission(appContext)) { Log.i(TAG, "MEDIASTORE_RETRY_SKIPPED permission=denied"); return@postDelayed }
            val requestId = UUID.randomUUID().toString()
            val written = NativeMailbox.write(appContext, JSONObject().put("type", "scan_request").put("requestId", requestId)
                .put("payload", JSONObject().put("origin", "MEDIASTORE_CHANGE").put("source", "mediastore").put("scopeRef", "").put("full", false)
                    .put("reason", reason).put("triggerRequestId", triggerRequestId ?: "")))
            Log.i(TAG, "MEDIASTORE_RETRY_PUBLISHED requestId=" + requestId + " triggerRequestId=" + (triggerRequestId ?: "-") + " written=" + written)
        }, 900L)
    }
}
