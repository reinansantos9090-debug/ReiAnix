package com.reiflix.reiflix_local.scanner

import com.reiflix.reiflix_local.MainActivity
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
            val dispatched = runCatching {
                appContext.startActivity(
                    MainActivity.createNativeStorageActionIntent(
                        appContext,
                        action = "scan_media_store",
                        reason = reason,
                        triggerRequestId = triggerRequestId,
                    ),
                )
            }.onFailure { Log.e(TAG, "MEDIASTORE_RETRY_DISPATCH_FAILED reason=" + reason, it) }
                .isSuccess
            Log.i(TAG, "MEDIASTORE_RETRY_NATIVE_DISPATCH requestId=" + requestId + " triggerRequestId=" + (triggerRequestId ?: "-") + " dispatched=" + dispatched)
        }, 900L)
    }
}
