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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Premium in-place embellishments. All geometry is fixed around the perimeter,
 * NEVER spun or drawn across the avatar. Only light intensity changes over time.
 * No network artwork, video, shader dependency or background work is needed.
 *
 * Sakura is deliberately rendered by the original AnimatedProfileFrame instead.
 */
@Composable
internal fun RefinedProfileFrame(frame: String, modifier: Modifier = Modifier) {
    if (frame != "gold" && frame != "neon" && frame != "galaxy") return
    val transition = rememberInfiniteTransition(label = "refined-profile-frame-$frame")
    val gleam by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = when (frame) {
                "gold" -> 2400
                "galaxy" -> 2800
                else -> 1800
            }),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stationary-sparkles",
    )
    Canvas(modifier) {
        val r = size.minDimension * 0.402f
        val unit = (size.minDimension * 0.020f).coerceAtLeast(0.65.dp.toPx())
        val compact = size.minDimension < 62.dp.toPx()
        when (frame) {
            "gold" -> drawRoyalFrame(center, r, unit, gleam, compact)
            "neon" -> drawNeonFrame(center, r, unit, gleam, compact)
            "galaxy" -> drawGalaxyFrame(center, r, unit, gleam, compact)
        }
    }
}

private fun DrawScope.drawLayeredCollar(
    at: Offset,
    radius: Float,
    unit: Float,
    core: Color,
    edge: Color,
    glow: Float,
) {
    // Fixed nested metalwork directly outside the portrait: nothing crosses the face.
    drawCircle(core.copy(alpha = 0.08f + glow * 0.10f),
        radius = radius, center = at, style = Stroke(width = unit * 4.4f))
    drawCircle(edge.copy(alpha = 0.82f), radius = radius, center = at,
        style = Stroke(width = unit * 2.55f))
    drawCircle(core, radius = radius, center = at,
        style = Stroke(width = unit * 1.35f))
    drawCircle(Color.White.copy(alpha = 0.22f + glow * 0.18f),
        radius = radius - unit * 1.25f, center = at,
        style = Stroke(width = unit * 0.35f))
    drawCircle(edge.copy(alpha = 0.85f), radius = radius + unit * 1.95f,
        center = at, style = Stroke(width = unit * 0.34f))
}

private fun DrawScope.gem(
    point: Offset,
    radius: Float,
    fill: Color,
    rim: Color,
    glow: Float,
) {
    drawCircle(fill.copy(alpha = 0.09f + glow * 0.15f), radius = radius * 1.6f, center = point)
    val shape = Path().apply {
        moveTo(point.x, point.y - radius)
        lineTo(point.x + radius * 0.82f, point.y)
        lineTo(point.x, point.y + radius)
        lineTo(point.x - radius * 0.82f, point.y)
        close()
    }
    drawPath(shape, Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.90f), fill, fill.copy(alpha = 0.70f)),
        startY = point.y - radius,
        endY = point.y + radius,
    ))
    drawPath(shape, rim, style = Stroke(width = (radius * 0.13f).coerceAtLeast(0.45f)))
    drawLine(Color.White.copy(alpha = 0.35f + glow * 0.6f),
        point.copy(x = point.x, y = point.y - radius * 0.67f),
        point.copy(x = point.x, y = point.y + radius * 0.30f),
        strokeWidth = radius * 0.10f)
}

private fun DrawScope.twinkle(
    at: Offset,
    unit: Float,
    tint: Color,
    strength: Float,
) {
    val alpha = (0.20f + strength * 0.72f).coerceIn(0f, 1f)
    drawCircle(tint.copy(alpha = alpha * 0.28f), radius = unit * 1.28f, center = at)
    drawLine(tint.copy(alpha = alpha),
        Offset(at.x - unit * 0.75f, at.y), Offset(at.x + unit * 0.75f, at.y),
        strokeWidth = unit * 0.26f, cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = alpha),
        Offset(at.x, at.y - unit * 0.75f), Offset(at.x, at.y + unit * 0.75f),
        strokeWidth = unit * 0.26f, cap = StrokeCap.Round)
}

private fun pointOnRing(at: Offset, r: Float, degrees: Float): Offset {
    val t = degrees * PI / 180.0
    return Offset(
        at.x + r * cos(t).toFloat(),
        at.y + r * sin(t).toFloat(),
    )
}

/** Gilded sapphire crown, engraved gold collar, relief leaves and royal seal. */
private fun DrawScope.drawRoyalFrame(
    at: Offset, r: Float, u: Float, light: Float, compact: Boolean,
) {
    val ivory = Color(0xFFFFE5A5)
    val deepGold = Color(0xFF8D541F)
    val royalGold = Color(0xFFF8C35B)
    val sapphire = Color(0xFF41B9FF)
    drawLayeredCollar(at, r, u, royalGold, deepGold, light)
    drawCircle(ivory.copy(alpha = 0.52f), radius = r - u * 2.6f,
        style = Stroke(width = u * 0.32f))
    // Four fixed, beveled relief arches decorate the rim; no rotating highlight.
    for (a in listOf(18f, 109f, 198f, 289f)) {
        drawArc(
            brush = Brush.sweepGradient(
                listOf(royalGold.copy(alpha = 0.12f), ivory, royalGold),
                center = at,
            ),
            startAngle = a, sweepAngle = 46f, useCenter = false,
            topLeft = Offset(at.x - r, at.y - r), size = Size(r * 2f, r * 2f),
            style = Stroke(width = u * 0.75f, cap = StrokeCap.Round),
        )
    }

    val crownY = at.y - r
    // 5-point imperial crown with sculpted shoulders and a blue sapphire centerpiece.
    val crown = Path().apply {
        moveTo(at.x - r * 0.38f, crownY + u * 0.65f)
        lineTo(at.x - r * 0.42f, crownY - r * 0.14f)
        lineTo(at.x - r * 0.23f, crownY - r * 0.035f)
        lineTo(at.x - r * 0.17f, crownY - r * 0.22f)
        lineTo(at.x - r * 0.045f, crownY - r * 0.09f)
        lineTo(at.x, crownY - r * 0.32f)
        lineTo(at.x + r * 0.045f, crownY - r * 0.09f)
        lineTo(at.x + r * 0.17f, crownY - r * 0.22f)
        lineTo(at.x + r * 0.23f, crownY - r * 0.035f)
        lineTo(at.x + r * 0.42f, crownY - r * 0.14f)
        lineTo(at.x + r * 0.38f, crownY + u * 0.65f)
        close()
    }
    drawPath(crown, Brush.verticalGradient(
        listOf(Color(0xFFFFF1BB), Color(0xFFFFC55B), Color(0xFFAC661C)),
        startY = crownY - r * 0.30f, endY = crownY + u,
    ))
    drawPath(crown, ivory.copy(alpha = 0.94f), style = Stroke(width = u * 0.45f))
    drawRoundRect(
        brush = Brush.horizontalGradient(listOf(deepGold, ivory, royalGold, deepGold)),
        topLeft = Offset(at.x - r * 0.40f, crownY + u * 0.15f),
        size = Size(r * 0.80f, u * 1.02f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(u * 0.42f),
    )
    gem(Offset(at.x, crownY - r * 0.12f), r * 0.13f, sapphire, ivory, light)
    if (!compact) {
        gem(Offset(at.x - r * 0.26f, crownY - r * 0.055f),
            r * 0.05f, Color(0xFFFFF5DF), deepGold, light)
        gem(Offset(at.x + r * 0.26f, crownY - r * 0.055f),
            r * 0.05f, Color(0xFFFFF5DF), deepGold, light)
    }

    // Symmetric inset sapphires at 3 and 9 o'clock, golden relief below each.
    for (side in listOf(-1f, 1f)) {
        gem(Offset(at.x + side * r * 1.015f, at.y - r * 0.03f),
            r * 0.10f, sapphire, ivory, light)
        if (!compact) {
            // Hand-shaped laurel leaves anchored along the *outside* bottom perimeter.
            for (i in 0..4) {
                val degrees = (42f + i * 11f)
                val p = pointOnRing(at, r * 1.025f,
                    if (side > 0) degrees else 180f - degrees)
                val leaf = Path().apply {
                    moveTo(p.x - side * u * 0.18f, p.y + u * 0.22f)
                    quadraticTo(p.x - side * u * 0.65f, p.y - u * 1.15f,
                        p.x + side * u * 0.16f, p.y - u * 1.65f)
                    quadraticTo(p.x + side * u * 1.28f, p.y - u * 0.35f,
                        p.x - side * u * 0.18f, p.y + u * 0.22f)
                    close()
                }
                drawPath(leaf,
                    if (i % 2 == 0) ivory.copy(alpha = 0.88f) else royalGold)
                drawPath(leaf, deepGold.copy(alpha = 0.65f),
                    style = Stroke(width = u * 0.22f))
            }
        }
    }
    // Bottom sapphire royal crest mirrors the top crown, no decoration covers the face.
    gem(Offset(at.x, at.y + r * 1.025f), r * 0.115f, sapphire, ivory, light)
    if (!compact) {
        twinkle(Offset(at.x - r * 0.94f, at.y - r * 0.53f), u, ivory, light)
        twinkle(Offset(at.x + r * 0.94f, at.y - r * 0.53f), u, ivory, 1f - light * 0.55f)
        twinkle(Offset(at.x + r * 0.62f, at.y + r * 0.85f), u * 0.82f,
            ivory, light * 0.75f)
    }
}

/** Electric-blue armored collar, inset crystalline tabs and stationary LED nodes. */
private fun DrawScope.drawNeonFrame(
    at: Offset, r: Float, u: Float, light: Float, compact: Boolean,
) {
    val cyan = Color(0xFF69E9FF)
    val blue = Color(0xFF2F80FF)
    val navy = Color(0xFF0B2049)
    drawLayeredCollar(at, r, u, cyan, navy, light)
    drawCircle(blue.copy(alpha = 0.78f),
        radius = r + u * 2.18f, style = Stroke(width = u * 0.44f))
    drawCircle(Color.White.copy(alpha = 0.20f + light * 0.17f),
        radius = r - u * 1.62f, style = Stroke(width = u * 0.34f))
    // Luminous fixed trim sections make it look like engineered armor, not a rotating loading icon.
    for (a in listOf(24f, 114f, 204f, 294f)) {
        drawArc(blue.copy(alpha = 0.75f + light * 0.24f),
            startAngle = a, sweepAngle = 38f, useCenter = false,
            topLeft = Offset(at.x - r, at.y - r), size = Size(2f * r, 2f * r),
            style = Stroke(width = u * 1.2f, cap = StrokeCap.Round))
    }
    // Four architectural faceted tabs sit OUTSIDE the portrait.
    for (a in listOf(0f, 90f, 180f, 270f)) {
        val p = pointOnRing(at, r * 1.035f, a)
        val gemSize = if (compact) r * 0.075f else r * 0.095f
        gem(p, gemSize, if (a == 0f || a == 180f) cyan else blue, Color.White, light)
    }
    if (!compact) {
        for (a in listOf(42f, 138f, 222f, 318f)) {
            val p = pointOnRing(at, r * 1.035f, a)
            val wing = Path().apply {
                moveTo(p.x - u * 0.78f, p.y - u * 0.28f)
                lineTo(p.x - u * 0.23f, p.y - u * 1.20f)
                lineTo(p.x + u * 0.90f, p.y + u * 0.06f)
                lineTo(p.x + u * 0.23f, p.y + u * 1.20f)
                close()
            }
            drawPath(wing, Brush.verticalGradient(
                listOf(Color(0xFFB6F9FF), blue, Color(0xFF243E88)),
                startY = p.y - u * 1.4f, endY = p.y + u * 1.4f))
            drawPath(wing, cyan.copy(alpha = 0.3f + light * 0.5f),
                style = Stroke(width = u * 0.28f))
        }
        twinkle(pointOnRing(at, r * 1.14f, 65f), u * 0.78f, cyan, light)
        twinkle(pointOnRing(at, r * 1.14f, 245f), u * 0.78f, cyan, 1f - light * 0.5f)
    }
}

/** Amethyst celestial seal with stationary constellation gems; no orbit crosses the portrait. */
private fun DrawScope.drawGalaxyFrame(
    at: Offset, r: Float, u: Float, light: Float, compact: Boolean,
) {
    val lavender = Color(0xFFB99BFF)
    val violet = Color(0xFF8059D5)
    val midnight = Color(0xFF1D174E)
    val ice = Color(0xFF88C9FF)
    drawLayeredCollar(at, r, u, lavender, midnight, light)
    drawCircle(violet.copy(alpha = 0.75f), radius = r + u * 2.04f,
        style = Stroke(width = u * 0.55f))
    drawCircle(ice.copy(alpha = 0.18f + light * 0.16f), radius = r - u * 1.48f,
        style = Stroke(width = u * 0.40f))
    // Four clusters of nebula enamel remain fixed in the border itself.
    for (a in listOf(10f, 100f, 190f, 280f)) {
        drawArc(
            brush = Brush.sweepGradient(
                listOf(violet, ice.copy(alpha = 0.75f), lavender.copy(alpha = 0.45f), violet),
                center = at,
            ),
            startAngle = a, sweepAngle = 55f, useCenter = false,
            topLeft = Offset(at.x - r, at.y - r), size = Size(2f * r, 2f * r),
            style = Stroke(width = u * 1.15f, cap = StrokeCap.Round),
        )
    }
    for ((index, a) in listOf(0f, 90f, 180f, 270f).withIndex()) {
        val p = pointOnRing(at, r * 1.025f, a)
        gem(p, if (index % 2 == 0) r * 0.11f else r * 0.085f,
            if (index % 2 == 0) violet else ice, Color(0xFFE0D8FF), light)
    }
    if (!compact) {
        // A fan of faceted crystal fragments and fixed starbursts outside the disk.
        for (a in listOf(36f, 144f, 216f, 324f)) {
            val p = pointOnRing(at, r * 1.065f, a)
            gem(p, r * 0.065f, ice, lavender, light * 0.80f)
        }
        for ((index, a) in listOf(57f, 127f, 237f, 307f).withIndex()) {
            twinkle(pointOnRing(at, r * 1.12f, a), u * 0.72f,
                if (index % 2 == 0) Color.White else ice,
                if (index % 2 == 0) light else 1f - light * 0.55f)
        }
    }
}
