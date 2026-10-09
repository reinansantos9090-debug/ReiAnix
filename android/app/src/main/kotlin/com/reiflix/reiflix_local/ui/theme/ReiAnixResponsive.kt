package com.reiflix.reiflix_local.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

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
    val mediumWidth = ReiAnixTokens.Responsive.mediumWidth
    val expandedWidth = ReiAnixTokens.Responsive.expandedWidth
    val mediumHeight = ReiAnixTokens.Responsive.mediumHeight
    val expandedHeight = ReiAnixTokens.Responsive.expandedHeight
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
    private fun preferredCardWidth(preference: String): Dp =
        when (preference.trim().lowercase()) {
            "small" -> ReiAnixTokens.Dimensions.cardWidthSmall
            "large" -> ReiAnixTokens.Dimensions.cardWidthLarge
            else -> ReiAnixTokens.Dimensions.cardWidthMedium
        }

    fun libraryGridMinWidth(preference: String): Dp {
        val base = when (preference.trim().lowercase()) {
            "small" -> ReiAnixTokens.Dimensions.gridMinWidthSmall
            "large" -> ReiAnixTokens.Dimensions.gridMinWidthLarge
            else -> ReiAnixTokens.Dimensions.gridMinWidthMedium
        }
        return when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> base
            ReiAnixWindowWidthClass.MEDIUM -> maxOf(base, ReiAnixTokens.Responsive.mediumLibraryGridMinWidth)
            ReiAnixWindowWidthClass.EXPANDED -> maxOf(base, ReiAnixTokens.Responsive.expandedLibraryGridMinWidth)
        }
    }

    fun homeCardWidth(preference: String): Dp {
        val base = preferredCardWidth(preference)
        return when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> base
            ReiAnixWindowWidthClass.MEDIUM -> maxOf(base, ReiAnixTokens.Responsive.mediumHomeCardWidth)
            ReiAnixWindowWidthClass.EXPANDED -> maxOf(base, ReiAnixTokens.Responsive.expandedHomeCardWidth)
        }.coerceAtMost(ReiAnixTokens.Dimensions.gridMaxItemWidth)
    }

    val homeContinueCardWidth: Dp
        get() = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> ReiAnixTokens.Responsive.compactContinueCardWidth
            ReiAnixWindowWidthClass.MEDIUM -> ReiAnixTokens.Responsive.mediumContinueCardWidth
            ReiAnixWindowWidthClass.EXPANDED -> ReiAnixTokens.Responsive.expandedContinueCardWidth
        }

    val searchGridMinWidth: Dp
        get() = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> ReiAnixTokens.Responsive.compactSearchGridMinWidth
            ReiAnixWindowWidthClass.MEDIUM -> ReiAnixTokens.Responsive.mediumSearchGridMinWidth
            ReiAnixWindowWidthClass.EXPANDED -> ReiAnixTokens.Responsive.expandedSearchGridMinWidth
        }

    fun gridItemMinWidth(preference: String): Dp = libraryGridMinWidth(preference)

    val myListGridMinWidth: Dp
        get() = libraryGridMinWidth("medium")

    fun homeHeroHeight(): Dp {
        if (isLandscape || heightClass == ReiAnixWindowHeightClass.COMPACT) {
            return (maxWidth * 0.50f).coerceIn(
                ReiAnixTokens.Responsive.homeHeroLandscapeMinHeight,
                ReiAnixTokens.Responsive.homeHeroLandscapeMaxHeight,
            )
        }
        return (maxWidth * 0.56f).coerceIn(
            ReiAnixTokens.Responsive.homeHeroPortraitMinHeight,
            if (widthClass == ReiAnixWindowWidthClass.EXPANDED) {
                ReiAnixTokens.Responsive.homeHeroExpandedMaxHeight
            } else {
                ReiAnixTokens.Responsive.homeHeroPortraitMaxHeight
            },
        )
    }

    fun detailsHeroHeight(): Dp {
        if (isLandscape) {
            return (maxWidth * 0.44f).coerceIn(
                ReiAnixTokens.Responsive.detailsHeroLandscapeMinHeight,
                ReiAnixTokens.Responsive.detailsHeroLandscapeMaxHeight,
            )
        }
        return (maxWidth * 0.66f).coerceIn(
            ReiAnixTokens.Dimensions.detailsHeroHeight,
            ReiAnixTokens.Dimensions.detailsHeroMaxHeight,
        )
    }

    companion object {
        fun compactDefaults(): ReiAnixResponsiveMetrics =
            ReiAnixResponsiveMetrics(
                maxWidth = ReiAnixTokens.Responsive.compactWindowWidth,
                maxHeight = ReiAnixTokens.Responsive.compactWindowHeight,
                widthClass = ReiAnixWindowWidthClass.COMPACT,
                heightClass = ReiAnixWindowHeightClass.MEDIUM,
                isLandscape = false,
                horizontalPadding = ReiAnixTokens.Dimensions.screenHorizontalPadding,
                contentMaxWidth = ReiAnixTokens.Responsive.compactWindowWidth,
                settingsMaxWidth = ReiAnixTokens.Responsive.compactWindowWidth,
                textMaxWidth = ReiAnixTokens.Responsive.compactWindowWidth,
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
            ReiAnixWindowWidthClass.MEDIUM -> ReiAnixTokens.Responsive.mediumHorizontalPadding
            ReiAnixWindowWidthClass.EXPANDED -> ReiAnixTokens.Responsive.expandedHorizontalPadding
        }

        val contentMaxWidth = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> maxWidth
            ReiAnixWindowWidthClass.MEDIUM -> ReiAnixTokens.Responsive.mediumContentMaxWidth
            ReiAnixWindowWidthClass.EXPANDED -> ReiAnixTokens.Responsive.expandedContentMaxWidth
        }

        val settingsMaxWidth = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> maxWidth
            ReiAnixWindowWidthClass.MEDIUM -> ReiAnixTokens.Responsive.mediumSettingsMaxWidth
            ReiAnixWindowWidthClass.EXPANDED -> ReiAnixTokens.Responsive.expandedSettingsMaxWidth
        }

        val textMaxWidth = when (widthClass) {
            ReiAnixWindowWidthClass.COMPACT -> maxWidth
            ReiAnixWindowWidthClass.MEDIUM -> ReiAnixTokens.Responsive.mediumTextMaxWidth
            ReiAnixWindowWidthClass.EXPANDED -> ReiAnixTokens.Responsive.expandedTextMaxWidth
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                content()
            }
        }
    }
}
