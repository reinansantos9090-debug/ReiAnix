package com.reiflix.reiflix_local

import android.app.Activity
import android.util.Log

/**
 * Activity-level startup hook invoked by MainActivity after process bootstrap.
 *
 * The Android Application owns the process dependency graph and MainActivity
 * preserves its explicit, idempotent bootstrap contract. This hook attaches
 * window diagnostics at the Activity lifecycle boundary without creating a
 * second service graph or runtime.
 */
internal object MainEntryPoint {
    fun onActivityCreated(activity: Activity) {
        PerformanceDiagnostics.attach(activity)
        PerformanceDiagnostics.sampleMemory(activity, "main_on_create")
        Log.i(TAG, "NATIVE_ACTIVITY_ENTRYPOINT_READY activity=${activity::class.java.simpleName}")
    }

    private const val TAG = "ReiAnixEntryPoint"
}
