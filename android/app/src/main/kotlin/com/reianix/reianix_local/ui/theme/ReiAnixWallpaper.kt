package com.reianix.reianix_local.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reianix.reianix_local.data.profile.ReiAnixWallpaper
import com.reianix.reianix_local.data.profile.ReiAnixWallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Set only while the shared wallpaper is actually visible. */
val LocalReiAnixWallpaperVisible = staticCompositionLocalOf { false }

@Composable
fun reiAnixScreenBackground(): Color =
    if (LocalReiAnixWallpaperVisible.current) Color.Transparent else MaterialTheme.colorScheme.background

/** Preserve original flat surfaces when disabled; remove opaque card blocks only in wallpaper mode. */
@Composable
fun reiAnixContentBackground(original: Color): Color =
    wallpaperContentColor(original, LocalReiAnixWallpaperVisible.current)

internal fun wallpaperContentColor(original: Color, active: Boolean): Color =
    if (active) Color.Transparent else original

/** Overlays which communicate selection stay subtly tinted rather than opaque. */
@Composable
fun reiAnixContentTint(original: Color, alpha: Float = 0.18f): Color =
    if (LocalReiAnixWallpaperVisible.current) original.copy(alpha = alpha) else original

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

/** Keep the image under the gesture centroid and clamp translation to the crop bounds. */
internal fun transformWallpaper(wallpaper: ReiAnixWallpaper, sw: Int, sh: Int, w: Int, h: Int,
    centerX: Float, centerY: Float, panX: Float, panY: Float, zoomChange: Float): ReiAnixWallpaper {
    if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0 || !zoomChange.isFinite() ||
        !centerX.isFinite() || !centerY.isFinite() || !panX.isFinite() || !panY.isFinite()) return wallpaper
    val old = wallpaper.normalized()
    val oldCrop = wallpaperCrop(sw, sh, w, h, old)
    val next = old.copy(zoom = (old.zoom * zoomChange).coerceIn(1f, 3f))
    val newCrop = wallpaperCrop(sw, sh, w, h, next)
    val scale = next.zoom / old.zoom
    val left = centerX - (centerX - oldCrop.x) * scale + panX
    val top = centerY - (centerY - oldCrop.y) * scale + panY
    fun position(offset: Float, excess: Int, fallback: Float) =
        if (excess > 0) (-offset / excess).coerceIn(0f, 1f) else fallback
    return next.copy(positionX = position(left, newCrop.width - w, old.positionX),
        positionY = position(top, newCrop.height - h, old.positionY))
}

/** Darken automatically according to the brightest sampled regions, without editing the original. */
internal fun automaticWallpaperDim(pixels: IntArray): Float {
    if (pixels.isEmpty()) return 0.65f
    val brightness = pixels.map { pixel ->
        val r = (pixel ushr 16) and 255
        val g = (pixel ushr 8) and 255
        val b = pixel and 255
        (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }.sorted()
    val bright = brightness[((brightness.size - 1) * 0.95f).roundToInt()]
    return (0.52f + bright * 0.20f).coerceIn(0.52f, 0.72f)
}

data class WallpaperImage(val bitmap: ImageBitmap, val dim: Float)

internal data class WallpaperFrame(
    val image: WallpaperImage,
    val wallpaper: ReiAnixWallpaper,
    val viewport: IntSize,
)
internal val LocalReiAnixWallpaperFrame = compositionLocalOf<WallpaperFrame?> { null }

/** Use the bottom of the full viewport, rather than stretching the image to the sheet. */
internal fun wallpaperPanelCrop(sw: Int, sh: Int, viewport: IntSize, panel: IntSize,
    wallpaper: ReiAnixWallpaper): WallpaperCrop {
    val width = maxOf(viewport.width, panel.width)
    val height = maxOf(viewport.height, panel.height)
    val crop = wallpaperCrop(sw, sh, width, height, wallpaper)
    return crop.copy(x = crop.x - (width - panel.width) / 2, y = crop.y - (height - panel.height))
}

/** A modal must cover the underlying cards. Paint the same shared bitmap, not a
 * transparent hole through the dialog to the library grid. No additional decode. */
@Composable
internal fun Modifier.reiAnixWallpaperPanel(shape: Shape): Modifier {
    val frame = LocalReiAnixWallpaperFrame.current ?: return this
    val base = MaterialTheme.colorScheme.background
    return clip(shape).drawBehind {
        val panel = IntSize(size.width.roundToInt(), size.height.roundToInt())
        if (panel.width <= 0 || panel.height <= 0) return@drawBehind
        val bitmap = frame.image.bitmap
        val crop = wallpaperPanelCrop(bitmap.width, bitmap.height, frame.viewport, panel, frame.wallpaper)
        drawRect(base)
        clipRect {
            drawImage(bitmap, dstOffset = IntOffset(crop.x, crop.y), dstSize = IntSize(crop.width, crop.height))
            drawRect(Color.Black.copy(alpha = frame.image.dim))
        }
    }
}

data class WallpaperImageLoad(val image: WallpaperImage? = null, val loading: Boolean = false)

@Composable
fun rememberWallpaperImage(store: ReiAnixWallpaperStore, wallpaper: ReiAnixWallpaper): WallpaperImageLoad {
    val name = wallpaper.imageName ?: return WallpaperImageLoad()
    return key(store, name) {
        val result by produceState(WallpaperImageLoad(loading = true), store, name) {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    val file = store.file(name) ?: return@runCatching null
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
                    val samples = IntArray(24 * 24) { index ->
                        bitmap.getPixel((index % 24) * (bitmap.width - 1) / 23,
                            (index / 24) * (bitmap.height - 1) / 23)
                    }
                    WallpaperImage(bitmap.asImageBitmap(), automaticWallpaperDim(samples))
                }.getOrNull()
            }
            value = WallpaperImageLoad(image = image)
        }
        result
    }
}

/** Single durable wallpaper layer outside the navigation viewport; never scrolls or crossfades with a destination. */
@Composable
fun ReiAnixWallpaperScope(enabled: Boolean = true, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ReiAnixWallpaperStore.from(context) }
    val wallpaper by store.state.collectAsStateWithLifecycle()
    val image = rememberWallpaperImage(store, wallpaper).image
    // Keep content at a stable composition position when loading/removing the image.
    val visible = enabled && image != null
    val scheme = if (visible) ReiAnixDarkColorScheme else MaterialTheme.colorScheme
    val inheritedContent = androidx.compose.material3.LocalContentColor.current
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).onSizeChanged { viewport = it }) {
            if (visible) image?.let { ReiAnixWallpaperLayer(it, wallpaper, Modifier.matchParentSize()) }
            val frame = if (visible && image != null) WallpaperFrame(image, wallpaper, viewport) else null
            CompositionLocalProvider(
                LocalReiAnixWallpaperVisible provides visible,
                LocalReiAnixWallpaperFrame provides frame,
            ) {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides (if (visible) MaterialTheme.colorScheme.onBackground else inheritedContent)) {
                    content()
                }
            }
        }
    }
}

/** Same crop renderer for the editor preview and destination background. */
@Composable
fun ReiAnixWallpaperLayer(image: WallpaperImage, wallpaper: ReiAnixWallpaper, modifier: Modifier = Modifier,
    editing: Boolean = false) {
    Canvas(modifier) {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@Canvas
        val bitmap = image.bitmap
        val crop = wallpaperCrop(bitmap.width, bitmap.height, w, h, wallpaper)
        clipRect {
            drawImage(bitmap, dstOffset = IntOffset(crop.x, crop.y), dstSize = IntSize(crop.width, crop.height))
            if (!editing) drawRect(Color.Black.copy(alpha = image.dim))
        }
    }
}
