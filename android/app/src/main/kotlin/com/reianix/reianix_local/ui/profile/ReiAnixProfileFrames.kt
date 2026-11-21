package com.reianix.reianix_local.ui.profile

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Locally drawn profile frames; no image downloads, new runtime dependencies or decoder work.
 * The motion belongs to the selected frame, not to a separate user setting.
 * Keep legacy ids to preserve saved profiles and their color/white options.
 */
internal data class ProfileFrameOption(
    val id: String,
    val title: String,
    val description: String,
    val animated: Boolean = false,
)

internal val profileFrameOptions = listOf(
    ProfileFrameOption("none", "Sem moldura", "Usar a foto do perfil sem efeitos."),
    ProfileFrameOption("neon", "Neon azul", "Armadura luminosa, cristais e LED pulsante.", true),
    ProfileFrameOption("sakura", "Sakura mágica", "Pétalas rosadas flutuando ao redor da foto.", true),
    ProfileFrameOption("gold", "Dourada royal", "Coroa imperial, safiras e ouro ornamentado.", true),
    ProfileFrameOption("galaxy", "Galáxia", "Cristais celestes, ametistas e estrelas cintilantes.", true),
    ProfileFrameOption("white", "Branca", "Contorno branco clássico."),
)

internal fun isAnimatedProfileFrame(frame: String) =
    frame == "neon" || frame == "sakura" || frame == "gold" || frame == "galaxy"

/**
 * Canvas renders a small bounded number of primitives; the portrait stays in its own
 * clipped layer underneath. This works for both the full preview and small avatar icons.
 */
@Composable
internal fun AnimatedProfileFrame(frame: String, modifier: Modifier = Modifier) {
    // Sakura keeps its originally approved animation. Remaining premium
    // frames use local illustrated assets rather than geometric rings.
    if (isIllustratedProfileFrame(frame)) {
        IllustratedProfileFrame(frame, modifier)
        return
    }
    if (!isAnimatedProfileFrame(frame)) return
    val transition = rememberInfiniteTransition(label = "profile-frame-" + frame)
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = when (frame) {
                "sakura" -> 9200
                "gold" -> 8400
                "galaxy" -> 11000
                else -> 6600
            }, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "frame-motion",
    )
    Canvas(modifier) {
        val mid = center
        val r = size.minDimension * 0.405f
        val line = (size.minDimension * 0.023f).coerceAtLeast(1.1.dp.toPx())
        val blue = Color(0xFF3BA7FF)
        val pink = Color(0xFFFF9CCF)
        val gold = Color(0xFFFFCA69)
        val purple = Color(0xFFB178FF)
        val primary = when (frame) {
            "neon" -> blue
            "sakura" -> pink
            "gold" -> gold
            else -> purple
        }
        val pulse = (sin(angle.toDouble() * PI / 90.0).toFloat() + 1f) / 2f
        val ringTopLeft = Offset(mid.x - r, mid.y - r)
        val ringSize = Size(r * 2f, r * 2f)

        // Ring and two thin, translucent light halos are clipped to the avatar footprint.
        drawCircle(primary.copy(alpha = 0.12f + pulse * 0.08f),
            radius = r, style = Stroke(width = line * 3.2f))
        drawCircle(primary.copy(alpha = 0.92f), radius = r,
            style = Stroke(width = line * 0.9f))
        drawCircle(Color.White.copy(alpha = 0.28f + pulse * 0.19f),
            radius = r - line * 0.9f, style = Stroke(width = line * 0.30f))
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(primary.copy(alpha = 0.12f), Color.White, primary, primary.copy(alpha = 0.12f)),
                center = mid,
            ),
            startAngle = angle,
            sweepAngle = if (frame == "galaxy") 105f else 67f,
            useCenter = false,
            topLeft = ringTopLeft,
            size = ringSize,
            style = Stroke(width = line * 1.50f, cap = StrokeCap.Round),
        )
        if (frame == "neon") {
            drawArc(blue.copy(alpha = 0.95f), angle + 153f, 47f, false,
                ringTopLeft, ringSize, style = Stroke(width = line * 1.75f, cap = StrokeCap.Round))
            drawArc(Color(0xFF89D9FF), angle + 275f, 25f, false,
                ringTopLeft, ringSize, style = Stroke(width = line * 1.15f, cap = StrokeCap.Round))
        }
        if (frame == "galaxy") {
            rotate(-28f, mid) {
                drawOval(
                    Color(0xFFB98DFF).copy(alpha = 0.55f + pulse * 0.25f),
                    topLeft = Offset(mid.x - r * 1.10f, mid.y - r * 0.33f),
                    size = Size(r * 2.20f, r * 0.66f),
                    style = Stroke(width = line * 0.75f),
                )
            }
            drawArc(Color(0xFF5D83FF).copy(alpha = 0.72f), angle + 70f,
                80f, false, ringTopLeft, ringSize, style = Stroke(width = line * 1.5f))
        }
        // Moving light points, petal flecks and stars. Bounded to 8 elements per avatar.
        val count = if (size.minDimension < 50.dp.toPx()) 3 else 8
        for (i in 0 until count) {
            val theta = (angle * when (frame) {
                "sakura" -> 0.68f
                "galaxy" -> -0.74f
                else -> 1f
            } + i * (360f / count)) * PI / 180.0
            val orbit = r * when (frame) {
                "sakura" -> 1.08f
                "galaxy" -> 1.07f
                else -> 1.035f
            }
            val point = Offset(
                mid.x + cos(theta).toFloat() * orbit,
                mid.y + sin(theta).toFloat() * orbit,
            )
            val sparkle = (sin(theta * 2.0 + angle * PI / 180.0).toFloat() + 1f) / 2f
            if (frame == "sakura" && count > 3) {
                rotate(angle * 0.42f + i * 39f, point) {
                    drawOval(
                        color = if (i % 2 == 0) Color(0xFFFFC6E0) else pink,
                        topLeft = Offset(point.x - line * 0.42f, point.y - line * 0.85f),
                        size = Size(line * 0.84f, line * 1.70f),
                    )
                }
            } else {
                val dotRadius = line * (if (frame == "gold") 0.48f else 0.43f)
                drawCircle(primary.copy(alpha = 0.45f + sparkle * 0.5f), dotRadius, point)
                if (i % 2 == 0 && count > 3) {
                    drawLine(Color.White.copy(alpha = 0.35f + sparkle * 0.5f),
                        Offset(point.x - line * 0.55f, point.y),
                        Offset(point.x + line * 0.55f, point.y), line * 0.20f)
                    drawLine(Color.White.copy(alpha = 0.35f + sparkle * 0.5f),
                        Offset(point.x, point.y - line * 0.55f),
                        Offset(point.x, point.y + line * 0.55f), line * 0.20f)
                }
            }
        }

        if (frame == "gold" && size.minDimension >= 50.dp.toPx()) {
            // The crown and laurel wreath are original vector ornaments, never external art.
            val top = mid.y - r
            val crown = Path().apply {
                moveTo(mid.x - r * 0.32f, top + line * 0.25f)
                lineTo(mid.x - r * 0.36f, top - r * 0.18f)
                lineTo(mid.x - r * 0.12f, top - r * 0.02f)
                lineTo(mid.x, top - r * 0.17f)
                lineTo(mid.x + r * 0.12f, top - r * 0.02f)
                lineTo(mid.x + r * 0.36f, top - r * 0.18f)
                lineTo(mid.x + r * 0.32f, top + line * 0.25f)
                close()
            }
            drawPath(crown, Color(0xFFFFD47A))
            drawPath(crown, Color.White.copy(alpha = 0.55f), style = Stroke(line * 0.25f))
            drawCircle(Color(0xFF47B8FF), r * 0.085f, Offset(mid.x, top - r * 0.11f))
            for (side in listOf(-1f, 1f)) {
                for (i in 0 until 4) {
                    val p = Offset(mid.x + side * (r * (0.27f + i * 0.17f)),
                        mid.y + r * (0.83f - i * 0.12f))
                    rotate(side * (28f + i * 11f), p) {
                        drawOval(Color(0xFFE7A83F).copy(alpha = 0.92f),
                            topLeft = Offset(p.x - r * 0.105f, p.y - r * 0.042f),
                            size = Size(r * 0.21f, r * 0.084f))
                    }
                }
            }
            drawCircle(Color(0xFF42AFFF), r * 0.075f,
                Offset(mid.x, mid.y + r * 1.02f))
        }
    }
}
