package com.reiflix.reiflix_local

import android.util.Log
import io.flutter.app.FlutterApplication

/**
 * Preserves Flutter/Flet's Application bootstrap during the hybrid migration
 * while exposing one lazy Kotlin composition root to Android components.
 *
 * onCreate deliberately performs no database open, scan, artwork work, or
 * remote request. Those operations remain demand-driven by their current owners.
 */
class ReiAnixApplication : FlutterApplication() {
    lateinit var dependencies: ReiAnixAppDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        dependencies = ReiAnixAppDependencies(this)
        Log.i(TAG, "APPLICATION_READY nativeDependencyGraph=lazy")
    }

    private companion object {
        const val TAG = "ReiAnixApplication"
    }
}
