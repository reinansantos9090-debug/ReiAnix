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
        val background = Color(0xFF050913)
        val surface = Color(0xFF0A101C)
        val surfaceVariant = Color(0xFF111A29)
        val surfaceRaised = Color(0xFF162238)

        val primary = Color(0xFF3D8BFF)
        val primaryContainer = Color(0xFF173D78)
        val onPrimary = Color(0xFFFFFFFF)
        val onPrimaryContainer = Color(0xFFE3EEFF)

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
        val border = Color(0xFF46556D)
        val divider = Color(0xFF273449)

        val error = Color(0xFFFF6B6B)
        val onError = Color(0xFF240608)
        val errorContainer = Color(0xFF5A1A1F)
        val onErrorContainer = Color(0xFFFFDADD)

        val success = Color(0xFF4ADE80)
        val warning = Color(0xFFF6C85F)
        val overlay = Color(0x99000000)

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
    }

    object Dimensions {
        val screenHorizontalPadding = 20.dp
        val sectionGap = 24.dp
        val cardMinHeight = 88.dp
        val buttonMinHeight = 52.dp
        val chipMinHeight = 36.dp
        val artworkMinSize = 96.dp
        val progressHeight = 4.dp
    }

    object Shapes {
        val chip = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
        val small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
        val card = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
        val large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        val artwork = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    }

    object Elevation {
        val none = 0.dp
        val card = 2.dp
        val raised = 6.dp
        val prominent = 10.dp
    }

    val typography = Typography(
        displayLarge = TextStyle(
            fontSize = 36.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.Bold,
        ),
        headlineLarge = TextStyle(
            fontSize = 30.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
        ),
        headlineSmall = TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold,
        ),
        titleLarge = TextStyle(
            fontSize = 21.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
        ),
        bodySmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal,
        ),
        labelLarge = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
        ),
    )

    val shapes = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        small = ReiAnixTokens.Shapes.small,
        medium = ReiAnixTokens.Shapes.card,
        large = ReiAnixTokens.Shapes.large,
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    )
}
