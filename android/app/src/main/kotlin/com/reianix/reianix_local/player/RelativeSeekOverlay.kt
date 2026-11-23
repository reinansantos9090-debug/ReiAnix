package com.reianix.reianix_local.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.hypot

/** Draws short-lived relative seek feedback without intercepting player input. */
internal class RelativeSeekOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curvedEdge = Path()
    private val triangle = Path()
    private var totalMs = 0L
    private var startedAt = 0L
    private var lastSeekAt = 0L
    private var rippleX: Float? = null
    private var rippleY: Float? = null
    private var label = ""

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = INVISIBLE
    }

    fun showSeek(deltaMs: Long, touchX: Float?, touchY: Float?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSeekAt >= VISIBLE_MS || (deltaMs > 0) != (totalMs > 0) || totalMs == 0L) {
            startedAt = now
            totalMs = 0L
        }
        totalMs += deltaMs
        label = if (totalMs > 0) "+${totalMs / 1_000L} s" else "−${-totalMs / 1_000L} s"
        lastSeekAt = now
        rippleX = touchX
        rippleY = touchY
        visibility = VISIBLE
        invalidate()
    }

    fun reset() {
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

        val forward = totalMs > 0
        val panelWidth = width * PANEL_WIDTH_FRACTION
        val centerX = if (forward) width - panelWidth / 2f else panelWidth / 2f
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
        canvas.drawPath(curvedEdge, backgroundPaint)
        canvas.clipPath(curvedEdge)
        if (sinceSeek < RIPPLE_MS) {
            val progress = sinceSeek.toFloat() / RIPPLE_MS
            val x = rippleX?.let { if (forward) width - it else it } ?: panelWidth / 2f
            val y = rippleY ?: centerY
            paint.color = Color.WHITE
            paint.alpha = (35 * (1f - progress)).toInt()
            canvas.drawCircle(x, y, hypot(panelWidth, height.toFloat()) * progress, paint)
        }
        canvas.restore()

        val animationTime = (now - startedAt) % ARROW_CYCLE_MS
        val direction = if (forward) 1f else -1f
        for (index in 0..2) {
            val delay = index * ARROW_STEP_MS
            val fadeIn = ((animationTime - delay).toFloat() / ARROW_STEP_MS).coerceIn(0f, 1f)
            val fadeOut = ((animationTime - delay - 3 * ARROW_STEP_MS).toFloat() / ARROW_STEP_MS)
                .coerceIn(0f, 1f)
            paint.color = Color.WHITE
            paint.alpha = (255 * (fadeIn - fadeOut)).toInt()
            val x = centerX + (index - 1) * direction * 20f * density
            val y = centerY - 7f * density
            triangle.rewind()
            triangle.moveTo(x - direction * 4f * density, y - 5f * density)
            triangle.lineTo(x + direction * 4f * density, y)
            triangle.lineTo(x - direction * 4f * density, y + 5f * density)
            triangle.close()
            canvas.drawPath(triangle, paint)
        }
        paint.color = Color.WHITE
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, centerX, centerY + 14f * density, paint)
        postInvalidateOnAnimation()
    }

    private companion object {
        const val PANEL_WIDTH_FRACTION = 0.35f
        const val VISIBLE_MS = 750L
        const val RIPPLE_MS = 300L
        const val ARROW_STEP_MS = 150L
        const val ARROW_CYCLE_MS = 6 * ARROW_STEP_MS
    }
}
