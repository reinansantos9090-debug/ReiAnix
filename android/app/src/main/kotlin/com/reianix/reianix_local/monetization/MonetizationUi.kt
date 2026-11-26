package com.reianix.reianix_local.monetization

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.reianix.reianix_local.R
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

@Composable
internal fun SettingsPremiumButton() {
    val access = rememberPremiumAccess()
    PremiumCrownBadge("Premium", onClick = access.open)
}

@Composable
private fun PremiumCrownBadge(label: String, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.heightIn(min = 40.dp).then(
            if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
        ),
        shape = RoundedCornerShape(50),
        color = ComposeColor(0xFF172B47),
        contentColor = premiumBlue,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Canvas(Modifier.size(17.dp)) {
                val w = size.width / 24f
                val h = size.height / 24f
                drawPath(
                    Path().apply {
                        moveTo(2f * w, 6f * h)
                        lineTo(7f * w, 11f * h)
                        lineTo(12f * w, 3f * h)
                        lineTo(17f * w, 11f * h)
                        lineTo(22f * w, 6f * h)
                        lineTo(19f * w, 18f * h)
                        lineTo(5f * w, 18f * h)
                        close()
                        moveTo(5f * w, 20f * h)
                        lineTo(19f * w, 20f * h)
                        lineTo(19f * w, 22f * h)
                        lineTo(5f * w, 22f * h)
                        close()
                    },
                    color = premiumBlue,
                )
            }
            Text(label, color = premiumBlue, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private val premiumBlue = ComposeColor(0xFF3D8BFF)
private val premiumText = ComposeColor.White
private val premiumMuted = ComposeColor(0xFFBDBDBD)

@Composable
private fun PremiumBenefit(icon: ImageVector, title: String, description: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, null, tint = premiumText, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = premiumText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(description, color = premiumMuted, fontSize = 14.sp)
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
    var confirmReward by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = ComposeColor.Black, contentColor = premiumText) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = premiumText)
                    }
                    Text("ReiAnix Premium", color = premiumText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
                Column(
                    Modifier.weight(1f).widthIn(max = 620.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (state.premium) {
                            PremiumCrownBadge("Premium ativo")
                        } else {
                            if (state.trialActive) {
                                PremiumCrownBadge("Premium temporário ativo")
                            }
                            if (price != null) {
                                Text("Somente", color = premiumMuted, fontSize = 14.sp)
                            }
                            Text(price ?: "Preço indisponível",
                                color = premiumBlue, fontSize = if (price == null) 28.sp else 40.sp,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Text("Compra única · Acesso vitalício", color = premiumText, fontSize = 14.sp,
                                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                            Text("Sem mensalidades", color = premiumMuted, fontSize = 14.sp)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = ComposeColor(0xFF1C1C1C),
                        contentColor = premiumText,
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Benefícios Premium", color = premiumBlue, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (state.hasPremium) {
                                    val unlockedGreen = ComposeColor(0xFF4CAF50)
                                    Icon(Icons.Filled.CheckCircle, null, tint = unlockedGreen,
                                        modifier = Modifier.size(16.dp))
                                    Text("Desbloqueados", color = unlockedGreen, fontSize = 12.sp)
                                }
                            }
                            PremiumBenefit(Icons.Filled.VerifiedUser, "Sem anúncios", "Navegue sem publicidade.")
                            PremiumBenefit(Icons.Filled.Palette, "Personalização completa", "Todas as artes desbloqueadas.")
                            PremiumBenefit(Icons.Filled.Tune, "Atalhos personalizados", "Organize os controles do player.")
                            PremiumBenefit(Icons.Filled.Settings, "Preferências por anime", "Configurações individuais.")
                            PremiumBenefit(Icons.Filled.Subtitles, "Legendas avançadas", "Personalize fontes e cores.")
                            PremiumBenefit(Icons.Filled.Favorite, "Apoie o desenvolvimento", "Ajude o ReiAnix a evoluir.")
                        }
                    }
                    if (!state.premium && allowReward &&
                        (BuildConfig.MONETIZATION_PREVIEW || BuildConfig.MONETIZATION_LIVE_ADS)) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = ComposeColor(0xFF1C1C1C),
                            contentColor = premiumText,
                        ) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                                Text("Experimente gratuitamente", color = premiumBlue, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(enabled = !state.trialActive && !state.busy) { confirmReward = true }
                                        .heightIn(min = 68.dp).padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    Icon(Icons.Filled.PlayCircleFilled, null, tint = premiumText, modifier = Modifier.size(26.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("1 dia de Premium", color = premiumText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                        Text(if (state.trialActive) "Premium temporário ativo." else
                                            "Assista a um anúncio para liberar todos os benefícios.",
                                            color = premiumMuted, fontSize = 14.sp)
                                    }
                                    if (!state.trialActive) Icon(Icons.Filled.ChevronRight, null,
                                        tint = premiumText, modifier = Modifier.size(22.dp))
                                }
                            }
                        }
                    }
                    if (allowReward && state.privacyRequired) TextButton(onClick = { manager.privacy(activity) }) {
                        Text("Opções de privacidade dos anúncios", color = premiumBlue)
                    }
                    if (BuildConfig.MONETIZATION_PREVIEW) TextButton(onClick = manager::resetPreview,
                        enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Redefinir monetização de teste", color = premiumMuted, fontSize = 12.sp)
                    }
                    state.notice?.let { Text(it, color = premiumMuted, fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(color = ComposeColor.Black, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = premiumBlue)
                        Button(onClick = { if (state.premium) close() else manager.buy(activity) },
                            enabled = state.premium || (!state.busy && price != null),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = premiumBlue, contentColor = ComposeColor.White)) {
                            Text(if (state.premium) "Continuar" else "Desbloquear Premium",
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { manager.restore(activity) }, enabled = !state.busy) {
                            Text("Restaurar compra", color = premiumBlue, fontSize = 14.sp)
                        }
                        Text(if (BuildConfig.MONETIZATION_PREVIEW) "Modo de teste · nenhuma cobrança real" else
                            "Compra vinculada à sua conta Google Play.",
                            color = premiumMuted, fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    if (confirmReward) AlertDialog(
        onDismissRequest = { confirmReward = false },
        title = { Text("1 dia de Premium grátis") },
        text = { Text("Assista voluntariamente a um anúncio para liberar todos os benefícios Premium por 24 horas.") },
        confirmButton = { TextButton(onClick = { confirmReward = false; manager.reward(activity, RewardKind.DAY) }) { Text("Assistir anúncio") } },
        dismissButton = { TextButton(onClick = { confirmReward = false }) { Text("Cancelar") } },
    )
}

@Composable
internal fun rememberHomePremiumOfferVisible(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { ReiAnixMonetization.from(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    return !state.premium
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
    val rewardAvailable = BuildConfig.MONETIZATION_PREVIEW || BuildConfig.MONETIZATION_LIVE_ADS
    val remaining = ((state.until - now).coerceAtLeast(0) + 59_999) / 60_000
    Surface(modifier = modifier.fillMaxWidth(),
        color = ComposeColor.Black, contentColor = ComposeColor.White) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Adaptive launcher icons must be rasterized, not loaded with painterResource.
                        val launcherIcon = remember(context) {
                            runCatching {
                                val size = (34 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                                context.getDrawable(R.mipmap.ic_launcher)
                                    ?.toBitmap(width = size, height = size)
                                    ?.asImageBitmap()
                            }.getOrNull()
                        }
                        if (launcherIcon != null) {
                            Image(bitmap = launcherIcon, contentDescription = null, modifier = Modifier.size(34.dp))
                        }
                        Text("ReiAnix Premium", fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, lineHeight = 17.sp)
                    }
                    Text(if (state.trialActive) "${remaining / 60}h ${remaining % 60}min restantes" else if (rewardAvailable) "1 dia gratuito" else "Recursos exclusivos",
                        color = premiumMuted, fontSize = 12.sp, lineHeight = 15.sp)
                }
                val compactPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                val buttonColors = ButtonDefaults.buttonColors(
                    containerColor = premiumBlue, contentColor = ComposeColor.White)
                if (rewardAvailable) Button(onClick = { confirm = true }, enabled = !state.trialActive && !state.busy,
                    shape = RoundedCornerShape(50), colors = buttonColors,
                    contentPadding = compactPadding) {
                    Text(if (state.trialActive) "Ativo" else "1 dia grátis", fontSize = 12.sp,
                        maxLines = 1, textAlign = TextAlign.Center)
                }
                Button(onClick = access.open, shape = RoundedCornerShape(50),
                    colors = buttonColors, contentPadding = compactPadding) {
                    Text("Fazer upgrade", fontSize = 12.sp, maxLines = 1, textAlign = TextAlign.Center)
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
    val cache = remember(activity) { ActivityAdCache.from(activity) }
    val manager = remember(activity) { ReiAnixMonetization.from(activity) }
    val state by manager.state.collectAsStateWithLifecycle()
    val width = LocalConfiguration.current.screenWidthDp
    val enabled = width >= 340 && !state.hideAds
    val native = rememberCompactNativeAd(ActivityAdCache.HOME_NATIVE, enabled)
    DisposableEffect(cache, enabled) {
        if (enabled) cache.acquireBanner()
        onDispose { if (enabled) cache.releaseBanner() }
    }
    return if (enabled && state.adsReady) HomeAds(cache.banner, native) else HomeAds(null, null)
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
    val activity = LocalContext.current.monetizationActivity() ?: return
    val cache = remember(activity) { ActivityAdCache.from(activity) }
    key(ad) {
        AndroidView(factory = { context ->
            cache.nativeView(ad) {
            val view = NativeAdView(context)
            val density = context.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).toInt()
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(8), px(12), px(12))
                setBackgroundColor(Color.BLACK)
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
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF3D8BFF.toInt())
                        cornerRadius = px(8).toFloat()
                    },
                    null,
                )
            }
            content.addView(cta)
            view.addView(content, FrameLayout.LayoutParams(-1, -2))
            view.adChoicesView = choices
            view.headlineView = headline
            view.bodyView = body
            view.callToActionView = cta
            view.setNativeAd(ad)
            view
            }
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}


/** Retain one valid native creative per placement while navigating. */
@Composable
internal fun rememberCompactNativeAd(placementKey: String, enabled: Boolean = true): NativeAd? {
    val activity = LocalContext.current.monetizationActivity() ?: return null
    val manager = remember(activity) { ReiAnixMonetization.from(activity) }
    val state by manager.state.collectAsStateWithLifecycle()
    val cache = remember(activity) { ActivityAdCache.from(activity) }
    val slot = remember(cache, placementKey) { cache.slot(placementKey) }
    val active = enabled && !state.hideAds
    LaunchedEffect(activity, active) {
        if (active) {
            // Keep ad initialization off the first content render.
            delay(1200)
            manager.start(activity)
        }
    }
    DisposableEffect(cache, slot, active) {
        if (active) cache.acquire(slot)
        onDispose { if (active) cache.release(slot) }
    }
    return if (active && state.adsReady) slot.ad else null
}

@Composable
internal fun CompactNativeAd(ad: NativeAd, modifier: Modifier = Modifier) {
    val activity = LocalContext.current.monetizationActivity() ?: return
    val cache = remember(activity) { ActivityAdCache.from(activity) }
    key(ad) {
        AndroidView(
            modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
            factory = { context ->
                cache.nativeView(ad) {
            val view = NativeAdView(context)
                val density = context.resources.displayMetrics.density
                fun px(dp: Int) = (dp * density).toInt()
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.BLACK)
                    setPadding(px(8), px(4), px(8), px(8))
                }
                val header = FrameLayout(context)
                val attribution = TextView(context).apply {
                    text = "Publicidade"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                }
                header.addView(attribution, FrameLayout.LayoutParams(-1, px(26)).apply {
                    rightMargin = px(40)
                })
                val choices = AdChoicesView(context)
                header.addView(choices, FrameLayout.LayoutParams(-2, -2,
                    android.view.Gravity.TOP or android.view.Gravity.END))
                content.addView(header)

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                ad.icon?.drawable?.let { drawable ->
                    val icon = android.widget.ImageView(context).apply {
                        setImageDrawable(drawable)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    row.addView(icon, LinearLayout.LayoutParams(px(44), px(44)).apply {
                        marginEnd = px(8)
                    })
                    view.iconView = icon
                }
                val textColumn = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val headline = TextView(context).apply {
                    text = ad.headline
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                textColumn.addView(headline)
                val body = TextView(context).apply {
                    text = ad.body.orEmpty()
                    textSize = 12f
                    setTextColor(Color.LTGRAY)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                }
                textColumn.addView(body)
                row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
                val cta = android.widget.Button(context).apply {
                    text = ad.callToAction.orEmpty()
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setPadding(px(12), px(4), px(12), px(4))
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = px(44)
                    minimumHeight = px(44)
                    maxLines = 2
                    visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(0xFF3D8BFF.toInt())
                            cornerRadius = px(22).toFloat()
                        },
                        null,
                    )
                }
                row.addView(cta, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
                content.addView(row, LinearLayout.LayoutParams(-1, -2))
                view.addView(content, FrameLayout.LayoutParams(-1, -2))
                view.adChoicesView = choices
                view.headlineView = headline
                view.bodyView = body
                view.callToActionView = cta
                view.setNativeAd(ad)
                view
                }
            },
        )
    }
}
