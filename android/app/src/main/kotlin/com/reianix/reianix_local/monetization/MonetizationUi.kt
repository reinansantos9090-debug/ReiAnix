package com.reianix.reianix_local.monetization

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.reianix.reianix_local.R
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
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
    return PremiumAccess(state.hasPremium) { open = true }
}

@Composable
internal fun MonetizationEntry() {
    val access = rememberPremiumAccess()
    TextButton(onClick = access.open, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(if (access.active) "ReiAnix Premium · Ativo" else "ReiAnix Premium")
    }
}

private val premiumBlue = ComposeColor(0xFF3D8BFF)
private val premiumText = ComposeColor.White
private val premiumMuted = ComposeColor(0xFFBDBDBD)

@Composable
private fun PremiumBenefit(icon: ImageVector, title: String, description: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(48.dp).background(ComposeColor(0xFF191919), CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = ComposeColor.White, modifier = Modifier.size(25.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = premiumText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = premiumMuted, fontSize = 13.sp)
        }
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
    val price = if (BuildConfig.MONETIZATION_PREVIEW) "R$ 9,90" else state.price
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = ComposeColor.Black) {
            BoxWithConstraints(Modifier.fillMaxSize().background(ComposeColor.Black).safeDrawingPadding()) {
                val compact = maxHeight < 480.dp
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp)) {
                        IconButton(onClick = close, modifier = Modifier.align(Alignment.CenterStart)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = premiumText)
                        }
                    }
                    Column(Modifier.weight(1f).widthIn(max = 620.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(if (compact) 4.dp else 20.dp))
                        Text("ReiAnix Premium", color = premiumText, fontSize = 29.sp,
                            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.background(premiumBlue.copy(alpha = .18f), RoundedCornerShape(30.dp))
                            .padding(horizontal = 22.dp, vertical = 8.dp)) {
                            Text(if (state.premium) "Premium ativo" else price?.let { "Somente " + it } ?: "Preço indisponível",
                                color = ComposeColor(0xFF92BFFF), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        if (state.trialActive && !state.premium) Text("Premium temporário ativo", color = premiumMuted, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier
                            .border(1.dp, ComposeColor(0xFFB49A60), RoundedCornerShape(30.dp))
                            .background(ComposeColor(0xFFB49A60).copy(alpha = .10f), RoundedCornerShape(30.dp))
                            .padding(horizontal = 16.dp, vertical = 5.dp)) {
                            Text("COMPRA ÚNICA", color = ComposeColor(0xFFE8CF8B), fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.fillMaxWidth().background(ComposeColor.White.copy(alpha = .05f), RoundedCornerShape(14.dp))
                            .border(1.dp, ComposeColor.White.copy(alpha = .12f), RoundedCornerShape(14.dp))
                            .padding(14.dp), contentAlignment = Alignment.Center) {
                            Text("Sem assinatura. Sem mensalidade.\nUma compra, sua para sempre.",
                                color = premiumText, textAlign = TextAlign.Center, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        PremiumBenefit(Icons.Filled.Block, "Experiência sem anúncios", "Assista e navegue sem publicidade automática.")
                        PremiumBenefit(Icons.Filled.Palette, "Personalização completa", "Todos os banners, papéis de parede, avatares e molduras.")
                        PremiumBenefit(Icons.Filled.Tune, "Atalhos personalizados", "Organize os controles do player do seu jeito.")
                        PremiumBenefit(Icons.Filled.Settings, "Preferências por anime", "Guarde configurações individuais de reprodução.")
                        PremiumBenefit(Icons.Filled.Subtitles, "Legendas avançadas", "Personalize fontes e cores das legendas.")
                        PremiumBenefit(Icons.Filled.Favorite, "Apoie o desenvolvimento", "Ajude o ReiAnix a continuar evoluindo.")
                        if (!state.premium && allowReward) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(color = ComposeColor.White.copy(alpha = .12f))
                            Spacer(Modifier.height(12.dp))
                            Text("1 dia de Premium grátis", color = premiumText, fontWeight = FontWeight.SemiBold)
                            Text("Assista a um anúncio para liberar todos os benefícios Premium por 24 horas. Após o prazo, você pode renovar.",
                                color = premiumMuted, textAlign = TextAlign.Center, fontSize = 13.sp)
                            OutlinedButton(onClick = { manager.reward(activity, RewardKind.DAY) },
                                enabled = !state.hideAds && !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.trialActive) "Premium temporário · Ativo" else "Assistir anúncio", color = premiumBlue)
                            }
                        }
                        if (allowReward && state.privacyRequired) TextButton(onClick = { manager.privacy(activity) }) {
                            Text("Opções de privacidade dos anúncios", color = premiumBlue)
                        }
                        if (BuildConfig.MONETIZATION_PREVIEW) TextButton(onClick = manager::resetPreview, enabled = !state.busy) {
                            Text("Redefinir monetização de teste", color = premiumMuted)
                        }
                        state.notice?.let { Text(it, color = premiumMuted, fontSize = 13.sp, textAlign = TextAlign.Center) }
                        Spacer(Modifier.height(18.dp))
                    }
                    Surface(color = ComposeColor.Black, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = premiumBlue)
                            Button(onClick = { if (state.premium) close() else manager.buy(activity) },
                                enabled = state.premium || (!state.busy && price != null),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = ComposeColor.White)) {
                                Text(if (state.premium) "Continuar" else if (BuildConfig.MONETIZATION_PREVIEW) "Testar Premium" else "Desbloquear Premium",
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(if (BuildConfig.MONETIZATION_PREVIEW) "Modo de teste · nenhuma cobrança real" else
                                "Compra vinculada à sua conta Google Play.",
                                color = premiumMuted, fontSize = 11.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp))
                            TextButton(onClick = { manager.restore(activity) }, enabled = !state.busy) {
                                Text("Já comprou? Restaurar compra", color = premiumBlue, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomePremiumOffer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.monetizationActivity() ?: return
    val manager = remember(context) { ReiAnixMonetization.from(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val access = rememberPremiumAccess()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(state.until) {
        while (state.until > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
        now = System.currentTimeMillis()
    }
    DisposableEffect(manager) { onDispose { manager.cancelPendingReward() } }
    if (state.premium) return
    val remaining = ((state.until - now).coerceAtLeast(0) + 59_999) / 60_000
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = ComposeColor(0xFF191919), contentColor = ComposeColor.White) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.mipmap.ic_launcher), contentDescription = null, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text("ReiAnix Premium", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(if (state.trialActive) "${remaining / 60}h ${remaining % 60}min restantes" else "1 dia gratuito",
                        color = premiumMuted, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { confirm = true }, enabled = !state.trialActive && !state.busy,
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = premiumBlue)) {
                    Text(if (state.trialActive) "Ativo" else "Ativar 1 dia grátis", fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = access.open, modifier = Modifier.weight(1f)) {
                    Text("Fazer upgrade", color = ComposeColor.White, fontSize = 12.sp)
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = premiumBlue)
            if (!state.trialActive) state.notice?.let { Text(it, fontSize = 11.sp, color = premiumMuted) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("1 dia de Premium grátis") },
        text = { Text("Assista voluntariamente a um anúncio para liberar todos os recursos Premium e remover a publicidade automática por 24 horas.") },
        confirmButton = { TextButton(onClick = { confirm = false; manager.reward(activity, RewardKind.DAY) }) { Text("Assistir anúncio") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
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
