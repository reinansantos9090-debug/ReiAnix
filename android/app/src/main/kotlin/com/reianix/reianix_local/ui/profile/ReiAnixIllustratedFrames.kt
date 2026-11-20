package com.reianix.reianix_local.ui.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.reianix.reianix_local.R
import androidx.compose.ui.unit.dp

/** PNG artwork is bundled in drawable-nodpi and never fetched from the network.
 * The lightweight animation is only a twinkle of fixed points, not a rotating
 * vector tracing or a moving stripe across the user's face.
 */
internal fun isIllustratedProfileFrame(frame: String): Boolean =
    frame == "gold" || frame == "neon" || frame == "galaxy" ||
        frame == "cat_ears" || frame == "headset"

@Composable
internal fun IllustratedProfileFrame(
    frame: String,
    modifier: Modifier = Modifier,
) {
    if (!isIllustratedProfileFrame(frame)) return
    // Static resource references guarantee these illustrated frames survive APK shrinking.
    // Never silently fall back to the old geometric designs.
    val drawableId = when (frame) {
        "gold" -> R.drawable.profile_frame_gold
        "neon" -> R.drawable.profile_frame_neon
        "galaxy" -> R.drawable.profile_frame_galaxy
        "cat_ears" -> R.drawable.profile_frame_cat_ears
        "headset" -> R.drawable.profile_frame_headset
        else -> return
    }

    val transition = rememberInfiniteTransition(label = "illustrated-frame-$frame")
    val light by transition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (frame == "gold") 2300 else 2900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fixed-starlight",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(drawableId),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Canvas(Modifier.fillMaxSize()) {
            if (size.minDimension < 55.dp.toPx()) return@Canvas
            val radius = size.minDimension * 0.008f
            val tint = when (frame) {
                "gold" -> Color(0xFFFFEEAF)
                "neon" -> Color(0xFF86E9FF)
                "galaxy" -> Color(0xFFCDB9FF)
                "cat_ears" -> Color(0xFFFFD3E8)
                else -> Color(0xFF98EBFF)
            }
            val points = when (frame) {
                "gold" -> listOf(0.13f to 0.31f, 0.86f to 0.30f, 0.71f to 0.83f)
                "neon" -> listOf(0.23f to 0.16f, 0.80f to 0.76f, 0.18f to 0.71f)
                "galaxy" -> listOf(0.19f to 0.31f, 0.79f to 0.25f, 0.65f to 0.83f)
                "cat_ears" -> listOf(0.20f to 0.19f, 0.81f to 0.19f, 0.50f to 0.82f)
                else -> listOf(0.15f to 0.44f, 0.84f to 0.44f, 0.50f to 0.87f)
            }
            points.forEachIndexed { index, pair ->
                drawTinyFixedStar(
                    Offset(size.width * pair.first, size.height * pair.second),
                    radius,
                    tint,
                    if (index % 2 == 0) light else (0.96f - light * 0.50f),
                )
            }
        }
    }
}

private fun DrawScope.drawTinyFixedStar(
    at: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    val a = alpha.coerceIn(0f, 1f)
    drawCircle(color.copy(alpha = 0.12f + a * 0.10f), radius = radius * 3f, center = at)
    drawLine(
        color.copy(alpha = 0.22f + a * 0.72f),
        Offset(at.x - radius * 1.65f, at.y),
        Offset(at.x + radius * 1.65f, at.y),
        strokeWidth = radius * 0.45f,
        cap = StrokeCap.Round,
    )
    drawLine(
        Color.White.copy(alpha = 0.10f + a * 0.75f),
        Offset(at.x, at.y - radius * 1.65f),
        Offset(at.x, at.y + radius * 1.65f),
        strokeWidth = radius * 0.45f,
        cap = StrokeCap.Round,
    )
}
