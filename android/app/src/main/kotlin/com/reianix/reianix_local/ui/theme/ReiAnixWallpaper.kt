package com.reianix.reianix_local.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Only full-screen backgrounds use this local; dialogs and artwork keep their material colors. */
val LocalReiAnixWallpaperVisible = staticCompositionLocalOf { false }

@Composable
fun reiAnixScreenBackground(): Color =
    if (LocalReiAnixWallpaperVisible.current) Color.Transparent else MaterialTheme.colorScheme.background

internal data class WallpaperCrop(val width: Int, val height: Int, val x: Int, val y: Int)

internal fun wallpaperCrop(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int, wallpaper: ReiAnixWallpaper): WallpaperCrop {
    require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0)
    val config = wallpaper.normalized()
    val scale = maxOf(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight) * config.zoom
    val scaledWidth = ceil(sourceWidth * scale).toInt().coerceAtLeast(width)
    val scaledHeight = ceil(sourceHeight * scale).toInt().coerceAtLeast(height)
    return WallpaperCrop(scaledWidth, scaledHeight,
        (-(scaledWidth - width) * config.positionX).roundToInt(),
        (-(scaledHeight - height) * config.positionY).roundToInt())
}

/** Bounded, separable box blur on a reduced image. No GPU effect or API-31 dependency. */
internal fun boxBlurPixels(input: IntArray, width: Int, height: Int, radius: Int): IntArray {
    require(width > 0 && height > 0 && input.size == width * height)
    val r = radius.coerceIn(0, 40)
    if (r == 0) return input.copyOf()
    val temp = IntArray(input.size)
    val output = IntArray(input.size)
    val divisor = r * 2 + 1
    fun packed(a: Int, red: Int, green: Int, blue: Int): Int =
        ((a / divisor) shl 24) or ((red / divisor) shl 16) or ((green / divisor) shl 8) or (blue / divisor)
    fun pass(source: IntArray, dest: IntArray, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        for (line in 0 until lines) {
            fun index(i: Int): Int = if (horizontal) line * width + i.coerceIn(0, length - 1)
                else i.coerceIn(0, length - 1) * width + line
            var a = 0; var red = 0; var green = 0; var blue = 0
            fun add(pixel: Int, sign: Int) {
                a += (pixel ushr 24) * sign
                red += ((pixel ushr 16) and 255) * sign
                green += ((pixel ushr 8) and 255) * sign
                blue += (pixel and 255) * sign
            }
            for (i in -r..r) add(source[index(i)], 1)
            for (i in 0 until length) {
                dest[index(i)] = packed(a, red, green, blue)
                add(source[index(i - r)], -1)
                add(source[index(i + r + 1)], 1)
            }
        }
    }
    pass(input, temp, true)
    pass(temp, output, false)
    return output
}

@Composable
fun rememberWallpaperBitmap(store: ReiAnixWallpaperStore, wallpaper: ReiAnixWallpaper): ImageBitmap? {
    val name = wallpaper.imageName
    val blurStep = (wallpaper.normalized().blur * 20).roundToInt()
    // Decode only when the file or blur level changes, never during navigation or scrolling.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, store, name, blurStep) {
        if (name == null) {
            value = null
            return@produceState
        }
        delay(90)
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val file = store.file(name) ?: return@runCatching null
                val source = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
                if (blurStep == 0) source.asImageBitmap()
                else {
                    val factor = minOf(1f, 400f / maxOf(source.width, source.height))
                    val w = (source.width * factor).roundToInt().coerceAtLeast(1)
                    val h = (source.height * factor).roundToInt().coerceAtLeast(1)
                    val reduced = Bitmap.createScaledBitmap(source, w, h, true)
                    try {
                        val pixels = IntArray(w * h)
                        reduced.getPixels(pixels, 0, w, 0, 0, w, h)
                        val blurred = boxBlurPixels(pixels, w, h, blurStep * 2)
                        Bitmap.createBitmap(blurred, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
                    } finally {
                        if (reduced !== source) reduced.recycle()
                        source.recycle()
                    }
                }
            }.getOrNull()
        }
        value = decoded
    }
    return if (name == null) null else bitmap
}

/** The same crop renderer is used by the global layer and the editor preview. */
@Composable
fun ReiAnixWallpaperLayer(bitmap: ImageBitmap, wallpaper: ReiAnixWallpaper, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@Canvas
        val crop = wallpaperCrop(bitmap.width, bitmap.height, w, h, wallpaper)
        clipRect {
            drawImage(bitmap, dstOffset = IntOffset(crop.x, crop.y), dstSize = IntSize(crop.width, crop.height))
            drawRect(Color.Black.copy(alpha = wallpaper.normalized().dim))
        }
    }
}
