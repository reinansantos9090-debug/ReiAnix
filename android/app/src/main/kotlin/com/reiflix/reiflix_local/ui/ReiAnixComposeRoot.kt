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
