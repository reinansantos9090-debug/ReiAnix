package com.reianix.reianix_local

import android.util.Log
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache

/**
 * Native process Application and single owner of the existing Kotlin dependency
 * graph. The idempotent bootstrap keeps recovery and native service startup
 * asynchronous and independent of Activity recreation.
 */
class ReiAnixApplication : Application(), ImageLoaderFactory {
    lateinit var dependencies: ReiAnixAppDependencies
        private set

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            // One cache instance for both the default and local artwork loaders.
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizePercent(0.02)
                .maximumMaxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        // Remove historical manual diagnostic reports without touching backups or library.
        runCatching { deleteSharedPreferences("reianix_manual_diagnostics_v1") }
        runCatching { ReiAnixLegacyStorageMigration.migrate(this) }
            .onFailure { Log.w("ReiAnixMigration", "Legacy metadata migration deferred", it) }
        dependencies = ReiAnixAppDependencies(this)
        dependencies.bootstrap.start()
        if (com.reianix.reianix_local.data.releases.ReiAnixReleaseMonitor.enabled(this)) {
            runCatching { com.reianix.reianix_local.data.releases.ReiAnixReleaseMonitor.schedule(this) }
                .onFailure { Log.w(TAG, "Release alerts scheduling deferred", it) }
        }
        Log.i(TAG, "APPLICATION_READY nativeDependencyGraph=lazy bootstrap=started")
    }

    private companion object {
        const val TAG = "ReiAnixApplication"
    }
}
