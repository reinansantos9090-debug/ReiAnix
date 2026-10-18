package com.reiflix.reiflix_local

import android.util.Log
import android.app.Application

/**
 * Owns the process-scoped native dependency graph for the Android application.
 *
 * The bootstrap validates the canonical database and coordinates only process-level
 * native services. Long-running scans, artwork requests and remote work remain with
 * their existing feature/service owners rather than Application.onCreate.
 */
class ReiAnixApplication : Application() {
    lateinit var dependencies: ReiAnixAppDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        dependencies = ReiAnixAppDependencies(this)
        dependencies.bootstrap.start()
        Log.i(TAG, "APPLICATION_READY nativeDependencyGraph=lazy bootstrap=started")
    }

    private companion object {
        const val TAG = "ReiAnixApplication"
    }
}
