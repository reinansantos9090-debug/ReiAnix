package com.reianix.reianix_local.ui.profile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Two purpose-built profile accessories rather than a recolored rotating ring.
 * Every animated highlight pulses in place: no spinning arcs or lines over the face.
 * Pure Compose drawing keeps these decorations offline and independent of anime artwork.
 */
@Composable
internal fun AnimatedAccessoryProfileFrame(
    frame: String,
    modifier: Modifier = Modifier,
) {
    if (frame != "cat_ears" && frame != "headset") return
    val transition = rememberInfiniteTransition(label = "profile-accessory-$frame")
    val glow by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.90f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (frame == "cat_ears") 2000 else 1750),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "frame-soft-glow",
    )
    Canvas(modifier) {
        val r = size.minDimension * 0.401f
        val centerX = center.x
        val centerY = center.y
        val stroke = (size.minDimension * 0.021f).coerceAtLeast(1.dp.toPx())
        val compact = size.minDimension < 65.dp.toPx()

        if (frame == "cat_ears") {
            val blue = Color(0xFF8BDFFF)
            val pink = Color(0xFFFFA2D4)
            // A luminous silver-blue sculpted collar with a pink inner highlight.
            drawCircle(blue.copy(alpha = 0.12f + glow * 0.12f),
                radius = r, style = Stroke(width = stroke * 3.2f))
            drawCircle(Color(0xFF162D57), radius = r,
                style = Stroke(width = stroke * 2.15f))
            drawCircle(blue.copy(alpha = 0.85f), radius = r,
                style = Stroke(width = stroke * 0.88f))
            drawCircle(Color.White.copy(alpha = 0.20f + glow * 0.23f),
                radius = r - stroke * 1.10f, style = Stroke(width = stroke * 0.35f))

            // Pink-lined cat ears are integral to the silhouette, not rotating stickers.
            for (side in listOf(-1f, 1f)) {
                val outer = Path().apply {
                    moveTo(centerX + side * r * 0.27f, centerY - r * 0.80f)
                    quadraticTo(centerX + side * r * 0.38f, centerY - r * 1.06f,
                        centerX + side * r * 0.56f, centerY - r * 1.19f)
                    quadraticTo(centerX + side * r * 0.81f, centerY - r * 1.02f,
                        centerX + side * r * 0.94f, centerY - r * 0.69f)
                    close()
                }
                drawPath(outer, Brush.verticalGradient(
                    listOf(Color(0xFFE1F6FF), Color(0xFF6DA6F3), Color(0xFF293470))))
                drawPath(outer, Color(0xFF98E8FF).copy(alpha = 0.6f + glow * 0.3f),
                    style = Stroke(width = stroke * 0.62f))
                val inner = Path().apply {
                    moveTo(centerX + side * r * 0.45f, centerY - r * 0.84f)
                    lineTo(centerX + side * r * 0.57f, centerY - r * 1.09f)
                    quadraticTo(centerX + side * r * 0.76f, centerY - r * 0.93f,
                        centerX + side * r * 0.79f, centerY - r * 0.78f)
                    close()
                }
                drawPath(inner, Brush.verticalGradient(
                    listOf(Color(0xFFFFE0F2), pink.copy(alpha = 0.95f))))
            }

            // A tiny paw-shaped charm at the bottom, understated on toolbar avatars.
            val paw = Offset(centerX, centerY + r * 0.98f)
            drawCircle(Color(0xFF80DBFF).copy(alpha = 0.40f + glow * 0.35f),
                radius = stroke * 0.94f, center = paw)
            if (!compact) {
                val dots = listOf(
                    Offset(paw.x - stroke * 1.10f, paw.y - stroke * 1.25f),
                    Offset(paw.x, paw.y - stroke * 1.62f),
                    Offset(paw.x + stroke * 1.10f, paw.y - stroke * 1.25f),
                )
                dots.forEach {
                    drawCircle(pink.copy(alpha = 0.75f + glow * 0.22f),
                        radius = stroke * 0.34f, center = it)
                }
                // Fixed glitter at the sides; only intensity varies.
                listOf(-1f, 1f).forEach { side ->
                    val x = centerX + side * r * 1.06f
                    val y = centerY + r * 0.15f
                    drawLine(blue.copy(alpha = 0.35f + glow * 0.5f),
                        Offset(x - stroke * 0.72f, y), Offset(x + stroke * 0.72f, y),
                        stroke * 0.20f, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = 0.35f + glow * 0.5f),
                        Offset(x, y - stroke * 0.72f), Offset(x, y + stroke * 0.72f),
                        stroke * 0.20f, cap = StrokeCap.Round)
                }
            }
        } else {
            val cyan = Color(0xFF49E2FF)
            val violet = Color(0xFFAD7FFF)
            val bright = Color.White.copy(alpha = 0.22f + glow * 0.38f)
            // Cushioned headset headband with a pair of inset metallic rails.
            drawCircle(cyan.copy(alpha = 0.08f + glow * 0.11f), radius = r,
                style = Stroke(width = stroke * 2.8f))
            drawCircle(Color(0xFF233157), radius = r,
                style = Stroke(width = stroke * 1.9f))
            drawCircle(violet.copy(alpha = 0.70f + glow * 0.18f), radius = r,
                style = Stroke(width = stroke * 0.65f))
            drawArc(
                color = Color(0xFF121B36),
                startAngle = 198f, sweepAngle = 145f, useCenter = false,
                topLeft = Offset(centerX - r * 0.99f, centerY - r * 1.02f),
                size = Size(r * 1.98f, r * 2.04f),
                style = Stroke(width = stroke * 3.5f, cap = StrokeCap.Round),
            )
            drawArc(
                color = cyan.copy(alpha = 0.75f + glow * 0.23f),
                startAngle = 198f, sweepAngle = 145f, useCenter = false,
                topLeft = Offset(centerX - r * 0.99f, centerY - r * 1.02f),
                size = Size(r * 1.98f, r * 2.04f),
                style = Stroke(width = stroke * 0.76f, cap = StrokeCap.Round),
            )
            // Distinct earcups: outer padded shells, metallic inserts and cyan LED slits.
            for (side in listOf(-1f, 1f)) {
                val cupX = centerX + side * r * 0.89f
                val cupY = centerY - r * 0.08f
                val w = r * 0.27f
                val h = r * 0.57f
                drawRoundRect(
                    color = Color(0xFF080E23),
                    topLeft = Offset(cupX - w * 0.50f, cupY - h * 0.50f),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w * 0.45f),
                )
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF8898C8), Color(0xFF323E76), Color(0xFF151E42))),
                    topLeft = Offset(cupX - w * 0.39f, cupY - h * 0.45f),
                    size = Size(w * 0.78f, h * 0.90f),
                    cornerRadius = CornerRadius(w * 0.38f),
                )
                drawRoundRect(
                    color = cyan.copy(alpha = 0.36f + glow * 0.56f),
                    topLeft = Offset(cupX - w * 0.10f, cupY - h * 0.32f),
                    size = Size(w * 0.20f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.10f),
                )
                if (!compact) {
                    drawCircle(bright, radius = stroke * 0.36f,
                        center = Offset(cupX, cupY - h * 0.27f))
                }
            }
            // Short microphone boom sits outside the face, ending in an illuminated capsule.
            if (!compact) {
                val boom = Path().apply {
                    moveTo(centerX + r * 0.92f, centerY + r * 0.34f)
                    quadraticTo(centerX + r * 1.02f, centerY + r * 0.65f,
                        centerX + r * 0.62f, centerY + r * 0.76f)
                }
                drawPath(boom, Color(0xFF64729E),
                    style = Stroke(width = stroke * 0.88f, cap = StrokeCap.Round))
                drawCircle(cyan.copy(alpha = 0.50f + glow * 0.48f),
                    radius = stroke * 0.73f,
                    center = Offset(centerX + r * 0.62f, centerY + r * 0.76f))
                // A stationary purple accent stud, not an orbiting particle.
                drawCircle(violet.copy(alpha = 0.55f + glow * 0.35f),
                    radius = stroke * 0.55f,
                    center = Offset(centerX - r * 0.85f, centerY - r * 0.46f))
            }
        }
    }
}
