package com.reiflix.reiflix_local

import android.util.Log
import android.app.Application
import kotlinx.coroutines.*

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
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val downloads=com.reiflix.reiflix_local.downloads.DownloadStore(this@ReiAnixApplication)
                downloads.ready()
                if(com.reiflix.reiflix_local.downloads.DownloadCaptureGuard.removeLegacyBanners(this@ReiAnixApplication,downloads))com.reiflix.reiflix_local.downloads.DownloadEvents.catalog.tryEmit(Unit)
                if(com.reiflix.reiflix_local.downloads.DownloadCatalogBridge.reconcile(dependencies.sqliteDatabase)) {
                    com.reiflix.reiflix_local.downloads.DownloadEvents.catalog.tryEmit(Unit)
                }
                com.reiflix.reiflix_local.downloads.DownloadSnapshots.refresh(this@ReiAnixApplication)
                if(downloads.all().any { it.state in com.reiflix.reiflix_local.downloads.DownloadState.pending }) {
                    com.reiflix.reiflix_local.downloads.DownloadScheduling.schedule(this@ReiAnixApplication,userInitiated=false)
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Recovery mode must not be bypassed by download startup. */ }
        }
        Log.i(TAG, "APPLICATION_READY nativeDependencyGraph=lazy bootstrap=started")
    }

    private companion object {
        const val TAG = "ReiAnixApplication"
    }
}
