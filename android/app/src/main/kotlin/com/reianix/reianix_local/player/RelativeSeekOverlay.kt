package com.reianix.reianix_local.player

import androidx.core.content.ContextCompat
import com.reianix.reianix_local.R
import com.reianix.reianix_local.ui.theme.ReiAnixTokens
import kotlin.math.abs
import kotlin.math.roundToInt
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View

/** Draws short-lived relative seek feedback without intercepting player input. */
internal class RelativeSeekOverlay(
    context: Context,
    private val onSecondsChanged: (Long) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curvedEdge = Path()
    private val circle = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_player_seek_circle)).mutate()
    private val circleSize = (ReiAnixTokens.PlayerDimensions.seekIconSize.value * density).roundToInt()
    var showCircle = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private var totalMs = 0L
    private var lastSeekAt = 0L
    private var label = ""

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun showSeek(deltaMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt >= VISIBLE_MS || (deltaMs > 0) != (totalMs > 0) || totalMs == 0L) {
            totalMs = 0L
        }
        totalMs += deltaMs
        val totalSeconds = totalMs / 1_000L
        label = abs(totalSeconds).toString()
        onSecondsChanged(totalSeconds)
        lastSeekAt = now
        visibility = VISIBLE
        invalidate()
    }

    fun reset() {
        if (totalMs != 0L) onSecondsChanged(0L)
        totalMs = 0L
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) {
            backgroundPaint.shader = LinearGradient(
                0f, 0f, w * PANEL_WIDTH_FRACTION, 0f,
                intArrayOf(0x52000000, 0x42000000, Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 0.9f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val sinceSeek = now - lastSeekAt
        if (totalMs == 0L || sinceSeek >= VISIBLE_MS) {
            reset()
            return
        }

        val remainingFraction = ((VISIBLE_MS - sinceSeek).toFloat() / FADE_MS).coerceAtMost(1f)
        val opacity = remainingFraction * remainingFraction * (3f - 2f * remainingFraction)
        val forward = totalMs > 0
        val panelWidth = width * PANEL_WIDTH_FRACTION
        val centerY = height / 2f

        canvas.save()
        if (forward) {
            canvas.translate(width.toFloat(), 0f)
            canvas.scale(-1f, 1f)
        }
        curvedEdge.rewind()
        curvedEdge.moveTo(0f, 0f)
        curvedEdge.lineTo(panelWidth * 0.9f, 0f)
        curvedEdge.quadTo(panelWidth * 1.1f, centerY, panelWidth * 0.9f, height.toFloat())
        curvedEdge.lineTo(0f, height.toFloat())
        curvedEdge.close()
        backgroundPaint.alpha = (255 * opacity).toInt()
        canvas.drawPath(curvedEdge, backgroundPaint)
        canvas.restore()

        if (showCircle) drawCounter(canvas, forward, centerY, opacity)
        postInvalidateOnAnimation()
    }

    private fun drawCounter(canvas: Canvas, forward: Boolean, centerY: Float, opacity: Float) {
        val dimensions = ReiAnixTokens.PlayerDimensions
        val controlsWidth = minOf(width.toFloat(), dimensions.centerControlsMaxWidth.value * density)
        val sidePadding = (dimensions.centerHorizontalPadding.value * density).roundToInt()
        val touchSize = (dimensions.seekButtonSize.value * density).roundToInt()
        val distance = ((controlsWidth - 2 * sidePadding - touchSize) / 2f)
            .coerceAtLeast(0f).roundToInt()
        val centerX = width / 2f + if (forward) distance else -distance
        val left = (centerX - circleSize / 2f).roundToInt()
        val top = (centerY - circleSize / 2f).roundToInt()
        val alpha = (255 * opacity).toInt()

        circle.setBounds(left, top, left + circleSize, top + circleSize)
        circle.alpha = alpha
        canvas.save()
        if (forward) canvas.scale(-1f, 1f, centerX, centerY)
        circle.draw(canvas)
        canvas.restore()

        paint.color = Color.WHITE
        paint.alpha = alpha
        paint.textSize = dimensions.seekNumberSize.value * resources.displayMetrics.scaledDensity
        paint.textSize *= minOf(1f, circleSize * 0.46f / paint.measureText(label))
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, centerX, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private companion object {
        const val PANEL_WIDTH_FRACTION = 0.35f
        const val VISIBLE_MS = 750L
        const val FADE_MS = 200L
    }
}
