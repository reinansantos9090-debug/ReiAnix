package com.reianix.reianix_local.monetization

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.ads.*
import com.google.android.gms.ads.nativead.AdChoicesView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.reianix.reianix_local.BuildConfig
import kotlinx.coroutines.delay

@Composable
internal fun MonetizationHost() {
    val activity = LocalContext.current.monetizationActivity() ?: return
    val manager = remember(activity) { ReiAnixMonetization.from(activity) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) manager.start(activity, withAds = false)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

internal data class PremiumAccess(val active: Boolean, val open: () -> Unit)

@Composable
internal fun rememberPremiumAccess(allowReward: Boolean = true): PremiumAccess {
    val context = LocalContext.current
    val manager = remember(context) { ReiAnixMonetization.from(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    if (open) PremiumDialog(manager, allowReward) { open = false }
    return PremiumAccess(state.premium) { open = true }
}

@Composable
internal fun MonetizationEntry() {
    val access = rememberPremiumAccess()
    TextButton(onClick = access.open, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(if (access.active) "ReiAnix Premium · Ativo" else "ReiAnix Premium")
    }
}

@Composable
private fun PremiumDialog(manager: ReiAnixMonetization, allowReward: Boolean, close: () -> Unit) {
    val activity = LocalContext.current.monetizationActivity() ?: return
    val state by manager.state.collectAsStateWithLifecycle()
    LaunchedEffect(activity) { manager.start(activity, withAds = allowReward) }
    DisposableEffect(manager, allowReward) {
        onDispose { if (allowReward) manager.cancelPendingReward() }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("ReiAnix Premium") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (BuildConfig.MONETIZATION_PREVIEW)
                    Text("Modo de teste · nenhuma cobrança real", color = MaterialTheme.colorScheme.primary)
                Text(if (state.premium) "Premium ativo" else "Compra única, sem mensalidade.", style = MaterialTheme.typography.titleMedium)
                Text("• Sem publicidade automática\n• Banners, papéis de parede, avatares e molduras\n• Atalhos personalizados\n• Preferências individuais por anime\n• Personalização avançada de legendas")
                if (!state.premium) {
                    Button(onClick = { manager.buy(activity) },
                        enabled = !state.busy && (BuildConfig.MONETIZATION_PREVIEW || state.price != null),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (BuildConfig.MONETIZATION_PREVIEW) "Testar Premium · R$ 9,90" else
                            state.price?.let { "Comprar Premium · " + it } ?: "Compra indisponível")
                    }
                    if (allowReward) {
                        HorizontalDivider()
                        Text("24 horas sem publicidade automática")
                        Text(if (state.hideAds) "Recompensa ativa." else "Assista a um anúncio opcional para remover banners publicitários e anúncios nativos por 24 horas.")
                        OutlinedButton(onClick = { manager.reward(activity, RewardKind.DAY) },
                            enabled = !state.hideAds && !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.hideAds) "24h sem anúncios · Ativo" else "Assistir anúncio")
                        }
                    }
                }
                TextButton(onClick = { manager.restore(activity) }, enabled = !state.busy) { Text("Restaurar compra") }
                if (allowReward && state.privacyRequired) TextButton(onClick = { manager.privacy(activity) }) {
                    Text("Opções de privacidade dos anúncios")
                }
                if (BuildConfig.MONETIZATION_PREVIEW) TextButton(onClick = manager::resetPreview, enabled = !state.busy) {
                    Text("Redefinir monetização de teste")
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Fechar") } }
    )
}

internal data class HomeAds(val banner: AdView?, val native: NativeAd?)

@Composable
internal fun rememberHomeAds(): HomeAds {
    val activity = LocalContext.current.monetizationActivity() ?: return HomeAds(null, null)
    val manager = remember(activity) { ReiAnixMonetization.from(activity) }
    val state by manager.state.collectAsStateWithLifecycle()
    val width = LocalConfiguration.current.screenWidthDp
    val canLoad = state.adsReady && !state.hideAds
    var banner by remember { mutableStateOf<AdView?>(null) }
    var native by remember { mutableStateOf<NativeAd?>(null) }
    LaunchedEffect(activity, state.premium) {
        // Do not block the existing first frame or the artwork/thumbnail workers.
        delay(1200)
        manager.start(activity)
    }
    DisposableEffect(activity, canLoad, width) {
        var disposed = false
        var loadedNative: NativeAd? = null
        var bannerView: AdView? = null
        if (canLoad && width >= 340) {
            val view = AdView(activity)
            bannerView = view
            view.setAdSize(AdSize.BANNER)
            view.adUnitId = manager.bannerId()
            view.adListener = object : AdListener() {
                override fun onAdLoaded() { if (!disposed) banner = view }
                override fun onAdFailedToLoad(error: LoadAdError) { if (!disposed) banner = null }
            }
            view.loadAd(AdRequest.Builder().build())
            AdLoader.Builder(activity, manager.nativeId())
                .forNativeAd { ad ->
                    if (disposed) ad.destroy() else {
                        loadedNative?.destroy()
                        loadedNative = ad
                        native = ad
                    }
                }
                .build().loadAd(AdRequest.Builder().build())
        }
        onDispose {
            disposed = true
            banner = null
            native = null
            bannerView?.destroy()
            loadedNative?.destroy()
        }
    }
    return if (canLoad) HomeAds(banner, native) else HomeAds(null, null)
}

@Composable
internal fun HomeBannerAd(view: AdView) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Publicidade", style = MaterialTheme.typography.labelSmall)
        AndroidView(factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view
        }, modifier = Modifier.width(320.dp).height(50.dp))
    }
}

@Composable
internal fun HomeNativeAd(ad: NativeAd) {
    key(ad) {
        AndroidView(factory = { context ->
            val view = NativeAdView(context)
            val density = context.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(8), px(12), px(12))
                setBackgroundColor(Color.rgb(38, 38, 44))
            }
            val header = FrameLayout(context)
            val attribution = TextView(context).apply { text = "Publicidade"; setTextColor(Color.WHITE); textSize = 12f }
            header.addView(attribution, FrameLayout.LayoutParams(-1, px(28)).apply { rightMargin = px(40) })
            val choices = AdChoicesView(context)
            header.addView(choices, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END or android.view.Gravity.TOP))
            content.addView(header)
            val headline = TextView(context).apply { text = ad.headline; textSize = 16f; setTextColor(Color.WHITE) }
            content.addView(headline)
            val media = MediaView(context)
            if (ad.mediaContent != null) {
                content.addView(media, LinearLayout.LayoutParams(-1, px(150)))
                view.mediaView = media
                media.mediaContent = ad.mediaContent
            }
            val body = TextView(context).apply { text = ad.body.orEmpty(); setTextColor(Color.LTGRAY); visibility = if (ad.body == null) android.view.View.GONE else android.view.View.VISIBLE }
            content.addView(body)
            val cta = android.widget.Button(context).apply {
                text = ad.callToAction.orEmpty()
                visibility = if (ad.callToAction == null) android.view.View.GONE else android.view.View.VISIBLE
                isAllCaps = false
            }
            content.addView(cta)
            view.addView(content, FrameLayout.LayoutParams(-1, -2))
            view.adChoicesView = choices
            view.headlineView = headline
            view.bodyView = body
            view.callToActionView = cta
            view.setNativeAd(ad)
            view
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}
