package com.reianix.reianix_local

import android.util.Log
import android.app.Application
import android.app.ActivityManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.reianix.reianix_local.ui.artwork.ReiAnixArtworkRequestInterceptor

/**
 * Native process Application and single owner of the existing Kotlin dependency
 * graph. The idempotent bootstrap keeps recovery and native service startup
 * asynchronous and independent of Activity recreation.
 */
class ReiAnixApplication : Application(), ImageLoaderFactory {
    lateinit var dependencies: ReiAnixAppDependencies
        private set

    override fun newImageLoader(): ImageLoader {
        // A single Coil instance serves library, home and detail artwork, so that
        // their memory caches never duplicate the same decoded poster.
        val isLowRam = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        return ImageLoader.Builder(this)
            .okHttpClient {
                okhttp3.OkHttpClient.Builder()
                    .followSslRedirects(false)
                    .addInterceptor { chain ->
                        if (!chain.request().url.isHttps) throw java.io.IOException("Remote artwork requires HTTPS")
                        chain.proceed(chain.request())
                    }.build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (isLowRam) 0.15 else 0.20)
                    .build()
            }
            .diskCache {
                // Keep the existing persistent cache path and size bounds.
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .maximumMaxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            // Coil 2.7's existing BitmapFactory parallelism default is four:
            // make it explicit without lowering first-load throughput.
            .bitmapFactoryMaxParallelism(4)
            .components {
                add(ReiAnixArtworkRequestInterceptor())
            }
            .build()
    }

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
