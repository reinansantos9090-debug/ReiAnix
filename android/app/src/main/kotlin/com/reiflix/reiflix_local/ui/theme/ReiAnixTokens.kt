package com.reiflix.reiflix_local.ui.theme

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Single source of truth for the ReiAnix Compose visual language.
 *
 * These values describe presentation only. They intentionally contain no
 * catalog, storage, playback, persistence, or navigation rules.
 */
object ReiAnixTokens {
    object Colors {
        // ReiAnix visual language: near-black canvas, cool dark surfaces and one
        // consistent electric-blue interaction accent. Keep semantic aliases here
        // so Compose screens never need ad-hoc hex/RGB values.
        val background = Color(0xFF05070C)
        val backgroundSecondary = Color(0xFF090D14)
        val surface = Color(0xFF0D121B)
        val surfaceVariant = Color(0xFF151C27)
        val surfaceRaised = Color(0xFF1C2532)
        val surfaceCard = surfaceVariant
        val surfaceDialog = Color(0xFF182331)
        val surfaceSheet = Color(0xFF111822)
        val surfacePlayer = Color(0xFF020408)
        val surfaceSelected = Color(0xFF173B6D)
        val surfaceNavigation = Color(0xFF0B1017)
        val playerControl = Color.White
        val playerScrim = Color.Black

        val primary = Color(0xFF3D8BFF)
        val primaryContainer = Color(0xFF173D78)
        // Dark-theme primary text uses a dark navy to keep the existing electric-blue
        // accent while bringing normal button text above WCAG AA contrast.
        val onPrimary = Color(0xFF07101E)
        val onPrimaryContainer = Color(0xFFE3EEFF)
        val active = primary
        val focus = Color(0xFF7FB3FF)
        val pressed = Color(0xFF2D6FD0)

        val secondary = Color(0xFF8BAFFF)
        val secondaryContainer = Color(0xFF203A66)
        val onSecondary = Color(0xFF07101E)
        val onSecondaryContainer = Color(0xFFDDE9FF)

        val tertiary = Color(0xFFB18CFF)
        val tertiaryContainer = Color(0xFF38275D)
        val onTertiary = Color(0xFF160D2B)
        val onTertiaryContainer = Color(0xFFEEDFFF)

        val text = Color(0xFFF5F7FB)
        val textMuted = Color(0xFFA7B0C0)
        val textTertiary = Color(0xFF778398)
        val textDisabled = Color(0xFF525B6A)
        val textOnPrimary = onPrimary
        val border = Color(0xFF334255)
        val borderStrong = Color(0xFF4D6484)
        val divider = Color(0xFF202B3A)

        val error = Color(0xFFFF6B6B)
        val onError = Color(0xFF240608)
        val errorContainer = Color(0xFF5A1A1F)
        val onErrorContainer = Color(0xFFFFDADD)
        val info = secondary

        val success = Color(0xFF4ADE80)
        val warning = Color(0xFFF6C85F)
        val disabledSurface = Color(0xFF121821)
        val overlay = Color(0xB3000000)
        val overlayStrong = Color(0xCC000000)
        val statusContainerAlpha = 0.16f
        val disabledContentAlpha = 0.55f
        val subtleBorderAlpha = 0.55f
        val surfaceOverlayAlpha = 0.84f
        val disabledContainerAlpha = 0.45f

        val inverseSurface = Color(0xFFE9EEF7)
        val inverseOnSurface = Color(0xFF1A1E27)
        val inversePrimary = Color(0xFF2D6FD0)
        val lightPrimary = Color(0xFF2563C7)
        val lightOnPrimary = Color(0xFFFFFFFF)
        val lightPrimaryContainer = Color(0xFFD8E7FF)
        val lightOnPrimaryContainer = Color(0xFF001A3D)
        val lightSecondary = Color(0xFF4D5F7D)
        val lightOnSecondary = Color(0xFFFFFFFF)
        val lightSecondaryContainer = Color(0xFFD9E5FF)
        val lightOnSecondaryContainer = Color(0xFF091B36)
        val lightTertiary = Color(0xFF6750A4)
        val lightOnTertiary = Color(0xFFFFFFFF)
        val lightTertiaryContainer = Color(0xFFEADDFF)
        val lightOnTertiaryContainer = Color(0xFF21005D)
        val lightError = Color(0xFFBA1A1A)
        val lightOnError = Color(0xFFFFFFFF)
        val lightErrorContainer = Color(0xFFFFDAD6)
        val lightOnErrorContainer = Color(0xFF410002)
        val lightBackground = Color(0xFFF7F9FC)
        val lightSurface = Color(0xFFFFFFFF)
        val lightSurfaceVariant = Color(0xFFE9EEF6)
        val lightSurfaceDialog = Color(0xFFF1F5FB)
        val lightText = Color(0xFF171A20)
        val lightTextMuted = Color(0xFF5E6572)
        val lightBorder = Color(0xFF727A88)
        val lightDivider = Color(0xFFD1D6DF)
    }

    object Spacing {
        val none = 0.dp
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 20.dp
        val xxl = 24.dp
        val xxxl = 32.dp
        val huge = 40.dp
        val section = 28.dp
        val screen = 20.dp
    }

    object Dimensions {
        val screenHorizontalPadding = 20.dp
        val screenTopPadding = 8.dp
        val screenBottomPadding = 24.dp
        val sectionGap = 24.dp
        val sectionTitleGap = 8.dp
        val cardMinHeight = 88.dp
        val buttonMinHeight = 40.dp
        val chipMinHeight = 32.dp
        val touchTarget = 48.dp
        val iconSmall = 18.dp
        val iconMedium = 22.dp
        val loadingIndicatorSize = 20.dp
        val loadingIndicatorStroke = 2.dp
        val accountAvatarSize = 72.dp
        val topBarMinHeight = 56.dp
        val bottomNavigationMinHeight = 64.dp
        val bottomNavigationIndicatorHeight = 32.dp
        val artworkMinSize = 96.dp
        val animeCardWidth = 154.dp
        val continueCardWidth = 250.dp
        val continuePosterWidth = 76.dp
        val continuePosterHeight = 108.dp
        // Minha Lista follows the reference's compact landscape thumbnail rather than
        // the portrait poster used by the generic card grid.
        val myListPosterWidth = 96.dp
        val myListPosterHeight = 64.dp
        val myListRowMinHeight = 86.dp
        val posterAspectRatio = 0.7f
        // Three-column phone layouts stay viable while Adaptive still scales the grid
        // on wider/landscape surfaces. A separate cap prevents tablet posters from
        // growing indefinitely.
        val libraryGridMinWidth = 96.dp
        val libraryGridMaxItemWidth = 184.dp
        val searchGridMinWidth = 150.dp
        val episodeThumbnailWidth = 120.dp
        val episodeThumbnailHeight = 68.dp
        val detailsEpisodeThumbnailFraction = 0.27f
        val detailsHeroHeight = 250.dp
        val searchFieldHeight = 56.dp
        val detailsHeroMaxHeight = 420.dp
        val detailsHeroWideBreakpoint = 600.dp
        val detailsHeroPosterWidth = 112.dp
        val emptyStateMinHeight = 280.dp
        val organizeGenreCardWidth = 170.dp
        val organizeCategoryCardWidth = 156.dp
        val detailsSeasonCardWidth = 340.dp
        val detailsSeasonPreviewWidth = 108.dp
        val detailsSeasonPreviewHeight = 72.dp
        val detailsInfoLabelWidth = 96.dp
        val homeHeroHeight = 282.dp
        val homeCardWidth = 122.dp
        val homeContinueCardWidth = 132.dp
        val homeLandscapeArtworkAspectRatio = 1.55f
        val progressHeight = 4.dp
        val dividerHeight = 1.dp
        val borderWidth = 1.dp
    }

    object Shapes {
        val chip = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        val small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        val button = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        val card = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
        val large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        val artwork = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        val hero = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        val dialog = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        val sheet = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        val textField = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    }

    object Elevation {
        val none = 0.dp
        val card = 0.dp
        val raised = 2.dp
        val prominent = 4.dp
    }

    object Motion {
        const val stateChangeMillis = 180
        const val contentEnterMillis = 240
        const val contentExitMillis = 160
    }

    object TypographyTokens {
        val display = TextStyle(
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        val brandTitle = TextStyle(
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        val heroTitle = TextStyle(
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        val screenTitle = TextStyle(
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        val emptyStateTitle = TextStyle(
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val sectionTitle = TextStyle(
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val cardTitle = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val subtitle = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        )
        val body = TextStyle(
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Normal,
        )
        val bodySecondary = TextStyle(
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Normal,
        )
        val metadata = TextStyle(
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Normal,
        )
        val episode = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        val label = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val chip = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val button = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val navigationLabel = TextStyle(
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        val playerTopLabel = TextStyle(
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val playerTechnical = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        val playerTime = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        val playerGlyph = TextStyle(
            fontSize = 17.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
        )
        val playerActionGlyph = TextStyle(
            fontSize = 22.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        val playerActionLabel = TextStyle(
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }

    val typography = Typography(
        displayLarge = TypographyTokens.display,
        headlineLarge = TypographyTokens.screenTitle,
        headlineMedium = TypographyTokens.heroTitle,
        headlineSmall = TypographyTokens.emptyStateTitle,
        titleLarge = TypographyTokens.sectionTitle,
        titleMedium = TypographyTokens.cardTitle,
        bodyLarge = TypographyTokens.body,
        bodyMedium = TypographyTokens.bodySecondary,
        bodySmall = TypographyTokens.metadata,
        labelLarge = TypographyTokens.button,
        labelMedium = TypographyTokens.chip,
    )

    val shapes = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        small = ReiAnixTokens.Shapes.small,
        medium = ReiAnixTokens.Shapes.card,
        large = ReiAnixTokens.Shapes.large,
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    )
}
