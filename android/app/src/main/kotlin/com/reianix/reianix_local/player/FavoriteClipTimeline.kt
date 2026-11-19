package com.reianix.reianix_local.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToLong

/** A single filmstrip with independent start/end handles and a playback cursor. */
internal class FavoriteClipTimeline(context: Context, private val durationMs: Long) : View(context) {
    enum class Target { START, END, CURSOR }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frames = arrayOfNulls<Bitmap>(FRAME_COUNT)
    private val density = resources.displayMetrics.density
    private var target: Target? = null
    private var startMs = 0L
    private var endMs = durationMs
    private var positionMs = 0L
    var onDrag: (Target, Long, Boolean) -> Unit = { _, _, _ -> }

    init {
        isFocusable = true
        contentDescription = "Linha do tempo do clipe. Arraste as extremidades para recortar ou toque para navegar."
    }

    fun setRange(start: Long, end: Long, position: Long) {
        startMs = start
        endMs = end
        positionMs = position
        invalidate()
    }

    fun setFrame(index: Int, bitmap: Bitmap?) {
        if (index in frames.indices) {
            frames[index] = bitmap
            invalidate()
        }
    }

    private val inset get() = 15f * density
    private val trackWidth get() = (width - 2 * inset).coerceAtLeast(1f)
    private fun x(time: Long): Float = inset + (time.toDouble() / durationMs * trackWidth).toFloat()
    private fun time(x: Float): Long =
        (((x - inset) / trackWidth).coerceIn(0f, 1f).toDouble() * durationMs).roundToLong()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = 10f * density
        val bottom = height - 10f * density
        if (bottom <= top) return
        val left = inset
        val right = left + trackWidth
        val tileWidth = trackWidth / FRAME_COUNT
        paint.style = Paint.Style.FILL
        paint.color = 0xFF252525.toInt()
        canvas.drawRoundRect(RectF(left, top, right, bottom), 3f * density, 3f * density, paint)
        val checkpoint = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        frames.forEachIndexed { index, bitmap ->
            if (bitmap != null && !bitmap.isRecycled) {
                val tile = RectF(left + index * tileWidth, top, left + (index + 1) * tileWidth, bottom)
                val ratio = tile.width() / tile.height()
                val sourceWidth = (bitmap.height * ratio).toInt().coerceIn(1, bitmap.width)
                val sourceHeight = (bitmap.width / ratio).toInt().coerceIn(1, bitmap.height)
                val sx = (bitmap.width - sourceWidth) / 2
                val sy = (bitmap.height - sourceHeight) / 2
                canvas.drawBitmap(bitmap, Rect(sx, sy, sx + sourceWidth, sy + sourceHeight), tile, paint)
            }
        }
        paint.color = 0xB8000000.toInt()
        val a = x(startMs)
        val b = x(endMs)
        canvas.drawRect(left, top, a, bottom, paint)
        canvas.drawRect(b, top, right, bottom, paint)
        canvas.restoreToCount(checkpoint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawRect(a, top + density, b, bottom - density, paint)
        paint.style = Paint.Style.FILL
        val handleWidth = 12f * density
        canvas.drawRoundRect(RectF(a - handleWidth, top, a + 2f * density, bottom), 4f * density, 4f * density, paint)
        canvas.drawRoundRect(RectF(b - 2f * density, top, b + handleWidth, bottom), 4f * density, 4f * density, paint)
        paint.color = 0xFF444444.toInt()
        val center = (top + bottom) / 2
        canvas.drawRoundRect(RectF(a - 6f * density, center - 5f * density, a - 4f * density, center + 5f * density), density, density, paint)
        canvas.drawRoundRect(RectF(b + 4f * density, center - 5f * density, b + 6f * density, center + 5f * density), density, density, paint)
        paint.color = Color.WHITE
        val cursor = x(positionMs.coerceIn(startMs, endMs))
        canvas.drawRoundRect(RectF(cursor - density, top - 4f * density, cursor + density, bottom + 4f * density), density, density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val sx = x(startMs)
                val ex = x(endMs)
                val distanceStart = abs(event.x - sx)
                val distanceEnd = abs(event.x - ex)
                val touchRadius = 19f * density
                target = when {
                    distanceStart <= touchRadius && distanceEnd <= touchRadius ->
                        if (event.x <= (sx + ex) / 2f) Target.START else Target.END
                    distanceStart <= touchRadius -> Target.START
                    distanceEnd <= touchRadius -> Target.END
                    else -> Target.CURSOR
                }
                if (target == Target.CURSOR) onDrag(Target.CURSOR, time(event.x), false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                target?.let { onDrag(it, time(event.x), false) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                target?.let { onDrag(it, time(event.x), true) }
                target = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                target = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object { const val FRAME_COUNT = 8 }
}
