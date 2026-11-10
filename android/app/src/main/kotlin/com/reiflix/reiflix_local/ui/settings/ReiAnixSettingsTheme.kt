package com.reiflix.reiflix_local.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Settings use Android preference typography rather than the compact library typography.
 * Scoped here so the library, artwork and player retain their existing presentation.
 * Reference: recloudstream/cloudstream, main_settings.xml (SettingsItem: 50dp/20dp)
 * and custom_preference_material.xml (16dp vertical preference padding).
 */
private fun preferenceText(size: Int, lineHeight: Int) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

private val SettingsTypography = Typography(
    displayLarge = preferenceText(32, 40),
    displayMedium = preferenceText(28, 36),
    displaySmall = preferenceText(24, 32),
    headlineLarge = preferenceText(24, 32),
    headlineMedium = preferenceText(22, 28),
    headlineSmall = preferenceText(20, 28),
    titleLarge = preferenceText(20, 28),
    titleMedium = preferenceText(18, 24),
    titleSmall = preferenceText(16, 22),
    bodyLarge = preferenceText(16, 22),
    bodyMedium = preferenceText(14, 20),
    bodySmall = preferenceText(14, 20),
    labelLarge = preferenceText(14, 20),
    labelMedium = preferenceText(12, 16),
    labelSmall = preferenceText(12, 16),
)

@Composable
fun ReiAnixSettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = SettingsTypography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}

@Composable
fun ReiAnixSettingsRoot(content: @Composable () -> Unit) {
    ReiAnixSettingsTheme {
        com.reiflix.reiflix_local.ui.theme.ReiAnixResponsiveRoot(content = content)
    }
}
