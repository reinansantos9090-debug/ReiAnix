package com.reiflix.reiflix_local.ui.theme

import androidx.annotation.Keep
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val ReiAnixLightColorScheme = lightColorScheme(
    primary = ReiAnixTokens.Colors.lightPrimary,
    onPrimary = ReiAnixTokens.Colors.lightOnPrimary,
    primaryContainer = ReiAnixTokens.Colors.lightPrimaryContainer,
    onPrimaryContainer = ReiAnixTokens.Colors.lightOnPrimaryContainer,
    secondary = ReiAnixTokens.Colors.lightSecondary,
    onSecondary = ReiAnixTokens.Colors.lightOnSecondary,
    secondaryContainer = ReiAnixTokens.Colors.lightSecondaryContainer,
    onSecondaryContainer = ReiAnixTokens.Colors.lightOnSecondaryContainer,
    tertiary = ReiAnixTokens.Colors.lightTertiary,
    onTertiary = ReiAnixTokens.Colors.lightOnTertiary,
    tertiaryContainer = ReiAnixTokens.Colors.lightTertiaryContainer,
    onTertiaryContainer = ReiAnixTokens.Colors.lightOnTertiaryContainer,
    error = ReiAnixTokens.Colors.lightError,
    onError = ReiAnixTokens.Colors.lightOnError,
    errorContainer = ReiAnixTokens.Colors.lightErrorContainer,
    onErrorContainer = ReiAnixTokens.Colors.lightOnErrorContainer,
    background = ReiAnixTokens.Colors.lightBackground,
    onBackground = ReiAnixTokens.Colors.lightOnBackground,
    surface = ReiAnixTokens.Colors.lightSurface,
    onSurface = ReiAnixTokens.Colors.lightOnSurface,
    surfaceVariant = ReiAnixTokens.Colors.lightSurfaceVariant,
    surfaceDim = ReiAnixTokens.Colors.lightSurfaceVariant,
    surfaceBright = ReiAnixTokens.Colors.lightSurface,
    surfaceContainerLowest = ReiAnixTokens.Colors.lightSurface,
    surfaceContainerLow = ReiAnixTokens.Colors.lightSurfaceVariant,
    surfaceContainer = ReiAnixTokens.Colors.lightSurfaceVariant,
    surfaceContainerHigh = ReiAnixTokens.Colors.lightSurfaceDialog,
    surfaceContainerHighest = ReiAnixTokens.Colors.lightSurfaceDialog,
    onSurfaceVariant = ReiAnixTokens.Colors.lightMuted,
    outline = ReiAnixTokens.Colors.lightBorder,
    outlineVariant = ReiAnixTokens.Colors.lightDivider,
    inverseSurface = ReiAnixTokens.Colors.inverseSurface,
    inverseOnSurface = ReiAnixTokens.Colors.inverseOnSurface,
    inversePrimary = ReiAnixTokens.Colors.inversePrimary,
    scrim = ReiAnixTokens.Colors.overlay,
)

private val ReiAnixDarkColorScheme = darkColorScheme(
    primary = ReiAnixTokens.Colors.primary,
    onPrimary = ReiAnixTokens.Colors.onPrimary,
    primaryContainer = ReiAnixTokens.Colors.primaryContainer,
    onPrimaryContainer = ReiAnixTokens.Colors.onPrimaryContainer,
    secondary = ReiAnixTokens.Colors.secondary,
    onSecondary = ReiAnixTokens.Colors.onSecondary,
    secondaryContainer = ReiAnixTokens.Colors.secondaryContainer,
    onSecondaryContainer = ReiAnixTokens.Colors.onSecondaryContainer,
    tertiary = ReiAnixTokens.Colors.tertiary,
    onTertiary = ReiAnixTokens.Colors.onTertiary,
    tertiaryContainer = ReiAnixTokens.Colors.tertiaryContainer,
    onTertiaryContainer = ReiAnixTokens.Colors.onTertiaryContainer,
    error = ReiAnixTokens.Colors.error,
    onError = ReiAnixTokens.Colors.onError,
    errorContainer = ReiAnixTokens.Colors.errorContainer,
    onErrorContainer = ReiAnixTokens.Colors.onErrorContainer,
    background = ReiAnixTokens.Colors.background,
    onBackground = ReiAnixTokens.Colors.onBackground,
    surface = ReiAnixTokens.Colors.surface,
    onSurface = ReiAnixTokens.Colors.onSurface,
    surfaceVariant = ReiAnixTokens.Colors.surfaceVariant,
    surfaceDim = ReiAnixTokens.Colors.background,
    surfaceBright = ReiAnixTokens.Colors.surfaceRaised,
    surfaceContainerLowest = ReiAnixTokens.Colors.background,
    surfaceContainerLow = ReiAnixTokens.Colors.backgroundSecondary,
    surfaceContainer = ReiAnixTokens.Colors.surface,
    surfaceContainerHigh = ReiAnixTokens.Colors.surfaceRaised,
    surfaceContainerHighest = ReiAnixTokens.Colors.surfaceDialog,
    onSurfaceVariant = ReiAnixTokens.Colors.muted,
    outline = ReiAnixTokens.Colors.border,
    outlineVariant = ReiAnixTokens.Colors.divider,
    inverseSurface = ReiAnixTokens.Colors.inverseSurface,
    inverseOnSurface = ReiAnixTokens.Colors.inverseOnSurface,
    inversePrimary = ReiAnixTokens.Colors.inversePrimary,
    scrim = ReiAnixTokens.Colors.overlay,
)

/**
 * Shared Material 3 theme for all future native ReiAnix screens.
 *
 * This step establishes visual tokens only. It does not select a screen,
 * create navigation, or connect to application/domain state.
 */
@Keep
@Composable
fun ReiAnixComposeTheme(
    themeMode: String? = null,
    content: @Composable () -> Unit,
) {
    val normalizedMode = themeMode?.trim()?.lowercase().orEmpty()
    val darkTheme = when (normalizedMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) ReiAnixDarkColorScheme else ReiAnixLightColorScheme,
        typography = ReiAnixTokens.typography,
        shapes = ReiAnixTokens.shapes,
        content = content,
    )
}
