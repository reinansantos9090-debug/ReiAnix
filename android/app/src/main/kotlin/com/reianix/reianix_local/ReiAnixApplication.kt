package com.reianix.reianix_local

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
        runCatching { ReiAnixLegacyStorageMigration.migrate(this) }
            .onFailure { Log.w("ReiAnixMigration", "Legacy metadata migration deferred", it) }
        dependencies = ReiAnixAppDependencies(this)
        dependencies.bootstrap.start()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val downloads=com.reianix.reianix_local.downloads.DownloadStore(this@ReiAnixApplication)
                downloads.ready()
                if(runCatching {com.reianix.reianix_local.downloads.DownloadCaptureGuard.removeLegacyBanners(this@ReiAnixApplication,downloads)}.getOrDefault(false))com.reianix.reianix_local.downloads.DownloadEvents.catalog.tryEmit(Unit)
                if(com.reianix.reianix_local.downloads.DownloadCatalogBridge.reconcile(dependencies.sqliteDatabase)) {
                    com.reianix.reianix_local.downloads.DownloadEvents.catalog.tryEmit(Unit)
                }
                com.reianix.reianix_local.downloads.DownloadSnapshots.refresh(this@ReiAnixApplication)
                if(downloads.all().any { it.state in com.reianix.reianix_local.downloads.DownloadState.pending }) {
                    com.reianix.reianix_local.downloads.DownloadScheduling.schedule(this@ReiAnixApplication,userInitiated=false)
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
