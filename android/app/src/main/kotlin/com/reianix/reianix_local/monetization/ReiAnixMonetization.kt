package com.reianix.reianix_local.monetization

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.android.billingclient.api.*
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.reianix.reianix_local.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal enum class RewardKind { DAY, BANNER, WALLPAPER }

internal data class MonetizationState(
    val premium: Boolean = false,
    val until: Long = 0,
    val unlocked: Set<String> = emptySet(),
    val adsReady: Boolean = false,
    val privacyRequired: Boolean = false,
    val price: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val trialActive get() = until > System.currentTimeMillis()
    val hasPremium get() = MonetizationPolicy.hasPremium(premium, until, System.currentTimeMillis())
    val hideAds get() = hasPremium
}

internal fun Context.monetizationActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.monetizationActivity()
    else -> null
}

/** Independent from the media database, artwork queue and player lifecycle. */
internal class ReiAnixMonetization private constructor(private val app: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences(
        if (BuildConfig.MONETIZATION_PREVIEW) "monetization_preview_v1" else "monetization_v1", Context.MODE_PRIVATE
    )
    private val current = MutableStateFlow(MonetizationState(
        premium = if (BuildConfig.MONETIZATION_PREVIEW) prefs.getBoolean("premium", false)
            else cachedPurchase() != null,
        until = prefs.getLong("until", 0),
        unlocked = prefs.getStringSet("unlocked", emptySet()).orEmpty().toSet(),
    ))
    val state = current.asStateFlow()
    private var billing: BillingClient? = null
    private var connecting = false
    private var product: ProductDetails? = null
    private var consentRequested = false
    private var initializingAds = false
    private var rewardInProgress = false
    private var rewardShowing = false
    private var rewardGeneration = 0L
    private var expiry: Runnable? = null

    private fun main(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
    private fun update(transform: (MonetizationState) -> MonetizationState) = main {
        current.value = transform(current.value)
    }
    private fun notice(text: String?) = update { it.copy(notice = text) }

    private fun scheduleExpiry() {
        expiry?.let(handler::removeCallbacks)
        val remaining = current.value.until - System.currentTimeMillis()
        if (remaining <= 0) {
            if (current.value.until != 0L) {
                prefs.edit().remove("until").apply()
                update { it.copy(until = 0) }
            }
            return
        }
        expiry = Runnable { scheduleExpiry() }.also { handler.postDelayed(it, remaining) }
    }

    fun start(activity: Activity, withAds: Boolean = true) = main {
        scheduleExpiry()
        connect()
        if (withAds && !activity.isFinishing && !activity.isDestroyed && !current.value.premium) {
            if (BuildConfig.MONETIZATION_PREVIEW) {
                // Only development artifacts request Google's sample ads.
                initializeAds()
            } else if (BuildConfig.MONETIZATION_LIVE_ADS && !consentRequested) {
                consentRequested = true
                val info = UserMessagingPlatform.getConsentInformation(app)
                info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        main { applyConsent() }
                    }
                }, { main { consentRequested = false; applyConsent() } })
            }
        }
    }

    private fun applyConsent() {
        val info = UserMessagingPlatform.getConsentInformation(app)
        val canRequest = info.canRequestAds()
        update { it.copy(privacyRequired = info.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
            adsReady = it.adsReady && canRequest) }
        if (canRequest) initializeAds()
    }
    fun privacy(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            main {
                applyConsent()
                if (error != null) notice("Não foi possível abrir as opções de privacidade.")
            }
        }
    }
    private fun initializeAds() {
        if (!BuildConfig.MONETIZATION_PREVIEW && !BuildConfig.MONETIZATION_LIVE_ADS) return
        if (initializingAds || current.value.adsReady) return
        initializingAds = true
        scope.launch {
            runCatching {
                MobileAds.initialize(app) {
                    main {
                        initializingAds = false
                        val allowed = !BuildConfig.MONETIZATION_LIVE_ADS ||
                            UserMessagingPlatform.getConsentInformation(app).canRequestAds()
                        update { it.copy(adsReady = allowed) }
                    }
                }
            }.onFailure { main { initializingAds = false } }
        }
    }

    fun bannerId() = if (BuildConfig.MONETIZATION_LIVE_ADS)
        "ca-app-pub-5427640733122650/5691419063" else "ca-app-pub-3940256099942544/6300978111"
    fun nativeId() = if (BuildConfig.MONETIZATION_LIVE_ADS)
        "ca-app-pub-5427640733122650/2024083909" else "ca-app-pub-3940256099942544/2247696110"
    private fun rewardId(kind: RewardKind) = if (!BuildConfig.MONETIZATION_LIVE_ADS)
        "ca-app-pub-3940256099942544/5224354917" else when (kind) {
        RewardKind.DAY -> "ca-app-pub-5427640733122650/6019877072"
        RewardKind.BANNER -> "ca-app-pub-5427640733122650/7248490423"
        RewardKind.WALLPAPER -> "ca-app-pub-5427640733122650/5936116595"
    }

    fun cancelPendingReward() = main {
        if (rewardInProgress && !rewardShowing) {
            rewardGeneration++
            rewardInProgress = false
            update { it.copy(busy = false, notice = null) }
        }
    }

    fun reward(activity: Activity, kind: RewardKind, artId: String? = null) = main {
        val snapshot = current.value
        if (snapshot.hasPremium || rewardInProgress || snapshot.busy) return@main
        if (kind == RewardKind.DAY && snapshot.hideAds) return@main
        val key = if (kind == RewardKind.DAY) null else
            MonetizationPolicy.artKey(kind == RewardKind.BANNER, artId ?: return@main)
        if (key != null && key in snapshot.unlocked) return@main
        if (!snapshot.adsReady) {
            start(activity)
            notice("Anúncio indisponível no momento. Tente novamente com internet.")
            return@main
        }
        rewardInProgress = true
        rewardShowing = false
        val generation = ++rewardGeneration
        update { it.copy(busy = true, notice = null) }
        fun finish(message: String? = null) {
            if (generation != rewardGeneration) return
            rewardInProgress = false
            rewardShowing = false
            update { it.copy(busy = false, notice = message) }
        }
        RewardedAd.load(activity, rewardId(kind), AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    finish("Nenhum anúncio disponível. Tente novamente mais tarde.")
                }
                override fun onAdLoaded(ad: RewardedAd) {
                    if (generation != rewardGeneration) return
                    if (activity.isFinishing || activity.isDestroyed ||
                        (activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == false ||
                        !current.value.adsReady || current.value.hasPremium) {
                        finish(); return
                    }
                    rewardShowing = true
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() { finish() }
                        override fun onAdFailedToShowFullScreenContent(error: AdError) {
                            finish("Não foi possível exibir o anúncio.")
                        }
                    }
                    ad.show(activity) {
                        main {
                            // Only the SDK's earned-reward callback grants anything.
                            if (key == null) {
                                val until = MonetizationPolicy.rewardUntil(System.currentTimeMillis())
                                prefs.edit().putLong("until", until).apply()
                                update { it.copy(until = until) }
                                scheduleExpiry()
                            } else {
                                val unlocked = current.value.unlocked + key
                                prefs.edit().putStringSet("unlocked", unlocked).apply()
                                update { it.copy(unlocked = unlocked) }
                            }
                        }
                    }
                }
            })
    }

    private fun verified(p: Purchase): Boolean {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED ||
            MonetizationPolicy.PRODUCT !in p.products || p.purchaseToken.isBlank()) return false
        return runCatching {
            val key = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.decode(BuildConfig.MONETIZATION_BILLING_KEY, Base64.DEFAULT)))
            Signature.getInstance("SHA1withRSA").run {
                initVerify(key)
                update(p.originalJson.toByteArray(Charsets.UTF_8))
                verify(Base64.decode(p.signature, Base64.DEFAULT))
            }
        }.getOrDefault(false)
    }
    private fun cachedPurchase(): Purchase? = runCatching {
        Purchase(prefs.getString("receipt", null) ?: return null,
            prefs.getString("signature", null) ?: return null).takeIf(::verified)
    }.getOrNull()

    private fun setPurchase(p: Purchase?) {
        if (p == null) prefs.edit().remove("receipt").remove("signature").apply()
        else prefs.edit().putString("receipt", p.originalJson).putString("signature", p.signature).apply()
        update { it.copy(premium = p != null) }
    }

    private fun connect() {
        if (BuildConfig.MONETIZATION_PREVIEW || BuildConfig.MONETIZATION_BILLING_KEY.isBlank()) return
        if (billing?.isReady == true) { refresh(); return }
        if (connecting) return
        connecting = true
        val client = billing ?: BillingClient.newBuilder(app)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .setListener { result, purchases ->
                main {
                    update { it.copy(busy = false) }
                    when (result.responseCode) {
                        BillingClient.BillingResponseCode.OK -> {
                            purchases.orEmpty().firstOrNull(::verified)?.let { accept(it) }
                            if (purchases.orEmpty().any { it.purchaseState == Purchase.PurchaseState.PENDING })
                                notice("Pagamento pendente. O Premium será liberado após a confirmação.")
                            else refresh()
                        }
                        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
                        BillingClient.BillingResponseCode.USER_CANCELED -> Unit
                        else -> notice("Não foi possível concluir a compra. Tente novamente.")
                    }
                }
            }.build().also { billing = it }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = main {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
                else notice("Google Play indisponível. Você pode tentar restaurar depois.")
            }
            override fun onBillingServiceDisconnected() = main { connecting = false; product = null; update { it.copy(price = null, busy = false) } }
        })
    }

    private fun accept(purchase: Purchase) {
        if (!verified(purchase)) return
        setPurchase(purchase)
        if (!purchase.isAcknowledged) {
            billing?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken).build()) { result ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK)
                    notice("Compra recebida; confirmação pendente. Conecte-se novamente à Google Play.")
            }
        }
    }

    private fun refresh() {
        val client = billing?.takeIf { it.isReady } ?: return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            main {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val owned = purchases.firstOrNull(::verified)
                    if (owned != null) accept(owned) else setPurchase(null)
                }
            }
        }
        val request = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(MonetizationPolicy.PRODUCT)
                .setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        client.queryProductDetailsAsync(request) { result, details ->
            main {
                product = if (result.responseCode == BillingClient.BillingResponseCode.OK)
                    details.productDetailsList.firstOrNull { it.productId == MonetizationPolicy.PRODUCT } else null
                update { it.copy(price = product?.oneTimePurchaseOfferDetails?.formattedPrice) }
            }
        }
    }

    fun restore(activity: Activity) {
        notice(null)
        start(activity, withAds = false)
        if (BuildConfig.MONETIZATION_PREVIEW) notice("Prévia: a compra simulada é salva neste aparelho.")
        else if (BuildConfig.MONETIZATION_BILLING_KEY.isBlank()) notice("Compras ainda indisponíveis na Google Play.")
    }

    fun buy(activity: Activity) = main {
        if (current.value.premium || current.value.busy) return@main
        if (BuildConfig.MONETIZATION_PREVIEW) {
            prefs.edit().putBoolean("premium", true).apply()
            update { it.copy(premium = true, notice = "Premium de teste ativado. Nenhuma cobrança.") }
            return@main
        }
        val client = billing?.takeIf { it.isReady }
        val details = product
        if (client == null || details == null) {
            connect(); notice("Produto indisponível na Google Play. Tente novamente."); return@main
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details)
        details.oneTimePurchaseOfferDetails?.offerToken?.takeIf { it.isNotBlank() }?.let { params.setOfferToken(it) }
        update { it.copy(busy = true, notice = null) }
        val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(params.build())).build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            update { it.copy(busy = false) }
            if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) refresh()
            else notice("Não foi possível abrir a compra.")
        }
    }

    fun resetPreview() = main {
        if (!BuildConfig.MONETIZATION_PREVIEW || current.value.busy) return@main
        prefs.edit().clear().apply()
        update { it.copy(premium = false, until = 0, unlocked = emptySet(), notice = null) }
        scheduleExpiry()
    }

    companion object {
        @Volatile private var instance: ReiAnixMonetization? = null
        fun from(context: Context): ReiAnixMonetization = instance ?: synchronized(this) {
            instance ?: ReiAnixMonetization(context.applicationContext).also { instance = it }
        }
    }
}
