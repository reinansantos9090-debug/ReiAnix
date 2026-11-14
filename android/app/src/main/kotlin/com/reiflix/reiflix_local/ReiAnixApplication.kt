package com.reiflix.reiflix_local

import android.util.Log
import android.app.Application

/**
 * Native process Application and single owner of the existing Kotlin dependency
 * graph. The idempotent bootstrap keeps recovery and native service startup
 * asynchronous and independent of Activity recreation.
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
