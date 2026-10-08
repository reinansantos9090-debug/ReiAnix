package com.reiflix.reiflix_local.ui

import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme

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
    ReiAnixComposeTheme(themeMode = themeMode) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            content()
        }
    }
}
