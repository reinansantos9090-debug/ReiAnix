package com.reiflix.reiflix_local.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ReiAnix responsive window policy.
 *
 * Decisions are based only on the currently available Compose constraints.
 * No device model, brand, resolution fingerprint, or orientation-specific
 * device profile is used. The compact values intentionally preserve the
 * existing phone visual language while medium/expanded windows gain room
 * without making cards or text grow without bounds.
 */
enum class ReiAnixWindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

enum class ReiAnixWindowHeightClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

object ReiAnixResponsiveBreakpoints {
    val mediumWidth = 600.dp
    val expandedWidth = 840.dp
    val mediumHeight = 480.dp
    val expandedHeight = 800.dp
}

@Immutable
data class ReiAnixResponsiveMetrics(
    val maxWidth: Dp,
    val maxHeight: Dp,
    val widthClass: ReiAnixWindowWidthClass,
    val heightClass: ReiAnixWindowHeightClass,
    val isLandscape: Boolean,
    val horizontalPadding: Dp,
    val contentMaxWidth: Dp,
    val settingsMaxWidth: Dp,
    val textMaxWidth: Dp,
) {
    fun libraryGridMinWidth(preference: String): Dp {
        val base = when (preference.trim().lowercase()) {
            "small" -> 120.dp
            "large" -> 172.dp
            else -> 146.dp
        }
        return when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> base
            ReiAnixWindowWidthClass.MEDIUM -> maxOf(base, 132.dp)
            ReiAnixWindowWidthClass.EXPANDED -> maxOf(base, 156.dp)
        }
    }

    fun homeCardWidth(preference: String): Dp {
        val base = when (preference.trim().lowercase()) {
            "small" -> 120.dp
            "large" -> 172.dp
            else -> 146.dp
        }
        return when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> base
            ReiAnixWindowWidthClass.MEDIUM -> maxOf(base, 146.dp)
            ReiAnixWindowWidthClass.EXPANDED -> maxOf(base, 160.dp)
        }.coerceAtMost(184.dp)
    }

    val homeContinueCardWidth: Dp
        get() = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> ReiAnixTokens.Dimensions.homeContinueCardWidth
            ReiAnixWindowWidthClass.MEDIUM -> 180.dp
            ReiAnixWindowWidthClass.EXPANDED -> 220.dp
        }

    val searchGridMinWidth: Dp
        get() = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> 280.dp
            ReiAnixWindowWidthClass.MEDIUM -> 300.dp
            ReiAnixWindowWidthClass.EXPANDED -> 320.dp
        }

    val myListGridMinWidth: Dp
        get() = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> 300.dp
            ReiAnixWindowWidthClass.MEDIUM -> 320.dp
            ReiAnixWindowWidthClass.EXPANDED -> 360.dp
        }

    fun homeHeroHeight(): Dp {
        if (isLandscape || heightClass == ReiAnixWindowHeightClass.COMPACT) {
            return (maxWidth * 0.48f).coerceIn(220.dp, 300.dp)
        }
        return (maxWidth / 1.48f).coerceIn(
            300.dp,
            if (widthClass == ReiAnixWindowWidthClass.EXPANDED) 420.dp else 400.dp,
        )
    }

    fun detailsHeroHeight(): Dp {
        if (isLandscape) {
            return (maxWidth * 0.45f).coerceIn(220.dp, 300.dp)
        }
        return (maxWidth * 0.94f).coerceIn(
            ReiAnixTokens.Dimensions.detailsHeroHeight,
            ReiAnixTokens.Dimensions.detailsHeroMaxHeight,
        )
    }

    companion object {
        fun compactDefaults(): ReiAnixResponsiveMetrics =
            ReiAnixResponsiveMetrics(
                maxWidth = 360.dp,
                maxHeight = 720.dp,
                widthClass = ReiAnixWindowWidthClass.COMPACT,
                heightClass = ReiAnixWindowHeightClass.MEDIUM,
                isLandscape = false,
                horizontalPadding = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                contentMaxWidth = 360.dp,
                settingsMaxWidth = 360.dp,
                textMaxWidth = 360.dp,
            )
    }
}

val LocalReiAnixResponsiveMetrics = staticCompositionLocalOf {
    ReiAnixResponsiveMetrics.compactDefaults()
}

@Composable
fun ReiAnixResponsiveRoot(
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val widthClass = when {
            maxWidth >= ReiAnixResponsiveBreakpoints.expandedWidth ->
                ReiAnixWindowWidthClass.EXPANDED
            maxWidth >= ReiAnixResponsiveBreakpoints.mediumWidth ->
                ReiAnixWindowWidthClass.MEDIUM
            else -> ReiAnixWindowWidthClass.COMPACT
        }
        val heightClass = when {
            maxHeight >= ReiAnixResponsiveBreakpoints.expandedHeight ->
                ReiAnixWindowHeightClass.EXPANDED
            maxHeight >= ReiAnixResponsiveBreakpoints.mediumHeight ->
                ReiAnixWindowHeightClass.MEDIUM
            else -> ReiAnixWindowHeightClass.COMPACT
        }

        val horizontalPadding = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> ReiAnixTokens.Dimensions.screenHorizontalPadding
            ReiAnixWindowWidthClass.MEDIUM -> 24.dp
            ReiAnixWindowWidthClass.EXPANDED -> 28.dp
        }

        val contentMaxWidth = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> maxWidth
            ReiAnixWindowWidthClass.MEDIUM -> 960.dp
            ReiAnixWindowWidthClass.EXPANDED -> 1200.dp
        }

        val settingsMaxWidth = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> maxWidth
            ReiAnixWindowWidthClass.MEDIUM -> 720.dp
            ReiAnixWindowWidthClass.EXPANDED -> 840.dp
        }

        val textMaxWidth = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> maxWidth
            ReiAnixWindowWidthClass.MEDIUM -> 680.dp
            ReiAnixWindowWidthClass.EXPANDED -> 760.dp
        }

        val metrics = ReiAnixResponsiveMetrics(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            widthClass = widthClass,
            heightClass = heightClass,
            isLandscape = maxHeight.value.isFinite() && maxWidth > maxHeight,
            horizontalPadding = horizontalPadding,
            contentMaxWidth = contentMaxWidth,
            settingsMaxWidth = settingsMaxWidth,
            textMaxWidth = textMaxWidth,
        )

        CompositionLocalProvider(
            LocalReiAnixResponsiveMetrics provides metrics,
        ) {
            content()
        }
    }
}
