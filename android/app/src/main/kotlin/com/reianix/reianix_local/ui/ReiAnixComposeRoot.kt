package com.reianix.reianix_local.ui

import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reianix.reianix_local.data.profile.ReiAnixProfileStore
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import com.reianix.reianix_local.ui.theme.LocalReiAnixWallpaperVisible
import com.reianix.reianix_local.ui.theme.ReiAnixWallpaperLayer
import com.reianix.reianix_local.ui.theme.rememberWallpaperBitmap
import androidx.compose.ui.Modifier
import com.reianix.reianix_local.ui.theme.ReiAnixComposeTheme

@Keep
/**
 * Root Compose presentation boundary used by the native App Shell during the
 * incremental Flet -> Compose cutover. Domain state, navigation and Android
 * services remain outside this theme/background wrapper.
 */
@Composable
fun ReiAnixComposeRoot(
    themeMode: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { ReiAnixWallpaperStore.from(context) }
    val wallpaper by store.state.collectAsStateWithLifecycle()
    val bitmap = rememberWallpaperBitmap(store, wallpaper)
    val wallpaperVisible = wallpaper.imageName != null && bitmap != null
    LaunchedEffect(context) {
        ReiAnixProfileStore.from(context).removeLegacyBanner()
    }
    ReiAnixComposeTheme(themeMode = if (wallpaperVisible) "dark" else themeMode) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            if (wallpaperVisible) bitmap?.let {
                ReiAnixWallpaperLayer(it, wallpaper, Modifier.matchParentSize())
            }
            CompositionLocalProvider(LocalReiAnixWallpaperVisible provides wallpaperVisible) {
                content()
            }
        }
    }
}
