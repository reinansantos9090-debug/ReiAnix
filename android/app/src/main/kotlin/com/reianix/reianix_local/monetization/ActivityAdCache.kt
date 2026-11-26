package com.reianix.reianix_local.monetization

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Activity-owned cache, independent of navigation/scroll composition.
 * No disk persistence, no sharing a creative between simultaneous placements.
 * All SDK operations and cache mutations run on the main thread.
 */
internal class ActivityAdCache private constructor(private val activity: Activity) :
    DefaultLifecycleObserver {
    internal class NativeSlot(val key: String) {
        var ad by mutableStateOf<NativeAd?>(null)
            internal set
        var view: NativeAdView? = null
        var users = 0
        var generation = 0L
        var loading = false
        var loadedAt = 0L
        var nextAttempt = 0L
    }

    private val owner = activity as LifecycleOwner
    private val manager = ReiAnixMonetization.from(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val slots = LinkedHashMap<String, NativeSlot>(8, 0.75f, true)
    private var closed = false
    private var foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var bannerUsers = 0
    private var bannerView: AdView? = null
    private var bannerNextAttempt = 0L
    var banner by mutableStateOf<AdView?>(null)
        private set

    init {
        owner.lifecycle.addObserver(this)
        scope.launch {
            manager.state.collect {
                if (!it.adsReady || it.hideAds) clearAds()
                else maintain()
            }
        }
        scope.launch {
            while (true) {
                delay(15_000)
                maintain()
            }
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun allowed(): Boolean {
        val state = manager.state.value
        return !closed && state.adsReady && !state.hideAds
    }

    private fun online(): Boolean = runCatching {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun slot(key: String): NativeSlot = slots.getOrPut(key) { NativeSlot(key) }

    fun acquire(slot: NativeSlot) {
        slot.users++
        maintain()
    }

    fun release(slot: NativeSlot) {
        slot.users = (slot.users - 1).coerceAtLeast(0)
        trimDetails()
    }

    private fun trimDetails() {
        // Keep recently visited anime details, but bound the number of retained creatives.
        val inactive = slots.values.filter { it.key.startsWith("details:") && it.users == 0 }
        inactive.take((inactive.size - 3).coerceAtLeast(0)).forEach {
            slots.remove(it.key)
            clearNative(it)
        }
    }

    fun acquireBanner() {
        bannerUsers++
        maintain()
        if (foreground && allowed()) bannerView?.resume()
    }

    fun releaseBanner() {
        bannerUsers = (bannerUsers - 1).coerceAtLeast(0)
        if (bannerUsers == 0) bannerView?.pause()
    }

    private fun maintain() {
        if (closed) return
        if (!allowed()) {
            clearAds()
            return
        }
        val time = now()
        val mayRequest = foreground && online()
        slots.values.toList().forEach { slot ->
            if (slot.ad != null && time - slot.loadedAt >= NATIVE_TTL_MS) {
                clearNative(slot)
            }
            if (mayRequest && slot.users > 0 && slot.ad == null && !slot.loading &&
                time >= slot.nextAttempt) {
                loadNative(slot)
            }
        }
        if (mayRequest && bannerUsers > 0 && bannerView == null && time >= bannerNextAttempt) {
            loadBanner()
        }
    }

    private fun loadNative(slot: NativeSlot) {
        slot.loading = true
        val generation = ++slot.generation
        fun failed() {
            if (slot.generation != generation) return
            slot.loading = false
            slot.nextAttempt = now() + RETRY_MS
        }
        runCatching {
            AdLoader.Builder(activity, manager.nativeId())
                .forNativeAd { ad ->
                    if (!allowed() || slot.generation != generation) {
                        ad.destroy()
                    } else if (slot.key != HOME_NATIVE && ad.mediaContent?.hasVideoContent() == true) {
                        // The compact format has no video asset view.
                        ad.destroy()
                        failed()
                    } else {
                        slot.loading = false
                        slot.loadedAt = now()
                        slot.ad = ad
                    }
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) = failed()
                })
                .build().loadAd(AdRequest.Builder().build())
        }.onFailure { failed() }
    }

    private fun clearNative(slot: NativeSlot) {
        slot.generation++
        slot.loading = false
        val oldAd = slot.ad
        val oldView = slot.view
        slot.ad = null
        slot.view = null
        slot.loadedAt = 0L
        slot.nextAttempt = 0L
        oldView?.destroy()
        oldAd?.destroy()
    }

    // Reattach the original registered view rather than registering the same ad again
    // on every return to a tab. The SDK retains ownership of impression tracking.
    fun nativeView(ad: NativeAd, create: () -> NativeAdView): NativeAdView {
        val slot = slots.values.firstOrNull { it.ad === ad }
        val view = slot?.view ?: create().also { if (slot != null) slot.view = it }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    private fun loadBanner() {
        val view = AdView(activity)
        bannerView = view
        view.setAdSize(AdSize.BANNER)
        view.adUnitId = manager.bannerId()
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                if (allowed() && bannerView === view) banner = view
            }
            override fun onAdFailedToLoad(error: LoadAdError) {
                if (bannerView !== view || banner === view) return
                // Retry only after a failed initial load; successful banners use SDK refresh.
                bannerView = null
                bannerNextAttempt = now() + RETRY_MS
                view.destroy()
            }
        }
        runCatching { view.loadAd(AdRequest.Builder().build()) }.onFailure {
            if (bannerView === view) {
                bannerView = null
                bannerNextAttempt = now() + RETRY_MS
                view.destroy()
            }
        }
    }

    private fun clearAds() {
        slots.values.forEach { slot ->
            if (slot.ad != null || slot.loading || slot.view != null) clearNative(slot)
        }
        val oldBanner = bannerView
        banner = null
        bannerView = null
        bannerNextAttempt = 0L
        oldBanner?.destroy()
    }

    override fun onResume(owner: LifecycleOwner) {
        foreground = true
        maintain()
        if (bannerUsers > 0 && allowed()) bannerView?.resume()
    }

    override fun onPause(owner: LifecycleOwner) {
        foreground = false
        bannerView?.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        closed = true
        scope.cancel()
        clearAds()
        slots.clear()
        owner.lifecycle.removeObserver(this)
        instances.remove(activity)
    }

    companion object {
        internal const val HOME_NATIVE = "home-native"
        private const val NATIVE_TTL_MS = 60 * 60 * 1000L
        private const val RETRY_MS = 60_000L
        private val instances = mutableMapOf<Activity, ActivityAdCache>()
        fun from(activity: Activity): ActivityAdCache =
            instances.getOrPut(activity) { ActivityAdCache(activity) }
    }
}
