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
        // ReiAnix visual language: true AMOLED black canvas, minimal near-black surfaces,
        // and one controlled electric-blue interaction accent. Keep semantic aliases here
        // so Compose screens never need ad-hoc hex/RGB values.
        val background = Color(0xFF000000)
        val backgroundSecondary = Color(0xFF030303)
        val surface = Color(0xFF050505)
        val surfaceVariant = Color(0xFF080808)
        val surfaceRaised = Color(0xFF0A0A0A)
        val surfaceCard = Color(0xFF050505)
        val surfaceDialog = Color(0xFF0A0A0A)
        val surfaceSheet = Color(0xFF080808)
        val surfacePlayer = Color(0xFF000000)
        val surfaceSelected = Color(0xFF080808)
        val surfaceNavigation = Color(0xFF000000)
        val playerControl = Color(0xFFFFFFFF)
        val playerScrim = Color(0xFF000000)

        val primary = Color(0xFF2579FF)
        val primaryContainer = surfaceRaised
        val onPrimary = Color(0xFFFFFFFF)
        val onPrimaryContainer = Color(0xFFFFFFFF)
        val active = primary
        val focus = Color(0xFF6AA3FF)
        val pressed = Color(0xFF1E61C7)

        val secondary = Color(0xFFB3B3B3)
        val secondaryContainer = surfaceVariant
        val onSecondary = background
        val onSecondaryContainer = Color(0xFFFFFFFF)

        val tertiary = Color(0xFF777777)
        val tertiaryContainer = surfaceVariant
        val onTertiary = background
        val onTertiaryContainer = Color(0xFFFFFFFF)

        val text = Color(0xFFFFFFFF)
        val textMuted = Color(0xFFB3B3B3)
        // Semantic roles used by shared UI components; values remain owned by this palette.
        val onBackground = text
        val onSurface = text
        val muted = textMuted
        val textTertiary = Color(0xFF777777)
        val textDisabled = Color(0xFF666666)
        val textOnPrimary = onPrimary
        val border = Color(0xFF202020)
        val borderStrong = Color(0xFF2A2A2A)
        val divider = Color(0xFF202020)

        val error = Color(0xFFFF5B61)
        val onError = Color(0xFF240608)
        val errorContainer = Color(0xFF5A1A1F)
        val onErrorContainer = Color(0xFFFFDADD)
        val info = secondary

        val success = Color(0xFF4ADE80)
        val warning = Color(0xFFF6C85F)
        val disabledSurface = Color(0xFF080808)
        val overlay = Color(0xB3000000)
        val overlayStrong = Color(0xCC000000)
        // Canonical semantic aliases used by shared components. Keep these as aliases
        // of the palette above so the UI has one visual source of truth.
        val surfaceElevated = surfaceRaised
        val textPrimary = text
        val textSecondary = textMuted
        val accent = primary
        val accentPressed = pressed
        val accentDisabled = primary.copy(alpha = 0.16f)

        val statusContainerAlpha = 0.16f
        val disabledContentAlpha = 0.55f
        val disabledTextAlpha = 0.60f
        val subtleBorderAlpha = 0.55f
        val dividerAlpha = 0.8f
        val surfaceOverlayAlpha = 0.84f
        val disabledContainerAlpha = 0.18f

        val inverseSurface = Color(0xFFECECEC)
        val inverseOnSurface = Color(0xFF1A1A1A)
        val inversePrimary = Color(0xFF2579FF)
        val lightPrimary = Color(0xFF2563C7)
        val lightOnPrimary = Color(0xFFFFFFFF)
        val lightPrimaryContainer = Color(0xFFE8E8EA)
        val lightOnPrimaryContainer = Color(0xFF17191C)
        val lightSecondary = Color(0xFF4D5F7D)
        val lightOnSecondary = Color(0xFFFFFFFF)
        val lightSecondaryContainer = Color(0xFFEFEFEF)
        val lightOnSecondaryContainer = Color(0xFF202124)
        val lightTertiary = Color(0xFF5F6670)
        val lightOnTertiary = Color(0xFFFFFFFF)
        val lightTertiaryContainer = Color(0xFFE9E9EC)
        val lightOnTertiaryContainer = Color(0xFF17191C)
        val lightError = Color(0xFFBA1A1A)
        val lightOnError = Color(0xFFFFFFFF)
        val lightErrorContainer = Color(0xFFFFDAD6)
        val lightOnErrorContainer = Color(0xFF410002)
        val lightBackground = Color(0xFFF7F7F7)
        val lightSurface = Color(0xFFFFFFFF)
        val lightSurfaceVariant = Color(0xFFEFEFEF)
        val lightSurfaceDialog = Color(0xFFE7E7EA)
        val lightText = Color(0xFF141414)
        val lightTextMuted = Color(0xFF5F5F5F)
        val lightOnBackground = lightText
        val lightOnSurface = lightText
        val lightMuted = lightTextMuted
        val lightBorder = Color(0xFFD0D0D0)
        val lightDivider = Color(0xFFDEDEDE)
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
        val section = 16.dp
        val screen = 16.dp
    }

    object Dimensions {
        val screenHorizontalPadding = 16.dp
        val screenTopPadding = 8.dp
        val screenBottomPadding = 24.dp
        val sectionGap = 16.dp
        val sectionTitleGap = 8.dp
        val cardMinHeight = 88.dp
        // Compact Settings rows follow the reference density while keeping the
        // complete Compose accessibility touch target.
        val settingsRowMinHeight = 56.dp
        val settingsIconContainerSize = 32.dp
        val settingsTrailingSize = 48.dp
        val settingsChoiceDialogMaxHeight = 420.dp
        val settingsChoiceValueMinWidth = 64.dp
        val settingsChoiceValueMaxWidth = 120.dp
        val organizeFilterMaxHeight = 260.dp
        val buttonMinHeight = 40.dp
        val chipMinHeight = 32.dp
        val touchTarget = 48.dp
        val iconSmall = 18.dp
        val iconSecondary = 20.dp
        val iconMedium = 22.dp
        val iconLarge = 26.dp
        val loadingIndicatorSize = 20.dp
        val loadingIndicatorStroke = 2.dp
        val accountAvatarSize = 72.dp
        val topBarMinHeight = 56.dp
        val bottomNavigationMinHeight = 64.dp
        val bottomNavigationIndicatorHeight = 32.dp
        val artworkMinSize = 96.dp
        val animeCardWidth = 154.dp
        // Shared responsive card widths. Screens consume these semantic tokens
        // instead of inventing per-screen poster dimensions.
        val cardWidthSmall = 76.dp
        val cardWidthMedium = 108.dp
        val cardWidthLarge = 144.dp
        val gridMinWidthSmall = 76.dp
        val gridMinWidthMedium = 108.dp
        val gridMinWidthLarge = 144.dp
        val gridMaxItemWidth = 184.dp
        // The genre picker stays scrollable and bounded even for large catalogs.
        val libraryGenrePickerMaxHeight = 360.dp
        // Grid gaps are expressed as density presets, not screen-local magic values.
        val libraryGridSpacingDense = 6.dp
        val libraryGridSpacingBalanced = 8.dp
        val libraryGridSpacingRelaxed = 12.dp
        val continueCardWidth = 250.dp
        val continuePosterWidth = 76.dp
        val continuePosterHeight = 108.dp
        val posterAspectRatio = 0.7f
        val episodeThumbnailWidth = 120.dp
        val episodeThumbnailHeight = 68.dp
        val detailsEpisodeThumbnailFraction = 0.27f
        val detailsHeroHeight = 240.dp
        val searchFieldHeight = 48.dp
        val detailsHeroMaxHeight = 300.dp
        val detailsHeroWideBreakpoint = 600.dp
        val detailsHeroPosterWidth = 112.dp
        val detailsHeroPosterOverlap = 64.dp
        val detailsHeroPosterHeight = 160.dp
        val emptyStateMinHeight = 240.dp
        val organizeGenreCardWidth = 170.dp
        val organizeCategoryCardWidth = 156.dp
        val detailsSeasonCardWidth = 236.dp
        val detailsSeasonPreviewWidth = 80.dp
        val detailsSeasonPreviewHeight = 54.dp
        val detailsSeasonCardMinWidth = 240.dp
        val iconViewportSize = 24.dp
        val detailsInfoLabelWidth = 96.dp
        val homeHeroHeight = 220.dp
        val homeCardWidth = 100.dp
        val homeContinueCardWidth = 110.dp
        val homeLandscapeArtworkAspectRatio = 1.55f
        val progressHeight = 4.dp
        val dividerHeight = 1.dp
        val borderWidth = 1.dp
        val cornerRadius = 12.dp
    }

    /**
     * Responsive layout tokens shared by Home, Library, Search, Details and Settings.
     * Keep window policy values here so screens consume one consistent sizing system.
     */
    object Responsive {
        val mediumWidth = Dimensions.detailsHeroWideBreakpoint
        val expandedWidth = 840.dp
        val mediumHeight = 480.dp
        val expandedHeight = 800.dp

        val mediumLibraryGridMinWidth = 132.dp
        val expandedLibraryGridMinWidth = 156.dp
        val mediumHomeCardWidth = 146.dp
        val expandedHomeCardWidth = 160.dp
        val compactContinueCardWidth = Dimensions.homeContinueCardWidth
        val mediumContinueCardWidth = 180.dp
        val expandedContinueCardWidth = 220.dp
        val compactSearchGridMinWidth = 280.dp
        val mediumSearchGridMinWidth = 300.dp
        val expandedSearchGridMinWidth = 320.dp

        val compactWindowWidth = 360.dp
        val compactWindowHeight = 720.dp
        val mediumHorizontalPadding = Spacing.xl
        val expandedHorizontalPadding = Spacing.xxl
        val mediumContentMaxWidth = 960.dp
        val expandedContentMaxWidth = 1200.dp
        val mediumSettingsMaxWidth = 720.dp
        val expandedSettingsMaxWidth = 840.dp
        val mediumTextMaxWidth = 680.dp
        val expandedTextMaxWidth = 760.dp

        val homeHeroLandscapeMinHeight = 180.dp
        val homeHeroLandscapeMaxHeight = 240.dp
        val homeHeroPortraitMinHeight = 190.dp
        val homeHeroPortraitMaxHeight = 240.dp
        val homeHeroExpandedMaxHeight = 280.dp
        val detailsHeroLandscapeMinHeight = 200.dp
        val detailsHeroLandscapeMaxHeight = 260.dp
    }

    object Shapes {
        val chip = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        val small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        val button = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
        val card = androidx.compose.foundation.shape.RoundedCornerShape(Dimensions.cornerRadius)
        val large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        val artwork = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        val hero = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
        val dialog = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        val sheet = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        val textField = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
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

    object PlayerDimensions {
        val topHorizontalPadding = 8.dp
        val topBottomPadding = 20.dp
        val topTitleWidthFraction = 0.84f
        val seekButtonSize = 60.dp
        val seekIconSize = 32.dp
        val centerButtonContainerSize = 76.dp
        val centerButtonSize = 68.dp
        val bufferingIndicatorSize = 32.dp
        val bufferingStroke = 3.dp
        val playIconSize = 48.dp
        val centerHorizontalPadding = 12.dp
        val centerColumnMinWidth = 280.dp
        val centerControlsMaxWidth = 420.dp
        val lockAffordanceSize = 48.dp
        val lockAffordanceBottomPadding = 10.dp
        val timelineHeight = 48.dp
        val timelineTimeWidth = 44.dp
        val bottomHorizontalPadding = 8.dp
        val bottomTopPadding = 22.dp
        val bottomExtraPadding = 4.dp
        val actionButtonSize = 48.dp
        val actionGlyphSize = 22.dp
        val actionIconSize = 22.dp
    }

    object TypographyTokens {
        // Canonical roles shared by all Compose screens. Values stay in sp so system
        // font scaling and accessibility settings continue to apply.
        val display = TextStyle(
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        val brandTitle = TextStyle(
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        val heroTitle = TextStyle(
            fontSize = 22.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Bold,
        )
        val screenTitle = TextStyle(
            fontSize = 22.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.Bold,
        )
        val emptyStateTitle = TextStyle(
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val sectionTitle = TextStyle(
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val itemTitle = TextStyle(
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // Keep the previous semantic alias for existing call sites.
        val cardTitle = itemTitle
        val subtitle = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        )
        val body = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
        )
        val bodySecondary = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        )
        // Semantic alias for screen descriptions and other secondary copy.
        val secondary = bodySecondary
        val metadata = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        )
        val caption = TextStyle(
            fontSize = 11.sp,
            lineHeight = 14.sp,
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
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        val settingsCategory = TextStyle(
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val settingsDescription = TextStyle(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        )
        // Player-specific roles intentionally remain unchanged by the compact UI pass.
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
        val playerPauseGlyph = TextStyle(
            fontSize = 30.sp,
            lineHeight = 32.sp,
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
        displayMedium = TypographyTokens.heroTitle,
        displaySmall = TypographyTokens.screenTitle,
        headlineLarge = TypographyTokens.screenTitle,
        headlineMedium = TypographyTokens.heroTitle,
        headlineSmall = TypographyTokens.emptyStateTitle,
        titleLarge = TypographyTokens.sectionTitle,
        titleMedium = TypographyTokens.itemTitle,
        titleSmall = TypographyTokens.label,
        bodyLarge = TypographyTokens.body,
        bodyMedium = TypographyTokens.bodySecondary,
        bodySmall = TypographyTokens.metadata,
        labelLarge = TypographyTokens.button,
        labelMedium = TypographyTokens.chip,
        labelSmall = TypographyTokens.caption,
    )

    val shapes = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        small = ReiAnixTokens.Shapes.small,
        medium = ReiAnixTokens.Shapes.card,
        large = ReiAnixTokens.Shapes.large,
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    )
}
