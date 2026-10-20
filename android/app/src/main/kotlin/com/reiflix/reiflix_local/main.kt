package com.reiflix.reiflix_local

import android.app.Activity
import android.util.Log

/**
 * Native application entrypoint for the Android launcher.
 *
 * MainActivity remains the manifest launcher because Android requires an Activity.
 * This entrypoint coordinates the existing process-owned dependency graph and
 * startup diagnostics without creating a parallel service graph or UI runtime.
 */
internal object MainEntryPoint {
    fun start(activity: Activity) {
        val dependencies = ReiAnixAppDependencies.from(activity.applicationContext)
        dependencies.bootstrap.start()
        PerformanceDiagnostics.attach(activity)
        PerformanceDiagnostics.sampleMemory(activity, "main_on_create")
        Log.i(TAG, "NATIVE_ENTRYPOINT_STARTED activity=${activity::class.java.simpleName}")
    }

    private const val TAG = "ReiAnixEntryPoint"
}
