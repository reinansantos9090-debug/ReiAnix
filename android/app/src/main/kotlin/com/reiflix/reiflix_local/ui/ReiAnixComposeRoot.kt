package com.reiflix.reiflix_local.ui

import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import com.reiflix.reiflix_local.ui.theme.ReiAnixComposeTheme

@Keep
/**
 * Reversible Compose host boundary.
 *
 * It is not attached to MainActivity in Prompt 01, so the current Flet UI and
 * navigation continue to run unchanged while Compose is introduced.
 */
@Composable
fun ReiAnixComposeRoot(
    themeMode: String? = null,
    content: @Composable () -> Unit,
) {
    ReiAnixComposeTheme(themeMode = themeMode, content = content)
}
